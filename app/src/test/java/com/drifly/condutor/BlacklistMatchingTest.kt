package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão do bug de 2026-11-23 12:21:
 * o motorista bloqueou "São Marcos Joinville" e o auto-aceite aceitou um destino
 * cujo OCR era apenas "Rua Maria Manes, 141, São Marcos" — a cidade não se repetia
 * no endereço, o token "joinville" não era encontrado e `blocked=false`.
 *
 * Regras cobertas:
 *  a) TEXTO     – termo normalizado contido no endereço
 *  b) TOKENS    – todos os tokens significativos (>=3 chars, sem stopwords) presentes
 *  c) FRAGMENTO – endereço é fragmento contíguo e distintivo do termo
 *  d) GEO       – proximidade < 1.5 km
 */
class BlacklistMatchingTest {

    // ----------------------------------------------------------------
    // Fixtures: os termos reais da lista negra do motorista
    // ----------------------------------------------------------------

    private val saoMarcosJoinville = BlacklistItem(
        id = 2L,
        rawTerm = "São Marcos Joinville",
        normalizedTerm = BlacklistManager.normalizeText("São Marcos Joinville")
    )

    private val saoMarcos = BlacklistItem(
        id = 3L,
        rawTerm = "São Marcos",
        normalizedTerm = BlacklistManager.normalizeText("São Marcos")
    )

    private val centroAraquari = BlacklistItem(
        id = 4L,
        rawTerm = "Centro Araquari",
        normalizedTerm = BlacklistManager.normalizeText("Centro Araquari")
    )

    private val vilaNovaJoinville = BlacklistItem(
        id = 5L,
        rawTerm = "Vila Nova Joinville",
        normalizedTerm = BlacklistManager.normalizeText("Vila Nova Joinville")
    )

    private val realOcrDestination = "Rua Maria Manes, 141, São Marcos"
    private val realFullDestination = "Rua Maria Manes, 141, São Marcos, Joinville - SC, 89214-060, Brasil"

    // ----------------------------------------------------------------
    // 1. Caso real do bug: endereço sem a cidade
    // ----------------------------------------------------------------

    @Test
    fun realBugCase_destinationWithoutCity_matchesSaoMarcosJoinville() {
        val item = BlacklistManager.matchesBlacklist(realOcrDestination, listOf(saoMarcosJoinville))
        assertNotNull("Destino 'Rua Maria Manes, 141, São Marcos' DEVE casar com 'São Marcos Joinville'", item)
        assertEquals(2L, item?.id)
    }

    @Test
    fun realBugCase_destinationWithoutCity_matchesSaoMarcos() {
        val item = BlacklistManager.matchesBlacklist(realOcrDestination, listOf(saoMarcos))
        assertNotNull("Destino deve casar também com o termo 'São Marcos'", item)
        assertEquals(3L, item?.id)
    }

    @Test
    fun realBugCase_fullAddressWithCityAndCep_matches() {
        val item = BlacklistManager.matchesBlacklist(realFullDestination, listOf(saoMarcosJoinville))
        assertNotNull("Endereço completo (com Joinville - SC e CEP) deve casar", item)
        assertEquals(2L, item?.id)
    }

    @Test
    fun normalizeTextIsPreservedForRealStrings() {
        assertEquals("sao marcos joinville", BlacklistManager.normalizeText("São Marcos Joinville"))
        assertEquals(
            "rua maria manes 141 sao marcos",
            BlacklistManager.normalizeText(realOcrDestination)
        )
    }

    // ----------------------------------------------------------------
    // 2. Bloqueio real via evaluateOffer (embarque e destino)
    // ----------------------------------------------------------------

    private fun baseOffer(pickup: String?, destination: String?) = RideOffer(
        platform = "99",
        price = 18.5,
        distanceKm = 7.2,
        durationMinutes = 15,
        ratePerKm = 2.57,
        ratePerHour = 74.0,
        pickupAddress = pickup,
        destinationAddress = destination
    )

    @Test
    fun evaluateOffer_blocksBlacklistedDestination() {
        val evaluated = BlacklistManager.evaluateOffer(
            baseOffer("Avenida Aluísio Pires Condeixa, Saguaçu, Joinville - SC", realOcrDestination),
            listOf(saoMarcosJoinville)
        )
        assertTrue("Destino em São Marcos deve bloquear a oferta", evaluated.isBlacklistBlocked)
        assertEquals("⛔ Destino em Lista Negra: São Marcos Joinville", evaluated.blacklistAlert)
    }

    @Test
    fun evaluateOffer_blocksBlacklistedPickup() {
        val evaluated = BlacklistManager.evaluateOffer(
            baseOffer(realOcrDestination, "Rua Blumenau, 200, Floresta, Joinville - SC"),
            listOf(saoMarcosJoinville)
        )
        assertTrue("Embarque em São Marcos deve bloquear a oferta", evaluated.isBlacklistBlocked)
        assertEquals("⛔ Embarque em Lista Negra: São Marcos Joinville", evaluated.blacklistAlert)
    }

