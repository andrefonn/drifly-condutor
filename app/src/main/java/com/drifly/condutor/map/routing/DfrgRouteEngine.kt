package com.drifly.condutor.map.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motor de roteamento 100% offline baseado em grafo viário binário compacto (`DFRG` v1).
 *
 * Arquitetura pensada para aparelhos com pouca RAM (ex.: moto g(30)):
 * - **A* Bidirecional com potencial balanceado** (`pi_f = 0.5*(h_f - h_b)`, `pi_b = -pi_f`),
 *   min-heap primitivo e arrays de geração reutilizados (zero alocação/GC por consulta);
 * - **Custeio dinâmico em tempo de consulta** (sem reconstruir o grafo): perfil Carro/Moto,
 *   rápido/curto, `avoidTolls`, `avoidUnpaved`, penalidades de conversão (retorno ~25s,
 *   esquerda em arterial ~4.5s, transição de hierarquia viária);
 * - **Evasão dinâmica de Lista Negra (`AvoidZone`)**: marcação O(E) ultrarrápida em `BitSet`
 *   com teste de distância segmento-círculo + ponto-em-polígono e penalidade *soft-hard*
 *   (desvia de bairros bloqueados, mas nunca falha se origem/destino estiver preso dentro);
 * - **Rotas alternativas inteligentes**: diversificação por penalização nos 70% centrais da rota
 *   principal com filtro de sobreposição <= 80% e custo <= 1.45x;
 * - **Matriz de custo exata 1-para-Muitos (`computeCostMatrix`)**: um Dijkstra por origem com
 *   parada antecipada assim que todos os destinos forem fixados (otimização TSP multi-paradas).
 */
