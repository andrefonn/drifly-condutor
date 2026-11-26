package com.drifly.condutor.map.geocoder

import android.database.sqlite.SQLiteDatabase
import com.drifly.condutor.BlacklistManager
import com.drifly.condutor.map.routing.GeoPoint
import com.drifly.condutor.map.routing.OfflineNeighborhoodLookup
import java.io.Closeable
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

data class GeocoderPlace(
    val id: Long,
    val kind: String,
    val subkind: String,
    val name: String,
    val suburb: String,
    val city: String,
    val point: GeoPoint,
    val houseNumber: String? = null
) {
    /** Rótulo amigável de exibição (ex.: "Rua Dr. João Colin, 500"). */
    val displayTitle: String
        get() = if (!houseNumber.isNullOrBlank()) "$name, $houseNumber" else name

    /** Subtítulo curto (ex.: "América · Joinville"). */
    val displaySubtitle: String
        get() = listOf(suburb, city).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

/**
 * Busca de endereços, bairros e números 100% offline sobre `joinville_geocoder.db` (SQLite FTS5).
 *
 * Esquema esperado (alinhado ao pacote Joinville):
 * - `places(id, kind, subkind, name, name_norm, short_norm, suburb, suburb_norm, city, city_norm, lat, lng, min_lat, min_lng, max_lat, max_lng, rank)`
 * - `places_fts(name_norm, short_norm, suburb_norm, city_norm)` (FTS5 `content='places'`)
 * - `street_numbers(street_norm, num_int, num_raw, suburb, lat, lng)`
 */
class OfflineGeocoder(private val dbFile: File) : OfflineNeighborhoodLookup, Closeable {

    private var db: SQLiteDatabase? = null

    @Volatile
    private var fts5Available: Boolean = true

    val isOpen: Boolean get() = db?.isOpen == true

    @Synchronized
    fun open(): Boolean {
        if (db?.isOpen == true) return true
        if (!dbFile.isFile || dbFile.length() == 0L) return false
        return try {
            val opened = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            db = opened
            fts5Available = detectFts5Support(opened)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pesquisa endereços, ruas (inclusive com número, ex. "João Colin 500"), bairros e POIs.
     * Normaliza acentos via [BlacklistManager.normalizeText] e ranqueia por BM25 + rank
     * quando FTS5 está presente no SQLite do aparelho, ou usa busca multi-token indexada
     * (sem spam de erro em aparelhos OEM sem módulo `fts5`).
     */
    fun search(rawQuery: String, limit: Int = 12): List<GeocoderPlace> {
        val database = ensureOpen() ?: return emptyList()
        val norm = BlacklistManager.normalizeText(rawQuery)
        if (norm.length < 2) return emptyList()

        // Detecta número residencial opcional (ex.: "joao colin 500" -> rua="joao colin", num=500)
        val tokens = norm.split(' ').filter { it.isNotBlank() }
        val extractedNum = tokens.lastOrNull()?.toIntOrNull()?.takeIf { tokens.size >= 2 }
        val textTokens = if (extractedNum != null) tokens.dropLast(1) else tokens
        if (textTokens.isEmpty()) return emptyList()

        if (!fts5Available) {
            return fallbackLikeSearch(database, textTokens, extractedNum, limit)
        }

        val ftsMatch = buildFtsPrefixQuery(textTokens)
        val results = mutableListOf<GeocoderPlace>()

        try {
            val sql = """
                SELECT p.id, p.kind, COALESCE(p.subkind, ''), p.name,
                       COALESCE(p.suburb, ''), COALESCE(p.city, ''),
                       p.lat, p.lng, p.name_norm
                FROM places_fts f
                JOIN places p ON p.id = f.rowid
                WHERE places_fts MATCH ?
                ORDER BY (bm25(places_fts, 4.0, 6.0, 2.0, 1.0) - p.rank * 0.15) ASC
                LIMIT ?
            """.trimIndent()

            database.rawQuery(sql, arrayOf(ftsMatch, limit.toString())).use { c ->
                while (c.moveToNext()) {
                    results.add(readPlaceRow(database, c, extractedNum))
                }
            }
        } catch (_: Exception) {
            // Desativa FTS5 na sessão para evitar repetição de E/SQLiteLog em OEMs sem módulo fts5.
            fts5Available = false
            return fallbackLikeSearch(database, textTokens, extractedNum, limit)
        }
        return results
    }

    /**
     * Resolve o centro de um bairro pelo nome normalizado para a Lista Negra offline
     * (implementa [OfflineNeighborhoodLookup]).
     */
    override fun findNeighborhoodCenter(normalizedTerm: String): GeoPoint? {
        val database = ensureOpen() ?: return null
        val norm = BlacklistManager.normalizeText(normalizedTerm)
            .replace(Regex("\\b(joinville|sc|bairro)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.length < 3) return null

        // 1) Correspondência exata em suburb_norm / name_norm
        val exactSql = """
            SELECT lat, lng FROM places
            WHERE kind = 'suburb' AND (suburb_norm = ? OR name_norm = ?)
            LIMIT 1
        """.trimIndent()
        try {
            database.rawQuery(exactSql, arrayOf(norm, norm)).use { c ->
                if (c.moveToFirst()) return GeoPoint(c.getDouble(0), c.getDouble(1))
            }
            // 2) Correspondência parcial (ex.: "jardim paraiso" em termo composto)
            val likeArg = "%$norm%"
            val likeSql = """
                SELECT lat, lng FROM places
                WHERE kind = 'suburb' AND (suburb_norm LIKE ? OR name_norm LIKE ?)
                ORDER BY rank DESC
                LIMIT 1
            """.trimIndent()
            database.rawQuery(likeSql, arrayOf(likeArg, likeArg)).use { c ->
                if (c.moveToFirst()) return GeoPoint(c.getDouble(0), c.getDouble(1))
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Geocodificação reversa offline rápida para toque longo no mapa:
     * busca a rua ou local mais próximo dentro de uma janela de ~350 m.
     */
    fun nearestPlaceLabel(point: GeoPoint, maxRadiusMeters: Double = 350.0): String? {
        val database = ensureOpen() ?: return null
        val dLat = maxRadiusMeters / 111_320.0
        val cosLat = cos(Math.toRadians(point.lat)).coerceAtLeast(0.01)
        val dLng = dLat / cosLat

        val sql = """
            SELECT name, COALESCE(suburb, ''), lat, lng, kind
            FROM places
            WHERE kind IN ('street', 'poi', 'suburb')
              AND lat BETWEEN ? AND ?
              AND lng BETWEEN ? AND ?
            LIMIT 40
        """.trimIndent()

        var bestLabel: String? = null
        var bestDist = Double.POSITIVE_INFINITY
        try {
            database.rawQuery(
                sql,
                arrayOf(
                    (point.lat - dLat).toString(),
                    (point.lat + dLat).toString(),
                    (point.lng - dLng).toString(),
                    (point.lng + dLng).toString()
                )
            ).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val suburb = c.getString(1) ?: ""
                    val lat = c.getDouble(2)
                    val lng = c.getDouble(3)
                    val kind = c.getString(4) ?: ""
                    val dy = (lat - point.lat) * 111_320.0
                    val dx = (lng - point.lng) * 111_320.0 * cosLat
                    val dist = hypot(dx, dy)
                    // Prefere nomes de ruas sobre bairros amplos quando a distância é próxima.
                    val weighted = if (kind == "street") dist else dist + 60.0
                    if (dist <= maxRadiusMeters && weighted < bestDist) {
                        bestDist = weighted
                        bestLabel = if (suburb.isNotBlank() && kind == "street") "$name, $suburb" else name
                    }
                }
            }
        } catch (_: Exception) {
        }
        return bestLabel
    }

    @Synchronized
    override fun close() {
        try {
            db?.close()
        } catch (_: Exception) {
        }
        db = null
    }

    private fun ensureOpen(): SQLiteDatabase? {
        if (db?.isOpen == true) return db
        return if (open()) db else null
    }

    private fun lookupStreetNumber(
        database: SQLiteDatabase,
        streetNorm: String,
        targetNum: Int
    ): Pair<GeoPoint, String>? {
        val sql = """
            SELECT lat, lng, num_raw
            FROM street_numbers
            WHERE street_norm = ?
            ORDER BY ABS(num_int - ?) ASC
            LIMIT 1
        """.trimIndent()
        return try {
            database.rawQuery(sql, arrayOf(streetNorm, targetNum.toString())).use { c ->
                if (c.moveToFirst()) {
                    GeoPoint(c.getDouble(0), c.getDouble(1)) to (c.getString(2) ?: targetNum.toString())
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun detectFts5Support(database: SQLiteDatabase): Boolean {
        return try {
            database.rawQuery("PRAGMA compile_options", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0).equals("ENABLE_FTS5", ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun readPlaceRow(
        database: SQLiteDatabase,
        c: android.database.Cursor,
        extractedNum: Int?
    ): GeocoderPlace {
        val id = c.getLong(0)
        val kind = c.getString(1) ?: ""
        val subkind = c.getString(2) ?: ""
        val name = c.getString(3) ?: ""
        val suburb = c.getString(4) ?: ""
        val city = c.getString(5) ?: ""
        var lat = c.getDouble(6)
        var lng = c.getDouble(7)
        val nameNorm = c.getString(8) ?: ""
        var matchedNum: String? = null

        if (extractedNum != null && kind == "street" && nameNorm.isNotEmpty()) {
            val house = lookupStreetNumber(database, nameNorm, extractedNum)
            if (house != null) {
                lat = house.first.lat
                lng = house.first.lng
                matchedNum = house.second
            }
        }

        return GeocoderPlace(
            id = id,
            kind = kind,
            subkind = subkind,
            name = name,
            suburb = suburb,
            city = city,
            point = GeoPoint(lat, lng),
            houseNumber = matchedNum
        )
    }

    private fun fallbackLikeSearch(
        database: SQLiteDatabase,
        tokens: List<String>,
        extractedNum: Int?,
        limit: Int
    ): List<GeocoderPlace> {
        val cleanTokens = tokens.take(5)
        if (cleanTokens.isEmpty()) return emptyList()

        val whereClauses = cleanTokens.joinToString(" AND ") {
            "(name_norm LIKE ? OR short_norm LIKE ? OR suburb_norm LIKE ?)"
        }
        val fullPrefix = "${cleanTokens.joinToString(" ")}%"
        val args = ArrayList<String>(cleanTokens.size * 3 + 3)
        for (tok in cleanTokens) {
            val like = "%$tok%"
            args.add(like)
            args.add(like)
            args.add(like)
        }
        args.add(fullPrefix)
        args.add(fullPrefix)
        args.add(limit.toString())

        val out = mutableListOf<GeocoderPlace>()
        val sql = """
            SELECT id, kind, COALESCE(subkind, ''), name, COALESCE(suburb, ''), COALESCE(city, ''), lat, lng, name_norm
            FROM places
            WHERE $whereClauses
            ORDER BY
                CASE WHEN short_norm LIKE ? THEN 0 WHEN name_norm LIKE ? THEN 1 ELSE 2 END ASC,
                rank DESC
            LIMIT ?
        """.trimIndent()
        try {
            database.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    out.add(readPlaceRow(database, c, extractedNum))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    companion object {
        /** Monta expressão segura FTS5 prefix-match (`"joao"* "colin"*`) sem risco de injeção de operadores. */
        internal fun buildFtsPrefixQuery(tokens: List<String>): String =
            tokens.mapNotNull { token ->
                val safe = token.replace(Regex("[^a-z0-9]"), "")
                if (safe.isEmpty()) null else "\"$safe\"*"
            }.joinToString(" ")
    }
}
