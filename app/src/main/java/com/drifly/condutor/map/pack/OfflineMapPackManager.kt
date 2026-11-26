package com.drifly.condutor.map.pack

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class PackInfo(
    val packId: String,
    val displayName: String,
    val version: String,
    val packDir: File,
    val pmTilesFile: File,
    val geocoderDbFile: File?,
    val routingDir: File?,
    val totalSizeBytes: Long,
    val sideloaded: Boolean,
    val bbox: GeoBBox? = null,
    val kind: String = "city"
) {
    fun contains(lat: Double, lng: Double, marginDeg: Double = 0.0, catalogPack: RegionPack? = null): Boolean {
        if (catalogPack != null) return catalogPack.contains(lat, lng, marginDeg)
        return bbox?.contains(lat, lng, marginDeg) == true
    }

    fun selectionArea(catalogPack: RegionPack? = null): Double =
        catalogPack?.selectionArea ?: bbox?.approxAreaDegSq ?: Double.MAX_VALUE
}

data class RemotePackDescriptor(
    val packId: String,
    val displayName: String,
    val version: String,
    val zipUrl: String,
    val sha256: String,
    val expectedSizeBytes: Long = 0L
)

data class StorageCheckResult(
    val hasEnoughSpace: Boolean,
    val requiredBytes: Long,
    val availableBytes: Long
)

sealed class PackState {
    data object NotInstalled : PackState()
    data class Downloading(
        val percent: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val packId: String = OfflineMapPackManager.PACK_JOINVILLE
    ) : PackState()
    data class Verifying(val packId: String = OfflineMapPackManager.PACK_JOINVILLE) : PackState()
    data class Extracting(
        val percent: Int,
        val packId: String = OfflineMapPackManager.PACK_JOINVILLE
    ) : PackState()
    data class Ready(val info: PackInfo) : PackState()
    data class Cancelled(val packId: String) : PackState()
    data class Error(val message: String, val packId: String = OfflineMapPackManager.PACK_JOINVILLE) : PackState()
}

/**
 * Gerenciador multi-pacote de mapas 100% offline do DriFly (cidades e estados do Brasil).
 *
 * Responsabilidades:
 * - Listar, inspecionar e selecionar entre múltiplos pacotes instalados (`joinville`, `pa`, `sc`, ...);
 * - Verificar espaço livre em disco via [StatFs] antes de iniciar qualquer download;
 * - Baixar pacotes de `andrefonn/drifly-condutor` (`map-packs-v1`) em streaming (64 KB), com retomada
 *   HTTP Range e cancelamento limpo a qualquer momento;
 * - Validar SHA-256 em streaming, proteger contra Zip Slip e realizar troca atômica de diretório.
 */
