package com.drifly.condutor.map.routing

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val lat: Double,
    val lng: Double
) {
    /** Distância em metros pela fórmula de Haversine. */
    fun distanceToMeters(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLng = Math.toRadians(other.lng - lng)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) *
            sin(dLng / 2.0) * sin(dLng / 2.0)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Azimute inicial (0°..360°, 0° = Norte) até [other]. */
    fun bearingToDegrees(other: GeoPoint): Float {
        val lat1 = Math.toRadians(lat)
        val lat2 = Math.toRadians(other.lat)
        val dLng = Math.toRadians(other.lng - lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}

data class RouteWaypoint(
    val point: GeoPoint,
    val label: String = "",
    val isLockedOrder: Boolean = false
)

data class AvoidZone(
    val id: String,
    val label: String,
    val center: GeoPoint,
    val radiusMeters: Double = DEFAULT_RADIUS_M,
    val polygon: List<GeoPoint> = buildOctagon(center, radiusMeters),
    val hardAvoid: Boolean = false
) {
    fun contains(point: GeoPoint): Boolean = center.distanceToMeters(point) <= radiusMeters

    companion object {
        const val DEFAULT_RADIUS_M = 1_200.0

        /**
         * Constrói um octógono geodésico fechado (9 pontos, último == primeiro) em torno
         * de [center], corrigido por cos(lat) para não distorcer em Joinville (~26°S).
         */
        fun buildOctagon(center: GeoPoint, radiusMeters: Double): List<GeoPoint> {
            val latRad = Math.toRadians(center.lat)
            val dLatDeg = (radiusMeters / GeoPoint.EARTH_RADIUS_M) * (180.0 / PI)
            val cosLat = cos(latRad).coerceAtLeast(0.01)
            val dLngDeg = dLatDeg / cosLat

            val verts = ArrayList<GeoPoint>(9)
            for (i in 0 until 8) {
                val angle = (2.0 * PI * i) / 8.0
                verts.add(
                    GeoPoint(
                        lat = center.lat + dLatDeg * sin(angle),
                        lng = center.lng + dLngDeg * cos(angle)
                    )
                )
            }
            verts.add(verts[0])
            return verts
        }
    }
}

enum class RouteProfile {
    CAR_FASTEST,
    CAR_SHORTEST,
    MOTO_FASTEST
}

data class RouteRequest(
    val origin: GeoPoint,
    val waypoints: List<RouteWaypoint>,
    val profile: RouteProfile = RouteProfile.CAR_FASTEST,
    val avoidZones: List<AvoidZone> = emptyList(),
    val computeAlternatives: Boolean = true,
    val originBearingDeg: Float? = null
)

enum class ManeuverType {
    DEPART,
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    UTURN_LEFT,
    UTURN_RIGHT,
    ROUNDABOUT_ENTER,
    ROUNDABOUT_EXIT,
    MERGE,
    RAMP_RIGHT,
    RAMP_LEFT,
    WAYPOINT_ARRIVE,
    ARRIVE
}

data class RouteStep(
    val index: Int,
    val maneuver: ManeuverType,
    val streetName: String,
    val instructionPtBr: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometryStartIndex: Int,
    val geometryEndIndex: Int,
    val roundaboutExit: Int? = null
)

data class RouteLeg(
    val from: RouteWaypoint,
    val to: RouteWaypoint,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>
)

data class ComputedRoute(
    val id: String,
    val geometry: List<GeoPoint>,
    val cumulativeDistancesM: DoubleArray,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val legs: List<RouteLeg>,
    val avoidedZonesCount: Int = 0,
    val warningMessage: String? = null
) {
    val allSteps: List<RouteStep>
        get() = legs.flatMap { it.steps }

    companion object {
        /** Pré-calcula distâncias acumuladas ao longo da geometria para snap O(1). */
        fun buildCumulativeDistances(geometry: List<GeoPoint>): DoubleArray {
            if (geometry.isEmpty()) return DoubleArray(0)
            val out = DoubleArray(geometry.size)
            var acc = 0.0
            for (i in 1 until geometry.size) {
                acc += geometry[i - 1].distanceToMeters(geometry[i])
                out[i] = acc
            }
            return out
        }
    }
}

sealed class RouteResponse {
    data class Success(
        val primary: ComputedRoute,
        val alternatives: List<ComputedRoute> = emptyList()
    ) : RouteResponse()

    data class Error(val reason: String) : RouteResponse()
}

data class OptimizedRoutePlan(
    val primary: ComputedRoute,
    val alternatives: List<ComputedRoute>,
    val orderedWaypoints: List<RouteWaypoint>,
    val activeAvoidZones: List<AvoidZone>,
    val skippedAvoidZones: List<AvoidZone>,
    val wasReordered: Boolean,
    val estimatedTimeSavedSeconds: Double,
    val estimatedDistanceSavedMeters: Double,
    val estimatedFuelCostBrl: Double,
    val summaryWarning: String?
)
