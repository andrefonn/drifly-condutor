package com.drifly.condutor.map.routing

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Grafo viário binário compacto em formato `DFRG` v1 (DriFly Road Graph).
 *
 * Projetado para rodar em aparelhos com pouca RAM (~4-6 MB para Joinville completa,
 * 0 KB de `.so`, carregamento em <20 ms e zero boxing/GC nos loops quentes de roteamento).
 *
 * Estrutura em memória:
 * - CSR direto (`firstEdge`, `edgeTo`, `edgeDistM`, `edgeTimeDs`, `edgeFlags`, `edgeNameId`, `edgeGeomOffset`)
 * - CSR reverso (`revFirstEdge`, `revEdgeIdx`, `edgeFrom`) para A* bidirecional
 * - Ângulos de saída/chegada quantizados em 1 byte (`edgeStartAngleByte`, `edgeEndAngleByte`)
 *   para penalidades de conversão sem trigonometria no hot loop
 * - Índice espacial em grade plana CSR (`cellFirstNode`, `cellNodes`) sem `HashMap`
 */
class DfrgGraph internal constructor(
    val version: Int,
    val headerFlags: Int,
    val nodeCount: Int,
    val edgeCount: Int,
    val nameCount: Int,
    val geomPointCount: Int,
    val bboxMinLatE6: Int,
    val bboxMinLngE6: Int,
    val bboxMaxLatE6: Int,
    val bboxMaxLngE6: Int,
    val nodeLatE6: IntArray,
    val nodeLngE6: IntArray,
    val firstEdge: IntArray,
    val edgeTo: IntArray,
    val edgeDistM: IntArray,
    val edgeTimeDs: IntArray,
    val edgeFlags: ShortArray,
    val edgeNameId: IntArray,
    val edgeGeomOffset: IntArray,
    val geomLatE6: IntArray,
    val geomLngE6: IntArray,
    val streetNames: Array<String>,
    val edgeFrom: IntArray,
    val revFirstEdge: IntArray,
    val revEdgeIdx: IntArray,
    val edgeStartAngleByte: ByteArray,
    val edgeEndAngleByte: ByteArray,
    val maxObservedSpeedMps: Double,
    val refCosLat: Double,
    private val gridCellSizeE6: Int,
    private val gridCols: Int,
    private val gridRows: Int,
    private val cellFirstNode: IntArray,
    private val cellNodes: IntArray
) {
    /** Resultado de snap em aresta/geometria com projeção exata. */
    data class EdgeSnap(
        val edgeId: Int,
        val fromNode: Int,
        val toNode: Int,
        val snappedPoint: GeoPoint,
        val distanceMeters: Double,
        val fractionAlongEdge: Double,
        val segmentBearingDeg: Float
    )

    fun nodeLat(nodeId: Int): Double = nodeLatE6[nodeId] * INV_E6
    fun nodeLng(nodeId: Int): Double = nodeLngE6[nodeId] * INV_E6
    fun nodePoint(nodeId: Int): GeoPoint = GeoPoint(nodeLatE6[nodeId] * INV_E6, nodeLngE6[nodeId] * INV_E6)

    fun edgeFlagsUnsigned(edgeId: Int): Int = edgeFlags[edgeId].toInt() and 0xFFFF
    fun roadClass(edgeId: Int): Int = edgeFlags[edgeId].toInt() and ROAD_CLASS_MASK
    fun isOneway(edgeId: Int): Boolean = (edgeFlags[edgeId].toInt() and FLAG_ONEWAY) != 0
    fun isRoundabout(edgeId: Int): Boolean = (edgeFlags[edgeId].toInt() and FLAG_ROUNDABOUT) != 0
    fun isToll(edgeId: Int): Boolean = (edgeFlags[edgeId].toInt() and FLAG_TOLL) != 0
    fun isUnpaved(edgeId: Int): Boolean = (edgeFlags[edgeId].toInt() and FLAG_UNPAVED) != 0
    fun isBridge(edgeId: Int): Boolean = (edgeFlags[edgeId].toInt() and FLAG_BRIDGE) != 0
    fun isTunnel(edgeId: Int): Boolean = (edgeFlags[edgeId].toInt() and FLAG_TUNNEL) != 0
    fun isLink(edgeId: Int): Boolean = (edgeFlags[edgeId].toInt() and FLAG_LINK) != 0

    fun edgeName(edgeId: Int): String {
        val id = edgeNameId[edgeId]
        return if (id in streetNames.indices) streetNames[id] else ""
    }

    /** Distância equiretangular rápida em metros entre dois nós (satisfaz desigualdade triangular). */
    fun fastNodeDistanceMeters(u: Int, v: Int): Double {
        val dLatM = (nodeLatE6[v] - nodeLatE6[u]) * METERS_PER_E6_LAT
        val dLngM = (nodeLngE6[v] - nodeLngE6[u]) * METERS_PER_E6_LAT * refCosLat
        return sqrt(dLatM * dLatM + dLngM * dLngM)
    }

    /** Verifica se existe aresta inversa direta (`v -> u`) para [edgeId] (`u -> v`). */
    fun findReverseEdge(edgeId: Int): Int {
        val u = edgeFrom[edgeId]
        val v = edgeTo[edgeId]
        val start = firstEdge[v]
        val end = firstEdge[v + 1]
        for (e in start until end) {
            if (edgeTo[e] == u) return e
        }
        return -1
    }

    /**
     * Encontra o nó viário mais próximo de ([lat], [lng]) dentro de [maxRadiusM] metros.
     * Usa busca por anéis expansivos na grade espacial plana CSR (sem alocações por consulta).
     * Retorna `-1` se nenhum nó estiver dentro do raio.
     */
    fun nearestNode(lat: Double, lng: Double, maxRadiusM: Double = 2_000.0): Int {
        if (nodeCount == 0 || maxRadiusM <= 0.0) return -1

        val qLatE6 = (lat * 1_000_000.0).toInt()
        val qLngE6 = (lng * 1_000_000.0).toInt()
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        val lngScale = METERS_PER_E6_LAT * cosLat

        val cellHeightM = gridCellSizeE6 * METERS_PER_E6_LAT
        val cellWidthM = gridCellSizeE6 * lngScale
        val minCellSpanM = min(cellHeightM, cellWidthM).coerceAtLeast(1.0)

        val rawRow = floor((qLatE6 - bboxMinLatE6).toDouble() / gridCellSizeE6).toInt()
        val rawCol = floor((qLngE6 - bboxMinLngE6).toDouble() / gridCellSizeE6).toInt()
        val centerRow = rawRow.coerceIn(0, gridRows - 1)
        val centerCol = rawCol.coerceIn(0, gridCols - 1)

        // Limite máximo de anéis considerando que o ponto pode estar ligeiramente fora da bbox.
        val outsideRowGap = max(0, max(-rawRow, rawRow - (gridRows - 1)))
        val outsideColGap = max(0, max(-rawCol, rawCol - (gridCols - 1)))
        val maxRing = ceil(maxRadiusM / minCellSpanM).toInt() + max(outsideRowGap, outsideColGap) + 1
        val gridMaxRing = max(max(centerRow, gridRows - 1 - centerRow), max(centerCol, gridCols - 1 - centerCol))
        val ringLimit = min(maxRing, gridMaxRing)

        var bestNode = -1
        var bestDistSq = maxRadiusM * maxRadiusM

        for (ring in 0..ringLimit) {
            // Distância mínima possível a qualquer célula neste anel a partir da célula clamped.
            if (ring >= 2 && bestNode != -1) {
                val minPossibleDistM = (ring - 1) * minCellSpanM
                if (minPossibleDistM * minPossibleDistM > bestDistSq && outsideRowGap == 0 && outsideColGap == 0) {
                    break
                }
            }

            val rMin = max(0, centerRow - ring)
            val rMax = min(gridRows - 1, centerRow + ring)
            val cMin = max(0, centerCol - ring)
            val cMax = min(gridCols - 1, centerCol + ring)

            for (r in rMin..rMax) {
                val rowOffset = r * gridCols
                for (c in cMin..cMax) {
                    // Em anéis > 0, visitar somente o perímetro.
                    if (ring > 0 && r > rMin && r < rMax && c > cMin && c < cMax) continue
                    val cellIdx = rowOffset + c
                    val start = cellFirstNode[cellIdx]
                    val end = cellFirstNode[cellIdx + 1]
                    for (i in start until end) {
                        val n = cellNodes[i]
                        val dLatM = (nodeLatE6[n] - qLatE6) * METERS_PER_E6_LAT
                        val dLngM = (nodeLngE6[n] - qLngE6) * lngScale
                        val dSq = dLatM * dLatM + dLngM * dLngM
                        if (dSq < bestDistSq) {
                            bestDistSq = dSq
                            bestNode = n
                        }
                    }
                }
            }
        }
        return bestNode
    }

    /**
     * Projeta ([lat], [lng]) na aresta direcionada mais próxima (incluindo geometria intermediária
     * de arestas contraídas) respeitando opcionalmente [preferredBearingDeg] para evitar snap na
     * pista contrária de avenidas duplicadas/mão única.
     */
    fun nearestEdge(
        lat: Double,
        lng: Double,
        maxRadiusM: Double = 2_000.0,
        preferredBearingDeg: Float? = null
    ): EdgeSnap? {
        if (nodeCount == 0 || edgeCount == 0 || maxRadiusM <= 0.0) return null

        val nearestN = nearestNode(lat, lng, maxRadiusM)
        if (nearestN == -1) return null

        val qLatE6 = (lat * 1_000_000.0).toInt()
        val qLngE6 = (lng * 1_000_000.0).toInt()
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.05)
        val lngScale = METERS_PER_E6_LAT * cosLat

        val dNodeLatM = (nodeLatE6[nearestN] - qLatE6) * METERS_PER_E6_LAT
        val dNodeLngM = (nodeLngE6[nearestN] - qLngE6) * lngScale
        val nearestNodeDistM = sqrt(dNodeLatM * dNodeLatM + dNodeLngM * dNodeLngM)

        // Expande o raio de busca de nós para cobrir extremidades de arestas contraídas longas.
        val searchRadiusM = min(maxRadiusM, max(450.0, nearestNodeDistM * 1.35 + 150.0))
        val cellHeightM = gridCellSizeE6 * METERS_PER_E6_LAT
        val cellWidthM = gridCellSizeE6 * lngScale
        val rSpan = ceil(searchRadiusM / cellHeightM).toInt() + 1
        val cSpan = ceil(searchRadiusM / cellWidthM).toInt() + 1

        val centerRow = floor((qLatE6 - bboxMinLatE6).toDouble() / gridCellSizeE6).toInt().coerceIn(0, gridRows - 1)
        val centerCol = floor((qLngE6 - bboxMinLngE6).toDouble() / gridCellSizeE6).toInt().coerceIn(0, gridCols - 1)

        val rMin = max(0, centerRow - rSpan)
        val rMax = min(gridRows - 1, centerRow + rSpan)
        val cMin = max(0, centerCol - cSpan)
        val cMax = min(gridCols - 1, centerCol + cSpan)

        var bestEdge = -1
        var bestScore = Double.POSITIVE_INFINITY
        var bestActualDistM = Double.POSITIVE_INFINITY
        var bestProjLatE6 = 0
        var bestProjLngE6 = 0
        var bestFraction = 0.0
        var bestBearing = 0f

        for (r in rMin..rMax) {
            val rowOffset = r * gridCols
            for (c in cMin..cMax) {
                val cellIdx = rowOffset + c
                val cStart = cellFirstNode[cellIdx]
                val cEnd = cellFirstNode[cellIdx + 1]
                for (i in cStart until cEnd) {
                    val u = cellNodes[i]
                    val eStart = firstEdge[u]
                    val eEnd = firstEdge[u + 1]
                    for (e in eStart until eEnd) {
                        val v = edgeTo[e]
                        val gStart = edgeGeomOffset[e]
                        val gEnd = edgeGeomOffset[e + 1]

                        var prevLatE6 = nodeLatE6[u]
                        var prevLngE6 = nodeLngE6[u]
                        val totalSteps = (gEnd - gStart) + 1
                        var accumLenM = 0.0
                        var edgeBestDistSq = Double.POSITIVE_INFINITY
                        var edgeBestLatE6 = prevLatE6
                        var edgeBestLngE6 = prevLngE6
                        var edgeBestAlongM = 0.0
                        var edgeBestBearing = 0f

                        for (s in 0 until totalSteps) {
                            val nextLatE6: Int
                            val nextLngE6: Int
                            if (s < gEnd - gStart) {
                                val gi = gStart + s
                                nextLatE6 = geomLatE6[gi]
                                nextLngE6 = geomLngE6[gi]
                            } else {
                                nextLatE6 = nodeLatE6[v]
                                nextLngE6 = nodeLngE6[v]
                            }

                            val ax = (prevLngE6 - qLngE6) * lngScale
                            val ay = (prevLatE6 - qLatE6) * METERS_PER_E6_LAT
                            val bx = (nextLngE6 - qLngE6) * lngScale
                            val by = (nextLatE6 - qLatE6) * METERS_PER_E6_LAT

                            val dx = bx - ax
                            val dy = by - ay
                            val segLenSq = dx * dx + dy * dy
                            val segLen = sqrt(segLenSq)
                            val t = if (segLenSq > 1e-6) {
                                (-(ax * dx + ay * dy) / segLenSq).coerceIn(0.0, 1.0)
                            } else {
                                0.0
                            }

                            val px = ax + t * dx
                            val py = ay + t * dy
                            val distSq = px * px + py * py
                            if (distSq < edgeBestDistSq) {
                                edgeBestDistSq = distSq
                                edgeBestLatE6 = (prevLatE6 + t * (nextLatE6 - prevLatE6)).toInt()
                                edgeBestLngE6 = (prevLngE6 + t * (nextLngE6 - prevLngE6)).toInt()
                                edgeBestAlongM = accumLenM + t * segLen
                                edgeBestBearing = if (segLen > 0.1) {
                                    ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
                                } else {
                                    ((edgeStartAngleByte[e].toInt() and 0xFF) * (360.0 / 256.0)).toFloat()
                                }
                            }
                            accumLenM += segLen
                            prevLatE6 = nextLatE6
                            prevLngE6 = nextLngE6
                        }

                        val distM = sqrt(edgeBestDistSq)
                        if (distM > maxRadiusM) continue

                        // Penalidade leve de direção desalinhada para acertar o sentido da pista.
                        var score = distM
                        if (preferredBearingDeg != null && distM <= 45.0) {
                            val angleDiff = absAngleDiffDeg(preferredBearingDeg, edgeBestBearing)
                            if (angleDiff > 105f) {
                                score += 28.0
                            } else if (angleDiff > 60f) {
                                score += 10.0
                            }
                        }

                        if (score < bestScore) {
                            bestScore = score
                            bestActualDistM = distM
                            bestEdge = e
                            bestProjLatE6 = edgeBestLatE6
                            bestProjLngE6 = edgeBestLngE6
                            bestFraction = if (accumLenM > 0.1) (edgeBestAlongM / accumLenM).coerceIn(0.0, 1.0) else 0.0
                            bestBearing = edgeBestBearing
                        }
                    }
                }
            }
        }

        if (bestEdge == -1) return null
        return EdgeSnap(
            edgeId = bestEdge,
            fromNode = edgeFrom[bestEdge],
            toNode = edgeTo[bestEdge],
            snappedPoint = GeoPoint(bestProjLatE6 * INV_E6, bestProjLngE6 * INV_E6),
            distanceMeters = bestActualDistM,
            fractionAlongEdge = bestFraction,
            segmentBearingDeg = bestBearing
        )
    }

    /** Expande todos os pontos de geometria de [edgeId] (incluindo `from` e `to`) para [out]. */
    fun appendEdgeGeometry(edgeId: Int, out: MutableList<GeoPoint>, includeStart: Boolean) {
        if (includeStart) {
            val u = edgeFrom[edgeId]
            out.add(GeoPoint(nodeLatE6[u] * INV_E6, nodeLngE6[u] * INV_E6))
        }
        val gStart = edgeGeomOffset[edgeId]
        val gEnd = edgeGeomOffset[edgeId + 1]
        for (i in gStart until gEnd) {
            out.add(GeoPoint(geomLatE6[i] * INV_E6, geomLngE6[i] * INV_E6))
        }
        val v = edgeTo[edgeId]
        out.add(GeoPoint(nodeLatE6[v] * INV_E6, nodeLngE6[v] * INV_E6))
    }

    /** Grau de saída de um nó (número de arestas direcionadas saindo de [nodeId]). */
    fun outDegree(nodeId: Int): Int = firstEdge[nodeId + 1] - firstEdge[nodeId]

    /** Grau de entrada de um nó (número de arestas direcionadas chegando em [nodeId]). */
    fun inDegree(nodeId: Int): Int = revFirstEdge[nodeId + 1] - revFirstEdge[nodeId]

    companion object {
        const val MAGIC = "DFRG"
        const val SUPPORTED_VERSION = 1
        const val HEADER_BYTES = 48

        const val ROAD_CLASS_MASK = 0x000F
        const val ROAD_CLASS_MOTORWAY = 0
        const val ROAD_CLASS_TRUNK = 1
        const val ROAD_CLASS_PRIMARY = 2
        const val ROAD_CLASS_SECONDARY = 3
        const val ROAD_CLASS_TERTIARY = 4
        const val ROAD_CLASS_RESIDENTIAL = 5
        const val ROAD_CLASS_SERVICE = 6
        const val ROAD_CLASS_UNCLASSIFIED = 7
        const val ROAD_CLASS_TRACK = 8

        const val FLAG_ONEWAY = 0x0010
        const val FLAG_ROUNDABOUT = 0x0020
        const val FLAG_TOLL = 0x0040
        const val FLAG_UNPAVED = 0x0080
        const val FLAG_BRIDGE = 0x0100
        const val FLAG_TUNNEL = 0x0200
        const val FLAG_LINK = 0x0400

        private const val INV_E6 = 1e-6
        private const val METERS_PER_E6_LAT = 0.11131949079327358 // 111_319.49 m / 1_000_000
        private const val DEFAULT_CELL_SIZE_E6 = 5_000 // 0.005° (~555 m)
        private const val MAX_GRID_CELLS = 250_000
        private const val STREAM_BUFFER_BYTES = 64 * 1024

        // Limites defensivos contra arquivos corrompidos/maliciosos.
        private const val MAX_NODES = 5_000_000
        private const val MAX_EDGES = 20_000_000
        private const val MAX_NAMES = 2_000_000
        private const val MAX_GEOM_POINTS = 50_000_000

        /** Carrega um arquivo `routing.dfrg` a partir do disco em modo streaming. */
        @Throws(IOException::class)
        fun load(file: File): DfrgGraph {
            BufferedInputStream(FileInputStream(file), STREAM_BUFFER_BYTES).use { stream ->
                return load(stream)
            }
        }

        /** Carrega um grafo `DFRG` v1 a partir de qualquer [InputStream] little-endian. */
        @Throws(IOException::class)
        fun load(input: InputStream): DfrgGraph {
            val stream = if (input is BufferedInputStream) input else BufferedInputStream(input, STREAM_BUFFER_BYTES)
            val scratch = ByteArray(STREAM_BUFFER_BYTES)
            val bb = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN)

            // 1. Header (48 bytes)
            readFully(stream, scratch, 0, HEADER_BYTES)
            bb.position(0)
            if (scratch[0] != 'D'.code.toByte() ||
                scratch[1] != 'F'.code.toByte() ||
                scratch[2] != 'R'.code.toByte() ||
                scratch[3] != 'G'.code.toByte()
            ) {
                throw IOException("Arquivo DFRG inválido: assinatura mágica incorreta.")
            }
            bb.position(4)
            val version = bb.short.toInt() and 0xFFFF
            if (version != SUPPORTED_VERSION) {
                throw IOException("Versão DFRG não suportada: $version (esperado $SUPPORTED_VERSION).")
            }
            val headerFlags = bb.short.toInt() and 0xFFFF
            val nodeCount = bb.int
            val edgeCount = bb.int
            val nameCount = bb.int
            val geomPointCount = bb.int
            val bboxMinLatE6 = bb.int
            val bboxMinLngE6 = bb.int
            val bboxMaxLatE6 = bb.int
            val bboxMaxLngE6 = bb.int
            // bytes 40..47: reserved (ignorados na leitura v1)

            if (nodeCount < 0 || nodeCount > MAX_NODES ||
                edgeCount < 0 || edgeCount > MAX_EDGES ||
                nameCount < 0 || nameCount > MAX_NAMES ||
                geomPointCount < 0 || geomPointCount > MAX_GEOM_POINTS
            ) {
                throw IOException(
                    "Contagens DFRG fora dos limites seguros: nodes=$nodeCount edges=$edgeCount names=$nameCount geom=$geomPointCount"
                )
            }

            // 2. Nodes
            val nodeLatE6 = readIntArray(stream, nodeCount, scratch, bb)
            val nodeLngE6 = readIntArray(stream, nodeCount, scratch, bb)
            val firstEdge = readIntArray(stream, nodeCount + 1, scratch, bb)

            if (firstEdge[0] != 0 || firstEdge[nodeCount] != edgeCount) {
                throw IOException(
                    "Tabela CSR firstEdge inconsistente: firstEdge[0]=${firstEdge[0]}, firstEdge[N]=${firstEdge[nodeCount]}, edgeCount=$edgeCount"
                )
            }

            // 3. Directed Edges
            val edgeTo = readIntArray(stream, edgeCount, scratch, bb)
            val edgeDistM = readIntArray(stream, edgeCount, scratch, bb)
            val edgeTimeDs = readIntArray(stream, edgeCount, scratch, bb)
            val edgeFlags = readShortArray(stream, edgeCount, scratch, bb)
            val edgeNameId = readIntArray(stream, edgeCount, scratch, bb)
            val edgeGeomOffset = readIntArray(stream, edgeCount + 1, scratch, bb)

            if (edgeGeomOffset[0] != 0 || edgeGeomOffset[edgeCount] != geomPointCount) {
                throw IOException(
                    "Tabela CSR edgeGeomOffset inconsistente: [0]=${edgeGeomOffset[0]}, [E]=${edgeGeomOffset[edgeCount]}, geom=$geomPointCount"
                )
            }

            // 4. Intermediate Edge Geometry
            val geomLatE6 = readIntArray(stream, geomPointCount, scratch, bb)
            val geomLngE6 = readIntArray(stream, geomPointCount, scratch, bb)

            // 5. Street Name Table
            val streetNames = Array(nameCount) { "" }
            val lenBuf = ByteArray(2)
            var nameBuf = ByteArray(256)
            for (i in 0 until nameCount) {
                readFully(stream, lenBuf, 0, 2)
                val byteLen = (lenBuf[0].toInt() and 0xFF) or ((lenBuf[1].toInt() and 0xFF) shl 8)
                if (byteLen > nameBuf.size) {
                    nameBuf = ByteArray(byteLen)
                }
                if (byteLen > 0) {
                    readFully(stream, nameBuf, 0, byteLen)
                    streetNames[i] = String(nameBuf, 0, byteLen, StandardCharsets.UTF_8)
                }
            }

            // Validação + construção de índices auxiliares em O(N + E) sem boxing.
            val midLatDeg = ((bboxMinLatE6.toLong() + bboxMaxLatE6.toLong()) * 0.5) * INV_E6
            val refCosLat = cos(Math.toRadians(midLatDeg)).coerceAtLeast(0.05)

            val edgeFrom = IntArray(edgeCount)
            val inDegreeCount = IntArray(nodeCount)
            val edgeStartAngleByte = ByteArray(edgeCount)
            val edgeEndAngleByte = ByteArray(edgeCount)
            var maxObservedSpeedMps = 36.0 // piso de ~130 km/h

            for (u in 0 until nodeCount) {
                val eStart = firstEdge[u]
                val eEnd = firstEdge[u + 1]
                if (eEnd < eStart || eEnd > edgeCount) {
                    throw IOException("firstEdge não monotônico no nó $u: $eStart..$eEnd")
                }
                val uLat = nodeLatE6[u]
                val uLng = nodeLngE6[u]
                for (e in eStart until eEnd) {
                    val v = edgeTo[e]
                    if (v < 0 || v >= nodeCount) {
                        throw IOException("edgeTo[$e]=$v fora do intervalo 0..${nodeCount - 1}")
                    }
                    val nId = edgeNameId[e]
                    if (nId < -1 || nId >= nameCount) {
                        throw IOException("edgeNameId[$e]=$nId fora do intervalo -1..${nameCount - 1}")
                    }
                    val gStart = edgeGeomOffset[e]
                    val gEnd = edgeGeomOffset[e + 1]
                    if (gEnd < gStart || gEnd > geomPointCount) {
                        throw IOException("edgeGeomOffset não monotônico na aresta $e: $gStart..$gEnd")
                    }

                    edgeFrom[e] = u
                    inDegreeCount[v]++

                    // Velocidade máxima observada para heurística A* matematicamente admissível.
                    val dist = edgeDistM[e]
                    val timeDs = edgeTimeDs[e]
                    if (dist > 0 && timeDs > 0) {
                        val speedMps = dist / (timeDs * 0.1)
                        if (speedMps > maxObservedSpeedMps && speedMps < 120.0) {
                            maxObservedSpeedMps = speedMps
                        }
                    }

                    // Pré-calcula ângulos de saída e chegada (1 byte = 1.4° de resolução).
                    val vLat = nodeLatE6[v]
                    val vLng = nodeLngE6[v]
                    val firstLat = if (gEnd > gStart) geomLatE6[gStart] else vLat
                    val firstLng = if (gEnd > gStart) geomLngE6[gStart] else vLng
                    val lastPrevLat = if (gEnd > gStart) geomLatE6[gEnd - 1] else uLat
                    val lastPrevLng = if (gEnd > gStart) geomLngE6[gEnd - 1] else uLng

                    edgeStartAngleByte[e] = quantizeBearingByte(uLat, uLng, firstLat, firstLng, refCosLat)
                    edgeEndAngleByte[e] = quantizeBearingByte(lastPrevLat, lastPrevLng, vLat, vLng, refCosLat)
                }
            }

            // Construção do CSR reverso (2 passadas lineares).
            val revFirstEdge = IntArray(nodeCount + 1)
            for (v in 0 until nodeCount) {
                revFirstEdge[v + 1] = revFirstEdge[v] + inDegreeCount[v]
            }
            val revCursor = revFirstEdge.copyOf(nodeCount)
            val revEdgeIdx = IntArray(edgeCount)
            for (e in 0 until edgeCount) {
                val v = edgeTo[e]
                val pos = revCursor[v]++
                revEdgeIdx[pos] = e
            }

            // Construção do índice espacial em grade CSR plana.
            val latSpanE6 = max(1L, bboxMaxLatE6.toLong() - bboxMinLatE6.toLong())
            val lngSpanE6 = max(1L, bboxMaxLngE6.toLong() - bboxMinLngE6.toLong())
            var cellSizeE6 = DEFAULT_CELL_SIZE_E6
            var gridRows = ((latSpanE6 / cellSizeE6) + 1L).toInt().coerceAtLeast(1)
            var gridCols = ((lngSpanE6 / cellSizeE6) + 1L).toInt().coerceAtLeast(1)
            while (gridRows.toLong() * gridCols.toLong() > MAX_GRID_CELLS) {
                cellSizeE6 *= 2
                gridRows = ((latSpanE6 / cellSizeE6) + 1L).toInt().coerceAtLeast(1)
                gridCols = ((lngSpanE6 / cellSizeE6) + 1L).toInt().coerceAtLeast(1)
            }

            val numCells = gridRows * gridCols
            val cellCount = IntArray(numCells)
            for (n in 0 until nodeCount) {
                val r = ((nodeLatE6[n].toLong() - bboxMinLatE6.toLong()) / cellSizeE6).toInt().coerceIn(0, gridRows - 1)
                val c = ((nodeLngE6[n].toLong() - bboxMinLngE6.toLong()) / cellSizeE6).toInt().coerceIn(0, gridCols - 1)
                cellCount[r * gridCols + c]++
            }
            val cellFirstNode = IntArray(numCells + 1)
            for (i in 0 until numCells) {
                cellFirstNode[i + 1] = cellFirstNode[i] + cellCount[i]
            }
            val cellCursor = cellFirstNode.copyOf(numCells)
            val cellNodes = IntArray(nodeCount)
            for (n in 0 until nodeCount) {
                val r = ((nodeLatE6[n].toLong() - bboxMinLatE6.toLong()) / cellSizeE6).toInt().coerceIn(0, gridRows - 1)
                val c = ((nodeLngE6[n].toLong() - bboxMinLngE6.toLong()) / cellSizeE6).toInt().coerceIn(0, gridCols - 1)
                val idx = r * gridCols + c
                cellNodes[cellCursor[idx]++] = n
            }

            return DfrgGraph(
                version = version,
                headerFlags = headerFlags,
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                nameCount = nameCount,
                geomPointCount = geomPointCount,
                bboxMinLatE6 = bboxMinLatE6,
                bboxMinLngE6 = bboxMinLngE6,
                bboxMaxLatE6 = bboxMaxLatE6,
                bboxMaxLngE6 = bboxMaxLngE6,
                nodeLatE6 = nodeLatE6,
                nodeLngE6 = nodeLngE6,
                firstEdge = firstEdge,
                edgeTo = edgeTo,
                edgeDistM = edgeDistM,
                edgeTimeDs = edgeTimeDs,
                edgeFlags = edgeFlags,
                edgeNameId = edgeNameId,
                edgeGeomOffset = edgeGeomOffset,
                geomLatE6 = geomLatE6,
                geomLngE6 = geomLngE6,
                streetNames = streetNames,
                edgeFrom = edgeFrom,
                revFirstEdge = revFirstEdge,
                revEdgeIdx = revEdgeIdx,
                edgeStartAngleByte = edgeStartAngleByte,
                edgeEndAngleByte = edgeEndAngleByte,
                maxObservedSpeedMps = maxObservedSpeedMps,
                refCosLat = refCosLat,
                gridCellSizeE6 = cellSizeE6,
                gridCols = gridCols,
                gridRows = gridRows,
                cellFirstNode = cellFirstNode,
                cellNodes = cellNodes
            )
        }

        private fun quantizeBearingByte(
            lat1E6: Int,
            lng1E6: Int,
            lat2E6: Int,
            lng2E6: Int,
            cosLat: Double
        ): Byte {
            val dy = (lat2E6 - lat1E6).toDouble()
            val dx = (lng2E6 - lng1E6).toDouble() * cosLat
            if (dx == 0.0 && dy == 0.0) return 0
            var rad = atan2(dx, dy) // 0 = Norte, +pi/2 = Leste
            if (rad < 0.0) rad += 2.0 * PI
            val quantized = ((rad / (2.0 * PI)) * 256.0).toInt() and 0xFF
            return quantized.toByte()
        }

        internal fun absAngleDiffDeg(a: Float, b: Float): Float {
            var diff = ((b - a + 540f) % 360f) - 180f
            if (diff < 0f) diff = -diff
            return diff
        }

        private fun readIntArray(
            stream: InputStream,
            count: Int,
            scratch: ByteArray,
            bb: ByteBuffer
        ): IntArray {
            val out = IntArray(count)
            val maxIntsPerChunk = scratch.size / 4
            var offset = 0
            while (offset < count) {
                val chunk = min(maxIntsPerChunk, count - offset)
                val bytes = chunk * 4
                readFully(stream, scratch, 0, bytes)
                bb.position(0)
                for (i in 0 until chunk) {
                    out[offset + i] = bb.int
                }
                offset += chunk
            }
            return out
        }

        private fun readShortArray(
            stream: InputStream,
            count: Int,
            scratch: ByteArray,
            bb: ByteBuffer
        ): ShortArray {
            val out = ShortArray(count)
            val maxShortsPerChunk = scratch.size / 2
            var offset = 0
            while (offset < count) {
                val chunk = min(maxShortsPerChunk, count - offset)
                val bytes = chunk * 2
                readFully(stream, scratch, 0, bytes)
                bb.position(0)
                for (i in 0 until chunk) {
                    out[offset + i] = bb.short
                }
                offset += chunk
            }
            return out
        }

        private fun readFully(stream: InputStream, buf: ByteArray, off: Int, len: Int) {
            var read = 0
            while (read < len) {
                val n = stream.read(buf, off + read, len - read)
                if (n < 0) throw EOFException("Fim inesperado ao ler arquivo DFRG (esperado $len bytes, lido $read).")
                read += n
            }
        }
    }
}