    @Test
    fun evaluateOffer_doesNotBlockUnrelatedOffer() {
        val evaluated = BlacklistManager.evaluateOffer(
            baseOffer(
                "Rua Blumenau, 200, Floresta, Joinville - SC",
                "Avenida Aluísio Pires Condeixa, Saguaçu, Joinville - SC"
            ),
            listOf(saoMarcosJoinville, centroAraquari, vilaNovaJoinville)
        )
        assertFalse("Oferta sem bairro bloqueado não deve ser bloqueada", evaluated.isBlacklistBlocked)
        assertNull(evaluated.blacklistAlert)
    }

    // ----------------------------------------------------------------
    // 3. Falsos positivos que NÃO podem acontecer
    // ----------------------------------------------------------------

    @Test
    fun streetNamedSantaCatarina_doesNotMatchSaoMarcos() {
        assertNull(
            "Rua Santa Catarina (Floresta) não pode casar com 'São Marcos Joinville'",
            BlacklistManager.matchesBlacklist(
                "Rua Santa Catarina, 403, Floresta",
                listOf(saoMarcosJoinville)
            )
        )
    }

    @Test
    fun differentNeighborhoodSameCity_doesNotMatchCentroAraquari() {
        assertNull(
            "'Avenida Aluísio Pires Condeixa, Saguaçu, Joinville - SC' não pode casar com 'Centro Araquari'",
            BlacklistManager.matchesBlacklist(
                "Avenida Aluísio Pires Condeixa, Saguaçu, Joinville - SC",
                listOf(centroAraquari)
            )
        )
    }

    @Test
    fun streetNamedRuaNova_doesNotMatchVilaNova() {
        assertNull(
            "'Rua Nova, Centro, Joinville' não pode casar com 'Vila Nova Joinville' só por causa de 'nova'",
            BlacklistManager.matchesBlacklist(
                "Rua Nova, Centro, Joinville",
                listOf(vilaNovaJoinville)
            )
        )
        // Controle positivo: o bairro real continua casando
        assertNotNull(
            "'Rua XV de Novembro, Vila Nova, Joinville' deve casar",
            BlacklistManager.matchesBlacklist(
                "Rua XV de Novembro, Vila Nova, Joinville",
                listOf(vilaNovaJoinville)
            )
        )
    }

    @Test
    fun shortGenericTokensDoNotMatch() {
        val belaVista = BlacklistItem(
            id = 6L,
            rawTerm = "Rua Bela Vista Joinville",
            normalizedTerm = BlacklistManager.normalizeText("Rua Bela Vista Joinville")
        )
        // Só "bela" aparece: "vista" falta -> não casa (nada de match por token curto/genérico)
        assertNull(
            "Endereço com apenas um dos tokens distintivos não pode casar",
            BlacklistManager.matchesBlacklist("Rua Bela, Floresta, Joinville", listOf(belaVista))
        )
        assertNull(
            "Endereço só com logradouro/cidade não pode casar",
            BlacklistManager.matchesBlacklist("Rua Bela Vista, Floresta, Joinville, SC", listOf(saoMarcosJoinville))
        )
    }

    @Test
    fun termWithOnlyGenericTokensRequiresTextMatch() {
        val onlyCity = BlacklistItem(
            id = 7L,
            rawTerm = "Joinville",
            normalizedTerm = BlacklistManager.normalizeText("Joinville")
        )
        // Sem token significativo -> só regra (a)/(c): precisa conter "joinville"
        assertNotNull(
            BlacklistManager.matchesBlacklist(realFullDestination, listOf(onlyCity))
        )
        assertNull(
            "Endereço sem o termo genérico não deve casar",
            BlacklistManager.matchesBlacklist(realOcrDestination, listOf(onlyCity))
        )

        val centro = BlacklistItem(
            id = 8L,
            rawTerm = "Centro",
            normalizedTerm = BlacklistManager.normalizeText("Centro")
        )
        val centroMatch = BlacklistManager.matchesBlacklist("Rua Blumenau, 200, Centro", listOf(centro))
        assertNotNull("'Centro' deve casar com endereço que contém Centro", centroMatch)
        assertEquals("Centro", centroMatch?.rawTerm)
        assertNull("'Centro' não deve casar com Floresta", BlacklistManager.matchesBlacklist("Rua Blumenau, 200, Floresta", listOf(centro)))
    }

    // ----------------------------------------------------------------
    // 4. Acentos, caixa, pontuação e ordem
    // ----------------------------------------------------------------

    @Test
    fun accentsAndCaseAreIrrelevant() {
        assertEquals(
            "sao marcos joinville",
            BlacklistManager.normalizeText("  SÃO   MARCOS   JOINVILLE  ")
        )
        assertNotNull(
            BlacklistManager.matchesBlacklist("RUA MARIA MANES, 141, SAO MARCOS", listOf(saoMarcosJoinville))
        )
        assertNotNull(
            BlacklistManager.matchesBlacklist("Rua Maria Manes, 141, São Marcos".uppercase(), listOf(saoMarcos))
        )
        assertNotNull(
            BlacklistManager.matchesBlacklist("rua maria manes 141 sao marcos", listOf(saoMarcosJoinville))
        )
    }

