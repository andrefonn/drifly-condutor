package com.drifly.condutor.map.pack

import com.drifly.condutor.map.routing.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/** Retângulo geográfico `[west, south, east, north]` em graus decimais. */
data class GeoBBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    fun contains(lat: Double, lng: Double, marginDeg: Double = 0.0): Boolean =
        lat >= (south - marginDeg) &&
            lat <= (north + marginDeg) &&
            lng >= (west - marginDeg) &&
            lng <= (east + marginDeg)

    /** Área aproximada em graus² para desempate (cidade sempre vence estado). */
    val approxAreaDegSq: Double
        get() = abs((east - west) * (north - south)).coerceAtLeast(1e-9)

    companion object {
        fun fromJsonArray(arr: JSONArray?): GeoBBox? {
            if (arr == null || arr.length() < 4) return null
            val w = arr.optDouble(0, Double.NaN)
            val s = arr.optDouble(1, Double.NaN)
            val e = arr.optDouble(2, Double.NaN)
            val n = arr.optDouble(3, Double.NaN)
            if (w.isNaN() || s.isNaN() || e.isNaN() || n.isNaN()) return null
            return GeoBBox(west = w, south = s, east = e, north = n)
        }
    }
}

/** Entrada de pacote regional/estadual/municipal no catálogo remoto `catalog.json`. */
data class RegionPack(
    val id: String,
    val uf: String,
    val name: String,
    val kind: String, // "city" | "state"
    val version: Int,
    val bbox: GeoBBox,
    val polygon: List<GeoPoint> = emptyList(),
    val zipUrl: String,
    val sizeBytes: Long,
    val installedSizeBytes: Long,
    val sha256: String,
    val routingNodes: Int = 0,
    val minZoom: Int = 0,
    val maxZoom: Int = 15,
    val available: Boolean = zipUrl.isNotBlank()
) {
    val isLargeWifiRecommended: Boolean
        get() = sizeBytes > WIFI_ADVICE_THRESHOLD_BYTES

    val sizeMb: Double
        get() = sizeBytes / (1024.0 * 1024.0)

    /**
     * Testa se `(lat, lng)` está dentro deste pacote.
     * Rejeita em O(1) via bbox e, quando há polígono simplificado, aplica ray-casting exato.
     */
    fun contains(lat: Double, lng: Double, marginDeg: Double = 0.0): Boolean {
        if (!bbox.contains(lat, lng, marginDeg)) return false
        if (polygon.size < 3) return true
        return if (marginDeg > 0.0) {
            pointInPolygon(lat, lng, polygon) || isWithinBorderMargin(lat, lng, polygon, marginDeg)
        } else {
            pointInPolygon(lat, lng, polygon)
        }
    }

    /** Área efetiva usada para priorizar o pacote mais específico (menor área). */
    val selectionArea: Double
        get() = if (polygon.size >= 3) {
            polygonAreaDegSq(polygon).coerceAtLeast(1e-9)
        } else {
            bbox.approxAreaDegSq
        }

    companion object {
        const val WIFI_ADVICE_THRESHOLD_BYTES = 100L * 1024L * 1024L

        /** Ray-casting 2D padrão (par/ímpar) em coordenadas `(lng, lat)`. */
        fun pointInPolygon(lat: Double, lng: Double, poly: List<GeoPoint>): Boolean {
            if (poly.size < 3) return false
            var inside = false
            var j = poly.lastIndex
            for (i in poly.indices) {
                val yi = poly[i].lat
                val xi = poly[i].lng
                val yj = poly[j].lat
                val xj = poly[j].lng
                val intersects = ((yi > lat) != (yj > lat)) &&
                    (lng < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { abs(it) > 1e-12 } ?: 1e-12) + xi)
                if (intersects) inside = !inside
                j = i
            }
            return inside
        }

        private fun isWithinBorderMargin(
            lat: Double,
            lng: Double,
            poly: List<GeoPoint>,
            marginDeg: Double
        ): Boolean {
            val mSq = marginDeg * marginDeg
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                val dx = b.lng - a.lng
                val dy = b.lat - a.lat
                val lenSq = dx * dx + dy * dy
                val t = if (lenSq <= 1e-12) 0.0 else (((lng - a.lng) * dx + (lat - a.lat) * dy) / lenSq).coerceIn(0.0, 1.0)
                val px = a.lng + t * dx
                val py = a.lat + t * dy
                val dSq = (lng - px) * (lng - px) + (lat - py) * (lat - py)
                if (dSq <= mSq) return true
            }
            return false
        }

        internal fun polygonAreaDegSq(poly: List<GeoPoint>): Double {
            if (poly.size < 3) return 0.0
            var sum = 0.0
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                sum += (a.lng * b.lat) - (b.lng * a.lat)
            }
            return abs(sum) * 0.5
        }
    }
}

