package com.drifly.condutor.map.nav

import com.drifly.condutor.map.routing.ComputedRoute
import com.drifly.condutor.map.routing.GeoPoint
import com.drifly.condutor.map.routing.ManeuverType
import com.drifly.condutor.map.routing.OfflineRouteEngine
import com.drifly.condutor.map.routing.RouteLeg
import com.drifly.condutor.map.routing.RouteRequest
import com.drifly.condutor.map.routing.RouteResponse
import com.drifly.condutor.map.routing.RouteStep
import com.drifly.condutor.map.routing.RouteWaypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OfflineNavigationControllerTest {

    private class RecordingVoiceSpeaker : NavigationVoiceSpeaker {
        override var isMuted: Boolean = false
        val spoken = mutableListOf<String>()
        override fun speak(text: String, flush: Boolean) {
            if (!isMuted) spoken.add(text)
        }
        override fun stop() = Unit
        override fun close() = Unit
    }

    private class CountingEngine(private val rerouteResult: ComputedRoute) : OfflineRouteEngine {
        override val engineName: String = "CountingEngine"
        override val isReady: Boolean = true
        var computeCalls = 0
        override suspend fun initialize(packDir: File): Boolean = true
        override suspend fun computeRoute(request: RouteRequest): RouteResponse {
            computeCalls++
            return RouteResponse.Success(primary = rerouteResult)
        }
        override fun close() = Unit
    }

    private fun sampleRoute(): ComputedRoute {
        // 0m -> 500m (Norte) -> 1000m (Leste)
        val p0 = GeoPoint(-26.3100, -48.8500)
        val p1 = GeoPoint(-26.3055, -48.8500) // ~500m norte
        val p2 = GeoPoint(-26.3055, -48.8450) // ~500m leste
        val geom = listOf(p0, p1, p2)
        val cum = ComputedRoute.buildCumulativeDistances(geom)

        val steps = listOf(
            RouteStep(
                index = 0,
                maneuver = ManeuverType.DEPART,
                streetName = "Rua Dr. João Colin",
                instructionPtBr = "Siga na Rua Dr. João Colin",
                distanceMeters = cum[1],
                durationSeconds = 50.0,
                geometryStartIndex = 0,
                geometryEndIndex = 1
            ),
            RouteStep(
                index = 1,
                maneuver = ManeuverType.RIGHT,
                streetName = "Rua XV de Novembro",
                instructionPtBr = "Vire à direita na Rua XV de Novembro",
                distanceMeters = cum[2] - cum[1],
                durationSeconds = 50.0,
                geometryStartIndex = 1,
                geometryEndIndex = 2
            ),
            RouteStep(
                index = 2,
                maneuver = ManeuverType.ARRIVE,
                streetName = "Rua XV de Novembro",
                instructionPtBr = "Você chegou ao destino",
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                geometryStartIndex = 2,
                geometryEndIndex = 2
            )
        )

        return ComputedRoute(
            id = "sample_jvl",
            geometry = geom,
            cumulativeDistancesM = cum,
            totalDistanceMeters = cum.last(),
            totalDurationSeconds = 100.0,
            legs = listOf(
                RouteLeg(
                    from = RouteWaypoint(p0, "Origem"),
                    to = RouteWaypoint(p2, "Destino"),
                    distanceMeters = cum.last(),
                    durationSeconds = 100.0,
                    steps = steps
                )
            )
        )
    }

    @Test
    fun triggersVoiceThresholdsOnceAndTransitionsToArrived() {
        val route = sampleRoute()
        val voice = RecordingVoiceSpeaker()
        val engine = CountingEngine(route)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var clock = 1_000L

        try {
            val ctrl = OfflineNavigationController(engine, voice, scope, nowMs = { clock })
            ctrl.startNavigation(
                route = route,
                waypoints = listOf(RouteWaypoint(route.geometry.last(), "Destino")),
                initialFix = NavLocationFix(route.geometry[0], speedMps = 10f, bearingDeg = 0f, timestampMs = clock)
            )
            assertTrue(ctrl.state.value is NavigationUiState.Navigating)

            // Avança para ~280m antes da conversão à direita (dispara alerta de 300m uma vez).
            clock += 2000L
            val at220m = RouteSimulator.interpolateAtDistance(route.geometry, route.cumulativeDistancesM, 220.0).first
            ctrl.onLocationUpdate(NavLocationFix(at220m, speedMps = 12f, bearingDeg = 0f, timestampMs = clock))
            val spokenAfter300 = voice.spoken.count { it.contains("300 metros") }
            assertEquals(1, spokenAfter300)

            // Segundo tick ainda na faixa de 300m não pode repetir a fala!
            clock += 1000L
            val at235m = RouteSimulator.interpolateAtDistance(route.geometry, route.cumulativeDistancesM, 235.0).first
            ctrl.onLocationUpdate(NavLocationFix(at235m, speedMps = 12f, bearingDeg = 0f, timestampMs = clock))
            assertEquals(1, voice.spoken.count { it.contains("300 metros") })

            // Avança para ~75m antes da conversão (dispara alerta de 80m).
            clock += 5000L
            val at425m = RouteSimulator.interpolateAtDistance(route.geometry, route.cumulativeDistancesM, 425.0).first
            ctrl.onLocationUpdate(NavLocationFix(at425m, speedMps = 10f, bearingDeg = 0f, timestampMs = clock))
            assertEquals(1, voice.spoken.count { it.contains("80 metros") })

            // Chegada (< 30m do destino)
            clock += 10_000L
            ctrl.onLocationUpdate(NavLocationFix(route.geometry.last(), speedMps = 2f, bearingDeg = 90f, timestampMs = clock))
            assertTrue(ctrl.state.value is NavigationUiState.Arrived)
            assertTrue(voice.spoken.last().contains("chegou ao destino", ignoreCase = true))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun enforcesOffRouteHysteresis40mFor3SecondsBeforeRerouting() = runBlocking {
        val route = sampleRoute()
        val voice = RecordingVoiceSpeaker()
        val engine = CountingEngine(route)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var clock = 10_000L

        try {
            val ctrl = OfflineNavigationController(engine, voice, scope, nowMs = { clock })
            ctrl.startNavigation(
                route = route,
                waypoints = listOf(RouteWaypoint(route.geometry.last(), "Destino")),
                initialFix = NavLocationFix(route.geometry[0], speedMps = 10f, bearingDeg = 0f, timestampMs = clock)
            )

            // Desvio de ~70m para Oeste (fora de rota > 40m)
            val offRoutePt = GeoPoint(-26.3080, -48.8507)

            // t = 11_000ms (início do desvio) -> ainda NÃO é off-route
            clock = 11_000L
            ctrl.onLocationUpdate(NavLocationFix(offRoutePt, speedMps = 10f, bearingDeg = 270f, timestampMs = clock))
            assertFalse((ctrl.state.value as NavigationUiState.Navigating).snapshot.isOffRoute)
            assertEquals(0, engine.computeCalls)

            // t = 13_500ms (2,5 s fora) -> ainda NÃO atingiu 3 s
            clock = 13_500L
            ctrl.onLocationUpdate(NavLocationFix(offRoutePt, speedMps = 10f, bearingDeg = 270f, timestampMs = clock))
            assertFalse((ctrl.state.value as NavigationUiState.Navigating).snapshot.isOffRoute)
            assertEquals(0, engine.computeCalls)

            // t = 14_100ms (3,1 s fora) -> DEVE disparar recálculo local e voz!
            clock = 14_100L
            ctrl.onLocationUpdate(NavLocationFix(offRoutePt, speedMps = 10f, bearingDeg = 270f, timestampMs = clock))
            assertEquals(1, engine.computeCalls)
            assertTrue(voice.spoken.any { it.contains("Recalculando", ignoreCase = true) })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun speedAdaptiveZoomAndBearingWrapSmoothingAreConsistent() {
        assertEquals(17.5, OfflineNavigationController.speedAdaptiveZoom(15f), 0.01)
        assertEquals(16.5, OfflineNavigationController.speedAdaptiveZoom(40f), 0.01)
        assertEquals(15.5, OfflineNavigationController.speedAdaptiveZoom(70f), 0.01)
        assertEquals(14.8, OfflineNavigationController.speedAdaptiveZoom(100f), 0.01)

        // Suavização através da fronteira 359° -> 1° (delta = +2°, não -358°!)
        val smoothed = OfflineNavigationController.smoothBearing(359f, 1f, alpha = 0.5f)
        assertTrue("Esperado próximo a 0°, obtido $smoothed", smoothed < 1.5f || smoothed > 358.5f)
    }
}