    @Test
    fun punctuationAndSeparatorVariationsMatch() {
        val variants = listOf(
            "Rua Maria Manes, 141, São Marcos",
            "Rua Maria Manes, nº 141 - São Marcos / Joinville - SC",
            "Rua Maria Manes... (São Marcos)!!!",
            "RUA MARIA MANES 141 – SÃO MARCOS",
            "Rua Maria Manes, 141, Sao Marcos, Joinville/SC, 89214-060"
        )
        for (variant in variants) {
            assertNotNull(
                "Variação '$variant' deveria casar com 'São Marcos Joinville'",
                BlacklistManager.matchesBlacklist(variant, listOf(saoMarcosJoinville))
            )
        }
    }

    @Test
    fun addressFragmentMatchesTerm() {
        // Regra (c): endereço é um fragmento contíguo e distintivo do termo
        assertNotNull(
            "'São Marcos' é fragmento de 'São Marcos Joinville'",
            BlacklistManager.matchesBlacklist("sao marcos", listOf(saoMarcosJoinville))
        )
        assertNotNull(
            "'Marcos Joinville' é fragmento de 'São Marcos Joinville'",
            BlacklistManager.matchesBlacklist("Marcos Joinville", listOf(saoMarcosJoinville))
        )
        // Fragmento só com tokens genéricos não pode casar
        assertNull(
            "'Rua Joinville' não pode casar com 'São Marcos Joinville'",
            BlacklistManager.matchesBlacklist("Rua Joinville", listOf(saoMarcosJoinville))
        )
    }

    // ----------------------------------------------------------------
    // 5. Geolocalização e múltiplos itens
    // ----------------------------------------------------------------

    @Test
    fun geoProximityStillMatchesWithin1500Meters() {
        val geoItem = BlacklistItem(
            id = 9L,
            rawTerm = "Ponto Geo Teste",
            normalizedTerm = BlacklistManager.normalizeText("Ponto Geo Teste"),
            latitude = -26.3500,
            longitude = -48.8300
        )
        assertNotNull(
            "Ponto a ~0.6 km deve casar por GPS",
            BlacklistManager.matchesBlacklist("Rua Sem Nome Aleatoria", listOf(geoItem), Pair(-26.3550, -48.8320))
        )
        assertNull(
            "Ponto a ~7.5 km não deve casar por GPS",
            BlacklistManager.matchesBlacklist("Rua Sem Nome Aleatoria", listOf(geoItem), Pair(-26.4100, -48.8600))
        )
    }

    @Test
    fun returnsTheSpecificMatchingItemFromMultipleEntries() {
        val items = listOf(centroAraquari, vilaNovaJoinville, saoMarcosJoinville)
        val item = BlacklistManager.matchesBlacklist(realOcrDestination, items)
        assertNotNull(item)
        assertEquals("Deve devolver o item específico do bairro do destino", 2L, item?.id)
        assertEquals("São Marcos Joinville", item?.rawTerm)

        val centroJoinville = BlacklistManager.matchesBlacklist("Rua Nova, Centro, Joinville", items)
        assertNull(
            "Centro de Joinville NÃO pode casar com 'Centro Araquari' (cidade diferente)",
            centroJoinville
        )
        assertNotNull(
            "Centro de Araquari deve casar com 'Centro Araquari'",
            BlacklistManager.matchesBlacklist("Rua XV de Novembro, Centro, Araquari - SC", items)
        )
    }

    // ----------------------------------------------------------------
    // 6. Diagnóstico (motivoDoBloqueio / findMatch)
    // ----------------------------------------------------------------

    @Test
    fun motivoDoBloqueioExplainsTheRule() {
        val motivo = BlacklistManager.motivoDoBloqueio(realOcrDestination, listOf(saoMarcosJoinville))
        assertNotNull("Deve haver motivo para o caso real", motivo)
        assertTrue("Motivo deve citar o termo", motivo!!.contains("São Marcos Joinville"))
        assertTrue("Motivo deve citar a regra por tokens", motivo.contains("TOKENS"))

        val match = BlacklistManager.findMatch(realOcrDestination, listOf(saoMarcosJoinville))
        assertEquals(BlacklistMatchRule.TOKENS, match?.regra)

        // Endereço completo casa pela regra textual
        assertEquals(
            BlacklistMatchRule.TEXTO,
            BlacklistManager.findMatch(realFullDestination, listOf(saoMarcosJoinville))?.regra
        )

        // Nada casando -> null
        assertNull(
            BlacklistManager.motivoDoBloqueio(
                "Avenida Aluísio Pires Condeixa, Saguaçu, Joinville - SC",
                listOf(centroAraquari)
            )
        )
        assertNull(BlacklistManager.findMatch(null, listOf(saoMarcosJoinville)))
        assertNull(BlacklistManager.findMatch("   ", listOf(saoMarcosJoinville)))
        assertNull(BlacklistManager.findMatch(realOcrDestination, emptyList()))
        assertNull(
            BlacklistManager.findMatch(
                realOcrDestination,
                listOf(BlacklistItem(id = 99L, rawTerm = "   ", normalizedTerm = ""))
            )
        )
    }
}
