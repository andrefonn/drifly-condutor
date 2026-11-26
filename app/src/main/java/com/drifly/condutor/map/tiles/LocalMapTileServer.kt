package com.drifly.condutor.map.tiles

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor HTTP embutido restrito ao loopback (`127.0.0.1`) para servir tiles PMTiles,
 * estilo MapLibre JSON e glifos PBF 100% offline com consumo mínimo de RAM (< 1 MB).
 *
 * Rotas expostas:
 * - `GET /health`
 * - `GET /style.json`
 * - `GET /tiles/{z}/{x}/{y}.mvt`
 * - `GET /glyphs/{fontstack}/{range}.pbf`
 */
class LocalMapTileServer(
    private val packDir: File,
    private val pmTilesFile: File,
    private val styleProvider: (baseUrl: String) -> String,
    private val assetGlyphProvider: ((fontstack: String, range: String) -> ByteArray?)? = null
) : Closeable {

    data class ServerHandle(
        val port: Int,
        val baseUrl: String,
        val styleUrl: String
    )

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workerPool: ExecutorService? = null
    private var reader: PmTilesReader? = null

    @Volatile
    private var handle: ServerHandle? = null

    /** Inicia o servidor em porta efêmera em `127.0.0.1` e devolve os endpoints ativos. */
    @Synchronized
    fun start(): ServerHandle {
        handle?.let { if (running.get()) return it }

        val pmReader = PmTilesReader(pmTilesFile)
        val loopback = InetAddress.getByName("127.0.0.1")
        val socket = ServerSocket(0, 32, loopback)
        val port = socket.localPort
        val baseUrl = "http://127.0.0.1:$port"
        val newHandle = ServerHandle(
            port = port,
            baseUrl = baseUrl,
            styleUrl = "$baseUrl/style.json"
        )

        val pool = Executors.newFixedThreadPool(WORKER_THREADS) { runnable ->
            Thread(runnable, "DriFlyTileHttp").apply { isDaemon = true }
        }

        reader = pmReader
        serverSocket = socket
        workerPool = pool
        handle = newHandle
        running.set(true)

        acceptThread = Thread({ acceptLoop(socket, pool) }, "DriFlyTileAccept").apply {
            isDaemon = true
            start()
        }

        return newHandle
    }

    fun currentHandle(): ServerHandle? = if (running.get()) handle else null

    fun pmTilesHeader(): PmTilesReader.Header? = reader?.header

    @Synchronized
    override fun close() {
        running.set(false)
        handle = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        workerPool?.shutdownNow()
        workerPool = null
        acceptThread = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (running.get() && !socket.isClosed) {
            try {
                val client = socket.accept()
                pool.execute { handleClient(client) }
            } catch (_: IOException) {
                if (!running.get()) break
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(sock.getInputStream(), 2048)
                val requestLine = readLineCrLf(input) ?: return
                // Consome cabeçalhos até a linha em branco (não precisamos manter em memória).
                var headerCount = 0
                while (headerCount < MAX_HEADERS) {
                    val line = readLineCrLf(input) ?: break
                    if (line.isEmpty()) break
                    headerCount++
                }

                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeEmptyResponse(sock.getOutputStream(), 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase()
                if (method != "GET" && method != "HEAD") {
                    writeEmptyResponse(sock.getOutputStream(), 405, "Method Not Allowed")
                    return
                }
                val rawPath = parts[1].substringBefore('?')
                val headOnly = method == "HEAD"
                routeRequest(rawPath, headOnly, sock.getOutputStream())
            } catch (_: Exception) {
                // Conexão abortada pelo cliente MapLibre (normal ao dar pan/zoom rápido).
            }
        }
    }

    private fun routeRequest(path: String, headOnly: Boolean, out: OutputStream) {
        when {
            path == "/health" -> {
                val body = """{"status":"ok","pack":"${escapeJson(packDir.name)}"}""".toByteArray(Charsets.UTF_8)
                writeBytesResponse(out, 200, "OK", "application/json; charset=utf-8", body, null, headOnly)
            }

            path == "/style.json" -> {
                val baseUrl = handle?.baseUrl ?: return writeEmptyResponse(out, 503, "Service Unavailable")
                val body = styleProvider(baseUrl).toByteArray(Charsets.UTF_8)
                writeBytesResponse(out, 200, "OK", "application/json; charset=utf-8", body, null, headOnly)
            }

            path.startsWith("/tiles/") -> serveTile(path, headOnly, out)

            path.startsWith("/glyphs/") -> serveGlyph(path, headOnly, out)

            else -> writeEmptyResponse(out, 404, "Not Found")
        }
    }

    private fun serveTile(path: String, headOnly: Boolean, out: OutputStream) {
        val currentReader = reader ?: return writeEmptyResponse(out, 503, "Service Unavailable")
        // Formato esperado: /tiles/{z}/{x}/{y}.mvt (ou .pbf)
        val segments = path.removePrefix("/tiles/").split('/')
        if (segments.size != 3) {
            writeEmptyResponse(out, 400, "Bad Request")
            return
        }
        val z = segments[0].toIntOrNull()
        val x = segments[1].toIntOrNull()
        val y = segments[2].substringBefore('.').toIntOrNull()
        if (z == null || x == null || y == null) {
            writeEmptyResponse(out, 400, "Bad Request")
            return
        }

        val tileBytes = currentReader.getTile(z, x, y)
        if (tileBytes == null || tileBytes.isEmpty()) {
            // 204 evita que o MapLibre registre erro de rede em áreas fora da cobertura.
            writeEmptyResponse(out, 204, "No Content")
            return
        }
        val encoding = if (currentReader.header.isTileGzipped) "gzip" else null
        writeBytesResponse(
            out = out,
            status = 200,
            reason = "OK",
            contentType = currentReader.header.contentType,
            body = tileBytes,
            contentEncoding = encoding,
            headOnly = headOnly
        )
    }

    private fun serveGlyph(path: String, headOnly: Boolean, out: OutputStream) {
        // Formato esperado: /glyphs/{fontstack}/{range}.pbf
        val rest = path.removePrefix("/glyphs/")
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.lastIndex) {
            writeEmptyResponse(out, 400, "Bad Request")
            return
        }
        val fontstack = try {
            URLDecoder.decode(rest.substring(0, slash), "UTF-8")
        } catch (_: Exception) {
            writeEmptyResponse(out, 400, "Bad Request")
            return
        }
        val rangeWithExt = rest.substring(slash + 1)
        val range = rangeWithExt.removeSuffix(".pbf")

        if (!isSafePathSegment(fontstack) || !SAFE_RANGE_REGEX.matches(range)) {
            writeEmptyResponse(out, 400, "Bad Request")
            return
        }

        // 1) Primeiro procura no pacote baixado em filesDir/map_packs/<pack>/glyphs/
        val glyphsRoot = File(packDir, "glyphs").canonicalFile
        val diskCandidate = File(File(glyphsRoot, fontstack), "$range.pbf").canonicalFile
        if (diskCandidate.path.startsWith(glyphsRoot.path + File.separator) && diskCandidate.isFile) {
            val bytes = diskCandidate.readBytes()
            writeBytesResponse(out, 200, "OK", "application/x-protobuf", bytes, null, headOnly)
            return
        }

        // 2) Fallback: glifos básicos embarcados nos assets do APK (0-255, 256-511 pt-BR).
        val assetBytes = assetGlyphProvider?.invoke(fontstack, range)
        if (assetBytes != null && assetBytes.isNotEmpty()) {
            writeBytesResponse(out, 200, "OK", "application/x-protobuf", assetBytes, null, headOnly)
            return
        }

        writeEmptyResponse(out, 204, "No Content")
    }

    private fun writeBytesResponse(
        out: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        contentEncoding: String?,
        headOnly: Boolean
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        if (contentEncoding != null) {
            sb.append("Content-Encoding: ").append(contentEncoding).append("\r\n")
        }
        sb.append("Cache-Control: public, max-age=86400\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n\r\n")

        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        if (!headOnly && body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
    }

    private fun writeEmptyResponse(out: OutputStream, status: Int, reason: String) {
        val resp = "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(resp.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun readLineCrLf(input: BufferedInputStream): String? {
        val sb = StringBuilder(128)
        while (sb.length < MAX_LINE_LENGTH) {
            val ch = input.read()
            if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) break
            if (ch != '\r'.code) sb.append(ch.toChar())
        }
        return sb.toString()
    }

    private fun isSafePathSegment(segment: String): Boolean {
        if (segment.isBlank() || segment.length > 120) return false
        if (segment.contains("..") || segment.contains('/') || segment.contains('\\') || segment.contains('\u0000')) {
            return false
        }
        return true
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val WORKER_THREADS = 4
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val MAX_HEADERS = 64
        private const val MAX_LINE_LENGTH = 2048
        private val SAFE_RANGE_REGEX = Regex("^[0-9]+-[0-9]+$")
    }
}