class DfrgRouteEngine(
    @Volatile var avoidTolls: Boolean = false,
    @Volatile var avoidUnpaved: Boolean = true
) : OfflineRouteEngine {

    override val engineName: String = "DFRG-v1 (Kotlin CSR Bidirectional A*)"

    @Volatile
    private var loadedGraph: DfrgGraph? = null

    private var workspace: RoutingWorkspace? = null
    private val searchMutex = Mutex()

    override val isReady: Boolean
        get() = loadedGraph != null

    /** Expõe o grafo carregado para diagnósticos e testes. */
    val graph: DfrgGraph?
        get() = loadedGraph

    override suspend fun initialize(packDir: File): Boolean = withContext(Dispatchers.IO) {
        val dfrgFile = resolveDfrgFile(packDir) ?: return@withContext false
        try {
            val g = DfrgGraph.load(dfrgFile)
            searchMutex.withLock {
                loadedGraph = g
                workspace = RoutingWorkspace(g.nodeCount, g.edgeCount)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Inicializa o motor diretamente a partir de um [DfrgGraph] já carregado em memória. */
    suspend fun initialize(graph: DfrgGraph): Boolean {
        searchMutex.withLock {
            loadedGraph = graph
            workspace = RoutingWorkspace(graph.nodeCount, graph.edgeCount)
        }
        return true
    }

    override suspend fun computeRoute(request: RouteRequest): RouteResponse = withContext(Dispatchers.Default) {
        if (request.waypoints.isEmpty()) {
            return@withContext RouteResponse.Error("Informe ao menos um destino.")
        }

        searchMutex.withLock {
            val g = loadedGraph ?: return@withContext RouteResponse.Error("Motor de rotas offline não inicializado.")
            val ws = workspace ?: RoutingWorkspace(g.nodeCount, g.edgeCount).also { workspace = it }

            val costParams = CostParams(
                profile = request.profile,
                avoidTolls = avoidTolls,
                avoidUnpaved = avoidUnpaved
            )

            // Marca todas as arestas que interceptam alguma AvoidZone da Lista Negra.
            val zoneContext = prepareAvoidZones(g, ws, request.avoidZones)

            // Rota principal (suporta 1..N paradas).
            val primaryResult = computeMultiLegRoute(
                graph = g,
                ws = ws,
                request = request,
                costParams = costParams,
                zoneContext = zoneContext,
                routeId = "dfrg_primary",
                useAltPenalty = false,
                altPenaltyFactor = 1.0
            ) ?: return@withContext RouteResponse.Error(
                "Não foi possível traçar rota offline entre os pontos informados."
            )

            val alternatives = mutableListOf<ComputedRoute>()
            if (request.computeAlternatives && request.waypoints.size == 1 && primaryResult.allEdges.size >= 2) {
                computeAlternatives(
                    graph = g,
                    ws = ws,
                    request = request,
                    costParams = costParams,
                    zoneContext = zoneContext,
                    primary = primaryResult,
                    outAlternatives = alternatives
                )
            }

            RouteResponse.Success(
                primary = primaryResult.route,
                alternatives = alternatives
            )
        }
    }

    override suspend fun computeCostMatrix(
        points: List<GeoPoint>,
        profile: RouteProfile,
        avoidZones: List<AvoidZone>
    ): Array<DoubleArray>? = withContext(Dispatchers.Default) {
        if (points.isEmpty()) return@withContext emptyArray()

        searchMutex.withLock {
            val g = loadedGraph ?: return@withContext null
            val ws = workspace ?: RoutingWorkspace(g.nodeCount, g.edgeCount).also { workspace = it }

            val n = points.size
            val snappedNodes = IntArray(n)
            for (i in 0 until n) {
                val node = g.nearestNode(points[i].lat, points[i].lng, MAX_SNAP_RADIUS_M)
                if (node == -1) return@withContext null
                snappedNodes[i] = node
            }

            val costParams = CostParams(
                profile = profile,
                avoidTolls = avoidTolls,
                avoidUnpaved = avoidUnpaved
            )
            val zoneContext = prepareAvoidZones(g, ws, avoidZones)
            val matrix = Array(n) { DoubleArray(n) { UNREACHABLE_TIME_SECONDS } }

            for (originIdx in 0 until n) {
                matrix[originIdx][originIdx] = 0.0
                val srcNode = snappedNodes[originIdx]
                runOneToManyDijkstra(
                    graph = g,
                    ws = ws,
                    originNode = srcNode,
                    targetNodes = snappedNodes,
                    originIdx = originIdx,
                    costParams = costParams,
                    zoneContext = zoneContext,
                    outRowSeconds = matrix[originIdx]
                )
            }
            matrix
        }
    }

    override fun close() {
        loadedGraph = null
        workspace = null
    }

    // -------------------------------------------------------------------------
    // Construção de rotas multi-perna e rotas alternativas
    // -------------------------------------------------------------------------

    private data class BuiltRouteResult(
        val route: ComputedRoute,
        val searchCost: Double,
        val allEdges: IntArray
    )

    private data class CostParams(
        val profile: RouteProfile,
        val avoidTolls: Boolean,
        val avoidUnpaved: Boolean
    ) {
        val isShortest: Boolean get() = profile == RouteProfile.CAR_SHORTEST
        val isMoto: Boolean get() = profile == RouteProfile.MOTO_FASTEST
    }

    private data class AvoidZoneContext(
        val zones: List<AvoidZone>,
        val hasAnyAvoidedEdge: Boolean,
        val hasHardAvoidEdge: Boolean
    )

    private data class SnapAnchor(
        val rawPoint: GeoPoint,
        val snappedPoint: GeoPoint,
        val directNode: Int,
        val edgeSnap: DfrgGraph.EdgeSnap?
    )

    private data class SearchSeed(
        val node: Int,
        val initCost: Double,
        val initEdge: Int,
        val partialFraction: Double,
        val partialDistM: Double,
        val partialTimeSec: Double
    )

    private data class LegPathSolution(
        val edges: IntArray,
        val startSeed: SearchSeed?,
        val endSeed: SearchSeed?,
        val sameEdgeDirect: DfrgGraph.EdgeSnap?,
        val sameEdgeStartFrac: Double,
        val sameEdgeEndFrac: Double,
        val searchCost: Double
    )

    private fun computeMultiLegRoute(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        request: RouteRequest,
        costParams: CostParams,
        zoneContext: AvoidZoneContext,
        routeId: String,
        useAltPenalty: Boolean,
        altPenaltyFactor: Double
    ): BuiltRouteResult? {
        val allGeometry = ArrayList<GeoPoint>(128)
        val legs = ArrayList<RouteLeg>(request.waypoints.size)
        val allEdgeIds = ArrayList<Int>(64)

        var totalDistM = 0.0
        var totalDurSec = 0.0
        var totalSearchCost = 0.0
        var nextStepIndex = 0
        var traversedAvoidedEdge = false

        var prevWaypoint = RouteWaypoint(request.origin, label = "Origem")

        for (legIdx in request.waypoints.indices) {
            val destWaypoint = request.waypoints[legIdx]
            val bearing = if (legIdx == 0) request.originBearingDeg else null

            val originAnchor = snapPoint(graph, prevWaypoint.point, bearing) ?: return null
            val destAnchor = snapPoint(graph, destWaypoint.point, null) ?: return null

            val solution = solveLeg(
                graph = graph,
                ws = ws,
                origin = originAnchor,
                dest = destAnchor,
                originBearingDeg = bearing,
                costParams = costParams,
                zoneContext = zoneContext,
                useAltPenalty = useAltPenalty,
                altPenaltyFactor = altPenaltyFactor
            ) ?: return null

            totalSearchCost += solution.searchCost

            val legSegments = ArrayList<ManeuverGeneratorPtBr.LegEdgeSegment>(solution.edges.size + 2)
            var legDistM = 0.0
            var legDurSec = 0.0

            if (solution.sameEdgeDirect != null) {
                val e = solution.sameEdgeDirect.edgeId
                val fracDelta = (solution.sameEdgeEndFrac - solution.sameEdgeStartFrac).coerceAtLeast(0.0)
                val segDist = graph.edgeDistM[e] * fracDelta
                val segDur = trueEdgeDurationSeconds(graph, e, costParams.profile) * fracDelta
                val gStartIdx = appendPartialEdgeGeometry(
                    graph, e, solution.sameEdgeStartFrac, solution.sameEdgeEndFrac, allGeometry
                )
                val gEndIdx = (allGeometry.size - 1).coerceAtLeast(gStartIdx)
                legSegments.add(ManeuverGeneratorPtBr.LegEdgeSegment(e, segDist, segDur, gStartIdx, gEndIdx))
                allEdgeIds.add(e)
                if (zoneContext.hasAnyAvoidedEdge && ws.avoidedEdges.get(e)) traversedAvoidedEdge = true
                legDistM += segDist
                legDurSec += segDur
            } else {
                // 1) Trecho parcial inicial (se origin foi projetado no meio de uma aresta)
                val startSeed = solution.startSeed
                if (startSeed != null && startSeed.initEdge != -1 && startSeed.partialDistM > 0.0) {
                    val e = startSeed.initEdge
                    val gStartIdx = appendPartialEdgeGeometry(
                        graph, e, startSeed.partialFraction, 1.0, allGeometry
                    )
                    val gEndIdx = (allGeometry.size - 1).coerceAtLeast(gStartIdx)
                    legSegments.add(
                        ManeuverGeneratorPtBr.LegEdgeSegment(
                            edgeId = e,
                            distanceMeters = startSeed.partialDistM,
                            durationSeconds = startSeed.partialTimeSec,
                            geomStartIndex = gStartIdx,
                            geomEndIndex = gEndIdx
                        )
                    )
                    allEdgeIds.add(e)
                    if (zoneContext.hasAnyAvoidedEdge && ws.avoidedEdges.get(e)) traversedAvoidedEdge = true
                    legDistM += startSeed.partialDistM
                    legDurSec += startSeed.partialTimeSec
                }

                // 2) Arestas completas do caminho A*
                for (e in solution.edges) {
                    val d = graph.edgeDistM[e].toDouble().coerceAtLeast(0.0)
                    val t = trueEdgeDurationSeconds(graph, e, costParams.profile)
                    val gStartIdx = appendFullEdgeGeometry(graph, e, allGeometry)
                    val gEndIdx = (allGeometry.size - 1).coerceAtLeast(gStartIdx)
                    legSegments.add(ManeuverGeneratorPtBr.LegEdgeSegment(e, d, t, gStartIdx, gEndIdx))
                    allEdgeIds.add(e)
                    if (zoneContext.hasAnyAvoidedEdge && ws.avoidedEdges.get(e)) traversedAvoidedEdge = true
                    legDistM += d
                    legDurSec += t
                }

                // 3) Trecho parcial final (se dest foi projetado no meio de uma aresta)
                val endSeed = solution.endSeed
                if (endSeed != null && endSeed.initEdge != -1 && endSeed.partialDistM > 0.0) {
                    val e = endSeed.initEdge
                    val gStartIdx = appendPartialEdgeGeometry(
                        graph, e, 0.0, endSeed.partialFraction, allGeometry
                    )
                    val gEndIdx = (allGeometry.size - 1).coerceAtLeast(gStartIdx)
                    legSegments.add(
                        ManeuverGeneratorPtBr.LegEdgeSegment(
                            edgeId = e,
                            distanceMeters = endSeed.partialDistM,
                            durationSeconds = endSeed.partialTimeSec,
                            geomStartIndex = gStartIdx,
                            geomEndIndex = gEndIdx
                        )
                    )
                    allEdgeIds.add(e)
                    if (zoneContext.hasAnyAvoidedEdge && ws.avoidedEdges.get(e)) traversedAvoidedEdge = true
                    legDistM += endSeed.partialDistM
                    legDurSec += endSeed.partialTimeSec
                }

                // Caso degenerado: origem e destino no mesmo nó sem arestas.
                if (legSegments.isEmpty()) {
                    appendPointDedup(allGeometry, originAnchor.snappedPoint)
                }
            }

            val isFinalLeg = (legIdx == request.waypoints.lastIndex)
            val steps = ManeuverGeneratorPtBr.generateLegSteps(
                graph = graph,
                segments = legSegments,
                geometry = allGeometry,
                destinationLabel = destWaypoint.label,
                stepIndexStart = nextStepIndex,
                waypointNumber = legIdx + 1,
                isFinalLeg = isFinalLeg
            )
            nextStepIndex += steps.size

            legs.add(
                RouteLeg(
                    from = prevWaypoint,
                    to = destWaypoint,
                    distanceMeters = legDistM,
                    durationSeconds = legDurSec,
                    steps = steps
                )
            )
            totalDistM += legDistM
            totalDurSec += legDurSec
            prevWaypoint = destWaypoint
        }

        val cumDistances = ComputedRoute.buildCumulativeDistances(allGeometry)

        // Conta quantas zonas da Lista Negra foram efetivamente evitadas.
        var avoidedCount = 0
        if (request.avoidZones.isNotEmpty()) {
            for (zone in request.avoidZones) {
                if (!routeIntersectsZone(allGeometry, zone)) {
                    avoidedCount++
                }
            }
        }
        val warning = if (traversedAvoidedEdge && request.avoidZones.isNotEmpty()) {
            "A rota precisou passar por área da Lista Negra por falta de desvio viável."
        } else {
            null
        }

        val computed = ComputedRoute(
            id = routeId,
            geometry = allGeometry,
            cumulativeDistancesM = cumDistances,
            totalDistanceMeters = totalDistM,
            totalDurationSeconds = totalDurSec,
            legs = legs,
            avoidedZonesCount = avoidedCount,
            warningMessage = warning
        )
        return BuiltRouteResult(
            route = computed,
            searchCost = totalSearchCost,
            allEdges = allEdgeIds.toIntArray()
        )
    }

    private fun computeAlternatives(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        request: RouteRequest,
        costParams: CostParams,
        zoneContext: AvoidZoneContext,
        primary: BuiltRouteResult,
        outAlternatives: MutableList<ComputedRoute>
    ) {
        val acceptedEdgeLists = mutableListOf(primary.allEdges)
        val primaryDur = primary.route.totalDurationSeconds.coerceAtLeast(1.0)
        val primaryDist = primary.route.totalDistanceMeters.coerceAtLeast(1.0)

        ws.altPenalizedEdges.clear()
        markMiddleEdges(graph, primary.allEdges, ws.altPenalizedEdges)

        val penaltyFactors = doubleArrayOf(2.5, 4.0)
        var altIndex = 1

        for (attempt in penaltyFactors.indices) {
            if (outAlternatives.size >= MAX_ALTERNATIVES) break

            val candidate = computeMultiLegRoute(
                graph = graph,
                ws = ws,
                request = request,
                costParams = costParams,
                zoneContext = zoneContext,
                routeId = "dfrg_alt_$altIndex",
                useAltPenalty = true,
                altPenaltyFactor = penaltyFactors[attempt]
            ) ?: continue

            // Avalia custo real (sem penalidade de diversificação) e sobreposição.
            val ratio = if (costParams.isShortest) {
                candidate.route.totalDistanceMeters / primaryDist
            } else {
                candidate.route.totalDurationSeconds / primaryDur
            }
            if (ratio > MAX_ALT_COST_RATIO) continue

            var tooSimilar = false
            for (existing in acceptedEdgeLists) {
                val overlap = computeEdgeOverlapRatio(graph, candidate.allEdges, existing)
                if (overlap > MAX_ALT_OVERLAP_RATIO) {
                    tooSimilar = true
                    break
                }
            }
            if (!tooSimilar) {
                outAlternatives.add(candidate.route)
                acceptedEdgeLists.add(candidate.allEdges)
                altIndex++
                // Na próxima tentativa, penaliza também o miolo desta alternativa para achar uma 3ª opção distinta.
                markMiddleEdges(graph, candidate.allEdges, ws.altPenalizedEdges)
            }
        }
        ws.altPenalizedEdges.clear()
    }

    private fun markMiddleEdges(graph: DfrgGraph, edges: IntArray, bitSet: BitSet) {
        if (edges.isEmpty()) return
        val start = if (edges.size <= 4) 0 else (edges.size * 0.15).toInt()
        val end = if (edges.size <= 4) edges.size else max(start + 1, (edges.size * 0.85).toInt())
        for (i in start until end) {
            val e = edges[i]
            bitSet.set(e)
            val rev = graph.findReverseEdge(e)
            if (rev != -1) bitSet.set(rev)
        }
    }

    private fun computeEdgeOverlapRatio(graph: DfrgGraph, a: IntArray, b: IntArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 1.0
        val bSet = HashSet<Int>(b.size * 2)
        var bDist = 0.0
        for (e in b) {
            bSet.add(e)
            bDist += graph.edgeDistM[e].coerceAtLeast(1)
        }
        var aDist = 0.0
        var sharedDist = 0.0
        for (e in a) {
            val d = graph.edgeDistM[e].coerceAtLeast(1).toDouble()
            aDist += d
            if (bSet.contains(e)) {
                sharedDist += d
            }
        }
        val denom = min(aDist, bDist).coerceAtLeast(1.0)
        return sharedDist / denom
    }

    // -------------------------------------------------------------------------
    // Snap & Bidirectional A* Core
    // -------------------------------------------------------------------------

    private fun snapPoint(graph: DfrgGraph, point: GeoPoint, bearingDeg: Float?): SnapAnchor? {
        val edgeSnap = graph.nearestEdge(point.lat, point.lng, MAX_SNAP_RADIUS_M, bearingDeg)
        if (edgeSnap != null) {
            val uPoint = graph.nodePoint(edgeSnap.fromNode)
            val vPoint = graph.nodePoint(edgeSnap.toNode)
            val dU = point.distanceToMeters(uPoint)
            val dV = point.distanceToMeters(vPoint)
            // Se já está praticamente sobre um nó de interseção, usa o nó diretamente.
            val closeToFrom = edgeSnap.fractionAlongEdge <= 0.04 || dU <= NODE_SNAP_THRESHOLD_M
            val closeToTo = edgeSnap.fractionAlongEdge >= 0.96 || dV <= NODE_SNAP_THRESHOLD_M
            val directNode = when {
                closeToFrom && (!closeToTo || dU <= dV) -> edgeSnap.fromNode
                closeToTo -> edgeSnap.toNode
                else -> -1
            }
            return SnapAnchor(
                rawPoint = point,
                snappedPoint = if (directNode != -1) graph.nodePoint(directNode) else edgeSnap.snappedPoint,
                directNode = directNode,
                edgeSnap = edgeSnap
            )
        }
        val node = graph.nearestNode(point.lat, point.lng, MAX_SNAP_RADIUS_M)
        if (node == -1) return null
        return SnapAnchor(
            rawPoint = point,
            snappedPoint = graph.nodePoint(node),
            directNode = node,
            edgeSnap = null
        )
    }

    private fun solveLeg(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        origin: SnapAnchor,
        dest: SnapAnchor,
        originBearingDeg: Float?,
        costParams: CostParams,
        zoneContext: AvoidZoneContext,
        useAltPenalty: Boolean,
        altPenaltyFactor: Double
    ): LegPathSolution? {
        // Caso direto 1: origem e destino no mesmo nó.
        if (origin.directNode != -1 && origin.directNode == dest.directNode) {
            return LegPathSolution(
                edges = IntArray(0),
                startSeed = null,
                endSeed = null,
                sameEdgeDirect = null,
                sameEdgeStartFrac = 0.0,
                sameEdgeEndFrac = 0.0,
                searchCost = 0.0
            )
        }

        // Caso direto 2: origem e destino projetados na mesma aresta (ou sentido inverso da mesma via).
        val oEdge = origin.edgeSnap
        val dEdge = dest.edgeSnap
        if (origin.directNode == -1 && dest.directNode == -1 && oEdge != null && dEdge != null) {
            if (oEdge.edgeId == dEdge.edgeId && oEdge.fractionAlongEdge <= dEdge.fractionAlongEdge) {
                val e = oEdge.edgeId
                val frac = dEdge.fractionAlongEdge - oEdge.fractionAlongEdge
                val c = edgeBaseCost(graph, ws, e, costParams, zoneContext, useAltPenalty, altPenaltyFactor) * frac
                return LegPathSolution(
                    edges = IntArray(0),
                    startSeed = null,
                    endSeed = null,
                    sameEdgeDirect = oEdge,
                    sameEdgeStartFrac = oEdge.fractionAlongEdge,
                    sameEdgeEndFrac = dEdge.fractionAlongEdge,
                    searchCost = c
                )
            }
            if (oEdge.edgeId == dEdge.edgeId && oEdge.fractionAlongEdge > dEdge.fractionAlongEdge && originBearingDeg == null) {
                val rev = graph.findReverseEdge(oEdge.edgeId)
                if (rev != -1) {
                    val startF = (1.0 - oEdge.fractionAlongEdge).coerceIn(0.0, 1.0)
                    val endF = (1.0 - dEdge.fractionAlongEdge).coerceIn(startF, 1.0)
                    val frac = endF - startF
                    val c = edgeBaseCost(graph, ws, rev, costParams, zoneContext, useAltPenalty, altPenaltyFactor) * frac
                    val revSnap = DfrgGraph.EdgeSnap(
                        edgeId = rev,
                        fromNode = graph.edgeFrom[rev],
                        toNode = graph.edgeTo[rev],
                        snappedPoint = oEdge.snappedPoint,
                        distanceMeters = oEdge.distanceMeters,
                        fractionAlongEdge = startF,
                        segmentBearingDeg = (oEdge.segmentBearingDeg + 180f) % 360f
                    )
                    return LegPathSolution(
                        edges = IntArray(0),
                        startSeed = null,
                        endSeed = null,
                        sameEdgeDirect = revSnap,
                        sameEdgeStartFrac = startF,
                        sameEdgeEndFrac = endF,
                        searchCost = c
                    )
                }
            }
        }

        // Constrói sementes iniciais (1 nó se snap em nó, ou 1-2 extremidades se snap no meio de aresta).
        val fwdSeeds = buildForwardSeeds(graph, ws, origin, originBearingDeg, costParams, zoneContext, useAltPenalty, altPenaltyFactor)
        val bwdSeeds = buildBackwardSeeds(graph, ws, dest, costParams, zoneContext, useAltPenalty, altPenaltyFactor)

        return runBidirectionalAStar(
            graph = graph,
            ws = ws,
            originPoint = origin.snappedPoint,
            destPoint = dest.snappedPoint,
            fwdSeeds = fwdSeeds,
            bwdSeeds = bwdSeeds,
            originBearingDeg = if (origin.directNode != -1) originBearingDeg else null,
            costParams = costParams,
            zoneContext = zoneContext,
            useAltPenalty = useAltPenalty,
            altPenaltyFactor = altPenaltyFactor
        )
    }

    private fun buildForwardSeeds(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        origin: SnapAnchor,
        originBearingDeg: Float?,
        costParams: CostParams,
        zoneContext: AvoidZoneContext,
        useAltPenalty: Boolean,
        altPenaltyFactor: Double
    ): List<SearchSeed> {
        if (origin.directNode != -1) {
            return listOf(SearchSeed(origin.directNode, 0.0, -1, 0.0, 0.0, 0.0))
        }
        val es = origin.edgeSnap!!
        val e = es.edgeId
        val remFrac = (1.0 - es.fractionAlongEdge).coerceIn(0.0, 1.0)
        val fwdCost = edgeBaseCost(graph, ws, e, costParams, zoneContext, useAltPenalty, altPenaltyFactor) * remFrac
        val fwdDist = graph.edgeDistM[e] * remFrac
        val fwdTime = trueEdgeDurationSeconds(graph, e, costParams.profile) * remFrac

        val seeds = ArrayList<SearchSeed>(2)
        seeds.add(SearchSeed(es.toNode, fwdCost, e, es.fractionAlongEdge, fwdDist, fwdTime))

        // Se a via for mão dupla, também permite partir pelo sentido inverso (com leve penalidade se oposto ao azimute).
        val revEdge = graph.findReverseEdge(e)
        if (revEdge != -1) {
            val revStartFrac = (1.0 - es.fractionAlongEdge).coerceIn(0.0, 1.0)
            val revRemFrac = es.fractionAlongEdge.coerceIn(0.0, 1.0)
            var revCost = edgeBaseCost(graph, ws, revEdge, costParams, zoneContext, useAltPenalty, altPenaltyFactor) * revRemFrac
            if (originBearingDeg != null) {
                val revBearing = (es.segmentBearingDeg + 180f) % 360f
                val diff = DfrgGraph.absAngleDiffDeg(originBearingDeg, revBearing)
                if (diff > 120f) revCost += if (costParams.isShortest) 120.0 else 18.0
            }
            val revDist = graph.edgeDistM[revEdge] * revRemFrac
            val revTime = trueEdgeDurationSeconds(graph, revEdge, costParams.profile) * revRemFrac
            seeds.add(SearchSeed(es.fromNode, revCost, revEdge, revStartFrac, revDist, revTime))
        }
        return seeds
    }

    private fun buildBackwardSeeds(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        dest: SnapAnchor,
        costParams: CostParams,
        zoneContext: AvoidZoneContext,
        useAltPenalty: Boolean,
        altPenaltyFactor: Double
    ): List<SearchSeed> {
        if (dest.directNode != -1) {
            return listOf(SearchSeed(dest.directNode, 0.0, -1, 0.0, 0.0, 0.0))
        }
        val es = dest.edgeSnap!!
        val e = es.edgeId
        val frac = es.fractionAlongEdge.coerceIn(0.0, 1.0)
        val bwdCost = edgeBaseCost(graph, ws, e, costParams, zoneContext, useAltPenalty, altPenaltyFactor) * frac
        val bwdDist = graph.edgeDistM[e] * frac
        val bwdTime = trueEdgeDurationSeconds(graph, e, costParams.profile) * frac

        val seeds = ArrayList<SearchSeed>(2)
        seeds.add(SearchSeed(es.fromNode, bwdCost, e, frac, bwdDist, bwdTime))

        val revEdge = graph.findReverseEdge(e)
        if (revEdge != -1) {
            val revEndFrac = (1.0 - es.fractionAlongEdge).coerceIn(0.0, 1.0)
            val revCost = edgeBaseCost(graph, ws, revEdge, costParams, zoneContext, useAltPenalty, altPenaltyFactor) * revEndFrac
            val revDist = graph.edgeDistM[revEdge] * revEndFrac
            val revTime = trueEdgeDurationSeconds(graph, revEdge, costParams.profile) * revEndFrac
            seeds.add(SearchSeed(es.toNode, revCost, revEdge, revEndFrac, revDist, revTime))
        }
        return seeds
    }

    /**
     * A* Bidirecional com potencial balanceado (`pi_f = 0.5*(h_f - h_b)`, `pi_b = -pi_f`).
     *
     * Invariante de segurança contra corrupção de pais: encontros são registrados apenas quando
     * o lado oposto já está permanentemente fixado (`settled == gen`), garantindo que tanto
     * `fwdPrevEdge` quanto `bwdNextEdge` sejam imutáveis durante a reconstrução.
     */
    private fun runBidirectionalAStar(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        originPoint: GeoPoint,
        destPoint: GeoPoint,
        fwdSeeds: List<SearchSeed>,
        bwdSeeds: List<SearchSeed>,
        originBearingDeg: Float?,
        costParams: CostParams,
        zoneContext: AvoidZoneContext,
        useAltPenalty: Boolean,
        altPenaltyFactor: Double
    ): LegPathSolution? {
        val gen = ws.nextGeneration()
        val fwdHeap = ws.fwdHeap
        val bwdHeap = ws.bwdHeap
        fwdHeap.clear()
        bwdHeap.clear()

        val origLatE6 = (originPoint.lat * 1_000_000.0).toInt()
        val origLngE6 = (originPoint.lng * 1_000_000.0).toInt()
        val destLatE6 = (destPoint.lat * 1_000_000.0).toInt()
        val destLngE6 = (destPoint.lng * 1_000_000.0).toInt()
        val cosLat = graph.refCosLat

        // Escala estritamente admissível para o potencial balanceado.
        val heuristicScale = if (costParams.isShortest) {
            0.99
        } else {
            0.99 / max(40.0, graph.maxObservedSpeedMps * 1.25)
        }

        fun potentialForward(node: Int): Double {
            val nLat = graph.nodeLatE6[node]
            val nLng = graph.nodeLngE6[node]
            val dfLat = (destLatE6 - nLat) * METERS_PER_E6_LAT
            val dfLng = (destLngE6 - nLng) * METERS_PER_E6_LAT * cosLat
            val hf = sqrt(dfLat * dfLat + dfLng * dfLng) * heuristicScale

            val dbLat = (nLat - origLatE6) * METERS_PER_E6_LAT
            val dbLng = (nLng - origLngE6) * METERS_PER_E6_LAT * cosLat
            val hb = sqrt(dbLat * dbLat + dbLng * dbLng) * heuristicScale

            return 0.5 * (hf - hb)
        }

        // Inicializa sementes diretas e reversas.
        for (seed in fwdSeeds) {
            val u = seed.node
            if (ws.fwdGen[u] != gen || seed.initCost < ws.fwdCost[u]) {
                ws.fwdGen[u] = gen
                ws.fwdCost[u] = seed.initCost
                ws.fwdPrevEdge[u] = -1
                ws.fwdSeedEdge[u] = seed.initEdge
                fwdHeap.push(u, seed.initCost + potentialForward(u))
            }
        }
        for (seed in bwdSeeds) {
            val v = seed.node
            if (ws.bwdGen[v] != gen || seed.initCost < ws.bwdCost[v]) {
                ws.bwdGen[v] = gen
                ws.bwdCost[v] = seed.initCost
                ws.bwdNextEdge[v] = -1
                ws.bwdSeedEdge[v] = seed.initEdge
                bwdHeap.push(v, seed.initCost - potentialForward(v))
            }
        }

        var bestTotalCost = Double.POSITIVE_INFINITY
        var meetIsDirectNode = false
        var meetNode = -1
        var meetBridgeEdge = -1
        var stopFwdTraceAtMeet = false
        var stopBwdTraceAtMeet = false

        val firstEdge = graph.firstEdge
        val edgeTo = graph.edgeTo
        val edgeFrom = graph.edgeFrom
        val revFirstEdge = graph.revFirstEdge
        val revEdgeIdx = graph.revEdgeIdx

        var fwdSettledCount = 0
        var bwdSettledCount = 0

        while (!fwdHeap.isEmpty && !bwdHeap.isEmpty) {
            val minF = fwdHeap.peekKey()
            val minB = bwdHeap.peekKey()
            if (minF + minB >= bestTotalCost) {
                break
            }

            // Equilibra nós fixados dos dois lados para garantir que ambas as sementes expandam em lockstep.
            val expandForward = fwdSettledCount <= bwdSettledCount

            if (expandForward) {
                val u = fwdHeap.popNode()
                if (ws.fwdSettled[u] == gen) continue
                ws.fwdSettled[u] = gen
                fwdSettledCount++
                val gU = ws.fwdCost[u]
                val prevE = ws.fwdPrevEdge[u]
                val effectivePrevE = if (prevE != -1) prevE else ws.fwdSeedEdge[u]

                // Encontro direto em nó já fixado pelo lado reverso.
                if (ws.bwdSettled[u] == gen) {
                    val nextE = ws.bwdNextEdge[u]
                    val effectiveNextE = if (nextE != -1) nextE else ws.bwdSeedEdge[u]
                    val turn = if (effectivePrevE != -1 && effectiveNextE != -1) {
                        turnPenalty(graph, effectivePrevE, effectiveNextE, costParams)
                    } else 0.0
                    val cand = gU + ws.bwdCost[u] + turn
                    if (cand < bestTotalCost) {
                        bestTotalCost = cand
                        meetIsDirectNode = true
                        meetNode = u
                        meetBridgeEdge = -1
                        stopFwdTraceAtMeet = false
                        stopBwdTraceAtMeet = false
                    }
                } else {
                    // Também verifica encontro direto com semente reversa (protege grafos de 1 aresta).
                    for (sIdx in bwdSeeds.indices) {
                        val bSeed = bwdSeeds[sIdx]
                        if (bSeed.node == u) {
                            val turn = if (effectivePrevE != -1 && bSeed.initEdge != -1) {
                                turnPenalty(graph, effectivePrevE, bSeed.initEdge, costParams)
                            } else 0.0
                            val cand = gU + bSeed.initCost + turn
                            if (cand < bestTotalCost) {
                                bestTotalCost = cand
                                meetIsDirectNode = true
                                meetNode = u
                                meetBridgeEdge = -1
                                stopFwdTraceAtMeet = false
                                stopBwdTraceAtMeet = true
                            }
                        }
                    }
                }

                val eStart = firstEdge[u]
                val eEnd = firstEdge[u + 1]
                for (e in eStart until eEnd) {
                    val v = edgeTo[e]
                    var stepCost = edgeBaseCost(graph, ws, e, costParams, zoneContext, useAltPenalty, altPenaltyFactor)
                    if (effectivePrevE != -1) {
                        stepCost += turnPenalty(graph, effectivePrevE, e, costParams)
                    } else if (originBearingDeg != null) {
                        stepCost += originHeadingPenalty(graph, e, originBearingDeg, costParams)
                    }
                    val newG = gU + stepCost

                    // Encontro por aresta ponte `e = (u -> v)` onde `v` já está fixado no reverso.
                    if (ws.bwdSettled[v] == gen) {
                        val bwdNext = ws.bwdNextEdge[v]
                        val effectiveNext = if (bwdNext != -1) bwdNext else ws.bwdSeedEdge[v]
                        val turnAtV = if (effectiveNext != -1) {
                            turnPenalty(graph, e, effectiveNext, costParams)
                        } else 0.0
                        val cand = newG + turnAtV + ws.bwdCost[v]
                        if (cand < bestTotalCost) {
                            bestTotalCost = cand
                            meetIsDirectNode = false
                            meetBridgeEdge = e
                            meetNode = -1
                            stopFwdTraceAtMeet = false
                            stopBwdTraceAtMeet = false
                        }
                    }

                    if (ws.fwdSettled[v] != gen) {
                        if (ws.fwdGen[v] != gen || newG < ws.fwdCost[v]) {
                            ws.fwdGen[v] = gen
                            ws.fwdCost[v] = newG
                            ws.fwdPrevEdge[v] = e
                            ws.fwdSeedEdge[v] = -1
                            fwdHeap.push(v, newG + potentialForward(v))
                        }
                    }
                }
            } else {
                val v = bwdHeap.popNode()
                if (ws.bwdSettled[v] == gen) continue
                ws.bwdSettled[v] = gen
                bwdSettledCount++
                val gV = ws.bwdCost[v]
                val nextE = ws.bwdNextEdge[v]
                val effectiveNextE = if (nextE != -1) nextE else ws.bwdSeedEdge[v]

                // Encontro direto em nó já fixado pelo lado direto.
                if (ws.fwdSettled[v] == gen) {
                    val prevE = ws.fwdPrevEdge[v]
                    val effectivePrevE = if (prevE != -1) prevE else ws.fwdSeedEdge[v]
                    val turn = if (effectivePrevE != -1 && effectiveNextE != -1) {
                        turnPenalty(graph, effectivePrevE, effectiveNextE, costParams)
                    } else 0.0
                    val cand = ws.fwdCost[v] + gV + turn
                    if (cand < bestTotalCost) {
                        bestTotalCost = cand
                        meetIsDirectNode = true
                        meetNode = v
                        meetBridgeEdge = -1
                        stopFwdTraceAtMeet = false
                        stopBwdTraceAtMeet = false
                    }
                } else {
                    for (sIdx in fwdSeeds.indices) {
                        val fSeed = fwdSeeds[sIdx]
                        if (fSeed.node == v) {
                            val turn = if (fSeed.initEdge != -1 && effectiveNextE != -1) {
                                turnPenalty(graph, fSeed.initEdge, effectiveNextE, costParams)
                            } else 0.0
                            val cand = fSeed.initCost + gV + turn
                            if (cand < bestTotalCost) {
                                bestTotalCost = cand
                                meetIsDirectNode = true
                                meetNode = v
                                meetBridgeEdge = -1
                                stopFwdTraceAtMeet = true
                                stopBwdTraceAtMeet = false
                            }
                        }
                    }
                }

                val rStart = revFirstEdge[v]
                val rEnd = revFirstEdge[v + 1]
                for (r in rStart until rEnd) {
                    val e = revEdgeIdx[r]
                    val u = edgeFrom[e]
                    var stepCost = edgeBaseCost(graph, ws, e, costParams, zoneContext, useAltPenalty, altPenaltyFactor)
                    if (effectiveNextE != -1) {
                        stepCost += turnPenalty(graph, e, effectiveNextE, costParams)
                    }
                    val newG = gV + stepCost

                    // Encontro por aresta ponte `e = (u -> v)` onde `u` já está fixado no direto.
                    if (ws.fwdSettled[u] == gen) {
                        val fwdPrev = ws.fwdPrevEdge[u]
                        val effectivePrev = if (fwdPrev != -1) fwdPrev else ws.fwdSeedEdge[u]
                        val turnAtU = if (effectivePrev != -1) {
                            turnPenalty(graph, effectivePrev, e, costParams)
                        } else if (originBearingDeg != null) {
                            originHeadingPenalty(graph, e, originBearingDeg, costParams)
                        } else 0.0
                        val cand = ws.fwdCost[u] + turnAtU + newG
                        if (cand < bestTotalCost) {
                            bestTotalCost = cand
                            meetIsDirectNode = false
                            meetBridgeEdge = e
                            meetNode = -1
                            stopFwdTraceAtMeet = false
                            stopBwdTraceAtMeet = false
                        }
                    }

                    if (ws.bwdSettled[u] != gen) {
                        if (ws.bwdGen[u] != gen || newG < ws.bwdCost[u]) {
                            ws.bwdGen[u] = gen
                            ws.bwdCost[u] = newG
                            ws.bwdNextEdge[u] = e
                            ws.bwdSeedEdge[u] = -1
                            bwdHeap.push(u, newG - potentialForward(u))
                        }
                    }
                }
            }
        }

        if (bestTotalCost == Double.POSITIVE_INFINITY) return null

        // Reconstrói lista de arestas completas do encontro até as sementes.
        val fwdTraceNode = if (meetIsDirectNode) meetNode else edgeFrom[meetBridgeEdge]
        val bwdTraceNode = if (meetIsDirectNode) meetNode else edgeTo[meetBridgeEdge]

        val fwdEdgesRev = ArrayList<Int>(32)
        var cur = fwdTraceNode
        var guard = 0
        if (!stopFwdTraceAtMeet) {
            while (ws.fwdPrevEdge[cur] != -1 && guard <= graph.nodeCount) {
                val e = ws.fwdPrevEdge[cur]
                fwdEdgesRev.add(e)
                cur = edgeFrom[e]
                guard++
            }
        }
        val winningStartNode = cur

        val bwdEdges = ArrayList<Int>(32)
        cur = bwdTraceNode
        guard = 0
        if (!stopBwdTraceAtMeet) {
            while (ws.bwdNextEdge[cur] != -1 && guard <= graph.nodeCount) {
                val e = ws.bwdNextEdge[cur]
                bwdEdges.add(e)
                cur = edgeTo[e]
                guard++
            }
        }
        val winningEndNode = cur

        val totalEdgeCount = fwdEdgesRev.size + (if (meetIsDirectNode) 0 else 1) + bwdEdges.size
        val resultEdges = IntArray(totalEdgeCount)
        var pos = 0
        for (i in fwdEdgesRev.lastIndex downTo 0) {
            resultEdges[pos++] = fwdEdgesRev[i]
        }
        if (!meetIsDirectNode) {
            resultEdges[pos++] = meetBridgeEdge
        }
        for (i in bwdEdges.indices) {
            resultEdges[pos++] = bwdEdges[i]
        }

        val chosenStartSeed = fwdSeeds.firstOrNull { it.node == winningStartNode }
        val chosenEndSeed = bwdSeeds.firstOrNull { it.node == winningEndNode }

        return LegPathSolution(
            edges = resultEdges,
            startSeed = chosenStartSeed,
            endSeed = chosenEndSeed,
            sameEdgeDirect = null,
            sameEdgeStartFrac = 0.0,
            sameEdgeEndFrac = 0.0,
            searchCost = bestTotalCost
        )
    }

    // -------------------------------------------------------------------------
    // 1-para-Muitos Dijkstra exato (Matriz NxN para TSP)
    // -------------------------------------------------------------------------

    private fun runOneToManyDijkstra(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        originNode: Int,
        targetNodes: IntArray,
        originIdx: Int,
        costParams: CostParams,
        zoneContext: AvoidZoneContext,
        outRowSeconds: DoubleArray
    ) {
        val gen = ws.nextGeneration()
        val tGen = ws.nextTargetGen()
        var uniqueTargetsRemaining = 0

        for (j in targetNodes.indices) {
            if (j == originIdx) continue
            val tNode = targetNodes[j]
            if (tNode == originNode) {
                outRowSeconds[j] = 0.0
                continue
            }
            if (ws.targetGen[tNode] != tGen) {
                ws.targetGen[tNode] = tGen
                uniqueTargetsRemaining++
            }
        }
        if (uniqueTargetsRemaining == 0) return

        val heap = ws.fwdHeap
        heap.clear()

        ws.fwdGen[originNode] = gen
        ws.fwdCost[originNode] = 0.0
        ws.fwdPrevEdge[originNode] = -1
        ws.fwdTrueTimeSec[originNode] = 0f
        heap.push(originNode, 0.0)

        val firstEdge = graph.firstEdge
        val edgeTo = graph.edgeTo

        while (!heap.isEmpty && uniqueTargetsRemaining > 0) {
            val u = heap.popNode()
            if (ws.fwdSettled[u] == gen) continue
            ws.fwdSettled[u] = gen

            val gU = ws.fwdCost[u]
            val tU = ws.fwdTrueTimeSec[u].toDouble()

            if (ws.targetGen[u] == tGen) {
                uniqueTargetsRemaining--
                for (j in targetNodes.indices) {
                    if (j != originIdx && targetNodes[j] == u) {
                        outRowSeconds[j] = tU
                    }
                }
                if (uniqueTargetsRemaining == 0) break
            }

            val prevE = ws.fwdPrevEdge[u]
            val eStart = firstEdge[u]
            val eEnd = firstEdge[u + 1]
            for (e in eStart until eEnd) {
                val v = edgeTo[e]
                if (ws.fwdSettled[v] == gen) continue

                var stepCost = edgeBaseCost(graph, ws, e, costParams, zoneContext, useAltPenalty = false, altPenaltyFactor = 1.0)
                if (prevE != -1) {
                    stepCost += turnPenalty(graph, prevE, e, costParams)
                }
                val newG = gU + stepCost
                if (ws.fwdGen[v] != gen || newG < ws.fwdCost[v]) {
                    ws.fwdGen[v] = gen
                    ws.fwdCost[v] = newG
                    ws.fwdPrevEdge[v] = e
                    val avoidSurcharge = if (zoneContext.hasAnyAvoidedEdge && ws.avoidedEdges.get(e)) 600.0 else 0.0
                    ws.fwdTrueTimeSec[v] = (tU + trueEdgeDurationSeconds(graph, e, costParams.profile) + avoidSurcharge).toFloat()
                    heap.push(v, newG)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Custeio Dinâmico, Penalidades de Conversão & Lista Negra
    // -------------------------------------------------------------------------

    private fun trueEdgeDurationSeconds(graph: DfrgGraph, edgeId: Int, profile: RouteProfile): Double {
        val baseSec = (graph.edgeTimeDs[edgeId] * 0.1).coerceAtLeast(0.1)
        if (profile != RouteProfile.MOTO_FASTEST) return baseSec
        val rc = graph.roadClass(edgeId)
        val motoFactor = when (rc) {
            DfrgGraph.ROAD_CLASS_MOTORWAY, DfrgGraph.ROAD_CLASS_TRUNK -> 0.96
            DfrgGraph.ROAD_CLASS_PRIMARY, DfrgGraph.ROAD_CLASS_SECONDARY -> 0.86
            DfrgGraph.ROAD_CLASS_TERTIARY, DfrgGraph.ROAD_CLASS_RESIDENTIAL -> 0.88
            else -> 0.94
        }
        return baseSec * motoFactor
    }

    private fun edgeBaseCost(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        edgeId: Int,
        params: CostParams,
        zoneContext: AvoidZoneContext,
        useAltPenalty: Boolean,
        altPenaltyFactor: Double
    ): Double {
        val flags = graph.edgeFlags[edgeId].toInt() and 0xFFFF
        val rc = flags and DfrgGraph.ROAD_CLASS_MASK

        var cost: Double
        if (params.isShortest) {
            cost = graph.edgeDistM[edgeId].toDouble().coerceAtLeast(1.0)
            if (rc == DfrgGraph.ROAD_CLASS_SERVICE) cost *= 1.18
            else if (rc == DfrgGraph.ROAD_CLASS_TRACK) cost *= 1.45
            if ((flags and DfrgGraph.FLAG_UNPAVED) != 0) {
                cost *= if (params.avoidUnpaved) 1.85 else 1.20
            }
            if ((flags and DfrgGraph.FLAG_TOLL) != 0 && params.avoidTolls) {
                cost *= 4.0
            }
        } else {
            val baseSec = (graph.edgeTimeDs[edgeId] * 0.1).coerceAtLeast(0.1)
            val classFactor = if (params.isMoto) {
                MOTO_CLASS_FACTOR[rc.coerceIn(0, MOTO_CLASS_FACTOR.lastIndex)]
            } else {
                CAR_CLASS_FACTOR[rc.coerceIn(0, CAR_CLASS_FACTOR.lastIndex)]
            }
            cost = baseSec * classFactor
            if ((flags and DfrgGraph.FLAG_UNPAVED) != 0) {
                cost *= if (params.avoidUnpaved) {
                    if (params.isMoto) 2.8 else 2.4
                } else {
                    1.35
                }
            }
            if ((flags and DfrgGraph.FLAG_TOLL) != 0 && params.avoidTolls) {
                cost *= 5.0
            }
            if ((flags and DfrgGraph.FLAG_LINK) != 0) {
                cost *= 1.05
            }
        }

        // Evasão dinâmica da Lista Negra (soft-hard: desvia ao redor, mas permite saída se preso).
        if (zoneContext.hasAnyAvoidedEdge && ws.avoidedEdges.get(edgeId)) {
            val isHard = zoneContext.hasHardAvoidEdge && ws.hardAvoidedEdges.get(edgeId)
            if (params.isShortest) {
                cost = cost * (if (isHard) 60.0 else 25.0) + (if (isHard) 12_000.0 else 4_500.0)
            } else {
                cost = cost * (if (isHard) 60.0 else 25.0) + (if (isHard) 1_800.0 else 600.0)
            }
        }

        if (useAltPenalty && ws.altPenalizedEdges.get(edgeId)) {
            cost *= altPenaltyFactor
        }

        return cost
    }

    /**
     * Penalidade de conversão em tempo O(1) sem trigonometria usando ângulos quantizados (1 byte).
     */
    private fun turnPenalty(
        graph: DfrgGraph,
        prevEdge: Int,
        nextEdge: Int,
        params: CostParams
    ): Double {
        // Sem penalidade artificial dentro de uma rotatória.
        if (graph.isRoundabout(prevEdge) && graph.isRoundabout(nextEdge)) return 0.0

        val isReverse = graph.edgeFrom[prevEdge] == graph.edgeTo[nextEdge]
        val endB = graph.edgeEndAngleByte[prevEdge].toInt() and 0xFF
        val startB = graph.edgeStartAngleByte[nextEdge].toInt() and 0xFF
        var signedSteps = (startB - endB) and 0xFF
        if (signedSteps >= 128) signedSteps -= 256 // [-128..127], 1 step = 1.40625°
        val absSteps = abs(signedSteps)

        var penaltySec = 0.0
        when {
            isReverse || absSteps >= 107 -> { // >= 150° -> retorno
                penaltySec = if (params.isMoto) 15.0 else 25.0
            }
            signedSteps <= -32 -> { // -150°..-45° -> esquerda (cruzando faixa contrária no BR)
                val prevClass = graph.roadClass(prevEdge)
                val nextClass = graph.roadClass(nextEdge)
                val crossingArterial = prevClass <= DfrgGraph.ROAD_CLASS_SECONDARY || nextClass <= DfrgGraph.ROAD_CLASS_SECONDARY
                penaltySec = when {
                    crossingArterial && params.isMoto -> 2.5
                    crossingArterial -> 4.5
                    params.isMoto -> 1.0
                    else -> 2.0
                }
            }
            signedSteps >= 32 -> { // +45°..+150° -> direita
                penaltySec = if (params.isMoto) 1.0 else 1.5
            }
            absSteps >= 15 -> { // 21°..45° -> curva leve
                penaltySec = 0.3
            }
        }

        // Penaliza saída de via arterial para rua residencial/local para evitar zigue-zague.
        val prevClass = graph.roadClass(prevEdge)
        val nextClass = graph.roadClass(nextEdge)
        if (prevClass <= DfrgGraph.ROAD_CLASS_PRIMARY && nextClass >= DfrgGraph.ROAD_CLASS_RESIDENTIAL) {
            penaltySec += if (params.isMoto) 1.5 else 3.0
        }

        return if (params.isShortest) penaltySec * 2.5 else penaltySec
    }

    private fun originHeadingPenalty(
        graph: DfrgGraph,
        firstEdge: Int,
        originBearingDeg: Float,
        params: CostParams
    ): Double {
        val edgeBearing = ((graph.edgeStartAngleByte[firstEdge].toInt() and 0xFF) * (360.0 / 256.0)).toFloat()
        val diff = DfrgGraph.absAngleDiffDeg(originBearingDeg, edgeBearing)
        val sec = when {
            diff >= 135f -> 18.0
            diff >= 90f -> 5.0
            else -> 0.0
        }
        return if (params.isShortest) sec * 4.0 else sec
    }

    private fun prepareAvoidZones(
        graph: DfrgGraph,
        ws: RoutingWorkspace,
        avoidZones: List<AvoidZone>
    ): AvoidZoneContext {
        ws.avoidedEdges.clear()
        ws.hardAvoidedEdges.clear()
        if (avoidZones.isEmpty() || graph.edgeCount == 0) {
            return AvoidZoneContext(avoidZones, hasAnyAvoidedEdge = false, hasHardAvoidEdge = false)
        }

        val zoneCount = avoidZones.size
        val zCenterLatE6 = IntArray(zoneCount)
        val zCenterLngE6 = IntArray(zoneCount)
        val zRadiusSqM = DoubleArray(zoneCount)
        val zMinLatE6 = IntArray(zoneCount)
        val zMaxLatE6 = IntArray(zoneCount)
        val zMinLngE6 = IntArray(zoneCount)
        val zMaxLngE6 = IntArray(zoneCount)
        val zHard = BooleanArray(zoneCount)

        for (z in 0 until zoneCount) {
            val zone = avoidZones[z]
            val cLat = (zone.center.lat * 1_000_000.0).toInt()
            val cLng = (zone.center.lng * 1_000_000.0).toInt()
            zCenterLatE6[z] = cLat
            zCenterLngE6[z] = cLng
            zRadiusSqM[z] = zone.radiusMeters * zone.radiusMeters
            zHard[z] = zone.hardAvoid

            val dLatE6 = ((zone.radiusMeters / METERS_PER_E6_LAT) * 1.05).toInt()
            val cosLat = cos(Math.toRadians(zone.center.lat)).coerceAtLeast(0.05)
            val dLngE6 = ((zone.radiusMeters / (METERS_PER_E6_LAT * cosLat)) * 1.05).toInt()
            var minLat = cLat - dLatE6
            var maxLat = cLat + dLatE6
            var minLng = cLng - dLngE6
            var maxLng = cLng + dLngE6

            for (p in zone.polygon) {
                val pLat = (p.lat * 1_000_000.0).toInt()
                val pLng = (p.lng * 1_000_000.0).toInt()
                if (pLat < minLat) minLat = pLat
                if (pLat > maxLat) maxLat = pLat
                if (pLng < minLng) minLng = pLng
                if (pLng > maxLng) maxLng = pLng
            }
            zMinLatE6[z] = minLat
            zMaxLatE6[z] = maxLat
            zMinLngE6[z] = minLng
            zMaxLngE6[z] = maxLng
        }

        val lngScale = METERS_PER_E6_LAT * graph.refCosLat
        var anyAvoided = false
        var anyHard = false

        val nodeLatE6 = graph.nodeLatE6
        val nodeLngE6 = graph.nodeLngE6
        val edgeFrom = graph.edgeFrom
        val edgeTo = graph.edgeTo
        val edgeGeomOffset = graph.edgeGeomOffset
        val geomLatE6 = graph.geomLatE6
        val geomLngE6 = graph.geomLngE6

        for (e in 0 until graph.edgeCount) {
            val u = edgeFrom[e]
            val v = edgeTo[e]
            val uLat = nodeLatE6[u]
            val uLng = nodeLngE6[u]
            val vLat = nodeLatE6[v]
            val vLng = nodeLngE6[v]
            val gStart = edgeGeomOffset[e]
            val gEnd = edgeGeomOffset[e + 1]

            // BBox da aresta (incluindo pontos intermediários, se houver).
            var eMinLat = min(uLat, vLat)
            var eMaxLat = max(uLat, vLat)
            var eMinLng = min(uLng, vLng)
            var eMaxLng = max(uLng, vLng)
            for (gi in gStart until gEnd) {
                val glat = geomLatE6[gi]
                val glng = geomLngE6[gi]
                if (glat < eMinLat) eMinLat = glat
                if (glat > eMaxLat) eMaxLat = glat
                if (glng < eMinLng) eMinLng = glng
                if (glng > eMaxLng) eMaxLng = glng
            }

            for (z in 0 until zoneCount) {
                // Rejeição rápida por AABB inteira.
                if (eMaxLat < zMinLatE6[z] || eMinLat > zMaxLatE6[z] ||
                    eMaxLng < zMinLngE6[z] || eMinLng > zMaxLngE6[z]
                ) {
                    continue
                }

                if (edgeIntersectsAvoidZone(
                        uLat, uLng, vLat, vLng, gStart, gEnd, geomLatE6, geomLngE6,
                        zCenterLatE6[z], zCenterLngE6[z], zRadiusSqM[z], avoidZones[z].polygon, lngScale
                    )
                ) {
                    ws.avoidedEdges.set(e)
                    anyAvoided = true
                    if (zHard[z]) {
                        ws.hardAvoidedEdges.set(e)
                        anyHard = true
                    }
                    break
                }
            }
        }

        return AvoidZoneContext(avoidZones, hasAnyAvoidedEdge = anyAvoided, hasHardAvoidEdge = anyHard)
    }

    private fun edgeIntersectsAvoidZone(
        uLat: Int,
        uLng: Int,
        vLat: Int,
        vLng: Int,
        gStart: Int,
        gEnd: Int,
        geomLatE6: IntArray,
        geomLngE6: IntArray,
        centerLatE6: Int,
        centerLngE6: Int,
        radiusSqM: Double,
        polygon: List<GeoPoint>,
        lngScale: Double
    ): Boolean {
        val segCount = (gEnd - gStart) + 1
        var prevLat = uLat
        var prevLng = uLng
        val hasPolygon = polygon.size >= 3

        for (s in 0 until segCount) {
            val nextLat: Int
            val nextLng: Int
            if (s < gEnd - gStart) {
                val gi = gStart + s
                nextLat = geomLatE6[gi]
                nextLng = geomLngE6[gi]
            } else {
                nextLat = vLat
                nextLng = vLng
            }

            // 1) Distância mínima do segmento [prev -> next] ao centro do círculo.
            val ax = (prevLng - centerLngE6) * lngScale
            val ay = (prevLat - centerLatE6) * METERS_PER_E6_LAT
            val bx = (nextLng - centerLngE6) * lngScale
            val by = (nextLat - centerLatE6) * METERS_PER_E6_LAT
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq > 1e-6) (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            if (px * px + py * py <= radiusSqM) return true

            // 2) Teste de ponto em polígono (cobre polígonos customizados não-circulares).
            if (hasPolygon) {
                if (pointInPolygon(prevLat * 1e-6, prevLng * 1e-6, polygon)) return true
                val midLat = ((prevLat.toLong() + nextLat.toLong()) * 0.5) * 1e-6
                val midLng = ((prevLng.toLong() + nextLng.toLong()) * 0.5) * 1e-6
                if (pointInPolygon(midLat, midLng, polygon)) return true
            }

            prevLat = nextLat
            prevLng = nextLng
        }

        if (hasPolygon && pointInPolygon(vLat * 1e-6, vLng * 1e-6, polygon)) return true
        return false
    }

    private fun routeIntersectsZone(geometry: List<GeoPoint>, zone: AvoidZone): Boolean {
        for (p in geometry) {
            if (zone.contains(p)) return true
            if (zone.polygon.size >= 3 && pointInPolygon(p.lat, p.lng, zone.polygon)) return true
        }
        return false
    }

    private fun pointInPolygon(lat: Double, lng: Double, poly: List<GeoPoint>): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val yi = poly[i].lat
            val xi = poly[i].lng
            val yj = poly[j].lat
            val xj = poly[j].lng
            val intersect = ((yi > lat) != (yj > lat)) &&
                (lng < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { abs(it) > 1e-12 } ?: 1e-12) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    // -------------------------------------------------------------------------
    // Helpers de Geometria
    // -------------------------------------------------------------------------

    private fun appendFullEdgeGeometry(graph: DfrgGraph, edgeId: Int, out: MutableList<GeoPoint>): Int {
        val u = graph.edgeFrom[edgeId]
        val startPt = graph.nodePoint(u)
        val startIdx = appendPointDedup(out, startPt)
        val gStart = graph.edgeGeomOffset[edgeId]
        val gEnd = graph.edgeGeomOffset[edgeId + 1]
        for (i in gStart until gEnd) {
            appendPointDedup(out, GeoPoint(graph.geomLatE6[i] * 1e-6, graph.geomLngE6[i] * 1e-6))
        }
        val v = graph.edgeTo[edgeId]
        appendPointDedup(out, graph.nodePoint(v))
        return startIdx
    }

    private fun appendPartialEdgeGeometry(
        graph: DfrgGraph,
        edgeId: Int,
        startFrac: Double,
        endFrac: Double,
        out: MutableList<GeoPoint>
    ): Int {
        val full = ArrayList<GeoPoint>(8)
        graph.appendEdgeGeometry(edgeId, full, includeStart = true)
        if (full.size < 2) {
            return appendPointDedup(out, full.firstOrNull() ?: graph.nodePoint(graph.edgeFrom[edgeId]))
        }

        val cum = DoubleArray(full.size)
        for (i in 1 until full.size) {
            cum[i] = cum[i - 1] + full[i - 1].distanceToMeters(full[i])
        }
        val totalLen = cum.last().coerceAtLeast(1e-6)
        val d0 = totalLen * startFrac.coerceIn(0.0, 1.0)
        val d1 = totalLen * endFrac.coerceIn(startFrac.coerceIn(0.0, 1.0), 1.0)

        val pStart = interpolatePolyline(full, cum, d0)
        val startIdx = appendPointDedup(out, pStart)
        for (i in 1 until full.lastIndex) {
            if (cum[i] > d0 + 0.5 && cum[i] < d1 - 0.5) {
                appendPointDedup(out, full[i])
            }
        }
        val pEnd = interpolatePolyline(full, cum, d1)
        appendPointDedup(out, pEnd)
        return startIdx
    }

    private fun interpolatePolyline(pts: List<GeoPoint>, cum: DoubleArray, distM: Double): GeoPoint {
        if (distM <= 0.0) return pts.first()
        if (distM >= cum.last()) return pts.last()
        for (i in 1 until pts.size) {
            if (cum[i] >= distM) {
                val segLen = (cum[i] - cum[i - 1]).coerceAtLeast(1e-6)
                val t = ((distM - cum[i - 1]) / segLen).coerceIn(0.0, 1.0)
                val a = pts[i - 1]
                val b = pts[i]
                return GeoPoint(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
            }
        }
        return pts.last()
    }

    private fun appendPointDedup(out: MutableList<GeoPoint>, pt: GeoPoint): Int {
        if (out.isNotEmpty()) {
            val last = out[out.lastIndex]
            if (abs(last.lat - pt.lat) < 1e-7 && abs(last.lng - pt.lng) < 1e-7) {
                return out.lastIndex
            }
        }
        out.add(pt)
        return out.lastIndex
    }

    private fun resolveDfrgFile(packDir: File): File? {
        if (packDir.isFile && packDir.exists()) return packDir
        val nested = File(packDir, "routing/routing.dfrg")
        if (nested.isFile) return nested
        val flat = File(packDir, "routing.dfrg")
        if (flat.isFile) return flat
        return null
    }

    // -------------------------------------------------------------------------
    // Estruturas primitivas reutilizáveis (zero GC churn por consulta)
    // -------------------------------------------------------------------------

    private class RoutingWorkspace(nodeCount: Int, edgeCount: Int) {
        val fwdGen = IntArray(nodeCount)
        val fwdSettled = IntArray(nodeCount)
        val fwdCost = DoubleArray(nodeCount)
        val fwdPrevEdge = IntArray(nodeCount)
        val fwdSeedEdge = IntArray(nodeCount)
        val fwdTrueTimeSec = FloatArray(nodeCount)

        val bwdGen = IntArray(nodeCount)
        val bwdSettled = IntArray(nodeCount)
        val bwdCost = DoubleArray(nodeCount)
        val bwdNextEdge = IntArray(nodeCount)
        val bwdSeedEdge = IntArray(nodeCount)

        val targetGen = IntArray(nodeCount)

        val avoidedEdges = BitSet(edgeCount)
        val hardAvoidedEdges = BitSet(edgeCount)
        val altPenalizedEdges = BitSet(edgeCount)

        val fwdHeap = PrimitiveMinHeap(4096)
        val bwdHeap = PrimitiveMinHeap(4096)

        private var generation = 0
        private var targetGeneration = 0

        fun nextGeneration(): Int {
            if (generation == Int.MAX_VALUE - 1) {
                fwdGen.fill(0)
                fwdSettled.fill(0)
                bwdGen.fill(0)
                bwdSettled.fill(0)
                generation = 0
            }
            generation++
            return generation
        }

        fun nextTargetGen(): Int {
            if (targetGeneration == Int.MAX_VALUE - 1) {
                targetGen.fill(0)
                targetGeneration = 0
            }
            targetGeneration++
            return targetGeneration
        }
    }

    /** Binary Min-Heap puramente primitivo (Int node + Double key), zero boxing. */
    private class PrimitiveMinHeap(initialCapacity: Int) {
        private var nodes = IntArray(initialCapacity)
        private var keys = DoubleArray(initialCapacity)
        var size: Int = 0
            private set

        val isEmpty: Boolean get() = size == 0

        fun clear() {
            size = 0
        }

        fun peekKey(): Double = keys[0]

        fun push(node: Int, key: Double) {
            if (size == nodes.size) {
                val newCap = nodes.size * 2
                nodes = nodes.copyOf(newCap)
                keys = keys.copyOf(newCap)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) ushr 1
                if (keys[parent] <= key) break
                nodes[i] = nodes[parent]
                keys[i] = keys[parent]
                i = parent
            }
            nodes[i] = node
            keys[i] = key
        }

        fun popNode(): Int {
            val top = nodes[0]
            val lastIdx = --size
            if (lastIdx > 0) {
                val movedNode = nodes[lastIdx]
                val movedKey = keys[lastIdx]
                var i = 0
                val half = lastIdx ushr 1
                while (i < half) {
                    var child = (i shl 1) + 1
                    var childKey = keys[child]
                    val right = child + 1
                    if (right < lastIdx && keys[right] < childKey) {
                        child = right
                        childKey = keys[right]
                    }
                    if (movedKey <= childKey) break
                    nodes[i] = nodes[child]
                    keys[i] = childKey
                    i = child
                }
                nodes[i] = movedNode
                keys[i] = movedKey
            }
            return top
        }
    }

    companion object {
        private const val MAX_SNAP_RADIUS_M = 2_000.0
        private const val NODE_SNAP_THRESHOLD_M = 12.0
        private const val MAX_ALTERNATIVES = 2
        private const val MAX_ALT_COST_RATIO = 1.45
        private const val MAX_ALT_OVERLAP_RATIO = 0.80
        private const val UNREACHABLE_TIME_SECONDS = 1_000_000.0
        private const val METERS_PER_E6_LAT = 0.11131949079327358

        // Fatores por classe viária (0=motorway..8=track) para preferir avenidas fluidas.
        private val CAR_CLASS_FACTOR = doubleArrayOf(
            0.96, // 0 motorway
            0.98, // 1 trunk
            1.00, // 2 primary
            1.02, // 3 secondary
            1.05, // 4 tertiary
            1.14, // 5 residential
            1.35, // 6 living_street / service
            1.10, // 7 unclassified
            1.65  // 8 track / unpaved
        )

        private val MOTO_CLASS_FACTOR = doubleArrayOf(
            0.95, // 0 motorway
            0.92, // 1 trunk
            0.85, // 2 primary
            0.86, // 3 secondary
            0.88, // 4 tertiary
            0.96, // 5 residential
            1.22, // 6 living_street / service
            0.94, // 7 unclassified
            1.80  // 8 track / unpaved
        )
    }
}
