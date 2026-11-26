package com.drifly.condutor.map.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class PmTilesReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun hilbertZxyToTileIdMatchesPmTilesV3Spec() {
        assertEquals(0L, PmTilesReader.zxyToTileId(0, 0, 0))
        // z=1: base=1
        assertEquals(1L, PmTilesReader.zxyToTileId(1, 0, 0))
        assertEquals(2L, PmTilesReader.zxyToTileId(1, 0, 1))
        assertEquals(3L, PmTilesReader.zxyToTileId(1, 1, 1))
        assertEquals(4L, PmTilesReader.zxyToTileId(1, 1, 0))
        // z=2: base=5
        assertEquals(5L, PmTilesReader.zxyToTileId(2, 0, 0))
        assertEquals(6L, PmTilesReader.zxyToTileId(2, 1, 0))
        assertEquals(7L, PmTilesReader.zxyToTileId(2, 1, 1))
        assertEquals(8L, PmTilesReader.zxyToTileId(2, 0, 1))
        // z=2 last tile in quadrant 3: (3,0) -> 5 + 15 = 20
        assertEquals(20L, PmTilesReader.zxyToTileId(2, 3, 0))
    }

    @Test
    fun readsRootAndLeafTilesAndMetadataFromBinaryArchive() {
        val tileA = gzip("MVT_TILE_Z0".toByteArray())
        val tileB = gzip("MVT_TILE_Z1_RUN".toByteArray())
        val tileC = gzip("MVT_TILE_Z2_LEAF".toByteArray())
        val metadata = """{"name":"Joinville","format":"pbf"}"""

        val archiveFile = buildSyntheticPmTilesV3(
            file = tmp.newFile("joinville.pmtiles"),
            tileA = tileA,
            tileB = tileB,
            tileC = tileC,
            metadataJson = metadata
        )

        PmTilesReader(archiveFile).use { reader ->
            assertEquals(3, reader.header.version)
            assertEquals(0, reader.header.minZoom)
            assertEquals(14, reader.header.maxZoom)
            assertTrue(reader.header.isTileGzipped)
            assertEquals("application/x-protobuf", reader.header.contentType)
            assertEquals(metadata, reader.readMetadataJson())

            // Direto do diretório raiz (z=0, x=0, y=0)
            assertArrayEquals(tileA, reader.getTile(0, 0, 0))
            // Run-length=2 cobrindo tileId 1 (1/0/0) e tileId 2 (1/0/1)
            assertArrayEquals(tileB, reader.getTile(1, 0, 0))
            assertArrayEquals(tileB, reader.getTile(1, 0, 1))
            // Não existe (tileId 3 -> 1/1/1)
            assertNull(reader.getTile(1, 1, 1))
            // Diretório folha (tileId 7 -> 2/1/1)
            assertArrayEquals(tileC, reader.getTile(2, 1, 1))
            // Fora do zoom
            assertNull(reader.getTile(15, 0, 0))
        }
    }

    @Test
    fun localMapTileServerServesTilesStyleGlyphsAndRejectsTraversal() {
        val packDir = tmp.newFolder("joinville")
        val tileA = gzip("JOINVILLE_TILE_DATA".toByteArray())
        val archiveFile = buildSyntheticPmTilesV3(
            file = File(packDir, "joinville.pmtiles"),
            tileA = tileA,
            tileB = gzip("TILE_B".toByteArray()),
            tileC = gzip("TILE_C".toByteArray()),
            metadataJson = """{"name":"Joinville"}"""
        )

        val fakeGlyph = byteArrayOf(0x0A, 0x05, 0x4E, 0x6F, 0x74, 0x6F, 0x00)
        val server = LocalMapTileServer(
            packDir = packDir,
            pmTilesFile = archiveFile,
            styleProvider = { baseUrl -> """{"version":8,"sources":{"v":{"tiles":["$baseUrl/tiles/{z}/{x}/{y}.mvt"]}}}""" },
            assetGlyphProvider = { font, range -> if (font == "Noto Sans Regular" && range == "0-255") fakeGlyph else null }
        )

        server.use { srv ->
            val handle = srv.start()
            assertTrue(handle.baseUrl.startsWith("http://127.0.0.1:"))

            // /health
            val health = httpGet("${handle.baseUrl}/health")
            assertEquals(200, health.first)
            assertTrue(String(health.second).contains("\"ok\""))

            // /style.json
            val style = httpGet(handle.styleUrl)
            assertEquals(200, style.first)
            assertTrue(String(style.second).contains("${handle.baseUrl}/tiles/{z}/{x}/{y}.mvt"))

            // /tiles/0/0/0.mvt (presente + gzip)
            val tileConn = (URL("${handle.baseUrl}/tiles/0/0/0.mvt").openConnection() as HttpURLConnection)
            tileConn.setRequestProperty("Accept-Encoding", "identity")
            assertEquals(200, tileConn.responseCode)
            assertEquals("gzip", tileConn.getHeaderField("Content-Encoding"))
            assertArrayEquals(tileA, tileConn.inputStream.use { it.readBytes() })
            tileConn.disconnect()

            // /tiles/1/1/1.mvt (ausente -> 204)
            val missing = httpGet("${handle.baseUrl}/tiles/1/1/1.mvt")
            assertEquals(204, missing.first)

            // /glyphs/Noto%20Sans%20Regular/0-255.pbf -> fallback asset
            val glyph = httpGet("${handle.baseUrl}/glyphs/Noto%20Sans%20Regular/0-255.pbf")
            assertEquals(200, glyph.first)
            assertArrayEquals(fakeGlyph, glyph.second)

            // Path traversal -> 400
            val evil = httpGet("${handle.baseUrl}/glyphs/..%2F..%2Fetc/0-255.pbf")
            assertEquals(400, evil.first)
        }
    }

    private fun httpGet(url: String): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private data class DirEntry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    private fun buildSyntheticPmTilesV3(
        file: File,
        tileA: ByteArray,
        tileB: ByteArray,
        tileC: ByteArray,
        metadataJson: String
    ): File {
        val metadataBytes = gzip(metadataJson.toByteArray(Charsets.UTF_8))

        // Tile data layout: tileA @ 0, tileB @ tileA.size, tileC @ tileA.size + tileB.size
        val offA = 0L
        val offB = tileA.size.toLong()
        val offC = offB + tileB.size.toLong()
        val tileData = tileA + tileB + tileC

        // Leaf dir contém tileId=7 (2/1/1) -> tileC
        val leafDirBytes = gzip(encodeDirectory(listOf(DirEntry(7L, offC, tileC.size, 1))))

        // Root dir contém:
        // - tileId=0 (run=1) -> tileA
        // - tileId=1 (run=2) -> tileB (cobre tileId 1 e 2)
        // - tileId=5 (run=0) -> aponta para leafDirBytes no offset 0
        val rootDirBytes = gzip(
            encodeDirectory(
                listOf(
                    DirEntry(0L, offA, tileA.size, 1),
                    DirEntry(1L, offB, tileB.size, 2),
                    DirEntry(5L, 0L, leafDirBytes.size, 0)
                )
            )
        )

        val rootOffset = PmTilesReader.HEADER_SIZE_BYTES.toLong()
        val metaOffset = rootOffset + rootDirBytes.size
        val leafOffset = metaOffset + metadataBytes.size
        val dataOffset = leafOffset + leafDirBytes.size

        val header = ByteBuffer.allocate(PmTilesReader.HEADER_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PMTiles".toByteArray(Charsets.US_ASCII))
        header.put(3.toByte())
        header.putLong(rootOffset)
        header.putLong(rootDirBytes.size.toLong())
        header.putLong(metaOffset)
        header.putLong(metadataBytes.size.toLong())
        header.putLong(leafOffset)
        header.putLong(leafDirBytes.size.toLong())
        header.putLong(dataOffset)
        header.putLong(tileData.size.toLong())
        header.putLong(4L) // numAddressedTiles
        header.putLong(3L) // numTileEntries
        header.putLong(3L) // numTileContents
        header.put(1.toByte()) // clustered
        header.put(PmTilesReader.COMPRESSION_GZIP.toByte()) // internalCompression
        header.put(PmTilesReader.COMPRESSION_GZIP.toByte()) // tileCompression
        header.put(PmTilesReader.TILE_TYPE_MVT.toByte()) // tileType
        header.put(0.toByte()) // minZoom
        header.put(14.toByte()) // maxZoom
        header.putInt((-49.05 * 1e7).toInt())
        header.putInt((-26.45 * 1e7).toInt())
        header.putInt((-48.65 * 1e7).toInt())
        header.putInt((-26.15 * 1e7).toInt())
        header.put(12.toByte())
        header.putInt((-48.845 * 1e7).toInt())
        header.putInt((-26.307 * 1e7).toInt())

        file.outputStream().use { out ->
            out.write(header.array())
            out.write(rootDirBytes)
            out.write(metadataBytes)
            out.write(leafDirBytes)
            out.write(tileData)
        }
        assertNotNull(file)
        return file
    }

    private fun encodeDirectory(entries: List<DirEntry>): ByteArray {
        val baos = ByteArrayOutputStream()
        writeVarint(baos, entries.size.toLong())
        var lastId = 0L
        for (e in entries) {
            writeVarint(baos, e.tileId - lastId)
            lastId = e.tileId
        }
        for (e in entries) writeVarint(baos, e.runLength.toLong())
        for (e in entries) writeVarint(baos, e.length.toLong())
        for (i in entries.indices) {
            val e = entries[i]
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) {
                writeVarint(baos, 0L)
            } else {
                writeVarint(baos, e.offset + 1L)
            }
        }
        return baos.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(bytes) }
        return baos.toByteArray()
    }
}
