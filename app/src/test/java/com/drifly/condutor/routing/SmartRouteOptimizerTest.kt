package com.drifly.condutor.map.routing

import com.drifly.condutor.BlacklistItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmartRouteOptimizerTest {

    private class FakeMatrixEngine(
        private val customMatrix: Array<DoubleArray>? = null
    ) : OfflineRouteEngine {
        override val engineName: String = "FakeEngine"
        override val isReady: Boolean = true
        var lastRequest: RouteRequest? = null

        override suspend fun initialize(packDir: File): Boolean = true

        override suspend fun computeRoute(request: RouteRequest): RouteResponse {
            lastRequest = request
            val pts = buildList {
                add(request.origin)
                request.waypoints.forEach { add(it.point) }
            }
            val cum = ComputedRoute.buildCumulativeDistances(pts)
            val dist = cum.lastOrNull() ?: 0.0
            val dur = dist / 10.0
            return RouteResponse.Success(
                primary = ComputedRoute(
                    id = "r1",
                    geometry = pts,
                    cumulativeDistancesM = cum,
                    totalDistanceMeters = dist,
                    totalDurationSeconds = dur,
                    legs = emptyList(),
                    avoidedZonesCount = request.avoidZones.size
                )
            )
        }

        override suspend fun computeCostMatrix(
            points: List<GeoPoint>,
            profile: RouteProfile,
            avoidZones: List<AvoidZone>
        ): Array<DoubleArray>? = customMatrix

        override fun close() = Unit
    }

    @Test
    fun optimizesMultiStopOrderInJoinvilleAndRespectsAsymmetricRoads() = runBlocking {
        // Origem: Centro Joinville (-26.3045, -48.8487)
        // Ordem ruim inserida pelo usuário: 1=Pirabeiraba (Norte distante), 2=América (perto), 3=Glória (perto), 4=Santo Antônio (meio)
        val origin = GeoPoint(-26.3045, -48.8487)
        val stopFarNorth = RouteWaypoint(GeoPoint(-26.2150, -48.9020), "Pirabeiraba")
        val stopAmerica = RouteWaypoint(GeoPoint(-26.2890, -48.8510), "América")
        val stopGloria = RouteWaypoint(GeoPoint(-26.2980, -48.8620), "Glória")
        val stopSantoAntonio = RouteWaypoint(GeoPoint(-26.2650, -48.8560), "Santo Antônio")

        // Matriz assimétrica (mão única): 0 -> Glória -> América -> Santo Antônio -> Pirabeiraba é ótimo (120+90+140+300=650s)
        val matrix = arrayOf(
            //          0(Orig)  1(Pirab)  2(Amer)  3(Glor)  4(S.Ant)
            doubleArrayOf(0.0,   720.0,    200.0,   120.0,   380.0), // 0
            doubleArrayOf(750.0, 0.0,      560.0,   640.0,   320.0), // 1
            doubleArrayOf(210.0, 520.0,    0.0,     180.0,   140.0), // 2 (América -> S.Ant = 140)
            doubleArrayOf(140.0, 610.0,    90.0,    0.0,     260.0), // 3 (Glória -> América = 90)
            doubleArrayOf(390.0, 300.0,    160.0,   270.0,   0.0)    // 4 (S.Ant -> Pirab = 300)
        )

        val engine = FakeMatrixEngine(customMatrix = matrix)
        val optimizer = SmartRouteOptimizer(engine)

        val result = optimizer.planRoute(
            origin = origin,
            waypoints = listOf(stopFarNorth, stopAmerica, stopGloria, stopSantoAntonio),
            profile = RouteProfile.MOTO_FASTEST,
            optimizeStopOrder = true,
            costPerKmBrl = 0.50
        ).getOrThrow()

        assertTrue(result.wasReordered)
        assertEquals(
            listOf("Glória", "América", "Santo Antônio", "Pirabeiraba"),
            result.orderedWaypoints.map { it.label }
        )
        assertTrue(result.estimatedTimeSavedSeconds > 500.0)
        assertTrue(result.estimatedFuelCostBrl > 0.0)
        assertNotNull(SmartRouteOptimizer.formatOptimizationSummary(result))
    }

    @Test
    fun lockFinalDestinationKeepsModoCasaLast() = runBlocking {
        // Arco 2D em Joinville: Centro -> Glória (Oeste) -> América (Norte) -> Boa Vista (Leste/Casa)
        // Ordem ruim: Centro -> América -> Glória -> Casa (zigue-zague mais longo).
        val origin = GeoPoint(-26.3045, -48.8487)
        val stopNorth = RouteWaypoint(GeoPoint(-26.2780, -48.8520), "América")
        val stopWest = RouteWaypoint(GeoPoint(-26.3020, -48.8780), "Glória")
        val homeEast = RouteWaypoint(GeoPoint(-26.2880, -48.8180), "Casa", isLockedOrder = true)

        val engine = FakeMatrixEngine()
        val optimizer = SmartRouteOptimizer(engine)

        val plan = optimizer.planRoute(
            origin = origin,
            waypoints = listOf(stopNorth, stopWest, homeEast),
            lockFinalDestination = true
        ).getOrThrow()

        assertTrue(plan.wasReordered)
        assertEquals("Casa", plan.orderedWaypoints.last().label)
        assertEquals("Glória", plan.orderedWaypoints.first().label)
    }

    @Test
    fun resolvesGeoAndTextOnlyBlacklistAndSkipsZoneEnclosingDestination() = runBlocking {
        val origin = GeoPoint(-26.3045, -48.8487) // Centro
        val destInJardimParaiso = RouteWaypoint(GeoPoint(-26.2240, -48.8290), "Jardim Paraíso")

        val blacklist = listOf(
            // Com coordenadas (Aventureiro) -> deve ser evitado
            BlacklistItem(
                id = 1L,
                rawTerm = "Aventureiro",
                normalizedTerm = "aventureiro",
                latitude = -26.2590,
                longitude = -48.8070
            ),
            // Sem coordenadas (modo texto offline: Comasa) -> resolvido via lookup offline!
            BlacklistItem(
                id = 2L,
                rawTerm = "Comasa",
                normalizedTerm = "comasa",
                latitude = null,
                longitude = null
            ),
            // Zona onde está o próprio destino (Jardim Paraíso) -> removida da evasão com aviso!
            BlacklistItem(
                id = 3L,
                rawTerm = "Jardim Paraíso",
                normalizedTerm = "jardim paraiso",
                latitude = -26.2245,
                longitude = -48.8295
            )
        )

        val lookup = OfflineNeighborhoodLookup { norm ->
            if (norm == "comasa") GeoPoint(-26.2830, -48.7980) else null
        }

        val engine = FakeMatrixEngine()
        val optimizer = SmartRouteOptimizer(engine, neighborhoodLookup = lookup)

        val plan = optimizer.planRoute(
            origin = origin,
            waypoints = listOf(destInJardimParaiso),
            blacklistItems = blacklist,
            avoidBlacklist = true
        ).getOrThrow()

        assertEquals(2, plan.activeAvoidZones.size)
        assertTrue(plan.activeAvoidZones.any { it.label == "Aventureiro" })
        assertTrue(plan.activeAvoidZones.any { it.label == "Comasa" })
        assertEquals(1, plan.skippedAvoidZones.size)
        assertEquals("Jardim Paraíso", plan.skippedAvoidZones.first().label)
        assertNotNull(plan.summaryWarning)
        assertTrue(plan.summaryWarning!!.contains("Jardim Paraíso"))
        assertEquals(2, engine.lastRequest?.avoidZones?.size)
        assertFalse(plan.activeAvoidZones.any { it.label == "Jardim Paraíso" })
    }
}