class OfflineMapPackManager(
    private val packsRootDir: File,
    private val availableBytesProvider: (() -> Long)? = null
) {

    constructor(context: Context) : this(File(context.filesDir, "map_packs"))

    private val _state = MutableStateFlow<PackState>(PackState.NotInstalled)
    val state: StateFlow<PackState> = _state.asStateFlow()

    fun getPackDir(packId: String = PACK_JOINVILLE): File = File(packsRootDir, sanitizePackId(packId))

    /** Espaço livre disponível no volume onde os pacotes são gravados. */
    fun availableStorageBytes(): Long {
        availableBytesProvider?.let { return it() }
        return try {
            packsRootDir.mkdirs()
            val target = if (packsRootDir.exists()) packsRootDir else packsRootDir.parentFile ?: File("/")
            StatFs(target.absolutePath).availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Verifica se há espaço livre para baixar o ZIP + extrair o pacote + margem de segurança (64 MB).
     */
    fun checkFreeSpaceFor(zipSizeBytes: Long, installedSizeBytes: Long = 0L): StorageCheckResult {
        val effectiveInstalled = if (installedSizeBytes > 0L) installedSizeBytes else (zipSizeBytes * 1.5).toLong()
        val required = zipSizeBytes + effectiveInstalled + STORAGE_SAFETY_MARGIN_BYTES
        val available = availableStorageBytes()
        return StorageCheckResult(
            hasEnoughSpace = available >= required,
            requiredBytes = required,
            availableBytes = available
        )
    }

    /** Lista todos os pacotes offline válidos instalados no aparelho. */
    fun listInstalledPacks(): List<PackInfo> {
        if (!packsRootDir.isDirectory) return emptyList()
        val dirs = packsRootDir.listFiles() ?: return emptyList()
        val result = mutableListOf<PackInfo>()
        for (dir in dirs) {
            if (!dir.isDirectory || dir.name.startsWith(".")) continue
            inspectPackDirectory(dir, dir.name)?.let { result.add(it) }
        }
        return result.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    /**
     * Encontra o pacote instalado de MENOR área que cobre `(lat, lng)` (cidade vence estado).
     */
    fun findInstalledPackCovering(
        lat: Double,
        lng: Double,
        catalog: RegionCatalogSnapshot? = null,
        marginDeg: Double = 0.0
    ): PackInfo? {
        val installed = listInstalledPacks()
        if (installed.isEmpty()) return null
        return installed
            .filter { info -> info.contains(lat, lng, marginDeg, catalog?.findById(info.packId)) }
            .minByOrNull { info -> info.selectionArea(catalog?.findById(info.packId)) }
    }

    /**
     * Inspeciona o disco para verificar se o pacote [packId] já está pronto (baixado ou sideloaded).
     */
    fun inspectPack(packId: String = PACK_JOINVILLE): PackState {
        val dir = getPackDir(packId)
        val info = inspectPackDirectory(dir, packId)
        val next = if (info != null) PackState.Ready(info) else PackState.NotInstalled
        _state.value = next
        return next
    }

    private fun inspectPackDirectory(dir: File, packId: String): PackInfo? {
        if (!dir.isDirectory) return null
        val pmTiles = findPmTilesFile(dir, packId) ?: return null
        if (pmTiles.length() <= 0L) return null

        val geocoderDb = findGeocoderDb(dir, packId)
        val routingDir = File(dir, "routing").takeIf { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }
        val metaFile = File(dir, PACK_META_FILENAME).takeIf { it.isFile }
            ?: File(dir, MANIFEST_FILENAME).takeIf { it.isFile }
        val meta = readManifestMeta(metaFile, packId)

        return PackInfo(
            packId = packId,
            displayName = meta.displayName,
            version = meta.version,
            packDir = dir,
            pmTilesFile = pmTiles,
            geocoderDbFile = geocoderDb,
            routingDir = routingDir,
            totalSizeBytes = computeDirSize(dir),
            sideloaded = metaFile == null,
            bbox = meta.bbox,
            kind = meta.kind
        )
    }

    /**
     * Consulta o `manifest.json` remoto na GitHub Release para descobrir URL e SHA-256 oficiais.
     */
    suspend fun fetchRemoteManifest(
        manifestUrl: String = DEFAULT_MANIFEST_URL,
        packId: String = PACK_JOINVILLE
    ): RemotePackDescriptor = withContext(Dispatchers.IO) {
        val conn = openFollowingRedirects(manifestUrl, rangeStart = null)
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("Não foi possível consultar o manifesto do mapa (HTTP ${conn.responseCode}).")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRemoteManifest(text, packId)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Baixa um [RegionPack] do catálogo após validar espaço livre em disco via [StatFs].
     */
    suspend fun downloadRegionPack(pack: RegionPack): PackState {
        if (pack.sizeBytes > 0L) {
            val space = checkFreeSpaceFor(pack.sizeBytes, pack.installedSizeBytes)
            if (!space.hasEnoughSpace) {
                val needMb = (space.requiredBytes / (1024.0 * 1024.0)).toInt()
                val freeMb = (space.availableBytes / (1024.0 * 1024.0)).toInt()
                val err = PackState.Error(
                    message = "Espaço insuficiente: precisa de ~${needMb} MB livres (disponível: ${freeMb} MB).",
                    packId = pack.id
                )
                _state.value = err
                return err
            }
        }
        return downloadAndInstall(
            packId = pack.id,
            zipUrl = pack.zipUrl,
            expectedSha256 = pack.sha256
        )
    }

    /**
     * Baixa, valida SHA-256 e instala o pacote offline de forma atômica.
     * Suporta cancelamento cooperativo da coroutine sem corromper pacotes já instalados.
     */
    suspend fun downloadAndInstall(
        packId: String = PACK_JOINVILLE,
        zipUrl: String = DEFAULT_JOINVILLE_ZIP_URL,
        expectedSha256: String = ""
    ): PackState = withContext(Dispatchers.IO) {
        val safeId = sanitizePackId(packId)
        packsRootDir.mkdirs()
        val partFile = File(packsRootDir, ".$safeId.zip.part")

        try {
            downloadWithResume(safeId, zipUrl, partFile)
            val result = installFromLocalZip(safeId, partFile, expectedSha256)
            partFile.delete()
            result
        } catch (ce: CancellationException) {
            _state.value = PackState.Cancelled(safeId)
            throw ce
        } catch (e: Exception) {
            val err = PackState.Error(e.message ?: "Erro ao baixar pacote offline.", safeId)
            _state.value = err
            err
        }
    }

    /**
     * Valida SHA-256 (se informado), extrai com proteção Zip Slip em diretório staging
     * e faz troca atômica para `packsRootDir/<packId>`.
     */
    suspend fun installFromLocalZip(
        packId: String = PACK_JOINVILLE,
        zipFile: File,
        expectedSha256: String = ""
    ): PackState = withContext(Dispatchers.IO) {
        val safeId = sanitizePackId(packId)
        packsRootDir.mkdirs()
        val stagingDir = File(packsRootDir, ".${safeId}_staging")
        val backupDir = File(packsRootDir, ".${safeId}_old")
        val targetDir = File(packsRootDir, safeId)

        try {
            if (!zipFile.isFile || zipFile.length() == 0L) {
                throw IOException("Arquivo do pacote offline ausente ou vazio.")
            }

            if (expectedSha256.isNotBlank()) {
                _state.value = PackState.Verifying(safeId)
                coroutineContext.ensureActive()
                val actual = sha256Hex(zipFile)
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    throw SecurityException(
                        "Integridade do pacote inválida (SHA-256 esperado=${expectedSha256.take(12)}..., obtido=${actual.take(12)}...)."
                    )
                }
            }

            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            _state.value = PackState.Extracting(0, safeId)
            extractZipSafely(safeId, zipFile, stagingDir)

            val effectiveStaging = unwrapSingleNestedDirIfNeeded(stagingDir, safeId)

            if (findPmTilesFile(effectiveStaging, safeId) == null) {
                throw IOException("Pacote inválido: nenhum arquivo .pmtiles encontrado.")
            }

            backupDir.deleteRecursively()
            val hadPrevious = targetDir.exists()
            if (hadPrevious && !targetDir.renameTo(backupDir)) {
                throw IOException("Não foi possível preparar a substituição do pacote anterior.")
            }

            if (!effectiveStaging.renameTo(targetDir)) {
                if (hadPrevious) backupDir.renameTo(targetDir)
                throw IOException("Falha na troca atômica do diretório do pacote.")
            }

            backupDir.deleteRecursively()
            stagingDir.deleteRecursively()
            inspectPack(safeId)
        } catch (ce: CancellationException) {
            stagingDir.deleteRecursively()
            _state.value = PackState.Cancelled(safeId)
            throw ce
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            val err = PackState.Error(e.message ?: "Erro ao instalar pacote offline.", safeId)
            _state.value = err
            err
        }
    }

    /** Remove o pacote offline do disco para liberar espaço. */
    fun deletePack(packId: String = PACK_JOINVILLE): Boolean {
        val safeId = sanitizePackId(packId)
        val ok = File(packsRootDir, safeId).deleteRecursively()
        File(packsRootDir, ".$safeId.zip.part").delete()
        File(packsRootDir, ".${safeId}_staging").deleteRecursively()
        val remaining = listInstalledPacks()
        _state.value = remaining.firstOrNull()?.let { PackState.Ready(it) } ?: PackState.NotInstalled
        return ok
    }

    private suspend fun downloadWithResume(packId: String, zipUrl: String, partFile: File) {
        val existingBytes = if (partFile.isFile) partFile.length() else 0L
        val conn = openFollowingRedirects(zipUrl, rangeStart = existingBytes.takeIf { it > 0L })
        try {
            val code = conn.responseCode
            val append: Boolean
            val bytesStart: Long
            val totalBytes: Long

            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    append = true
                    bytesStart = existingBytes
                    val contentLen = conn.contentLengthLong
                    totalBytes = if (contentLen > 0L) existingBytes + contentLen else -1L
                }
                HttpURLConnection.HTTP_OK -> {
                    append = false
                    bytesStart = 0L
                    totalBytes = conn.contentLengthLong
                }
                416 -> {
                    conn.disconnect()
                    partFile.delete()
                    downloadWithResume(packId, zipUrl, partFile)
                    return
                }
                else -> throw IOException("Falha ao baixar pacote offline (HTTP $code).")
            }

            var downloaded = bytesStart
            var lastPercent = -1
            val buffer = ByteArray(STREAM_BUFFER_BYTES)

            BufferedInputStream(conn.inputStream, STREAM_BUFFER_BYTES).use { input ->
                FileOutputStream(partFile, append).use { output ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val pct = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (pct != lastPercent) {
                            lastPercent = pct
                            _state.value = PackState.Downloading(pct, downloaded, totalBytes, packId)
                        }
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowingRedirects(urlString: String, rangeStart: Long?): HttpURLConnection {
        var currentUrl = urlString
        for (hop in 0 until MAX_REDIRECTS) {
            val url = URL(currentUrl)
            if (url.protocol != "https" && url.host != "127.0.0.1" && url.host != "localhost") {
                throw SecurityException("Apenas downloads HTTPS são permitidos: ${url.protocol}")
            }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "DriFly-Android-OfflineMap/1.0")
                if (rangeStart != null && rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirecionamento HTTP sem cabeçalho Location.")
                }
                currentUrl = URL(url, location).toString()
                continue
            }
            return conn
        }
        throw IOException("Excesso de redirecionamentos ao baixar pacote offline.")
    }

    private suspend fun extractZipSafely(packId: String, zipFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalPath + File.separator
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        val zipSize = zipFile.length().coerceAtLeast(1L)
        var bytesExtracted = 0L

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile), STREAM_BUFFER_BYTES)).use { zis ->
            var entry = zis.nextEntry
            var entryCount = 0
            while (entry != null) {
                coroutineContext.ensureActive()
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw SecurityException("Pacote ZIP excede limite seguro de arquivos.")
                }
                val outFile = File(destDir, entry.name)
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest)) {
                    throw SecurityException("Zip Slip detectado na entrada: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = zis.read(buffer)
                            if (n < 0) break
                            fos.write(buffer, 0, n)
                            bytesExtracted += n
                        }
                    }
                }
                val pct = ((bytesExtracted * 100L) / (zipSize * 2L)).toInt().coerceIn(5, 99)
                _state.value = PackState.Extracting(pct, packId)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        _state.value = PackState.Extracting(100, packId)
    }

    private fun unwrapSingleNestedDirIfNeeded(stagingDir: File, packId: String): File {
        if (findPmTilesFile(stagingDir, packId) != null) return stagingDir
        val children = stagingDir.listFiles() ?: return stagingDir
        if (children.size == 1 && children[0].isDirectory) {
            return children[0]
        }
        return stagingDir
    }

    private fun findPmTilesFile(dir: File, packId: String): File? {
        val preferred = File(dir, "$packId.pmtiles")
        if (preferred.isFile && preferred.length() > 0L) return preferred
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".pmtiles", ignoreCase = true) && it.length() > 0L }
    }

    private fun findGeocoderDb(dir: File, packId: String): File? {
        val preferred = File(dir, "${packId}_geocoder.db")
        if (preferred.isFile && preferred.length() > 0L) return preferred
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".db", ignoreCase = true) && it.length() > 0L }
    }

    private data class ParsedPackMeta(
        val displayName: String,
        val version: String,
        val bbox: GeoBBox?,
        val kind: String
    )

    private fun readManifestMeta(manifestFile: File?, packId: String): ParsedPackMeta {
        val fallbackName = packId.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val fallbackBbox = if (packId.equals(PACK_JOINVILLE, ignoreCase = true)) {
            RegionCatalog.JOINVILLE_FALLBACK_BBOX
        } else {
            null
        }
        val fallbackKind = if (packId.equals(PACK_JOINVILLE, ignoreCase = true)) "city" else "state"

        if (manifestFile == null || !manifestFile.isFile) {
            return ParsedPackMeta(fallbackName, "sideload", fallbackBbox, fallbackKind)
        }
        return try {
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val name = json.optString("displayName")
                .ifBlank { json.optString("name") }
                .ifBlank { fallbackName }
            val version = json.opt("version")?.toString()?.takeIf { it.isNotBlank() } ?: "1"
            val bbox = GeoBBox.fromJsonArray(json.optJSONArray("bbox")) ?: fallbackBbox
            val kind = json.optString("kind").ifBlank { fallbackKind }
            ParsedPackMeta(name, version, bbox, kind)
        } catch (_: Exception) {
            ParsedPackMeta(fallbackName, "local", fallbackBbox, fallbackKind)
        }
    }

    private fun computeDirSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    private fun sanitizePackId(packId: String): String {
        val clean = packId.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "")
        require(clean.isNotEmpty()) { "packId inválido: '$packId'" }
        return clean
    }

    companion object {
        const val PACK_JOINVILLE = "joinville"
        const val PACK_META_FILENAME = "pack_meta.json"
        const val MANIFEST_FILENAME = "manifest.json"
        const val GITHUB_RELEASES_BASE = "https://github.com/andrefonn/drifly-condutor/releases/download/map-packs-v1"
        const val DEFAULT_MANIFEST_URL = "$GITHUB_RELEASES_BASE/manifest.json"
        const val DEFAULT_JOINVILLE_ZIP_URL = "$GITHUB_RELEASES_BASE/joinville-pack-v1.zip"

        /** Margem mínima adicional exigida além de zip + pacote extraído (64 MB). */
        const val STORAGE_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L

        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_ZIP_ENTRIES = 5_000

        /** Calcula SHA-256 em streaming usando buffer de 64 KB (zero alocação proporcional ao arquivo). */
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            FileInputStream(file).use { fis ->
                while (true) {
                    val read = fis.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun parseRemoteManifest(jsonText: String, packId: String): RemotePackDescriptor {
            val root = JSONObject(jsonText)
            val packObj: JSONObject = when {
                root.optJSONArray("packs") != null -> {
                    val arr = root.getJSONArray("packs")
                    var found: JSONObject? = null
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (item.optString("id").equals(packId, ignoreCase = true)) {
                            found = item
                            break
                        }
                    }
                    found ?: throw IOException("Pacote '$packId' não encontrado no manifesto remoto.")
                }
                root.optJSONObject("packs") != null -> {
                    root.getJSONObject("packs").optJSONObject(packId)
                        ?: throw IOException("Pacote '$packId' não encontrado no manifesto remoto.")
                }
                else -> root
            }
            val zipUrl = packObj.optString("zipUrl")
                .ifBlank { packObj.optString("zip_url") }
                .ifBlank { packObj.optString("url") }
                .ifBlank { "$GITHUB_RELEASES_BASE/${packId}-pack-v1.zip" }
            val displayName = packObj.optString("displayName")
                .ifBlank { packObj.optString("name") }
                .ifBlank { packId }
            val version = packObj.opt("version")?.toString()?.takeIf { it.isNotBlank() } ?: "1"
            val expectedSize = packObj.optLong("size_bytes", 0L).takeIf { it > 0L }
                ?: packObj.optLong("sizeBytes", 0L)
            return RemotePackDescriptor(
                packId = packId,
                displayName = displayName,
                version = version,
                zipUrl = zipUrl,
                sha256 = packObj.optString("sha256", ""),
                expectedSizeBytes = expectedSize
            )
        }
    }
}
