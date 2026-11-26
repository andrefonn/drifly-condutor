package com.drifly.condutor

import android.content.Context
import java.text.Normalizer
import java.util.Locale

data class BlacklistItem(
    val id: Long = 0L,
    val rawTerm: String,
    val normalizedTerm: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Regra que produziu uma correspondência com a Lista Negra.
 */
enum class BlacklistMatchRule {
    /** (a) O termo normalizado aparece literalmente dentro do endereço normalizado. */
    TEXTO,
    /** (b) Todos os tokens significativos do termo estão presentes no endereço. */
    TOKENS,
    /** (c) O endereço é um fragmento contíguo (e distintivo) do termo. */
    FRAGMENTO,
    /** (d) Proximidade geográfica (< 1.5 km). */
    GEO
}

/**
 * Resultado detalhado de uma correspondência (item + regra + explicação legível).
 */
data class BlacklistMatch(
    val item: BlacklistItem,
    val regra: BlacklistMatchRule,
    val detalhe: String
)

object BlacklistManager {

    /**
     * Normaliza o texto removendo acentos/diacríticos, pontuação,
     * convertendo para minúsculas e eliminando múltiplos espaços.
     */
    fun normalizeText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val nfd = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        val withoutDiacritics = nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return withoutDiacritics
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ------------------------------------------------------------------
    // Vocabulário genérico usado na comparação por tokens
    // ------------------------------------------------------------------

    /**
     * Tokens geográficos/de logradouro genéricos: não identificam um bairro e por isso
     * são ignorados na comparação por tokens (regra b).
     *
     * Motivo (bug de 2026-11-23): o termo "São Marcos Joinville" exigia o token
     * "joinville", mas o OCR do destino entregou apenas "Rua Maria Manes, 141, São Marcos"
     * (o histórico do app mostra "..., São Marcos, Joinville - SC"). Resultado: o
     * bloqueio era silenciosamente ignorado. Cidade/UF/CEP e o logradouro não são
     * distintivos do bairro, então não podem ser obrigatórios.
     *
     * Observações:
     * - "centro" é genérico na comparação por tokens: "Centro Araquari" exige o
     *   termo completo, senão bloquearia qualquer corrida no Centro de Joinville.
     *   Um item que seja só "Centro" continua casando pela regra (a) de texto.
     * - "jardim"/"vila" são tratados em [conditionalBairroTokens].
     * - nomes de cidades são genéricos para permitir o casamento bairro-only; como
     *   ainda exigimos TODOS os tokens distintivos, ignorar a cidade não gera falso
     *   positivo por si só (o bairro precisa bater).
     */
    private val genericTokens: Set<String> = setOf(
        // cidade / estado / país
        "joinville", "araquari", "jaragua", "sc", "br", "brasil",
        // logradouro / tipo de via / sufixo de endereço
        "rua", "r", "avenida", "av", "travessa", "tv", "alameda",
        "estrada", "rodovia", "praca", "bairro", "cidade",
        // "centro" só identifica um bairro quando o termo é exatamente "Centro";
        // em "Centro Araquari" ele não pode casar com o Centro de Joinville.
        "centro",
        // regiões
        "zona", "norte", "sul", "leste", "oeste"
    )

    /**
     * Sequências genéricas de mais de uma palavra ("Santa Catarina" é estado, não bairro).
     * Removidas como bloco antes da tokenização significativa.
     */
    private val genericPhrases: List<List<String>> = listOf(listOf("santa", "catarina"))

    /**
     * Tokens que podem ser parte legítima do nome do bairro ("Jardim Paraíso", "Vila Nova").
     * Só são descartados da comparação quando o termo ainda deixa >= 2 tokens distintivos,
     * ou seja, quando há contexto suficiente para identificar o bairro sem eles.
     *
     * Efeito prático: "Vila Nova Joinville" continua exigindo "vila", então NÃO casa
     * com uma rua chamada "Rua Nova" só porque "nova" aparece; "Cidade Jardim
     * Parauapebas" continua exigindo "jardim", então não casa com outro bairro da
     * mesma cidade.
     */
    private val conditionalBairroTokens: Set<String> = setOf("jardim", "vila")

    /** Comprimento mínimo de um token para ser considerado distintivo. */
    private const val MIN_SIGNIFICANT_TOKEN_LENGTH = 3

    /** Comprimento mínimo do fragmento de endereço aceito na regra (c). */
    private const val MIN_FRAGMENT_LENGTH = 5

    /** Raio máximo (km) da correspondência geográfica (regra d). */
    private const val GEO_RADIUS_KM = 1.5

    /**
     * Verifica se o endereço fornecido corresponde a algum item da Lista Negra.
     * Realiza verificação textual normalizada offline (<1ms) e, se coordenadas
     * estiverem disponíveis, verificação geográfica por raio de 1.5 km (Haversine).
     */
    fun matchesBlacklist(
        address: String?,
        items: List<BlacklistItem>,
        addressCoords: Pair<Double, Double>? = null
    ): BlacklistItem? = findMatch(address, items, addressCoords)?.item

    /**
     * Diagnóstico legível do bloqueio, ou `null` quando nada casa.
     * Ex.: `"termo='São Marcos Joinville' regra=TOKENS tokens=[sao marcos]"`.
     */
    fun motivoDoBloqueio(
        address: String?,
        items: List<BlacklistItem>,
        addressCoords: Pair<Double, Double>? = null
    ): String? {
        val match = findMatch(address, items, addressCoords) ?: return null
        return "termo='${match.item.rawTerm}' regra=${match.regra.name} ${match.detalhe}"
    }

    /**
     * Mesma lógica de [matchesBlacklist], mas devolvendo item + regra + detalhe
     * (usado por logs e pelo diagnóstico [motivoDoBloqueio]).
     *
     * Regras, na ordem:
     * a) TEXTO      – termo normalizado contido no endereço normalizado.
     * b) TOKENS     – todos os tokens significativos do termo presentes no endereço.
     * c) FRAGMENTO  – endereço é um fragmento contíguo e distintivo do termo.
     * d) GEO        – distância < 1.5 km quando há coordenadas.
     */
    fun findMatch(
        address: String?,
        items: List<BlacklistItem>,
        addressCoords: Pair<Double, Double>? = null
    ): BlacklistMatch? {
        if (address.isNullOrBlank() || items.isEmpty()) return null

        val normAddr = normalizeText(address)
        if (normAddr.isEmpty()) return null

        val addrTokens = normAddr.split(" ").filter { it.isNotBlank() }
        if (addrTokens.isEmpty()) return null
        val addrTokenSet = addrTokens.toSet()

        for (item in items) {
            val normTerm = item.normalizedTerm.ifBlank { normalizeText(item.rawTerm) }
            if (normTerm.isEmpty()) continue

            val termTokens = normTerm.split(" ").filter { it.isNotBlank() }
            if (termTokens.isEmpty()) continue

            // (a) Correspondência textual direta por substring
            if (normAddr.contains(normTerm)) {
                return BlacklistMatch(item, BlacklistMatchRule.TEXTO, "termo='$normTerm'")
            }

            // (b) Todos os tokens significativos presentes (cidade/UF/logradouro ignorados)
            val significant = significantTokens(termTokens)
            if (significant.isNotEmpty()) {
                val allPresent = significant.all { token ->
                    addrTokenSet.contains(token) || (token.length >= 5 && normAddr.contains(token))
                }
                if (allPresent) {
                    return BlacklistMatch(
                        item,
                        BlacklistMatchRule.TOKENS,
                        "tokens=[${significant.joinToString(" ")}]"
                    )
                }
            }

            // (c) Endereço é um fragmento menor (mas distintivo) do termo
            if (isFragmentOfTerm(termTokens, addrTokens, significant)) {
                return BlacklistMatch(
                    item,
                    BlacklistMatchRule.FRAGMENTO,
                    "fragmento='${addrTokens.joinToString(" ")}' termo='$normTerm'"
                )
            }

            // (d) Correspondência geográfica (se ambos possuírem coordenadas válidas)
            if (addressCoords != null && item.latitude != null && item.longitude != null) {
                val distanceKm = ModoCasaHelper.calculateDistanceKm(
                    lat1 = addressCoords.first,
                    lon1 = addressCoords.second,
                    lat2 = item.latitude,
                    lon2 = item.longitude
                )
                if (distanceKm < GEO_RADIUS_KM) {
                    val distancia = String.format(Locale.ROOT, "%.2f", distanceKm)
                    return BlacklistMatch(item, BlacklistMatchRule.GEO, "distancia=${distancia}km")
                }
            }
        }

        return null
    }

    /**
     * Tokens do termo que realmente o identificam:
     * - remove frases genéricas ("santa catarina");
     * - remove tokens curtos (< 3), genéricos e "jardim"/"vila";
     * - devolve "jardim"/"vila" ao conjunto quando, sem eles, sobraria menos de 2
     *   tokens distintivos (proteção contra casar "Rua Nova" com "Vila Nova").
     */
    private fun significantTokens(termTokens: List<String>): List<String> {
        val withoutPhrases = removeGenericPhrases(termTokens)
        val base = withoutPhrases
            .filter {
                it.length >= MIN_SIGNIFICANT_TOKEN_LENGTH &&
                    it !in genericTokens &&
                    it !in conditionalBairroTokens
            }
            .distinct()
        val conditional = withoutPhrases.filter { it in conditionalBairroTokens }.distinct()
        if (conditional.isEmpty() || base.size >= 2) return base
        return (base + conditional).distinct()
    }

    /** Remove blocos genéricos multi-palavra (ex.: "santa catarina"). */
    private fun removeGenericPhrases(tokens: List<String>): List<String> {
        if (tokens.isEmpty() || genericPhrases.isEmpty()) return tokens
        val result = mutableListOf<String>()
        var i = 0
        loop@ while (i < tokens.size) {
            for (phrase in genericPhrases) {
                if (i + phrase.size <= tokens.size &&
                    tokens.subList(i, i + phrase.size) == phrase
                ) {
                    i += phrase.size
                    continue@loop
                }
            }
            result.add(tokens[i])
            i++
        }
        return result
    }

    /**
     * Regra (c): o endereço é um fragmento contíguo do termo, com comprimento mínimo
     * e carregando ao menos um token distintivo (evita casar "rua nova" com
     * "vila nova joinville").
     */
    private fun isFragmentOfTerm(
        termTokens: List<String>,
        addrTokens: List<String>,
        significant: List<String>
    ): Boolean {
        if (addrTokens.size >= termTokens.size) return false
        if (addrTokens.joinToString(" ").length < MIN_FRAGMENT_LENGTH) return false

        val carriesDistinctiveToken = if (significant.isEmpty()) {
            addrTokens.any { it !in genericTokens && it !in conditionalBairroTokens }
        } else {
            addrTokens.any { it in significant }
        }
        if (!carriesDistinctiveToken) return false

        return containsContiguousSublist(termTokens, addrTokens)
    }

    private fun containsContiguousSublist(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            if (haystack.subList(start, start + needle.size) == needle) return true
        }
        return false
    }

    /**
     * Formata mensagem de alerta de Lista Negra conforme especificações:
     * - Apenas embarque: "⛔ Embarque em Lista Negra: <Bairro>"
     * - Apenas destino: "⛔ Destino em Lista Negra: <Bairro>"
     * - Ambos: "⛔ Embarque e Destino em Lista Negra (<Bairros>)"
     */
    fun formatBlacklistAlert(pickupMatch: BlacklistItem?, destMatch: BlacklistItem?): String? {
        return when {
            pickupMatch != null && destMatch != null -> {
                if (pickupMatch.rawTerm.equals(destMatch.rawTerm, ignoreCase = true)) {
                    "⛔ Embarque e Destino em Lista Negra (${pickupMatch.rawTerm})"
                } else {
                    "⛔ Embarque e Destino em Lista Negra (${pickupMatch.rawTerm}, ${destMatch.rawTerm})"
                }
            }
            pickupMatch != null -> {
                "⛔ Embarque em Lista Negra: ${pickupMatch.rawTerm}"
            }
            destMatch != null -> {
                "⛔ Destino em Lista Negra: ${destMatch.rawTerm}"
            }
            else -> null
        }
    }

    /**
     * Avalia uma oferta contra a lista negra ativa.
     * Retorna uma nova instância de RideOffer com os campos de bloqueio devidamente preenchidos.
     */
    fun evaluateOffer(
        offer: RideOffer,
        blacklistItems: List<BlacklistItem>,
        pickupCoords: Pair<Double, Double>? = null,
        destCoords: Pair<Double, Double>? = null
    ): RideOffer {
        if (blacklistItems.isEmpty()) {
            return offer.copy(isBlacklistBlocked = false, blacklistAlert = null)
        }

        val pickupMatch = matchesBlacklist(offer.pickupAddress, blacklistItems, pickupCoords)
        val destMatch = matchesBlacklist(offer.destinationAddress, blacklistItems, destCoords)

        val isBlocked = pickupMatch != null || destMatch != null
        val alert = formatBlacklistAlert(pickupMatch, destMatch)

        return offer.copy(
            isBlacklistBlocked = isBlocked,
            blacklistAlert = alert
        )
    }

    /**
     * Geocodifica um termo textual de forma síncrona/utilitária em background via ModoCasaHelper.
     */
    fun geocodeTerm(context: Context, rawTerm: String): Pair<Double, Double>? {
        return ModoCasaHelper.geocodeAddress(context, rawTerm)
    }
}
