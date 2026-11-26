package com.drifly.condutor.map.pack

import com.drifly.condutor.map.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionCatalogTest {

    @Test
    fun parsesCatalogV1AndPicksSmallestAreaPackForJoinvilleOverSc() {
        val catalogJson = """
            {
              "version": 1,
              "updated_at": "2026-11-25T17:00:00Z",
              "packs": [
                {
                  "id": "sc",
                  "uf": "SC",
                  "name": "Santa Catarina",
                  "kind": "state",
                  "version": 1,
                  "bbox": [-53.84, -29.35, -48.32, -25.95],
                  "polygon": [[-53.84,-29.35],[-48.32,-29.35],[-48.32,-25.95],[-53.84,-25.95]],
                  "zip_url": "https://github.com/andrefonn/drifly-condutor/releases/download/map-packs-v1/sc-pack-v1.zip",
                  "size_bytes": 120000000,
                  "installed_size_bytes": 180000000,
                  "sha256": "aaaa",
                  "available": true
                },
                {
                  "id": "joinville",
                  "uf": "SC",
                  "name": "Joinville e Região (SC)",
                  "kind": "city",
                  "version": 1,
                  "bbox": [-49.18, -26.55, -48.58, -26.02],
                  "polygon": [[-49.18,-26.55],[-48.58,-26.55],[-48.58,-26.02],[-49.18,-26.02]],
                  "zip_url": "https://github.com/andrefonn/drifly-condutor/releases/download/map-packs-v1/joinville-pack-v1.zip",
                  "size_bytes": 28500483,
                  "installed_size_bytes": 41039714,
                  "sha256": "bbbb",
                  "available": true
                },
                {
                  "id": "pa",
                  "uf": "PA",
                  "name": "Pará",
                  "kind": "state",
                  "version": 1,
                  "bbox": [-58.90, -9.84, -46.06, 2.59],
                  "zip_url": "https://github.com/andrefonn/drifly-condutor/releases/download/map-packs-v1/pa-pack-v1.zip",
                  "size_bytes": 115000000,
                  "installed_size_bytes": 165000000,
                  "sha256": "cccc",
                  "available": true
                },
                {
                  "id": "am",
                  "uf": "AM",
                  "name": "Amazonas",
                  "kind": "state",
                  "version": 1,
                  "bbox": [-73.80, -9.82, -56.10, 2.25],
                  "available": false
                }
              ]
            }
        """.trimIndent()

        val snap = RegionCatalog.parseCatalogOrManifest(catalogJson)
        assertEquals(4, snap.packs.size)

        // Dentro de Joinville: cidade (menor área) DEVE vencer Santa Catarina (estado).
        val jvl = snap.findBestPackFor(-26.3044, -48.8487)
        assertNotNull(jvl)
        assertEquals("joinville", jvl!!.id)
        assertFalse(jvl.isLargeWifiRecommended)

        // Em Florianópolis (SC fora de Joinville): escolhe SC.
        val floripa = snap.findBestPackFor(-27.5954, -48.5480)
        assertEquals("sc", floripa?.id)
        assertTrue(floripa!!.isLargeWifiRecommended)

        // Em Belém (Pará): escolhe PA.
        val belem = snap.findBestPackFor(-1.4558, -48.5044)
        assertEquals("pa", belem?.id)
        assertTrue(belem!!.available)
        assertTrue(belem.isLargeWifiRecommended)

        // Em Manaus (Amazonas): retorna AM com available = false.
        val manaus = snap.findBestPackFor(-3.1190, -60.0217)
        assertEquals("am", manaus?.id)
        assertFalse(manaus!!.available)
    }

    @Test
    fun pointInPolygonHandlesConcaveShapeAccurately() {
        // Polígono em formato de L (côncavo): quadrante superior direito fica FORA.
        val lShape = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 2.0),
            GeoPoint(1.0, 2.0),
            GeoPoint(1.0, 1.0),
            GeoPoint(2.0, 1.0),
            GeoPoint(2.0, 0.0)
        )
        assertTrue(RegionPack.pointInPolygon(0.5, 0.5, lShape))
        assertTrue(RegionPack.pointInPolygon(1.5, 0.5, lShape))
        assertTrue(RegionPack.pointInPolygon(0.5, 1.5, lShape))
        assertFalse(RegionPack.pointInPolygon(1.5, 1.5, lShape))
    }

    @Test
    fun embeddedBrazilFallbackCoversAll27StatesPlusJoinville() {
        val fallback = RegionCatalog.embeddedBrazilFallback()
        assertEquals(28, fallback.packs.size)
        assertEquals("joinville", fallback.findBestPackFor(-26.3044, -48.8487)?.id)
        assertEquals("pa", fallback.findBestPackFor(-1.4558, -48.5044)?.id)
        assertEquals("sp", fallback.findBestPackFor(-23.5505, -46.6333)?.id)
        assertNull(fallback.findBestPackFor(40.7128, -74.0060))
    }
}
