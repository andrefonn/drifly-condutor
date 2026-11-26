package com.drifly.condutor.map.routing

import com.drifly.condutor.BlacklistItem
import java.util.Locale

/**
 * Consulta offline de coordenadas de bairro pelo nome normalizado (usada quando um item
 * da Lista Negra foi salvo sem latitude/longitude enquanto o motorista estava offline).
 */
fun interface OfflineNeighborhoodLookup {
    fun findNeighborhoodCenter(normalizedTerm: String): GeoPoint?
}

/**
 * Otimizador inteligente de rotas offline do DriFly.
 *
 * Combina:
 * 1. Perfil automático do veículo (Carro ou Moto via `PreferencesManager.isMotorcycleMode`);
 * 2. Evasão automática da Lista Negra de bairros (resolve itens com lat/lng e itens só texto
 *    via geocoder offline, e protege contra deadlock caso origem/destino esteja dentro da zona);
 * 3. Otimização multi-paradas (TSP assimétrico para vias de mão única: Vizinho Mais Próximo +
 *    2-Opt + Or-Opt para até 12 paradas em < 1 ms), preservando destino final travado quando pedido;
 * 4. Estimativa de custo real de combustível (`km * costPerKm`).
 */
class SmartRouteOptimizer(
    private val engine: OfflineRouteEngine,
    private val neighborhoodLookup: OfflineNeighborhoodLookup? = null
) {

    /**
     * Converte itens da Lista Negra do DriFly em zonas de evasão geodésicas.
     * Itens dentro dos quais a origem ou alguma parada já se encontra são removidos da
     * evasão rígida para que a rota nunca falhe, sendo retornados em `skipped`.
     */
    fun resolveAvoidZones(
        blacklistItems: List<BlacklistItem>,
        origin: GeoPoint,
        waypoints: List<RouteWaypoint>,
        avoidBlacklist: Boolean,
        radiusMeters: Double = AvoidZone.DEFAULT_RADIUS_M
    ): Pair<List<AvoidZone>, List<AvoidZone>> {
        if (!avoidBlacklist || blacklistItems.isEmpty()) return emptyList<AvoidZone>() to emptyList()

        val allPoints = buildList {
            add(origin)
            waypoints.forEach { add(it.point) }
        }

        val active = mutableListOf<AvoidZone>()
        val skipped = mutableListOf<AvoidZone>()

        for (item in blacklistItems) {
            val center = when {
                item.latitude != null && item.longitude != null -> GeoPoint(item.latitude, item.longitude)
                else -> neighborhoodLookup?.findNeighborhoodCenter(item.normalizedTerm)
            } ?: continue

            val zone = AvoidZone(
                id = "bl_${item.id}",
                label = item.rawTerm,
                center = center,
                radiusMeters = radiusMeters,
                hardAvoid = false
            )

            if (allPoints.any { zone.contains(it) }) {
                skipped.add(zone)
            } else {
                active.add(zone)
            }
        }
        return active to skipped
    }

    /**
     * Planeja e (quando há >= 2 paradas) otimiza a sequência de visitas antes de calcular
     * a geometria completa e as instruções passo a passo no [engine].
     */
    suspend fun planRoute(
        origin: GeoPoint,
        waypoints: List<RouteWaypoint>,
        profile: RouteProfile = RouteProfile.CAR_FASTEST,
        blacklistItems: List<BlacklistItem> = emptyList(),
        avoidBlacklist: Boolean = true,
        optimizeStopOrder: Boolean = true,
        lockFinalDestination: Boolean = false,
        costPerKmBrl: Double = 0.0,
        originBearingDeg: Float? = null,
        computeAlternatives: Boolean = true
    ): Result<OptimizedRoutePlan> {
        if (waypoints.isEmpty()) {
            return Result.failure(IllegalArgumentException("Informe ao menos um destino."))
        }

        val (activeAvoidZones, skippedAvoidZones) = resolveAvoidZones(
            blacklistItems = blacklistItems,
            origin = origin,
            waypoints = waypoints,
            avoidBlacklist = avoidBlacklist
        )

        var ordered = waypoints
        var wasReordered = false
        var savedSeconds = 0.0
        var savedMeters = 0.0

        val canOptimize = optimizeStopOrder && waypoints.size >= 2 && waypoints.size <= MAX_TSP_STOPS
        if (canOptimize) {
            val fixedLast = lockFinalDestination || waypoints.last().isLockedOrder
            val optimizableCount = if (fixedLast) waypoints.size - 1 else waypoints.size
            if (optimizableCount >= 2) {
                val points = buildList {
                    add(origin)
                    waypoints.forEach { add(it.point) }
                }
                val matrix = buildTravelTimeMatrix(points, profile, activeAvoidZones)
                val originalIndices = (1..waypoints.size).toList()
                val bestIndices = solveAsymmetricTsp(
                    costMatrix = matrix,
                    stopCount = waypoints.size,
                    lockLastStop = fixedLast
                )

                val originalCost = pathCost(originalIndices, matrix)
                val bestCost = pathCost(bestIndices, matrix)
                if (bestIndices != originalIndices && bestCost + MIN_IMPROVEMENT_SECONDS < originalCost) {
                    ordered = bestIndices.map { idx -> waypoints[idx - 1] }
                    wasReordered = true
                    savedSeconds = (originalCost - bestCost).coerceAtLeast(0.0)
                    val origDist = estimatePolylineHaversineMeters(origin, waypoints)
                    val bestDist = estimatePolylineHaversineMeters(origin, ordered)
                    savedMeters = (origDist - bestDist).coerceAtLeast(0.0)
                }
            }
        }

        val request = RouteRequest(
            origin = origin,
            waypoints = ordered,
            profile = profile,
            avoidZones = activeAvoidZones,
            computeAlternatives = computeAlternatives && ordered.size == 1,
            originBearingDeg = originBearingDeg
        )

        return when (val response = engine.computeRoute(request)) {
            is RouteResponse.Error -> Result.failure(IllegalStateException(response.reason))
            is RouteResponse.Success -> {
                val primary = response.primary
                val fuelCost = (primary.totalDistanceMeters / 1000.0) * costPerKmBrl.coerceAtLeast(0.0)
                val warning = buildWarning(primary.warningMessage, skippedAvoidZones)
                Result.success(
                    OptimizedRoutePlan(
                        primary = primary.copy(
                            avoidedZonesCount = activeAvoidZones.size,
                            warningMessage = warning
                        ),
                        alternatives = response.alternatives,
                        orderedWaypoints = ordered,
                        activeAvoidZones = activeAvoidZones,
                        skippedAvoidZones = skippedAvoidZones,
                        wasReordered = wasReordered,
                        estimatedTimeSavedSeconds = savedSeconds,
                        estimatedDistanceSavedMeters = savedMeters,
                        estimatedFuelCostBrl = fuelCost,
                        summaryWarning = warning
                    )
                )
            }
        }
    }

    private suspend fun buildTravelTimeMatrix(
        points: List<GeoPoint>,
        profile: RouteProfile,
        avoidZones: List<AvoidZone>
    ): Array<DoubleArray> {
        // 1) Tenta matriz nativa do motor (rápida e exata com mão única).
        val native = try {
            engine.computeCostMatrix(points, profile, avoidZones)
        } catch (_: Exception) {
            null
        }
        if (native != null && native.size == points.size) return native

        // 2) Fallback: se N <= 6, consulta pares direto no motor; senão usa distância viária
        // estimada com penalidade de Lista Negra para manter latência instantânea (< 5 ms).
        val n = points.size
        val matrix = Array(n) { DoubleArray(n) }
        val avgSpeedMps = if (profile == RouteProfile.MOTO_FASTEST) 11.5 else 9.5

        if (n <= PAIRWISE_ENGINE_LIMIT) {
            for (i in 0 until n) {
                for (j in 0 until n) {
                    if (i == j) continue
                    val resp = engine.computeRoute(
                        RouteRequest(
                            origin = points[i],
                            waypoints = listOf(RouteWaypoint(points[j])),
                            profile = profile,
                            avoidZones = avoidZones,
                            computeAlternatives = false
                        )
                    )
                    matrix[i][j] = when (resp) {
                        is RouteResponse.Success -> resp.primary.totalDurationSeconds
                        is RouteResponse.Error -> fallbackEdgeCostSeconds(points[i], points[j], avgSpeedMps, avoidZones)
                    }
                }
            }
        } else {
            for (i in 0 until n) {
                for (j in 0 until n) {
                    if (i == j) continue
                    matrix[i][j] = fallbackEdgeCostSeconds(points[i], points[j], avgSpeedMps, avoidZones)
                }
            }
        }
        return matrix
    }

    private fun fallbackEdgeCostSeconds(
        a: GeoPoint,
        b: GeoPoint,
        avgSpeedMps: Double,
        avoidZones: List<AvoidZone>
    ): Double {
        val straight = a.distanceToMeters(b)
        // Fator de sinuosidade urbana típico (~1.32x sobre linha reta).
        var seconds = (straight * 1.32) / avgSpeedMps
        if (avoidZones.isNotEmpty()) {
            val mid = GeoPoint((a.lat + b.lat) / 2.0, (a.lng + b.lng) / 2.0)
            if (avoidZones.any { it.contains(mid) }) {
                seconds *= 2.5
            }
        }
        return seconds
    }

    private fun estimatePolylineHaversineMeters(origin: GeoPoint, waypoints: List<RouteWaypoint>): Double {
        var total = 0.0
        var prev = origin
        for (wp in waypoints) {
            total += prev.distanceToMeters(wp.point)
            prev = wp.point
        }
        return total * 1.32
    }

    private fun buildWarning(engineWarning: String?, skipped: List<AvoidZone>): String? {
        val parts = mutableListOf<String>()
        if (!engineWarning.isNullOrBlank()) parts.add(engineWarning)
        if (skipped.isNotEmpty()) {
            val nomes = skipped.joinToString(", ") { it.label }
            parts.add("Origem/parada em área da Lista Negra ($nomes) — evitando demais bairros.")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    companion object {
        const val MAX_TSP_STOPS = 12
        private const val PAIRWISE_ENGINE_LIMIT = 6
        private const val MIN_IMPROVEMENT_SECONDS = 5.0

        /**
         * Resolve o caminho aberto de menor custo partindo do índice 0 (`origin`) e visitando
         * todos os índices `1..stopCount`. Quando [lockLastStop] é `true`, o índice `stopCount`
         * permanece fixo como destino final (ex.: Modo Casa).
         *
         * Usa Vizinho Mais Próximo + busca local 2-Opt + Or-Opt (blocos de 1 e 2 paradas),
         * recalculando sempre o custo direcionado completo para respeitar vias de mão única.
         */
        internal fun solveAsymmetricTsp(
            costMatrix: Array<DoubleArray>,
            stopCount: Int,
            lockLastStop: Boolean
        ): List<Int> {
            val mutableStops = if (lockLastStop) (1 until stopCount).toMutableList() else (1..stopCount).toMutableList()
            if (mutableStops.size <= 1) {
                return (1..stopCount).toList()
            }

            // 1) Heurística construtiva: Vizinho Mais Próximo a partir de 0.
            val remaining = mutableStops.toMutableSet()
            val route = ArrayList<Int>(stopCount)
            var current = 0
            while (remaining.isNotEmpty()) {
                var bestNext = remaining.first()
                var bestCost = Double.POSITIVE_INFINITY
                for (cand in remaining) {
                    val c = costMatrix[current][cand]
                    if (c < bestCost) {
                        bestCost = c
                        bestNext = cand
                    }
                }
                route.add(bestNext)
                remaining.remove(bestNext)
                current = bestNext
            }
            if (lockLastStop) route.add(stopCount)

            val editableEnd = if (lockLastStop) route.size - 1 else route.size
            var bestTour = route.toMutableList()
            var bestCost = pathCost(bestTour, costMatrix)

            // 2) Busca local combinada 2-Opt + Or-Opt até convergência.
            var improved = true
            var iterations = 0
            while (improved && iterations < 50) {
                improved = false
                iterations++

                // 2-Opt: inverte subsegmento [i..j]
                for (i in 0 until editableEnd - 1) {
                    for (j in i + 1 until editableEnd) {
                        val candidate = bestTour.toMutableList()
                        var l = i
                        var r = j
                        while (l < r) {
                            val tmp = candidate[l]
                            candidate[l] = candidate[r]
                            candidate[r] = tmp
                            l++
                            r--
                        }
                        val candCost = pathCost(candidate, costMatrix)
                        if (candCost + 1e-6 < bestCost) {
                            bestTour = candidate
                            bestCost = candCost
                            improved = true
                        }
                    }
                }

                // Or-Opt: realoca blocos de tamanho 1 e 2 para outra posição.
                for (blockLen in 1..2) {
                    if (editableEnd <= blockLen) continue
                    for (start in 0..(editableEnd - blockLen)) {
                        val block = bestTour.subList(start, start + blockLen).toList()
                        val without = ArrayList<Int>(bestTour.size - blockLen)
                        for (idx in bestTour.indices) {
                            if (idx < start || idx >= start + blockLen) without.add(bestTour[idx])
                        }
                        val maxInsert = if (lockLastStop) without.size - 1 else without.size
                        for (insertAt in 0..maxInsert) {
                            if (insertAt == start) continue
                            val candidate = ArrayList<Int>(bestTour.size)
                            candidate.addAll(without.subList(0, insertAt))
                            candidate.addAll(block)
                            candidate.addAll(without.subList(insertAt, without.size))
                            val candCost = pathCost(candidate, costMatrix)
                            if (candCost + 1e-6 < bestCost) {
                                bestTour = candidate
                                bestCost = candCost
                                improved = true
                            }
                        }
                    }
                }
            }

            return bestTour
        }

        internal fun pathCost(order: List<Int>, costMatrix: Array<DoubleArray>): Double {
            var total = 0.0
            var prev = 0
            for (next in order) {
                total += costMatrix[prev][next]
                prev = next
            }
            return total
        }

        /** Formata resumo curto da economia obtida pela otimização ("Rota otimizada: -2,4 km · -6 min"). */
        fun formatOptimizationSummary(plan: OptimizedRoutePlan): String? {
            if (!plan.wasReordered) return null
            val minSaved = (plan.estimatedTimeSavedSeconds / 60.0).toInt()
            val kmSaved = plan.estimatedDistanceSavedMeters / 1000.0
            return when {
                kmSaved >= 0.2 && minSaved >= 1 ->
                    String.format(Locale.forLanguageTag("pt-BR"), "Rota otimizada: -%.1f km · -%d min", kmSaved, minSaved)
                minSaved >= 1 -> "Rota otimizada: -$minSaved min"
                kmSaved >= 0.2 -> String.format(Locale.forLanguageTag("pt-BR"), "Rota otimizada: -%.1f km", kmSaved)
                else -> "Ordem das paradas otimizada"
            }
        }
    }
}
