package com.drifly.condutor.map.routing

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class DfrgRouteEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // Builder sintético de binário DFRG v1 (Little-Endian, header de 48 bytes)
    // =========================================================================

    private class SyntheticDfrgBuilder {
        private data class Node(val latE6: Int, val lngE6: Int)
        private data class Edge(
            val from: Int,
            val to: Int,
            val distM: Int,
            val timeDs: Int,
            val flags: Int,
            val nameId: Int,
            val geom: List<Pair<Int, Int>>
        )

        private val nodes = mutableListOf<Node>()
        private val edges = mutableListOf<Edge>()
        private val nameMap = LinkedHashMap<String, Int>()
        private val names = mutableListOf<String>()

        fun addNode(lat: Double, lng: Double): Int {
            val id = nodes.size
            nodes.add(Node((lat * 1_000_000.0).toInt(), (lng * 1_000_000.0).toInt()))
            return id
        }

        fun internName(name: String?): Int {
            if (name.isNullOrBlank()) return -1
            return nameMap.getOrPut(name) {
                val id = names.size
                names.add(name)
                id
            }
        }

        fun addDirectedEdge(
            from: Int,
            to: Int,
            distM: Int? = null,
            speedKmh: Double = 50.0,
            flags: Int = DfrgGraph.ROAD_CLASS_SECONDARY,
            name: String? = null,
            intermediatePoints: List<GeoPoint> = emptyList()
        ) {
            val pFrom = GeoPoint(nodes[from].latE6 * 1e-6, nodes[from].lngE6 * 1e-6)
            val pTo = GeoPoint(nodes[to].latE6 * 1e-6, nodes[to].lngE6 * 1e-6)
            val computedDist = distM ?: run {
                var acc = 0.0
                var prev = pFrom
                for (pt in intermediatePoints) {
                    acc += prev.distanceToMeters(pt)
                    prev = pt
                }
                acc += prev.distanceToMeters(pTo)
                acc.toInt().coerceAtLeast(1)
            }
            val speedMps = (speedKmh / 3.6).coerceAtLeast(1.0)
            val timeDs = ((computedDist / speedMps) * 10.0).toInt().coerceAtLeast(1)
            val geomE6 = intermediatePoints.map { (it.lat * 1_000_000.0).toInt() to (it.lng * 1_000_000.0).toInt() }
            edges.add(Edge(from, to, computedDist, timeDs, flags, internName(name), geomE6))
        }

        fun addTwoWayEdge(
            a: Int,
            b: Int,
            distM: Int? = null,
            speedKmh: Double = 50.0,
            flags: Int = DfrgGraph.ROAD_CLASS_SECONDARY,
            name: String? = null,
            intermediatePoints: List<GeoPoint> = emptyList()
        ) {
            addDirectedEdge(a, b, distM, speedKmh, flags, name, intermediatePoints)
            addDirectedEdge(b, a, distM, speedKmh, flags, name, intermediatePoints.asReversed())
        }

        fun buildBytes(): ByteArray {
            val n = nodes.size
            val sortedEdges = edges.sortedBy { it.from }
            val eCount = sortedEdges.size

            val firstEdge = IntArray(n + 1)
            for (edge in sortedEdges) {
                firstEdge[edge.from + 1]++
            }
            for (i in 0 until n) {
                firstEdge[i + 1] += firstEdge[i]
            }

            var minLat = Int.MAX_VALUE
            var minLng = Int.MAX_VALUE
            var maxLat = Int.MIN_VALUE
            var maxLng = Int.MIN_VALUE
            for (node in nodes) {
                if (node.latE6 < minLat) minLat = node.latE6
                if (node.lngE6 < minLng) minLng = node.lngE6
                if (node.latE6 > maxLat) maxLat = node.latE6
                if (node.lngE6 > maxLng) maxLng = node.lngE6
            }
            if (n == 0) {
                minLat = 0; minLng = 0; maxLat = 0; maxLng = 0
            }

            val geomOffsets = IntArray(eCount + 1)
            var totalGeom = 0
            for (i in 0 until eCount) {
                geomOffsets[i] = totalGeom
                totalGeom += sortedEdges[i].geom.size
            }
            geomOffsets[eCount] = totalGeom

            val out = ByteArrayOutputStream()
            fun writeInt(v: Int) {
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
                out.write(b)
            }
            fun writeShort(v: Int) {
                val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
                out.write(b)
            }

            // 1. Header (48 bytes)
            out.write("DFRG".toByteArray(StandardCharsets.US_ASCII)) // 4
            writeShort(1) // version = 1 (2)
            writeShort(0) // flags = 0 (2)
            writeInt(n) // 4
            writeInt(eCount) // 4
            writeInt(names.size) // 4
            writeInt(totalGeom) // 4
            writeInt(minLat) // 4
            writeInt(minLng) // 4
            writeInt(maxLat) // 4
            writeInt(maxLng) // 4
            out.write(ByteArray(8)) // reserved = 8 bytes -> total = 48

            // 2. Nodes
            for (node in nodes) writeInt(node.latE6)
            for (node in nodes) writeInt(node.lngE6)
            for (v in firstEdge) writeInt(v)

            // 3. Directed Edges
            for (e in sortedEdges) writeInt(e.to)
            for (e in sortedEdges) writeInt(e.distM)
            for (e in sortedEdges) writeInt(e.timeDs)
            for (e in sortedEdges) writeShort(e.flags)
            for (e in sortedEdges) writeInt(e.nameId)
            for (v in geomOffsets) writeInt(v)

            // 4. Intermediate Edge Geometry
            for (e in sortedEdges) for (pt in e.geom) writeInt(pt.first)
            for (e in sortedEdges) for (pt in e.geom) writeInt(pt.second)

            // 5. Street Name Table
            for (name in names) {
                val utf8 = name.toByteArray(StandardCharsets.UTF_8)
                writeShort(utf8.size)
                out.write(utf8)
            }

            return out.toByteArray()
        }
    }

    /**
     * Constrói um grafo realista estilo Joinville (~4x4 grade + via expressa com pedágio
     * + rua de mão única + rotatória de 4 saídas + alça rodoviária).
     *
     * Grade 4x4 (linhas r=0..3 de Sul para Norte, colunas c=0..3 de Oeste para Leste):
     * Espaçamento: ~0.005° (~555 m em lat, ~498 m em lng).
     */
    private data class CityFixture(
        val bytes: ByteArray,
        val grid: Array<IntArray>,
        val rbSouth: Int,
        val rbEast: Int,
        val rbNorth: Int,
        val rbWest: Int,
        val rbExitEast: Int,
        val rbExitNorth: Int,
        val rbExitWest: Int,
        val tollMid: Int,
        val rampNode: Int,
        val graph: DfrgGraph
    )

    private fun buildJoinvilleFixture(): CityFixture {
        val b = SyntheticDfrgBuilder()
        val baseLat = -26.3100
        val baseLng = -48.8600
        val step = 0.0050 // ~500-555 m

        // Grade 4x4: nós 0..15
        val grid = Array(4) { r ->
            IntArray(4) { c ->
                b.addNode(baseLat + r * step, baseLng + c * step)
            }
        }

        val hNames = arrayOf("Rua Graciosa", "Rua João Colin", "Av. Beira Rio", "Rua XV de Novembro")
        val vNames = arrayOf("Rua Blumenau", "Rua Ottokar Doerffel", "Rua Dona Francisca", "Av. Santos Dumont")

        // Arestas horizontais (com geometria intermediária em uma das ruas para testar expansão)
        for (r in 0..3) {
            val isPrimary = (r == 1 || r == 2)
            val rc = if (isPrimary) DfrgGraph.ROAD_CLASS_PRIMARY else DfrgGraph.ROAD_CLASS_RESIDENTIAL
            val speed = if (isPrimary) 60.0 else 35.0
            for (c in 0..2) {
                val u = grid[r][c]
                val v = grid[r][c + 1]
                // Trecho de mão única exclusivo Leste->Oeste em r=1, c=0..1 (Rua João Colin)
                if (r == 1 && c == 0) {
                    // Somente v -> u (mão única para Oeste)
                    b.addDirectedEdge(
                        from = v,
                        to = u,
                        speedKmh = speed,
                        flags = rc or DfrgGraph.FLAG_ONEWAY,
                        name = hNames[r]
                    )
                } else {
                    val midPts = if (r == 2 && c == 1) {
                        listOf(
                            GeoPoint(baseLat + r * step + 0.0004, baseLng + (c + 0.5) * step)
                        )
                    } else emptyList()
                    b.addTwoWayEdge(u, v, speedKmh = speed, flags = rc, name = hNames[r], intermediatePoints = midPts)
                }
            }
        }

        // Arestas verticais
        for (c in 0..3) {
            val isPrimary = (c == 0 || c == 3)
            val rc = if (isPrimary) DfrgGraph.ROAD_CLASS_SECONDARY else DfrgGraph.ROAD_CLASS_TERTIARY
            val speed = if (isPrimary) 55.0 else 40.0
            for (r in 0..2) {
                b.addTwoWayEdge(grid[r][c], grid[r + 1][c], speedKmh = speed, flags = rc, name = vNames[c])
            }
        }

        // Via expressa com pedágio (motorway, 110 km/h) ligando grid[0][0] -> tollMid -> grid[0][3]
        // Mais longa em metros, porém muito mais rápida que atravessar a grade.
        val tollMid = b.addNode(baseLat - 0.0040, baseLng + 1.5 * step)
        val rampNode = b.addNode(baseLat - 0.0020, baseLng + 0.35 * step)
        b.addTwoWayEdge(
            grid[0][0],
            rampNode,
            speedKmh = 70.0,
            flags = DfrgGraph.ROAD_CLASS_MOTORWAY or DfrgGraph.FLAG_LINK or DfrgGraph.FLAG_TOLL,
            name = ""
        )
        b.addTwoWayEdge(
            rampNode,
            tollMid,
            speedKmh = 110.0,
            flags = DfrgGraph.ROAD_CLASS_MOTORWAY or DfrgGraph.FLAG_TOLL,
            name = "BR-101"
        )
        b.addTwoWayEdge(
            tollMid,
            grid[0][3],
            speedKmh = 110.0,
            flags = DfrgGraph.ROAD_CLASS_MOTORWAY or DfrgGraph.FLAG_TOLL,
            name = "BR-101"
        )

        // Rotatória de 4 saídas conectada ao topo-direito da grade (grid[3][3] entra pelo Sul)
        val rbCenterLat = baseLat + 3 * step + 0.0020
        val rbCenterLng = baseLng + 3 * step
        val rbRadius = 0.0005 // ~55m
        val rbSouth = b.addNode(rbCenterLat - rbRadius, rbCenterLng)
        val rbEast = b.addNode(rbCenterLat, rbCenterLng + rbRadius)
        val rbNorth = b.addNode(rbCenterLat + rbRadius, rbCenterLng)
        val rbWest = b.addNode(rbCenterLat, rbCenterLng - rbRadius)

        val rbExitEast = b.addNode(rbCenterLat, rbCenterLng + 0.0035)
        val rbExitNorth = b.addNode(rbCenterLat + 0.0035, rbCenterLng)
        val rbExitWest = b.addNode(rbCenterLat, rbCenterLng - 0.0035)

        val rbFlags = DfrgGraph.ROAD_CLASS_SECONDARY or DfrgGraph.FLAG_ONEWAY or DfrgGraph.FLAG_ROUNDABOUT
        // Anel anti-horário (mão brasileira): Sul -> Leste -> Norte -> Oeste -> Sul
        b.addDirectedEdge(rbSouth, rbEast, speedKmh = 30.0, flags = rbFlags, name = "Rotatória do Pórtico")
        b.addDirectedEdge(rbEast, rbNorth, speedKmh = 30.0, flags = rbFlags, name = "Rotatória do Pórtico")
        b.addDirectedEdge(rbNorth, rbWest, speedKmh = 30.0, flags = rbFlags, name = "Rotatória do Pórtico")
        b.addDirectedEdge(rbWest, rbSouth, speedKmh = 30.0, flags = rbFlags, name = "Rotatória do Pórtico")

        // Conexões de entrada/saída da rotatória
        b.addTwoWayEdge(grid[3][3], rbSouth, speedKmh = 50.0, flags = DfrgGraph.ROAD_CLASS_SECONDARY, name = "Av. Santos Dumont")
        b.addTwoWayEdge(rbEast, rbExitEast, speedKmh = 50.0, flags = DfrgGraph.ROAD_CLASS_SECONDARY, name = "Rua Tuiuti")
        b.addTwoWayEdge(rbNorth, rbExitNorth, speedKmh = 55.0, flags = DfrgGraph.ROAD_CLASS_PRIMARY, name = "Av. Santos Dumont")
        b.addTwoWayEdge(rbWest, rbExitWest, speedKmh = 50.0, flags = DfrgGraph.ROAD_CLASS_SECONDARY, name = "Rua Tenente Antônio João")

        val bytes = b.buildBytes()
        val graph = DfrgGraph.load(ByteArrayInputStream(bytes))
        return CityFixture(
            bytes = bytes,
            grid = grid,
            rbSouth = rbSouth,
            rbEast = rbEast,
            rbNorth = rbNorth,
            rbWest = rbWest,
            rbExitEast = rbExitEast,
            rbExitNorth = rbExitNorth,
            rbExitWest = rbExitWest,
            tollMid = tollMid,
            rampNode = rampNode,
            graph = graph
        )
    }

    // =========================================================================
    // Testes
    // =========================================================================

    @Test
    fun `binary parsing loads header, CSR, reverse CSR, geometry, street names, and spatial index`() {
        val fx = buildJoinvilleFixture()
        val g = fx.graph

        assertEquals(1, g.version)
        assertEquals(25, g.nodeCount)
        assertTrue(g.edgeCount > 40)
        assertTrue(g.geomPointCount >= 2)
        assertTrue(g.streetNames.contains("Av. Beira Rio"))
        assertTrue(g.streetNames.contains("BR-101"))

        // Verifica snap espacial exato em um nó conhecido.
        val targetNode = fx.grid[2][2]
        val pt = g.nodePoint(targetNode)
        val snapped = g.nearestNode(pt.lat + 0.0001, pt.lng - 0.0001, maxRadiusM = 500.0)
        assertEquals(targetNode, snapped)

        // Ponto muito distante retorna -1.
        assertEquals(-1, g.nearestNode(0.0, 0.0, maxRadiusM = 2_000.0))
    }

    @Test
    fun `initialize from packDir loads nested routing dfrg and rejects corrupted file`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val packDir = tempFolder.newFolder("joinville_pack")
        val routingDir = File(packDir, "routing").apply { mkdirs() }
        File(routingDir, "routing.dfrg").writeBytes(fx.bytes)

        val engine = DfrgRouteEngine()
        assertFalse(engine.isReady)
        assertTrue(engine.initialize(packDir))
        assertTrue(engine.isReady)

        engine.close()
        assertFalse(engine.isReady)

        // Arquivo com assinatura mágica corrompida deve falhar graciosamente.
        val badPack = tempFolder.newFolder("bad_pack")
        File(badPack, "routing.dfrg").writeBytes("NOPE".toByteArray() + ByteArray(64))
        assertFalse(engine.initialize(badPack))
    }

    @Test(expected = IOException::class)
    fun `invalid DFRG version throws IOException`() {
        val buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("DFRG".toByteArray(StandardCharsets.US_ASCII))
        buf.putShort(99) // versão inválida
        DfrgGraph.load(ByteArrayInputStream(buf.array()))
    }

    @Test
    fun `oneway street is strictly respected and produces asymmetric travel times`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val engine = DfrgRouteEngine().apply { initialize(fx.graph) }

        val westNode = fx.grid[1][0]
        val eastNode = fx.grid[1][1]
        val westPt = fx.graph.nodePoint(westNode)
        val eastPt = fx.graph.nodePoint(eastNode)

        // Leste -> Oeste: direto na mão única (~500m)
        val eastToWest = engine.computeRoute(
            RouteRequest(origin = eastPt, waypoints = listOf(RouteWaypoint(westPt)), computeAlternatives = false)
        ) as RouteResponse.Success

        // Oeste -> Leste: proibido ir direto; precisa contornar a quadra (~1500m)
        val westToEast = engine.computeRoute(
            RouteRequest(origin = westPt, waypoints = listOf(RouteWaypoint(eastPt)), computeAlternatives = false)
        ) as RouteResponse.Success

        assertTrue(
            "Sentido a favor da mão única deve ser bem menor (${eastToWest.primary.totalDistanceMeters}m vs ${westToEast.primary.totalDistanceMeters}m)",
            eastToWest.primary.totalDistanceMeters * 2.0 < westToEast.primary.totalDistanceMeters
        )
    }

    @Test
    fun `fastest vs shortest and avoidTolls switch between BR-101 motorway and urban grid`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val origin = fx.graph.nodePoint(fx.grid[0][0])
        val dest = fx.graph.nodePoint(fx.grid[0][3])

        // 1) CAR_FASTEST com pedágio liberado deve pegar BR-101 (110 km/h)
        val fastWithTollEngine = DfrgRouteEngine(avoidTolls = false).apply { initialize(fx.graph) }
        val fastWithToll = fastWithTollEngine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(dest)), profile = RouteProfile.CAR_FASTEST, computeAlternatives = false)
        ) as RouteResponse.Success
        val usesBr101 = fastWithToll.primary.allSteps.any { it.streetName.contains("BR-101") }
        assertTrue("CAR_FASTEST deve usar BR-101 quando pedágio permitido", usesBr101)

        // Partindo de uma rua urbana (grid[1][0] -> grid[0][0] -> alça -> BR-101), deve gerar manobra RAMP + MERGE.
        val fromUrbanToBr101 = fastWithTollEngine.computeRoute(
            RouteRequest(
                origin = fx.graph.nodePoint(fx.grid[1][0]),
                waypoints = listOf(RouteWaypoint(dest)),
                profile = RouteProfile.CAR_FASTEST,
                computeAlternatives = false
            )
        ) as RouteResponse.Success
        val hasRamp = fromUrbanToBr101.primary.allSteps.any {
            it.maneuver == ManeuverType.RAMP_RIGHT || it.maneuver == ManeuverType.RAMP_LEFT
        }
        assertTrue("Deve gerar manobra de acesso (RAMP) ao entrar da rua urbana na alça rodoviária", hasRamp)

        // 2) CAR_SHORTEST deve preferir a reta urbana (Rua Graciosa, mais curta em metros que a curva pela BR-101)
        val shortest = fastWithTollEngine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(dest)), profile = RouteProfile.CAR_SHORTEST, computeAlternatives = false)
        ) as RouteResponse.Success
        assertTrue(
            "CAR_SHORTEST (${shortest.primary.totalDistanceMeters}m) deve ser mais curto que BR-101 (${fastWithToll.primary.totalDistanceMeters}m)",
            shortest.primary.totalDistanceMeters < fastWithToll.primary.totalDistanceMeters
        )

        // 3) avoidTolls = true deve evitar BR-101 mesmo no CAR_FASTEST
        val noTollEngine = DfrgRouteEngine(avoidTolls = true).apply { initialize(fx.graph) }
        val fastNoToll = noTollEngine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(dest)), profile = RouteProfile.CAR_FASTEST, computeAlternatives = false)
        ) as RouteResponse.Success
        val avoidedBr101 = fastNoToll.primary.allSteps.none { it.streetName.contains("BR-101") }
        assertTrue("avoidTolls=true deve evitar BR-101", avoidedBr101)

        // 4) MOTO_FASTEST deve estimar tempo urbano menor que CAR_FASTEST na mesma via sem pedágio
        val motoNoToll = noTollEngine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(dest)), profile = RouteProfile.MOTO_FASTEST, computeAlternatives = false)
        ) as RouteResponse.Success
        assertTrue(
            "MOTO_FASTEST (${motoNoToll.primary.totalDurationSeconds}s) deve ser mais rápido no trecho urbano que CAR_FASTEST (${fastNoToll.primary.totalDurationSeconds}s)",
            motoNoToll.primary.totalDurationSeconds < fastNoToll.primary.totalDurationSeconds
        )
    }

    @Test
    fun `blacklist AvoidZone forces clean detour around blocked corridor and warns if trapped inside`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val engine = DfrgRouteEngine(avoidTolls = true).apply { initialize(fx.graph) }

        val origin = fx.graph.nodePoint(fx.grid[2][0])
        val dest = fx.graph.nodePoint(fx.grid[2][3])

        // Sem Lista Negra: segue reto pela Av. Beira Rio (passando por grid[2][1] e grid[2][2])
        val baseline = engine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(dest)), computeAlternatives = false)
        ) as RouteResponse.Success

        // Coloca zona de Lista Negra exatamente sobre o miolo da Av. Beira Rio (entre grid[2][1] e grid[2][2])
        val p1 = fx.graph.nodePoint(fx.grid[2][1])
        val p2 = fx.graph.nodePoint(fx.grid[2][2])
        val blockedZone = AvoidZone(
            id = "bl_centro",
            label = "Bairro Restrito",
            center = GeoPoint((p1.lat + p2.lat) / 2.0, (p1.lng + p2.lng) / 2.0),
            radiusMeters = 350.0,
            hardAvoid = false
        )

        val detoured = engine.computeRoute(
            RouteRequest(
                origin = origin,
                waypoints = listOf(RouteWaypoint(dest)),
                avoidZones = listOf(blockedZone),
                computeAlternatives = false
            )
        ) as RouteResponse.Success

        assertEquals(1, detoured.primary.avoidedZonesCount)
        assertNull("Desvio limpo não deve emitir alerta de passagem pela Lista Negra", detoured.primary.warningMessage)
        assertTrue(
            "Rota desviada (${detoured.primary.totalDistanceMeters}m) deve ser mais longa que a reta bloqueada (${baseline.primary.totalDistanceMeters}m)",
            detoured.primary.totalDistanceMeters > baseline.primary.totalDistanceMeters + 200.0
        )
        assertTrue(
            "Nenhum ponto da geometria desviada deve entrar na zona bloqueada",
            detoured.primary.geometry.none { blockedZone.contains(it) }
        )

        // Cenário preso dentro da zona: destino dentro da zona bloqueada -> ainda retorna rota + warning!
        val trappedZone = AvoidZone(
            id = "bl_trapped",
            label = "Destino Bloqueado",
            center = dest,
            radiusMeters = 300.0,
            hardAvoid = true
        )
        val trappedResp = engine.computeRoute(
            RouteRequest(
                origin = origin,
                waypoints = listOf(RouteWaypoint(dest)),
                avoidZones = listOf(trappedZone),
                computeAlternatives = false
            )
        ) as RouteResponse.Success

        assertNotNull("Quando preso na zona, motor deve retornar rota válida com aviso", trappedResp.primary.warningMessage)
        assertEquals(0, trappedResp.primary.avoidedZonesCount)
    }

    @Test
    fun `alternative routes are distinct within overlap and cost bounds`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val engine = DfrgRouteEngine(avoidTolls = true).apply { initialize(fx.graph) }

        val origin = fx.graph.nodePoint(fx.grid[0][0])
        val dest = fx.graph.nodePoint(fx.grid[3][3])

        val resp = engine.computeRoute(
            RouteRequest(
                origin = origin,
                waypoints = listOf(RouteWaypoint(dest)),
                profile = RouteProfile.CAR_FASTEST,
                computeAlternatives = true
            )
        ) as RouteResponse.Success

        assertTrue("Grade 4x4 deve gerar ao menos 1 rota alternativa distinta", resp.alternatives.isNotEmpty())
        val primaryDur = resp.primary.totalDurationSeconds
        for (alt in resp.alternatives) {
            assertTrue(
                "Alternativa não pode exceder 1.45x da duração principal (${alt.totalDurationSeconds}s vs ${primaryDur}s)",
                alt.totalDurationSeconds <= primaryDur * 1.45 + 1.0
            )
            assertTrue("Alternativa deve ter geometria distinta da principal", alt.geometry != resp.primary.geometry)
        }
    }

    @Test
    fun `roundabout exit counting produces accurate pt-BR instructions for 1st, 2nd, and 3rd exits`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val engine = DfrgRouteEngine().apply { initialize(fx.graph) }
        val origin = fx.graph.nodePoint(fx.grid[2][3]) // Vem do Sul pela Av. Santos Dumont -> entra na rotatória

        // 1ª saída -> Leste (Rua Tuiuti)
        val exit1 = engine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(fx.graph.nodePoint(fx.rbExitEast), "Tuiuti")), computeAlternatives = false)
        ) as RouteResponse.Success
        val step1 = exit1.primary.allSteps.firstOrNull { it.maneuver == ManeuverType.ROUNDABOUT_ENTER }
        assertNotNull("Deve emitir manobra de rotatória para 1ª saída", step1)
        assertEquals(1, step1!!.roundaboutExit)
        assertEquals("Na rotatória, pegue a 1ª saída para Rua Tuiuti", step1.instructionPtBr)

        // 2ª saída -> Norte (Av. Santos Dumont)
        val exit2 = engine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(fx.graph.nodePoint(fx.rbExitNorth), "Aeroporto")), computeAlternatives = false)
        ) as RouteResponse.Success
        val step2 = exit2.primary.allSteps.firstOrNull { it.maneuver == ManeuverType.ROUNDABOUT_ENTER }
        assertNotNull("Deve emitir manobra de rotatória para 2ª saída", step2)
        assertEquals(2, step2!!.roundaboutExit)
        assertEquals("Na rotatória, pegue a 2ª saída para Av. Santos Dumont", step2.instructionPtBr)

        // 3ª saída -> Oeste (Rua Tenente Antônio João)
        val exit3 = engine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(RouteWaypoint(fx.graph.nodePoint(fx.rbExitWest), "Oeste")), computeAlternatives = false)
        ) as RouteResponse.Success
        val step3 = exit3.primary.allSteps.firstOrNull { it.maneuver == ManeuverType.ROUNDABOUT_ENTER }
        assertNotNull("Deve emitir manobra de rotatória para 3ª saída", step3)
        assertEquals(3, step3!!.roundaboutExit)
        assertEquals("Na rotatória, pegue a 3ª saída para Rua Tenente Antônio João", step3.instructionPtBr)
    }

    @Test
    fun `multi-stop route produces globally sequential step indices, valid geometry ranges, and pt-BR waypoint arrival`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val engine = DfrgRouteEngine(avoidTolls = true).apply { initialize(fx.graph) }

        val origin = fx.graph.nodePoint(fx.grid[0][0])
        val stop1 = RouteWaypoint(fx.graph.nodePoint(fx.grid[2][1]), label = "Terminal Central")
        val stop2 = RouteWaypoint(fx.graph.nodePoint(fx.grid[3][3]), label = "Aeroporto")

        val resp = engine.computeRoute(
            RouteRequest(origin = origin, waypoints = listOf(stop1, stop2), computeAlternatives = false)
        ) as RouteResponse.Success

        val route = resp.primary
        assertEquals(2, route.legs.size)
        assertEquals(route.geometry.size, route.cumulativeDistancesM.size)

        val steps = route.allSteps
        assertTrue(steps.isNotEmpty())
        // Índices de step devem ser estritamente sequenciais (0..N-1) entre pernas para o TTS não suprimir avisos.
        for (i in steps.indices) {
            assertEquals(i, steps[i].index)
            assertTrue(steps[i].geometryStartIndex in route.geometry.indices)
            assertTrue(steps[i].geometryEndIndex in steps[i].geometryStartIndex..route.geometry.lastIndex)
            assertFalse("Instrução pt-BR não deve terminar com ponto final", steps[i].instructionPtBr.endsWith("."))
        }

        assertEquals(ManeuverType.WAYPOINT_ARRIVE, route.legs[0].steps.last().maneuver)
        assertTrue(route.legs[0].steps.last().instructionPtBr.contains("parada 1"))
        assertEquals(ManeuverType.ARRIVE, route.legs[1].steps.last().maneuver)
        assertTrue(route.legs[1].steps.last().instructionPtBr.contains("destino"))
    }

    @Test
    fun `computeCostMatrix computes exact asymmetric NxN travel times and integrates with SmartRouteOptimizer`() = runBlocking {
        val fx = buildJoinvilleFixture()
        val engine = DfrgRouteEngine(avoidTolls = true).apply { initialize(fx.graph) }

        val points = listOf(
            fx.graph.nodePoint(fx.grid[1][0]), // 0 (extremidade oeste da mão única)
            fx.graph.nodePoint(fx.grid[1][1]), // 1 (extremidade leste da mão única)
            fx.graph.nodePoint(fx.grid[3][3]), // 2 (canto NE)
            fx.graph.nodePoint(fx.grid[0][2]), // 3 (sul)
            fx.graph.nodePoint(fx.grid[2][2])  // 4 (centro)
        )

        val matrix = engine.computeCostMatrix(points, RouteProfile.CAR_FASTEST, emptyList())
        assertNotNull(matrix)
        assertEquals(5, matrix!!.size)

        for (i in 0 until 5) {
            assertEquals(0.0, matrix[i][i], 1e-6)
            for (j in 0 until 5) {
                if (i != j) {
                    assertTrue("Custo ($i->$j) deve ser positivo e finito", matrix[i][j] > 1.0 && matrix[i][j] < 10_000.0)
                }
            }
        }

        // Assimetria real de mão única: 1 -> 0 é direto, 0 -> 1 exige contorno da quadra!
        assertTrue(
            "Matriz deve capturar assimetria de mão única (1->0=${matrix[1][0]}s vs 0->1=${matrix[0][1]}s)",
            matrix[1][0] * 1.8 < matrix[0][1]
        )

        // Integração ponta a ponta com SmartRouteOptimizer (reordena paradas fora de ordem ótima)
        val optimizer = SmartRouteOptimizer(engine)
        val planResult = optimizer.planRoute(
            origin = fx.graph.nodePoint(fx.grid[0][0]),
            waypoints = listOf(
                RouteWaypoint(fx.graph.nodePoint(fx.grid[3][3]), "Longe"),
                RouteWaypoint(fx.graph.nodePoint(fx.grid[0][1]), "Perto"),
                RouteWaypoint(fx.graph.nodePoint(fx.grid[2][1]), "Meio")
            ),
            optimizeStopOrder = true,
            computeAlternatives = false
        )
        assertTrue(planResult.isSuccess)
        val plan = planResult.getOrThrow()
        assertTrue("Otimizador deve reordenar paradas usando matriz exata DFRG", plan.wasReordered)
        assertEquals("Perto", plan.orderedWaypoints.first().label)
    }

    @Test
    fun `real Joinville DFRG pack loads fast and routes Terminal Central to Aeroporto with blacklist detour and 5x5 matrix`() = runBlocking {
        // Resolve scripts/map-pack/out/routing/routing.dfrg a partir do diretório de trabalho (:app ou raiz do repo).
        val candidates = listOf(
            File("../scripts/map-pack/out"),
            File("scripts/map-pack/out")
        )
        val packDir = candidates.firstOrNull { File(it, "routing/routing.dfrg").isFile }
        assumeTrue("Pacote local real de Joinville não encontrado — teste de integração ignorado em CI.", packDir != null)

        val engine = DfrgRouteEngine(avoidTolls = false, avoidUnpaved = true)
        val tLoad0 = System.nanoTime()
        assertTrue("initialize(packDir) deve carregar routing/routing.dfrg real", engine.initialize(packDir!!))
        val loadMs = (System.nanoTime() - tLoad0) / 1_000_000.0

        val g = engine.graph!!
        assertTrue("Joinville deve ter > 30.000 nós (lido ${g.nodeCount})", g.nodeCount > 30_000)
        assertTrue("Joinville deve ter > 70.000 arestas (lido ${g.edgeCount})", g.edgeCount > 70_000)
        assertTrue("Joinville deve ter > 5.000 nomes de ruas (lido ${g.nameCount})", g.nameCount > 5_000)

        val terminalCentral = GeoPoint(-26.3044, -48.8464)
        val aeroporto = GeoPoint(-26.2244, -48.7975)
        val shoppingGarten = GeoPoint(-26.2547, -48.8531)
        val iririu = GeoPoint(-26.2695, -48.8219)
        val floresta = GeoPoint(-26.3360, -48.8470)

        // 1) Rota real Terminal Central -> Aeroporto + alternativas
        val tRoute0 = System.nanoTime()
        val resp = engine.computeRoute(
            RouteRequest(
                origin = terminalCentral,
                waypoints = listOf(RouteWaypoint(aeroporto, "Aeroporto de Joinville")),
                profile = RouteProfile.CAR_FASTEST,
                computeAlternatives = true
            )
        ) as RouteResponse.Success
        val routeMs = (System.nanoTime() - tRoute0) / 1_000_000.0

        val primary = resp.primary
        val km = primary.totalDistanceMeters / 1000.0
        val min = primary.totalDurationSeconds / 60.0
        assertTrue("Terminal -> Aeroporto deve ficar entre 9 km e 20 km (obtido $km km)", km in 9.0..20.0)
        assertTrue("Terminal -> Aeroporto deve ficar entre 8 min e 35 min (obtido $min min)", min in 8.0..35.0)
        assertTrue("Deve gerar geometria detalhada (> 40 pontos)", primary.geometry.size > 40)
        assertTrue("Deve gerar múltiplas instruções pt-BR (>= 4 passos)", primary.allSteps.size >= 4)

        // Valida integridade de índices de geometria e ausência de ponto final nas instruções TTS
        for (i in primary.allSteps.indices) {
            val st = primary.allSteps[i]
            assertEquals(i, st.index)
            assertTrue(st.geometryStartIndex in primary.geometry.indices)
            assertTrue(st.geometryEndIndex in st.geometryStartIndex..primary.geometry.lastIndex)
            assertFalse(st.instructionPtBr.endsWith("."))
        }

        // 2) Desvio de Lista Negra real colocando zona bloqueada no meio do trajeto principal
        val midPt = primary.geometry[primary.geometry.size / 2]
        val zone = AvoidZone(
            id = "bl_live_mid",
            label = "Trecho Bloqueado",
            center = midPt,
            radiusMeters = 800.0,
            hardAvoid = false
        )
        val tDetour0 = System.nanoTime()
        val detourResp = engine.computeRoute(
            RouteRequest(
                origin = terminalCentral,
                waypoints = listOf(RouteWaypoint(aeroporto, "Aeroporto de Joinville")),
                profile = RouteProfile.CAR_FASTEST,
                avoidZones = listOf(zone),
                computeAlternatives = false
            )
        ) as RouteResponse.Success
        val detourMs = (System.nanoTime() - tDetour0) / 1_000_000.0
        assertEquals(1, detourResp.primary.avoidedZonesCount)
        assertNull("Deve encontrar desvio limpo em Joinville fora da zona bloqueada", detourResp.primary.warningMessage)
        assertTrue("Geometria desviada não deve conter o centro bloqueado", detourResp.primary.geometry.none { zone.contains(it) })

        // 3) Matriz exata 5x5 em Joinville real + SmartRouteOptimizer
        val pts = listOf(terminalCentral, aeroporto, shoppingGarten, iririu, floresta)
        val tMat0 = System.nanoTime()
        val matrix = engine.computeCostMatrix(pts, RouteProfile.CAR_FASTEST, emptyList())
        val matMs = (System.nanoTime() - tMat0) / 1_000_000.0
        assertNotNull(matrix)
        assertEquals(5, matrix!!.size)
        for (i in 0 until 5) {
            assertEquals(0.0, matrix[i][i], 1e-6)
            for (j in 0 until 5) {
                if (i != j) {
                    assertTrue("Matriz real ($i->$j) inválida: ${matrix[i][j]}", matrix[i][j] in 60.0..3600.0)
                }
            }
        }

        println(
            "REAL JOINVILLE DFRG BENCHMARK: nodes=${g.nodeCount}, edges=${g.edgeCount}, geom=${g.geomPointCount}, names=${g.nameCount} | " +
                "load=${"%.1f".format(loadMs)}ms | route+alts=${"%.1f".format(routeMs)}ms (${"%.2f".format(km)} km, ${"%.1f".format(min)} min, alts=${resp.alternatives.size}, steps=${primary.allSteps.size}) | " +
                "blacklistDetour=${"%.1f".format(detourMs)}ms (${"%.2f".format(detourResp.primary.totalDistanceMeters / 1000.0)} km) | " +
                "matrix5x5=${"%.1f".format(matMs)}ms"
        )
        println("Sample pt-BR steps: " + primary.allSteps.take(6).joinToString(" -> ") { it.instructionPtBr })
        engine.close()
    }
}
