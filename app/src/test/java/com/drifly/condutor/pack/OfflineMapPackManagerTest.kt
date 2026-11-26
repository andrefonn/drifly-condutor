package com.drifly.condutor.map.pack

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfflineMapPackManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun detectsPreSideloadedPackWithoutManifest() {
        val root = tmp.newFolder("packs")
        val joinvilleDir = File(root, "joinville").apply { mkdirs() }
        File(joinvilleDir, "joinville.pmtiles").writeBytes(ByteArray(256) { 1 })
        File(joinvilleDir, "joinville_geocoder.db").writeBytes(ByteArray(128) { 2 })
        File(joinvilleDir, "routing").apply { mkdirs() }.let {
            File(it, "graph.bin").writeBytes(ByteArray(64) { 3 })
        }

        val manager = OfflineMapPackManager(root)
        val state = manager.inspectPack("joinville")
        assertTrue(state is PackState.Ready)
        val info = (state as PackState.Ready).info
        assertTrue(info.sideloaded)
        assertEquals("joinville.pmtiles", info.pmTilesFile.name)
        assertEquals("joinville_geocoder.db", info.geocoderDbFile?.name)
        assertTrue(info.routingDir?.isDirectory == true)
    }

    @Test
    fun installsValidZipVerifiesSha256AndPreservesOnMismatch() = runBlocking {
        val root = tmp.newFolder("packs")
        val manager = OfflineMapPackManager(root)

        val validZip = createZip(
            tmp.newFile("joinville_v1.zip"),
            mapOf(
                "manifest.json" to """{"displayName":"Joinville","version":"1.0.0"}""".toByteArray(),
                "joinville.pmtiles" to ByteArray(512) { 7 },
                "joinville_geocoder.db" to ByteArray(128) { 9 },
                "routing/edges.bin" to ByteArray(64) { 5 }
            )
        )
        val expectedSha = OfflineMapPackManager.sha256Hex(validZip)

        val installed = manager.installFromLocalZip("joinville", validZip, expectedSha)
        assertTrue(installed is PackState.Ready)
        val info = (installed as PackState.Ready).info
        assertFalse(info.sideloaded)
        assertEquals("1.0.0", info.version)

        // Tentativa com SHA-256 inválido deve falhar e manter o pacote anterior intacto!
        val badResult = manager.installFromLocalZip("joinville", validZip, "0".repeat(64))
        assertTrue(badResult is PackState.Error)
        val afterInspect = manager.inspectPack("joinville")
        assertTrue(afterInspect is PackState.Ready)
        assertEquals("1.0.0", (afterInspect as PackState.Ready).info.version)
    }

    @Test
    fun blocksZipSlipAttack() = runBlocking {
        val root = tmp.newFolder("packs")
        val manager = OfflineMapPackManager(root)
        val evilZip = createZip(
            tmp.newFile("evil.zip"),
            mapOf(
                "joinville.pmtiles" to ByteArray(64) { 1 },
                "../../escaped_pwn.txt" to "owned".toByteArray()
            )
        )

        val result = manager.installFromLocalZip("joinville", evilZip)
        assertTrue(result is PackState.Error)
        assertTrue((result as PackState.Error).message.contains("Zip Slip", ignoreCase = true))
        assertFalse(File(root.parentFile, "escaped_pwn.txt").exists())
    }

    @Test
    fun resumesInterruptedDownloadViaHttpRangeAndInstalls() = runBlocking {
        val root = tmp.newFolder("packs")
        val zipFile = createZip(
            tmp.newFile("remote_joinville.zip"),
            mapOf(
                "manifest.json" to """{"displayName":"Joinville","version":"2.0.0"}""".toByteArray(),
                "joinville.pmtiles" to ByteArray(2048) { (it % 251).toByte() }
            )
        )
        val zipBytes = zipFile.readBytes()
        val expectedSha = OfflineMapPackManager.sha256Hex(zipFile)

        // Pré-escreve metade do arquivo em .joinville.zip.part para forçar retomada HTTP 206.
        val half = zipBytes.size / 2
        File(root, ".joinville.zip.part").writeBytes(zipBytes.copyOfRange(0, half))

        var sawRangeHeader = false
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverThread = Thread {
            server.use { srv ->
                val client = srv.accept()
                client.use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    var rangeStart = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            sawRangeHeader = true
                            val m = Regex("bytes=(\\d+)-").find(line)
                            if (m != null) rangeStart = m.groupValues[1].toInt()
                        }
                    }
                    val slice = zipBytes.copyOfRange(rangeStart, zipBytes.size)
                    val header = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Length: ${slice.size}\r\n")
                        append("Content-Range: bytes $rangeStart-${zipBytes.size - 1}/${zipBytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    val out = sock.getOutputStream()
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    out.write(slice)
                    out.flush()
                }
            }
        }
        serverThread.start()

        val manager = OfflineMapPackManager(root)
        val state = manager.downloadAndInstall(
            packId = "joinville",
            zipUrl = "http://127.0.0.1:$port/joinville-pack.zip",
            expectedSha256 = expectedSha
        )
        serverThread.join(3000)

        assertTrue(sawRangeHeader)
        assertTrue(state is PackState.Ready)
        assertEquals("2.0.0", (state as PackState.Ready).info.version)
        assertFalse(File(root, ".joinville.zip.part").exists())
    }

    @Test
    fun parsesGitHubReleaseManifestArraySchema() {
        val manifestJson = """
            {
              "schema_version": 1,
              "packs": [
                {
                  "id": "joinville",
                  "name": "Joinville e Região (SC)",
                  "version": 1,
                  "url": "https://github.com/andrefonn/drifly-condutor/releases/download/map-packs-v1/joinville-pack-v1.zip",
                  "size_bytes": 28500483,
                  "sha256": "7d51173c936d770e3defb855003d5c1aa1f129aec2377decc42ea08d92964afb"
                }
              ]
            }
        """.trimIndent()

        val desc = OfflineMapPackManager.parseRemoteManifest(manifestJson, "joinville")
        assertEquals("joinville", desc.packId)
        assertEquals("Joinville e Região (SC)", desc.displayName)
        assertEquals("1", desc.version)
        assertEquals(28500483L, desc.expectedSizeBytes)
        assertEquals("7d51173c936d770e3defb855003d5c1aa1f129aec2377decc42ea08d92964afb", desc.sha256)
        assertTrue(desc.zipUrl.endsWith("/joinville-pack-v1.zip"))
    }

    @Test
    fun rejectsDownloadWhenFreeSpaceIsInsufficient() = runBlocking {
        val root = tmp.newFolder("packs_low_space")
        // Simula apenas 50 MB livres no aparelho
        val manager = OfflineMapPackManager(root, availableBytesProvider = { 50L * 1024L * 1024L })
        val bigPack = RegionPack(
            id = "pa",
            uf = "PA",
            name = "Pará",
            kind = "state",
            version = 1,
            bbox = GeoBBox(-58.90, -9.84, -46.06, 2.59),
            zipUrl = "https://github.com/andrefonn/drifly-condutor/releases/download/map-packs-v1/pa-pack-v1.zip",
            sizeBytes = 90L * 1024L * 1024L,
            installedSizeBytes = 140L * 1024L * 1024L,
            sha256 = "",
            available = true
        )

        val result = manager.downloadRegionPack(bigPack)
        assertTrue(result is PackState.Error)
        assertTrue((result as PackState.Error).message.contains("Espaço insuficiente", ignoreCase = true))
    }

    @Test
    fun listsMultipleInstalledPacksAndPicksSmallestCoveringPoint() {
        val root = tmp.newFolder("packs_multi")
        val jvlDir = File(root, "joinville").apply { mkdirs() }
        File(jvlDir, "joinville.pmtiles").writeBytes(ByteArray(128) { 1 })
        File(jvlDir, "pack_meta.json").writeText(
            """{"id":"joinville","name":"Joinville","kind":"city","bbox":[-49.18,-26.55,-48.58,-26.02]}"""
        )

        val scDir = File(root, "sc").apply { mkdirs() }
        File(scDir, "sc.pmtiles").writeBytes(ByteArray(128) { 2 })
        File(scDir, "pack_meta.json").writeText(
            """{"id":"sc","name":"Santa Catarina","kind":"state","bbox":[-53.84,-29.35,-48.32,-25.95]}"""
        )

        val manager = OfflineMapPackManager(root)
        val installed = manager.listInstalledPacks()
        assertEquals(2, installed.size)

        // Dentro de Joinville: cidade vence estado
        assertEquals("joinville", manager.findInstalledPackCovering(-26.3044, -48.8487)?.packId)
        // Em Florianópolis: estado SC cobre
        assertEquals("sc", manager.findInstalledPackCovering(-27.5954, -48.5480)?.packId)
    }

    private fun createZip(dest: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return dest
    }
}
