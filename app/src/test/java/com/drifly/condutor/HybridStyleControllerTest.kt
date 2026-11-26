package com.drifly.condutor.map

import com.drifly.condutor.EstiloMapa
import com.drifly.condutor.map.pack.GeoBBox
import com.drifly.condutor.map.pack.PackInfo
import com.drifly.condutor.map.pack.RegionCatalog
import com.drifly.condutor.map.pack.RegionCatalogSnapshot
import com.drifly.condutor.map.pack.RegionPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HybridStyleControllerTest {

    private fun fakeInstalledJoinville(): PackInfo = PackInfo(
        packId = "joinville",
        displayName = "Joinville e Região (SC)",
        version = "1",
        packDir = File("/tmp/joinville"),
        pmTilesFile = File("/tmp/joinville/joinville.pmtiles"),
        geocoderDbFile = null,
        routingDir = null,
        totalSizeBytes = 28_500_000L,
        sideloaded = false,
        bbox = RegionCatalog.JOINVILLE_FALLBACK_BBOX,
        kind = "city"
    )

    private fun sampleCatalog(): RegionCatalogSnapshot = RegionCatalogSnapshot(
        version = 1,
        updatedAt = "2026-11-25T17:00:00Z",
        fromCache = false,
        packs = listOf(
            RegionPack(
                id = "joinville",
                uf = "SC",
                name = "Joinville e Região (SC)",
                kind = "city",
                version = 1,
                bbox = RegionCatalog.JOINVILLE_FALLBACK_BBOX,
                zipUrl = "https://example.com/joinville-pack-v1.zip",
                sizeBytes = 28_500_483L,
                installedSizeBytes = 41_039_714L,
                sha256 = "abc",
                available = true
            ),
            RegionPack(
                id = "pa",
                uf = "PA",
                name = "Pará",
                kind = "state",
                version = 1,
                bbox = GeoBBox(-58.90, -9.84, -46.06, 2.59),
                zipUrl = "https://example.com/pa-pack-v1.zip",
                sizeBytes = 85_000_000L,
                installedSizeBytes = 120_000_000L,
                sha256 = "def",
                available = true
            )
        )
    )

    @Test
    fun selectsOfflinePackInsideJoinvilleAndOpenFreeMapInPara() {
        val ctrl = HybridStyleController(borderHysteresisDeg = 0.02, minSwitchIntervalMs = 1000L)
        val installed = listOf(fakeInstalledJoinville())
        val catalog = sampleCatalog()

        // 1) Em Joinville -> OfflinePack
        val (m1, changed1) = ctrl.evaluate(-26.3044, -48.8487, installed, catalog, isOnline = true, nowMs = 1_000L)
        assertTrue(changed1)
        assertTrue(m1 is MapVisualMode.OfflinePack)
        assertEquals("joinville", (m1 as MapVisualMode.OfflinePack).pack.packId)

        // 2) Em Belém (Pará) com rede após debounce -> OnlineOpenFreeMap sugerindo "pa"
        val (m2, changed2) = ctrl.evaluate(-1.4558, -48.5044, installed, catalog, isOnline = true, nowMs = 3_000L)
        assertTrue(changed2)
        assertTrue(m2 is MapVisualMode.OnlineOpenFreeMap)
        val online = m2 as MapVisualMode.OnlineOpenFreeMap
        assertEquals(EstiloMapa.OPENFREEMAP_STYLE_URL, online.styleUrl)
        assertEquals("pa", online.suggestedPack?.id)

        // 3) Em Belém sem rede -> OfflineUnavailable sugerindo "pa"
        val (m3, changed3) = ctrl.evaluate(-1.4558, -48.5044, installed, catalog, isOnline = false, nowMs = 5_000L)
        assertTrue(changed3)
        assertTrue(m3 is MapVisualMode.OfflineUnavailable)
        assertEquals("pa", (m3 as MapVisualMode.OfflineUnavailable).suggestedPack?.id)
    }

    @Test
    fun appliesBorderHysteresisAndTemporalDebounceToPreventFlicker() {
        val ctrl = HybridStyleController(borderHysteresisDeg = 0.02, minSwitchIntervalMs = 1_200L)
        val installed = listOf(fakeInstalledJoinville())
        val catalog = sampleCatalog()

        // Entra em Joinville
        val (m1, c1) = ctrl.evaluate(-26.3044, -48.8487, installed, catalog, isOnline = true, nowMs = 1_000L)
        assertTrue(c1 && m1 is MapVisualMode.OfflinePack)

        // 0.01° fora da borda norte (-26.02 -> -26.01): dentro da histerese (0.02°) -> NÃO troca!
        val (m2, c2) = ctrl.evaluate(-26.01, -48.8487, installed, catalog, isOnline = true, nowMs = 5_000L)
        assertFalse(c2)
        assertTrue(m2 is MapVisualMode.OfflinePack)

        // Longe (Pará) mas apenas 200ms após troca anterior -> debounce impede flicker!
        ctrl.reset()
        ctrl.evaluate(-26.3044, -48.8487, installed, catalog, isOnline = true, nowMs = 10_000L)
        val (m3, c3) = ctrl.evaluate(-1.4558, -48.5044, installed, catalog, isOnline = true, nowMs = 10_200L)
        assertFalse(c3)
        assertTrue(m3 is MapVisualMode.OfflinePack)

        // Com forceImmediate = true -> troca imediatamente!
        val (m4, c4) = ctrl.evaluate(
            -1.4558, -48.5044, installed, catalog, isOnline = true, nowMs = 10_200L, forceImmediate = true
        )
        assertTrue(c4)
        assertTrue(m4 is MapVisualMode.OnlineOpenFreeMap)
    }
}
