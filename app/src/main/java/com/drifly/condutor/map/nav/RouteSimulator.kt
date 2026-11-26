package com.drifly.condutor.map.nav

import com.drifly.condutor.map.routing.ComputedRoute
import com.drifly.condutor.map.routing.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Simulador de direção ao longo de qualquer [ComputedRoute] (ex.: teste estacionário
 * em Joinville para validar câmera 3D, banner de manobra e voz pt-BR sem sair do lugar).
 */
class RouteSimulator(
    private val scope: CoroutineScope,
    private val tickIntervalMs: Long = DEFAULT_TICK_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var job: Job? = null

    @Volatile
    var speedMultiplier: Double = 1.0

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Inicia a simulação percorrendo [route] a partir da origem.
     * Desacelera automaticamente próximo a curvas/manobras para parecer condução real.
     */
    fun start(
        route: ComputedRoute,
        onFix: (NavLocationFix) -> Unit,
        onFinished: () -> Unit = {}
    ) {
        stop()
        if (route.geometry.size < 2) {
            onFinished()
            return
        }

        val cum = if (route.cumulativeDistancesM.size == route.geometry.size) {
            route.cumulativeDistancesM
        } else {
            ComputedRoute.buildCumulativeDistances(route.geometry)
        }
        val totalDistM = cum.last().coerceAtLeast(1.0)
        val maneuverDistances = route.allSteps.mapNotNull { step ->
            val idx = step.geometryStartIndex.coerceIn(0, cum.lastIndex)
            cum[idx]
        }

        job = scope.launch {
            var traveledM = 0.0
            while (isActive && traveledM <= totalDistM) {
                val nearTurn = maneuverDistances.any { m ->
                    val diff = m - traveledM
                    diff in 0.0..60.0
                }
                val baseSpeedMps = if (nearTurn) TURN_SPEED_MPS else CRUISE_SPEED_MPS
                val effectiveSpeedMps = baseSpeedMps * speedMultiplier.coerceIn(0.5, 4.0)

                val (point, bearing) = interpolateAtDistance(route.geometry, cum, traveledM)
                onFix(
                    NavLocationFix(
                        point = point,
                        speedMps = effectiveSpeedMps.toFloat(),
                        bearingDeg = bearing,
                        accuracyMeters = 3.5f,
                        timestampMs = nowMs(),
                        isSimulated = true
                    )
                )

                if (traveledM >= totalDistM) break
                delay(tickIntervalMs)
                val stepM = effectiveSpeedMps * (tickIntervalMs / 1000.0)
                traveledM = (traveledM + stepM).coerceAtMost(totalDistM)
            }
            onFinished()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_TICK_MS = 500L
        /** ~45 km/h em trecho reto urbano. */
        private const val CRUISE_SPEED_MPS = 12.5
        /** ~22 km/h ao se aproximar de conversões/rotatórias. */
        private const val TURN_SPEED_MPS = 6.1

        /** Interpola posição e azimute exatos a [distanceMeters] ao longo da polilinha. */
        fun interpolateAtDistance(
            geometry: List<GeoPoint>,
            cumulativeDistancesM: DoubleArray,
            distanceMeters: Double
        ): Pair<GeoPoint, Float> {
            if (geometry.size < 2) {
                return (geometry.firstOrNull() ?: GeoPoint(0.0, 0.0)) to 0f
            }
            val total = cumulativeDistancesM.last()
            val clamped = distanceMeters.coerceIn(0.0, total)

            // Busca binária do segmento contendo `clamped`.
            var low = 0
            var high = geometry.size - 2
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (cumulativeDistancesM[mid + 1] < clamped) {
                    low = mid + 1
                } else if (cumulativeDistancesM[mid] > clamped) {
                    high = mid - 1
                } else {
                    low = mid
                    break
                }
            }
            val seg = low.coerceIn(0, geometry.size - 2)
            val segStart = cumulativeDistancesM[seg]
            val segEnd = cumulativeDistancesM[seg + 1]
            val segLen = (segEnd - segStart).coerceAtLeast(1e-6)
            val frac = ((clamped - segStart) / segLen).coerceIn(0.0, 1.0)

            val a = geometry[seg]
            val b = geometry[seg + 1]
            val pt = GeoPoint(
                lat = a.lat + frac * (b.lat - a.lat),
                lng = a.lng + frac * (b.lng - a.lng)
            )
            return pt to a.bearingToDegrees(b)
        }
    }
}
