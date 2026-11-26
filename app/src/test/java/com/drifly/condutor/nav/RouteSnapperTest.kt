package com.drifly.condutor.map.nav

import com.drifly.condutor.map.routing.ComputedRoute
import com.drifly.condutor.map.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSnapperTest {

    @Test
    fun snapsPointOnJoinvilleCorridorWithSubMeterPrecision() {
        // Trecho reto Norte-Sul em Joinville (~1 km total em 2 segmentos de ~500m)
        val p0 = GeoPoint(-26.3050, -48.8480)
        val p1 = GeoPoint(-26.3005, -48.8480) // ~500m ao norte
        val p2 = GeoPoint(-26.2960, -48.8480) // ~1000m ao norte
        val geom = listOf(p0, p1, p2)
        val cum = ComputedRoute.buildCumulativeDistances(geom)
        val route = ComputedRoute(
            id = "jvl_test",
            geometry = geom,
            cumulativeDistancesM = cum,
            totalDistanceMeters = cum.last(),
            totalDurationSeconds = 100.0,
            legs = emptyList()
        )

        // Ponto exatamente no meio do 1º segmento, deslocado ~15m para Leste
        val midLat = (p0.lat + p1.lat) / 2.0
        val offsetLng = p0.lng + (15.0 / (111_320.0 * kotlin.math.cos(Math.toRadians(midLat))))
        val snap = RouteSnapper.snap(route, GeoPoint(midLat, offsetLng), hintSegmentIndex = 0)

        assertEquals(0, snap.segmentIndex)
        assertEquals(0.5, snap.segmentFraction, 0.02)
        assertEquals(15.0, snap.crossTrackMeters, 0.5)
        assertEquals(cum[1] / 2.0, snap.alongTrackMeters, 2.0)
        assertEquals(cum[2] - cum[1] / 2.0, snap.remainingMeters, 2.0)
        // Azimute rumo ao Norte (~0° ou 360°)
        assertTrue(snap.segmentBearingDeg < 2f || snap.segmentBearingDeg > 358f)
    }

    @Test
    fun fallsBackToGlobalScanWhenGpsJumpsBeyondLocalWindow() {
        // Cria polilinha longa com 50 segmentos (~5 km)
        val geom = (0..50).map { i -> GeoPoint(-26.3100 + i * 0.0009, -48.8480) }
        val cum = ComputedRoute.buildCumulativeDistances(geom)
        val route = ComputedRoute(
            id = "long_route",
            geometry = geom,
            cumulativeDistancesM = cum,
            totalDistanceMeters = cum.last(),
            totalDurationSeconds = 400.0,
            legs = emptyList()
        )

        // Hint no segmento 0, mas GPS está no segmento 45 (fora da janela de +30)
        val target = geom[45]
        val snap = RouteSnapper.snap(route, target, hintSegmentIndex = 0)

        assertTrue(snap.segmentIndex in 44..45)
        assertTrue(snap.crossTrackMeters < 1.0)
    }
}
