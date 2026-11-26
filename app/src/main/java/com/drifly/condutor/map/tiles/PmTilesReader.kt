package com.drifly.condutor.map.tiles

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * Leitor PMTiles v3 de baixíssimo consumo de RAM.
 *
 * Lê diretamente do arquivo em disco usando [FileChannel.read] com offset absoluto
 * (thread-safe, sem mmap do arquivo inteiro e sem carregar o archive na heap).
 * Apenas o diretório raiz (~16–32 KB) e um cache LRU de 8 diretórios folha (~128 KB)
 * permanecem em memória.
 */
class PmTilesReader(val file: File) : Closeable {

    data class Header(
        val version: Int,
        val rootDirOffset: Long,
        val rootDirLength: Long,
        val jsonMetadataOffset: Long,
        val jsonMetadataLength: Long,
        val leafDirsOffset: Long,
        val leafDirsLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val numAddressedTiles: Long,
        val numTileEntries: Long,
        val numTileContents: Long,
        val clustered: Boolean,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
        val centerZoom: Int,
        val centerLon: Double,
        val centerLat: Double
    ) {
        val isTileGzipped: Boolean get() = tileCompression == COMPRESSION_GZIP

        val contentType: String
            get() = when (tileType) {
                TILE_TYPE_MVT -> "application/x-protobuf"
                TILE_TYPE_PNG -> "image/png"
                TILE_TYPE_JPEG -> "image/jpeg"
                TILE_TYPE_WEBP -> "image/webp"
                TILE_TYPE_AVIF -> "image/avif"
                else -> "application/octet-stream"
            }
    }

    /**
     * Diretório compacto em arrays primitivos para evitar overhead de objetos por entrada.
     */
    internal class Directory(
        val tileIds: LongArray,
        val offsets: LongArray,
        val lengths: IntArray,
        val runLengths: IntArray
    ) {
        val size: Int get() = tileIds.size
    }

    private val raf: RandomAccessFile = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel

    val header: Header
    private val rootDir: Directory