data class RegionCatalogSnapshot(
    val version: Int,
    val updatedAt: String,
    val packs: List<RegionPack>,
    val fromCache: Boolean
) {
    /**
     * Escolhe o pacote de MENOR área cujo polígono/bbox contém `(lat, lng)`.
     * Ex.: dentro de Joinville, `joinville` (`kind="city"`) vence `sc` (`kind="state"`).
     */
    fun findBestPackFor(lat: Double, lng: Double, marginDeg: Double = 0.0): RegionPack? =
        packs.filter { it.contains(lat, lng, marginDeg) }
            .minByOrNull { it.selectionArea }

    fun findById(packId: String): RegionPack? =
        packs.firstOrNull { it.id.equals(packId, ignoreCase = true) }
}

/**
 * Cliente + cache local do catálogo de pacotes offline do Brasil (`catalog.json` v1),
 * com fallback automático para o `manifest.json` legado de Joinville.
 */
class RegionCatalog(
    private val cacheDir: File,
    private val catalogUrl: String = DEFAULT_CATALOG_URL,
    private val legacyManifestUrl: String = OfflineMapPackManager.DEFAULT_MANIFEST_URL
) {

    private val cacheFile: File get() = File(cacheDir, CACHE_FILENAME)

    /** Carrega o último catálogo salvo em disco (instantâneo, 100% offline), mesclando os 27 estados. */
    fun loadCached(): RegionCatalogSnapshot? {
        val file = cacheFile
        if (!file.isFile || file.length() == 0L) return null
        return try {
            mergeWithEmbeddedFallback(parseCatalogOrManifest(file.readText(Charsets.UTF_8), fromCache = true))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Busca `catalog.json` remoto na GitHub Release pública `andrefonn/drifly-condutor`.
     * Se `catalog.json` ainda não existir (404), faz fallback automático para `manifest.json`
     * mesclado com a tabela embarcada de 27 estados.
     */
    suspend fun fetchRemoteOrCached(): RegionCatalogSnapshot? = withContext(Dispatchers.IO) {
        val primaryText = fetchUrlTextOrNull(catalogUrl)
        if (primaryText != null) {
            try {
                val parsed = parseCatalogOrManifest(primaryText, fromCache = false)
                if (parsed.packs.isNotEmpty()) {
                    saveCache(primaryText)
                    return@withContext mergeWithEmbeddedFallback(parsed)
                }
            } catch (_: Exception) {
            }
        }

        val legacyText = fetchUrlTextOrNull(legacyManifestUrl)
        if (legacyText != null) {
            try {
                val parsed = parseCatalogOrManifest(legacyText, fromCache = false)
                if (parsed.packs.isNotEmpty()) {
                    saveCache(legacyText)
                    return@withContext mergeWithEmbeddedFallback(parsed)
                }
            } catch (_: Exception) {
            }
        }

        loadCached()
    }

    private fun saveCache(jsonText: String) {
        try {
            cacheDir.mkdirs()
            cacheFile.writeText(jsonText, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun fetchUrlTextOrNull(urlString: String): String? {
        return try {
            var currentUrl = urlString
            for (hop in 0 until MAX_REDIRECTS) {
                val url = URL(currentUrl)
                if (url.protocol != "https" && url.host != "127.0.0.1" && url.host != "localhost") {
                    return null
                }
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "DriFly-Android-OfflineMap/1.0")
                }
                try {
                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location") ?: return null
                        currentUrl = URL(url, loc).toString()
                        continue
                    }
                    if (code !in 200..299) return null
                    return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_CATALOG_URL = "${OfflineMapPackManager.GITHUB_RELEASES_BASE}/catalog.json"
        private const val CACHE_FILENAME = ".catalog_cache.json"
        private const val MAX_REDIRECTS = 5

        /** BBox conhecido do pacote piloto de Joinville (usado como fallback local quando sem meta). */
        val JOINVILLE_FALLBACK_BBOX = GeoBBox(
            west = -49.18,
            south = -26.55,
            east = -48.58,
            north = -26.02
        )

        /**
         * Analisa tanto o novo `catalog.json` v1 quanto o `manifest.json` legado.
         */
        fun parseCatalogOrManifest(jsonText: String, fromCache: Boolean = false): RegionCatalogSnapshot {
            val root = JSONObject(jsonText)
            val version = root.optInt("version", root.optInt("schema_version", 1))
            val updatedAt = root.optString("updated_at", "")
            val packsOut = mutableListOf<RegionPack>()

            val packsArr = root.optJSONArray("packs")
            if (packsArr != null) {
                for (i in 0 until packsArr.length()) {
                    val obj = packsArr.optJSONObject(i) ?: continue
                    parsePackObject(obj)?.let { packsOut.add(it) }
                }
            } else {
                val packsMap = root.optJSONObject("packs")
                if (packsMap != null) {
                    val keys = packsMap.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = packsMap.optJSONObject(key) ?: continue
                        if (!obj.has("id")) obj.put("id", key)
                        parsePackObject(obj)?.let { packsOut.add(it) }
                    }
                }
            }

            return RegionCatalogSnapshot(
                version = version,
                updatedAt = updatedAt,
                packs = packsOut,
                fromCache = fromCache
            )
        }

        private fun parsePackObject(obj: JSONObject): RegionPack? {
            val id = obj.optString("id").trim().lowercase(Locale.ROOT)
            if (id.isEmpty()) return null

            val bbox = GeoBBox.fromJsonArray(obj.optJSONArray("bbox"))
                ?: if (id == OfflineMapPackManager.PACK_JOINVILLE) JOINVILLE_FALLBACK_BBOX else return null

            val polygon = parsePolygon(obj.optJSONArray("polygon"))
            val name = obj.optString("name")
                .ifBlank { obj.optString("displayName") }
                .ifBlank { id.replaceFirstChar { it.titlecase(Locale.ROOT) } }
            val kind = obj.optString("kind").ifBlank {
                if (id == OfflineMapPackManager.PACK_JOINVILLE) "city" else "state"
            }
            val uf = obj.optString("uf").ifBlank {
                if (id == OfflineMapPackManager.PACK_JOINVILLE) "SC" else id.uppercase(Locale.ROOT)
            }
            val rawZipUrl = obj.optString("zip_url")
                .ifBlank { obj.optString("url") }
                .ifBlank { obj.optString("zipUrl") }
            val explicitAvailable = if (obj.has("available")) obj.optBoolean("available", false) else null
            val available = explicitAvailable ?: (rawZipUrl.isNotBlank() || id == OfflineMapPackManager.PACK_JOINVILLE)
            val zipUrl = rawZipUrl.ifBlank {
                if (available) "${OfflineMapPackManager.GITHUB_RELEASES_BASE}/$id-pack-v1.zip" else ""
            }

            val sizeBytes = obj.optLong("size_bytes", obj.optLong("sizeBytes", 0L))
            val installedBytes = obj.optLong(
                "installed_size_bytes",
                obj.optLong("uncompressed_bytes", (sizeBytes * 1.5).toLong())
            )

            return RegionPack(
                id = id,
                uf = uf,
                name = name,
                kind = kind,
                version = obj.optInt("version", 1),
                bbox = bbox,
                polygon = polygon,
                zipUrl = zipUrl,
                sizeBytes = sizeBytes,
                installedSizeBytes = installedBytes,
                sha256 = obj.optString("sha256", ""),
                routingNodes = obj.optInt("routing_nodes", 0),
                minZoom = obj.optInt("minzoom", 0),
                maxZoom = obj.optInt("maxzoom", obj.optInt("pmtiles_maxzoom", 15)),
                available = available
            )
        }

        /**
         * Tabela embarcada de contingência com Joinville + os 27 estados brasileiros (bboxes),
         * garantindo identificação regional instantânea mesmo 100% offline na 1ª abertura.
         */
        fun embeddedBrazilFallback(): RegionCatalogSnapshot {
            fun st(id: String, uf: String, name: String, w: Double, s: Double, e: Double, n: Double) =
                RegionPack(
                    id = id,
                    uf = uf,
                    name = name,
                    kind = "state",
                    version = 1,
                    bbox = GeoBBox(w, s, e, n),
                    zipUrl = "",
                    sizeBytes = 0L,
                    installedSizeBytes = 0L,
                    sha256 = "",
                    available = false
                )

            val packs = listOf(
                RegionPack(
                    id = OfflineMapPackManager.PACK_JOINVILLE,
                    uf = "SC",
                    name = "Joinville e Região (SC)",
                    kind = "city",
                    version = 1,
                    bbox = JOINVILLE_FALLBACK_BBOX,
                    zipUrl = OfflineMapPackManager.DEFAULT_JOINVILLE_ZIP_URL,
                    sizeBytes = 28_500_483L,
                    installedSizeBytes = 41_039_714L,
                    sha256 = "",
                    available = true
                ),
                st("ac", "AC", "Acre", -73.99, -11.15, -66.62, -7.11),
                st("al", "AL", "Alagoas", -38.24, -10.50, -35.15, -8.81),
                st("ap", "AP", "Amapá", -54.88, -1.24, -49.87, 4.44),
                st("am", "AM", "Amazonas", -73.80, -9.82, -56.10, 2.25),
                st("ba", "BA", "Bahia", -46.62, -18.35, -37.34, -8.53),
                st("ce", "CE", "Ceará", -41.42, -7.86, -37.25, -2.78),
                st("df", "DF", "Distrito Federal", -48.29, -16.05, -47.31, -15.50),
                st("es", "ES", "Espírito Santo", -41.88, -21.30, -39.67, -17.89),
                st("go", "GO", "Goiás", -53.25, -19.50, -45.91, -12.39),
                RegionPack(
                    id = "ma",
                    uf = "MA",
                    name = "Maranhão",
                    kind = "state",
                    version = 1,
                    bbox = GeoBBox(-48.76, -10.26, -41.79, -1.04),
                    // Polígono simplificado respeitando o Rio Gurupi (evita sobrepor Belém/PA).
                    polygon = listOf(
                        GeoPoint(-1.04, -46.06),
                        GeoPoint(-1.04, -41.79),
                        GeoPoint(-10.26, -41.79),
                        GeoPoint(-10.26, -48.76),
                        GeoPoint(-5.20, -48.76),
                        GeoPoint(-2.80, -46.60)
                    ),
                    zipUrl = "",
                    sizeBytes = 0L,
                    installedSizeBytes = 0L,
                    sha256 = "",
                    available = false
                ),
                st("mt", "MT", "Mato Grosso", -61.63, -18.04, -50.22, -7.35),
                st("ms", "MS", "Mato Grosso do Sul", -58.17, -24.07, -50.92, -17.17),
                st("mg", "MG", "Minas Gerais", -51.05, -22.92, -39.86, -14.23),
                st("pa", "PA", "Pará", -58.90, -9.84, -46.06, 2.59),
                st("pb", "PB", "Paraíba", -38.77, -8.30, -34.79, -6.02),
                st("pr", "PR", "Paraná", -54.62, -26.72, -48.02, -22.52),
                st("pe", "PE", "Pernambuco", -41.36, -9.48, -34.81, -7.15),
                st("pi", "PI", "Piauí", -45.99, -10.93, -40.37, -2.74),
                st("rj", "RJ", "Rio de Janeiro", -44.89, -23.37, -40.96, -20.76),
                st("rn", "RN", "Rio Grande do Norte", -38.58, -6.98, -34.97, -4.83),
                st("rs", "RS", "Rio Grande do Sul", -57.65, -33.75, -49.69, -27.08),
                st("ro", "RO", "Rondônia", -66.81, -13.69, -59.77, -7.97),
                st("rr", "RR", "Roraima", -64.83, -1.58, -58.89, 5.27),
                st("sc", "SC", "Santa Catarina", -53.84, -29.35, -48.32, -25.95),
                st("sp", "SP", "São Paulo", -53.11, -25.31, -44.16, -19.78),
                st("se", "SE", "Sergipe", -38.25, -11.57, -36.39, -9.51),
                st("to", "TO", "Tocantins", -50.74, -13.47, -45.70, -5.17)
            )
            return RegionCatalogSnapshot(
                version = 1,
                updatedAt = "embedded",
                packs = packs,
                fromCache = true
            )
        }

        /**
         * Garante que todos os 27 estados brasileiros continuem presentes mesmo quando o servidor
         * responder com o `manifest.json` legado (que lista apenas Joinville).
         */
        fun mergeWithEmbeddedFallback(snapshot: RegionCatalogSnapshot): RegionCatalogSnapshot {
            val byId = LinkedHashMap<String, RegionPack>()
            for (p in snapshot.packs) {
                byId[p.id.lowercase(Locale.ROOT)] = p
            }
            for (fallback in embeddedBrazilFallback().packs) {
                val key = fallback.id.lowercase(Locale.ROOT)
                if (!byId.containsKey(key)) {
                    byId[key] = fallback
                }
            }
            return snapshot.copy(packs = byId.values.toList())
        }

        private fun parsePolygon(arr: JSONArray?): List<GeoPoint> {
            if (arr == null || arr.length() < 3) return emptyList()
            val out = ArrayList<GeoPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                if (pair.length() < 2) continue
                val lng = pair.optDouble(0, Double.NaN)
                val lat = pair.optDouble(1, Double.NaN)
                if (!lat.isNaN() && !lng.isNaN()) {
                    out.add(GeoPoint(lat = lat, lng = lng))
                }
            }
            return out
        }
    }
}
