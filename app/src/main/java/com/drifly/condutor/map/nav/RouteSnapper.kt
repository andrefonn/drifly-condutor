package com.drifly.condutor.map.nav

import com.drifly.condutor.map.routing.ComputedRoute
import com.drifly.condutor.map.routing.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot

data class SnapResult(
    val snappedPoint: GeoPoint,
    val segmentIndex: Int,
    val segmentFraction: Double,
    val crossTrackMeters: Double,
    val alongTrackMeters: Double,
    val remainingMeters: Double,
    val segmentBearingDeg: Float
)

/**
 * Projetor de GPS na polilinha da rota com busca em janela local (`O(1)` por tick, zero GC).
 *
 * Usa projeção equiretangular local em metros (precisão < 0,1 m em distâncias urbanas),
 * evitando dezenas de chamadas trigonométricas de Haversine por segundo.
 */
object RouteSnapper {

    private const val METERS_PER_DEG_LAT = 111_320.0
    private const val WINDOW_BACK = 4
    private const val WINDOW_AHEAD = 30
    private const val GLOBAL_FALLBACK_THRESHOLD_M = 80.0

    /**
     * Projeta [position] sobre [route], iniciando a busca ao redor de [hintSegmentIndex].
     * Se a menor distância na janela local superar 80 m (ex.: salto de GPS ou início de rota),
     * faz varredura global como rede de segurança.
     */
    fun snap(
        route: ComputedRoute,
        position: GeoPoint,
        hintSegmentIndex: Int = 0
    ): SnapResult {
        val geom = route.geometry
        if (geom.size < 2) {
            val pt = geom.firstOrNull() ?: position
            return SnapResult(
                snappedPoint = pt,
                segmentIndex = 0,
                segmentFraction = 0.0,
                crossTrackMeters = position.distanceToMeters(pt),
                alongTrackMeters = 0.0,
                remainingMeters = 0.0,
                segmentBearingDeg = 0f
            )
        }

        val lastSeg = geom.size - 2
        val clampedHint = hintSegmentIndex.coerceIn(0, lastSeg)
        val winStart = (clampedHint - WINDOW_BACK).coerceAtLeast(0)
        val winEnd = (clampedHint + WINDOW_AHEAD).coerceAtMost(lastSeg)

        var best = findBestInRange(route, position, winStart, winEnd)
        if (best.crossTrackMeters > GLOBAL_FALLBACK_THRESHOLD_M && (winStart > 0 || winEnd < lastSeg)) {
            val global = findBestInRange(route, position, 0, lastSeg)
            if (global.crossTrackMeters < best.crossTrackMeters) {
                best = global
            }
        }
        return best
    }

    private fun findBestInRange(
        route: ComputedRoute,
        position: GeoPoint,
        startSeg: Int,
        endSeg: Int
    ): SnapResult {
        val geom = route.geometry
        val cum = route.cumulativeDistancesM
        val cosLat = cos(Math.toRadians(position.lat)).coerceAtLeast(0.01)
        val metersPerDegLng = METERS_PER_DEG_LAT * cosLat

        var bestDistM = Double.POSITIVE_INFINITY
        var bestSeg = startSeg
        var bestT = 0.0
        var bestLat = geom[startSeg].lat
        var bestLng = geom[startSeg].lng

        for (i in startSeg..endSeg) {
            val a = geom[i]
            val b = geom[i + 1]

            // Coordenadas locais relativas a `a`, em metros.
            val bx = (b.lng - a.lng) * metersPerDegLng
            val by = (b.lat - a.lat) * METERS_PER_DEG_LAT
            val px = (position.lng - a.lng) * metersPerDegLng
            val py = (position.lat - a.lat) * METERS_PER_DEG_LAT

            val segLenSq = bx * bx + by * by
            val t = if (segLenSq <= 1e-9) {
                0.0
            } else {
                ((px * bx + py * by) / segLenSq).coerceIn(0.0, 1.0)
            }

            val projX = t * bx
            val projY = t * by
            val distM = hypot(px - projX, py - projY)

            if (distM < bestDistM) {
                bestDistM = distM
                bestSeg = i
                bestT = t
                bestLat = a.lat + t * (b.lat - a.lat)
                bestLng = a.lng + t * (b.lng - a.lng)
            }
        }

        val segStartCum = if (bestSeg < cum.size) cum[bestSeg] else 0.0
        val segEndCum = if (bestSeg + 1 < cum.size) cum[bestSeg + 1] else segStartCum
        val alongTrack = segStartCum + bestT * (segEndCum - segStartCum)
        val totalGeomDist = if (cum.isNotEmpty()) cum.last() else route.totalDistanceMeters
        val effectiveTotal = maxOf(totalGeomDist, route.totalDistanceMeters)
        val remaining = (effectiveTotal - alongTrack).coerceAtLeast(0.0)
        val bearing = geom[bestSeg].bearingToDegrees(geom[bestSeg + 1])

        return SnapResult(
            snappedPoint = GeoPoint(bestLat, bestLng),
            segmentIndex = bestSeg,
            segmentFraction = bestT,
            crossTrackMeters = bestDistM,
            alongTrackMeters = alongTrack,
            remainingMeters = remaining,
            segmentBearingDeg = bearing
        )
    }
}