    private val leafCacheLock = Any()
    private val leafCache = object : LinkedHashMap<Long, Directory>(LEAF_CACHE_MAX_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Directory>?): Boolean {
            return size > LEAF_CACHE_MAX_ENTRIES
        }
    }

    @Volatile
    private var closed = false

    init {
        try {
            header = parseHeader(readExact(0L, HEADER_SIZE_BYTES))
            val rootBytes = readCompressedSection(header.rootDirOffset, header.rootDirLength, header.internalCompression)
            rootDir = deserializeDirectory(rootBytes)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * Retorna os bytes brutos do tile (z, x, y) conforme armazenados no PMTiles
     * (mantendo gzip quando `header.isTileGzipped == true` para passthrough HTTP zero-cópia),
     * ou `null` quando o tile não existe no arquivo.
     */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        if (closed) return null
        if (z < header.minZoom || z > header.maxZoom) return null
        val maxCoord = 1 shl z
        if (x < 0 || y < 0 || x >= maxCoord || y >= maxCoord) return null

        val tileId = zxyToTileId(z, x, y)
        var dir = rootDir

        // PMTiles v3 limita a profundidade a raiz -> folha (até 3 níveis por segurança).
        for (depth in 0 until MAX_DIR_DEPTH) {
            val idx = findEntryIndex(dir, tileId)
            if (idx < 0) return null

            val runLen = dir.runLengths[idx]
            val offset = dir.offsets[idx]
            val length = dir.lengths[idx]

            if (runLen > 0) {
                if (tileId < dir.tileIds[idx] + runLen.toLong()) {
                    if (length <= 0) return null
                    return readExact(header.tileDataOffset + offset, length)
                }
                return null
            }

            // runLength == 0 indica ponteiro para um diretório folha.
            dir = getOrLoadLeafDirectory(header.leafDirsOffset + offset, length)
        }
        return null
    }

    /** Lê o JSON de metadados do archive PMTiles, descomprimindo se necessário. */
    fun readMetadataJson(): String? {
        if (closed || header.jsonMetadataLength <= 0L) return null
        val bytes = readCompressedSection(
            header.jsonMetadataOffset,
            header.jsonMetadataLength,
            header.internalCompression
        )
        return String(bytes, Charsets.UTF_8)
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(leafCacheLock) {
            leafCache.clear()
        }
        try {
            channel.close()
        } catch (_: Exception) {
        }
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }

    private fun getOrLoadLeafDirectory(absoluteOffset: Long, length: Int): Directory {
        synchronized(leafCacheLock) {
            leafCache[absoluteOffset]?.let { return it }
        }
        val raw = readCompressedSection(absoluteOffset, length.toLong(), header.internalCompression)
        val parsed = deserializeDirectory(raw)
        synchronized(leafCacheLock) {
            leafCache[absoluteOffset] = parsed
        }
        return parsed
    }

    private fun readCompressedSection(offset: Long, length: Long, compression: Int): ByteArray {
        if (length < 0L || length > MAX_SECTION_BYTES) {
            throw IOException("Seção PMTiles com tamanho inválido: $length bytes")
        }
        val raw = readExact(offset, length.toInt())
        return when (compression) {
            COMPRESSION_NONE, COMPRESSION_UNKNOWN -> raw
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            else -> throw IOException("Compressão interna PMTiles não suportada: $compression")
        }
    }

    private fun readExact(offset: Long, length: Int): ByteArray {
        if (offset < 0L || length < 0) {
            throw IOException("Offset/tamanho inválido: offset=$offset len=$length")
        }
        val bytes = ByteArray(length)
        val buf = ByteBuffer.wrap(bytes)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) {
                throw IOException("EOF inesperado ao ler PMTiles em offset=$pos (esperado $length bytes)")
            }
            pos += n
        }
        return bytes
    }

    companion object {
        const val HEADER_SIZE_BYTES = 127
        const val LEAF_CACHE_MAX_ENTRIES = 8
        private const val MAX_DIR_DEPTH = 4
        private const val MAX_SECTION_BYTES = 16L * 1024L * 1024L

        const val COMPRESSION_UNKNOWN = 0
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2

        const val TILE_TYPE_UNKNOWN = 0
        const val TILE_TYPE_MVT = 1
        const val TILE_TYPE_PNG = 2
        const val TILE_TYPE_JPEG = 3
        const val TILE_TYPE_WEBP = 4
        const val TILE_TYPE_AVIF = 5

        /**
         * Converte coordenadas (z, x, y) em TileID PMTiles v3 usando curva de Hilbert.
         * Suporta zoom 0..26 (limite prático de mapas vetoriais é 0..22).
         */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..26) { "Zoom fora da faixa suportada (0..26): $z" }
            if (z == 0) return 0L

            val baseId = ((1L shl (2 * z)) - 1L) / 3L
            val n = 1L shl z
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = n ushr 1
            while (s > 0L) {
                val rx = if ((tx and s) != 0L) 1L else 0L
                val ry = if ((ty and s) != 0L) 1L else 0L
                d += s * s * ((3L * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = n - 1L - tx
                        ty = n - 1L - ty
                    }
                    val tmp = tx
                    tx = ty
                    ty = tmp
                }
                s = s ushr 1
            }
            return baseId + d
        }

        internal fun parseHeader(bytes: ByteArray): Header {
            if (bytes.size < HEADER_SIZE_BYTES) {
                throw IOException("Cabeçalho PMTiles truncado: ${bytes.size} bytes")
            }
            val magic = String(bytes, 0, 7, Charsets.US_ASCII)
            if (magic != "PMTiles") {
                throw IOException("Assinatura PMTiles inválida: '$magic'")
            }
            val version = bytes[7].toInt() and 0xFF
            if (version != 3) {
                throw IOException("Versão PMTiles não suportada: $version (esperado 3)")
            }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            bb.position(8)
            val rootDirOffset = bb.long
            val rootDirLength = bb.long
            val jsonMetadataOffset = bb.long
            val jsonMetadataLength = bb.long
            val leafDirsOffset = bb.long
            val leafDirsLength = bb.long
            val tileDataOffset = bb.long
            val tileDataLength = bb.long
            val numAddressedTiles = bb.long
            val numTileEntries = bb.long
            val numTileContents = bb.long
            val clustered = (bb.get().toInt() and 0xFF) == 1
            val internalCompression = bb.get().toInt() and 0xFF
            val tileCompression = bb.get().toInt() and 0xFF
            val tileType = bb.get().toInt() and 0xFF
            val minZoom = bb.get().toInt() and 0xFF
            val maxZoom = bb.get().toInt() and 0xFF
            val minLon = bb.int / 1e7
            val minLat = bb.int / 1e7
            val maxLon = bb.int / 1e7
            val maxLat = bb.int / 1e7
            val centerZoom = bb.get().toInt() and 0xFF
            val centerLon = bb.int / 1e7
            val centerLat = bb.int / 1e7

            return Header(
                version = version,
                rootDirOffset = rootDirOffset,
                rootDirLength = rootDirLength,
                jsonMetadataOffset = jsonMetadataOffset,
                jsonMetadataLength = jsonMetadataLength,
                leafDirsOffset = leafDirsOffset,
                leafDirsLength = leafDirsLength,
                tileDataOffset = tileDataOffset,
                tileDataLength = tileDataLength,
                numAddressedTiles = numAddressedTiles,
                numTileEntries = numTileEntries,
                numTileContents = numTileContents,
                clustered = clustered,
                internalCompression = internalCompression,
                tileCompression = tileCompression,
                tileType = tileType,
                minZoom = minZoom,
                maxZoom = maxZoom,
                minLon = minLon,
                minLat = minLat,
                maxLon = maxLon,
                maxLat = maxLat,
                centerZoom = centerZoom,
                centerLon = centerLon,
                centerLat = centerLat
            )
        }

        internal fun deserializeDirectory(data: ByteArray): Directory {
            val cursor = VarintCursor(data)
            val count = cursor.readVarint().toInt()
            if (count < 0 || count > 2_000_000) {
                throw IOException("Contagem de entradas PMTiles inválida: $count")
            }
            val tileIds = LongArray(count)
            val runLengths = IntArray(count)
            val lengths = IntArray(count)
            val offsets = LongArray(count)

            var lastId = 0L
            for (i in 0 until count) {
                lastId += cursor.readVarint()
                tileIds[i] = lastId
            }
            for (i in 0 until count) {
                runLengths[i] = cursor.readVarint().toInt()
            }
            for (i in 0 until count) {
                lengths[i] = cursor.readVarint().toInt()
            }
            for (i in 0 until count) {
                val rawOffset = cursor.readVarint()
                offsets[i] = if (rawOffset == 0L && i > 0) {
                    offsets[i - 1] + lengths[i - 1].toLong()
                } else {
                    rawOffset - 1L
                }
            }
            return Directory(tileIds, offsets, lengths, runLengths)
        }

        /**
         * Busca binária da maior entrada com `tileIds[i] <= tileId`.
         * Retorna -1 se o diretório estiver vazio ou se `tileId < tileIds[0]`.
         */
        internal fun findEntryIndex(dir: Directory, tileId: Long): Int {
            var low = 0
            var high = dir.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val midId = dir.tileIds[mid]
                when {
                    midId < tileId -> low = mid + 1
                    midId > tileId -> high = mid - 1
                    else -> return mid
                }
            }
            return high
        }
    }

    private class VarintCursor(private val data: ByteArray) {
        private var pos = 0

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Varint PMTiles corrompido (> 64 bits)")
            }
            throw IOException("Fim inesperado ao decodificar varint PMTiles")
        }
    }
}
