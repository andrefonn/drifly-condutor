package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceRulesTest {

    private val t0 = 1_000_000L

    @Test
    fun clickWithFreshPendingCommits() {
        assertTrue(AcceptanceRules.canCommitOnAcceptClick(true, t0, t0 + 10_000))
    }

    @Test
    fun clickWithoutPendingNeverCommits() {
        assertFalse(AcceptanceRules.canCommitOnAcceptClick(false, t0, t0 + 1_000))
    }

    @Test
    fun staleOfferNeverCommits() {
        assertFalse(AcceptanceRules.canCommitOnAcceptClick(true, t0, t0 + 46_000))
        assertFalse(AcceptanceRules.canCommitOnActiveRideScreen(true, t0, t0 + 46_000, false))
    }

    @Test
    fun activeRideScreenProvesAcceptWhenOfferWasNotDuringRide() {
        assertTrue(AcceptanceRules.canCommitOnActiveRideScreen(true, t0, t0 + 5_000, false))
    }

    @Test
    fun activeRideScreenDoesNotProveAcceptWhenOfferArrivedDuringRide() {
        assertFalse(AcceptanceRules.canCommitOnActiveRideScreen(true, t0, t0 + 5_000, true))
    }

    @Test
    fun activeRideScreenWithoutPendingNeverCommits() {
        assertFalse(AcceptanceRules.canCommitOnActiveRideScreen(false, t0, t0 + 5_000, false))
    }

    @Test
    fun consecutiveOfferOnlyCommitsAfterRealPickupTransition() {
        val t = 100_000L
        assertTrue(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = t,
                now = t + 30_000,
                offerArrivedDuringRide = true,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = t - 120_000,
                samePlatform = true,
                isPickupPhase = true,
                hudMatchesPendingOffer = true
            )
        )
        // HUD da corrida anterior (sem fase de embarque) nunca confirma
        assertFalse(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = t,
                now = t + 10_000,
                offerArrivedDuringRide = true,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = t - 120_000,
                samePlatform = true,
                isPickupPhase = false,
                hudMatchesPendingOffer = true
            )
        )
        // Oferta que não chegou durante corrida usa a regra normal, não a consecutiva
        assertFalse(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = t,
                now = t + 10_000,
                offerArrivedDuringRide = false,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = t - 120_000,
                samePlatform = true,
                isPickupPhase = true,
                hudMatchesPendingOffer = true
            )
        )
    }

    @Test
    fun classifyPostTapVerificationRejectsOfferIdleErrorsAndEmptyFinalAttempt() {
        val sampleTexts = listOf("Moto", "R$ 7,90", "3,2 km", "8 min")

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = sampleTexts,
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                stillSameOffer = true,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Buscando", "Recompensas", "Moto", "99"),
                offerPrice = 7.90,
                isIdleScreen = true,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Corrida aceita por outro motorista", "OK", "99", "Moto"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Histórico de corridas", "Ganhos", "Hoje", "R$ 21,00"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = true,
                isActiveRide = false,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.RETRY,
            AcceptanceRules.classifyPostTapVerification(
                texts = null,
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0,
                maxAttempts = 2
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.FAILED,
            AcceptanceRules.classifyPostTapVerification(
                texts = null,
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 2,
                maxAttempts = 2
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.RETRY,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Carregando...", "99", "Moto", "14:18"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0,
                maxAttempts = 2
            )
        )
    }

    @Test
    fun classifyPostTapVerificationConfirmsActiveRideOrGpsTransition() {
        assertEquals(
            AcceptanceRules.PostTapOutcome.CONFIRMED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("Cheguei", "R. Jacob Richlin", "12 km/h", "Ajustes"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = true,
                attempt = 0
            )
        )

        assertEquals(
            AcceptanceRules.PostTapOutcome.CONFIRMED,
            AcceptanceRules.classifyPostTapVerification(
                texts = listOf("120 m", "Rua Corupá, 386", "0 km/h", "Chegada prevista: 14:25"),
                offerPrice = 7.90,
                isIdleScreen = false,
                isEarningsPanel = false,
                isActiveRide = false,
                attempt = 0
            )
        )
    }

    @Test
    fun doesGpsHudMatchPendingOfferOnlyWhenOutsideDemandNavAndWithSpeedCue() {
        val pendingWithPickup = RideOffer(
            platform = "99",
            price = 7.90,
            distanceKm = 3.2,
            durationMinutes = 8,
            ratePerKm = 2.47,
            pickupAddress = "Rua Corupá, 386 - Itaum",
            destinationAddress = "Rua Guanabara, 910"
        )
        val hudTexts = listOf("109", "m", "Rua Corupá, 386 - Itaum", "Ir", "0", "km/h")

        assertTrue(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = hudTexts,
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = false
            )
        )

        assertFalse(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = hudTexts,
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = true
            )
        )

        assertFalse(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = listOf("109", "m", "Rua Corupá, 386 - Itaum", "Ir"),
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = false
            )
        )

        assertFalse(
            AcceptanceRules.doesGpsHudMatchPendingOffer(
                texts = listOf("Buscando", "Rua Corupá, 386", "0", "km/h"),
                pendingOffer = pendingWithPickup,
                hasRecentDemandNavigation = false
            )
        )
    }

    @Test
    fun isGpsHudTransitionFromFreshOfferConfirmsIntermediateStreetOnlyWhenOfferWasIdle() {
        val pendingOffer = RideOffer(
            platform = "99",
            price = 6.00,
            distanceKm = 2.4,
            durationMinutes = 7,
            ratePerKm = 2.50,
            pickupAddress = "Rua Corupá, 386",
            destinationAddress = "Rua Guanabara, 910"
        )
        // HUD da 99 mostrando rua intermediária da manobra ("R. Iririú") logo após aceitar oferta no "Buscando"
        val intermediateStreetHud = listOf(
            "11:26 9 99", "28", "km/h", "450 m", "R. Iririú", "4 min 1,8 km", "• Chegada prevista: 11:30"
        )

        assertTrue(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = false,
                offerArrivedDuringFreeGps = false,
                hasRecentDemandNavigation = false
            )
        )

        // Nunca confirma se a oferta chegou durante outra corrida em andamento
        assertFalse(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = true,
                offerArrivedDuringFreeGps = false,
                hasRecentDemandNavigation = false
            )
        )

        // Nunca confirma se o motorista já estava em navegação GPS livre antes da oferta
        assertFalse(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = false,
                offerArrivedDuringFreeGps = true,
                hasRecentDemandNavigation = false
            )
        )

        // Nunca confirma se havia navegação recente para zona de demanda
        assertFalse(
            AcceptanceRules.isGpsHudTransitionFromFreshOffer(
                texts = intermediateStreetHud,
                pendingOffer = pendingOffer,
                offerArrivedDuringRide = false,
                offerArrivedDuringFreeGps = false,
                hasRecentDemandNavigation = true
            )
        )
    }

    private fun offerForAutomation(
        ratePerKm: Double = 3.00,
        distanceKm: Double = 5.0,
        rating: Float? = 4.90f,
        blacklistBlocked: Boolean = false,
        homeDeltaKm: Double = -1.0
    ) = RideOffer(
        platform = "99",
        price = ratePerKm * distanceKm,
        distanceKm = distanceKm,
        durationMinutes = 12,
        ratePerKm = ratePerKm,
        ratePerHour = 40.0,
        passengerRating = rating,
        destinationAddress = "Rua Teste, 100",
        isBlacklistBlocked = blacklistBlocked,
        isHeadingHome = homeDeltaKm <= 0.0,
        homeDistanceDeltaKm = homeDeltaKm
    )

    private fun decide(
        offer: RideOffer,
        minRate: Double = 2.20,
        maxDistance: Double = 12.0,
        minRating: Float = 4.75f,
        modoCasa: Boolean = false,
        accept: Boolean = true,
        decline: Boolean = true
    ) = AcceptanceRules.decideAutomation(
        offer = offer,
        minRatePerKm = minRate,
        maxDistanceKm = maxDistance,
        minPassengerRating = minRating,
        modoCasaActive = modoCasa,
        autoAcceptEnabled = accept,
        autoDeclineEnabled = decline
    )

    @Test
    fun uncertaintyBlocksBothAutoAcceptAndAutoDecline() {
        val incertaPorDistancia = offerForAutomation().copy(isDistanceUncertain = true)
        val incertaPorModoCasa = offerForAutomation().copy(isModoCasaUncertain = true)

        assertEquals(AcceptanceRules.AutoAction.IGNORE, decide(incertaPorDistancia))
        assertEquals(AcceptanceRules.AutoAction.IGNORE, decide(incertaPorModoCasa))
        assertFalse(
            AcceptanceRules.isEligible(incertaPorDistancia, 2.20, 12.0, 4.75f, false)
        )
        assertFalse(
            AcceptanceRules.isEligible(incertaPorModoCasa, 2.20, 12.0, 4.75f, false)
        )
    }

    @Test
    fun automationAcceptsOnlyWhenEveryRideSettingPasses() {
        assertEquals(AcceptanceRules.AutoAction.ACCEPT, decide(offerForAutomation()))
    }

    @Test
    fun automationDeclinesWhenRateIsBelowTheConfiguredMinimum() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(ratePerKm = 1.50))
        )
    }

    @Test
    fun automationDeclinesWhenDistanceIsAboveTheConfiguredMaximum() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(distanceKm = 13.0))
        )
    }

    @Test
    fun automationDeclinesLowRatedPassengerInsteadOfReturningEarly() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(rating = 4.20f))
        )
    }

    @Test
    fun automationDeclinesBlacklistedOfferEvenWhenMetricsAreGood() {
        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(offerForAutomation(blacklistBlocked = true))
        )
    }

    @Test
    fun automationHonorsModoCasaWhenActive() {
        val awayFromHome = offerForAutomation(homeDeltaKm = 2.5)
        val towardsHome = offerForAutomation(homeDeltaKm = -2.0)

        assertEquals(
            AcceptanceRules.AutoAction.DECLINE,
            decide(awayFromHome, modoCasa = true)
        )
        assertEquals(
            AcceptanceRules.AutoAction.ACCEPT,
            decide(towardsHome, modoCasa = true)
        )
        // Fora do Modo Casa a distância de casa não interfere
        assertEquals(
            AcceptanceRules.AutoAction.ACCEPT,
            decide(awayFromHome, modoCasa = false)
        )
    }

    @Test
    fun automationDoesNothingWhenTheRelevantSwitchIsOff() {
        assertEquals(
            AcceptanceRules.AutoAction.IGNORE,
            decide(offerForAutomation(ratePerKm = 1.50), decline = false)
        )
        assertEquals(
            AcceptanceRules.AutoAction.IGNORE,
            decide(offerForAutomation(), accept = false, decline = true)
        )
        assertEquals(
            AcceptanceRules.AutoAction.IGNORE,
            decide(offerForAutomation(), accept = false, decline = false)
        )
    }

    @Test
    fun automationDoesNotDeclineUnknownPassengerRating() {
        assertEquals(
            AcceptanceRules.AutoAction.ACCEPT,
            decide(offerForAutomation(rating = null))
        )
    }

    @Test
    fun declineControlRecognitionCovers99XAndMergedBadge() {
        assertTrue(ParsingEngine.isExactDeclineGlyph("X"))
        assertTrue(ParsingEngine.isExactDeclineGlyph("×"))
        assertTrue(ParsingEngine.isDeclineGlyphMergedWithBadge("X Não afeta a TA"))
        assertTrue(ParsingEngine.isDeclineActionText("Recusar"))
        assertTrue(ParsingEngine.isDeclineButtonText("Recusar"))
        assertTrue(ParsingEngine.isDeclineButtonText("Recusar corrida"))
        assertFalse(ParsingEngine.isDeclineButtonText("X"))
        assertFalse(ParsingEngine.isDeclineButtonText("Fechar"))
        assertFalse(ParsingEngine.isExactDeclineGlyph("X Não afeta a TA"))
        assertFalse(ParsingEngine.isDeclineActionText("Recusar novas corridas"))
    }

    // --- Elegibilidade: overlay e automação precisam usar a MESMA regra ---

    @Test
    fun elegibilidadeIgnoraRatePorHoraBaixo() {
        // Corrida longa em minutos derruba o R$/h, mas rate/km, distância e nota estão ok.
        // O overlay dizia "RECUSAR" (exigia R$/h >= 30) enquanto a automação aceitava.
        val longa = offerForAutomation(ratePerKm = 1.35, distanceKm = 8.4f.toDouble()).copy(
            durationMinutes = 42,
            ratePerHour = 5.1 / (42.0 / 60.0)
        )
        assertTrue(
            AcceptanceRules.isEligible(
                offer = longa,
                minRatePerKm = 1.20,
                maxDistanceKm = 9.0,
                minPassengerRating = 4.89f,
                modoCasaActive = false
            )
        )
        assertEquals(AcceptanceRules.AutoAction.ACCEPT, decide(longa, minRate = 1.20, maxDistance = 9.0, minRating = 4.89f))
    }

    @Test
    fun elegibilidadeRespeitaDistanciaMaxima() {
        // Overlay antigo não olhava distância: mostrava ACEITAR e a automação recusava.
        val longe = offerForAutomation(ratePerKm = 3.0, distanceKm = 14.0)
        assertFalse(
            AcceptanceRules.isEligible(
                offer = longe,
                minRatePerKm = 1.20,
                maxDistanceKm = 9.0,
                minPassengerRating = 4.75f,
                modoCasaActive = false
            )
        )
        assertEquals(AcceptanceRules.AutoAction.DECLINE, decide(longe, minRate = 1.20, maxDistance = 9.0))
    }

    @Test
    fun elegibilidadeRespeitaNotaMinimaDaConfiguracao() {
        val notaBaixa = offerForAutomation(rating = 4.80f)
        assertFalse(
            AcceptanceRules.isEligible(
                offer = notaBaixa,
                minRatePerKm = 2.20,
                maxDistanceKm = 12.0,
                minPassengerRating = 4.89f,
                modoCasaActive = false
            )
        )
        assertTrue(
            AcceptanceRules.isEligible(
                offer = notaBaixa,
                minRatePerKm = 2.20,
                maxDistanceKm = 12.0,
                minPassengerRating = 4.75f,
                modoCasaActive = false
            )
        )
    }

    @Test
    fun elegibilidadeModoCasaExigeNaoAfastarDeCasa() {
        val afasta = offerForAutomation(homeDeltaKm = 4.0)
        assertFalse(
            AcceptanceRules.isEligible(afasta, minRatePerKm = 2.20, maxDistanceKm = 12.0, minPassengerRating = 4.75f, modoCasaActive = true)
        )
        assertTrue(
            AcceptanceRules.isEligible(afasta, minRatePerKm = 2.20, maxDistanceKm = 12.0, minPassengerRating = 4.75f, modoCasaActive = false)
        )
    }

    @Test
    fun elegibilidadeBloqueiaListaNegra() {
        val bloqueada = offerForAutomation(blacklistBlocked = true)
        assertFalse(
            AcceptanceRules.isEligible(bloqueada, minRatePerKm = 2.20, maxDistanceKm = 12.0, minPassengerRating = 4.75f, modoCasaActive = false)
        )
    }

    @Test
    fun reciboDeOutraCorridaNaoFechaEsta() {
        // Caso real de 21/11: corrida de R$ 5,10 recebeu o recibo de R$ 8,91 da anterior.
        assertFalse(ParsingEngine.isPlausibleFinalFare(8.91, 5.10))
        assertTrue(ParsingEngine.isPlausibleFinalFare(8.91, 8.90))
        assertTrue(ParsingEngine.isPlausibleFinalFare(5.10, 5.10))
        assertFalse(ParsingEngine.isPlausibleFinalFare(0.0, 5.10))
    }

    // ---------------------------------------------------------------------------------------------
    // BUG A (falso aceite fantasma #259, 10:14:05): o HUD da corrida ANTERIOR não prova o aceite da
    // oferta consecutiva. Reproduz exatamente os textos do log de campo.
    // ---------------------------------------------------------------------------------------------

    private fun ofertaFantasma259() = RideOffer(
        platform = "99",
        price = 5.10,
        distanceKm = 1.2,
        durationMinutes = 2,
        ratePerKm = 4.25,
        pickupAddress = "Rua Sen. Rodrigo Lobo, 1762, Jardim Iririú",
        destinationAddress = "Rua Rocha Pombo, 1115, Jardim Iririú"
    )

    @Test
    fun hudDaCorridaAnteriorNaoProvaAceiteDaOfertaConsecutiva() {
        // TEXTS reais de 10:14:05 (navegação da #258, destino "Odontologia Dra.Gelly ... R. Frontin").
        val hudDaCorridaAnterior = listOf(
            "Corrida aceita",
            "R. Cel. Vieira",
            "41",
            "km/h",
            "213",
            "m",
            "R. das Cegonhas",
            "R. Frontin",
            "Odontologia Dra.Gelly Betina S.Tomaselli, em frente a Joci Modas - R. Frontin, 1797 - Sl 03 - Jardim"
        )
        val rastroAnterior = listOf(
            "R. Frontin",
            "Odontologia Dra.Gelly Betina S.Tomaselli, em frente a Joci Modas - R. Frontin, 1797 - Sl 03 - Jardim"
        )
        val offer = ofertaFantasma259()

        assertFalse(
            "HUD da corrida anterior nunca pode provar o aceite da nova oferta",
            AcceptanceRules.doesHudBelongToPendingOffer(
                texts = hudDaCorridaAnterior,
                pendingOffer = offer,
                previousRideHudTexts = rastroAnterior
            )
        )

        val t = 10_000_000L
        assertFalse(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = t,
                now = t + 3_000,
                offerArrivedDuringRide = true,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = t - 600_000,
                samePlatform = true,
                isPickupPhase = true,
                hudMatchesPendingOffer = AcceptanceRules.doesHudBelongToPendingOffer(
                    texts = hudDaCorridaAnterior,
                    pendingOffer = offer,
                    previousRideHudTexts = rastroAnterior
                )
            )
        )
    }

    @Test
    fun hudDaNovaOfertaConfirmaAceiteConsecutivo() {
        // TEXTS reais da oferta consecutiva de 10:15:28 (#260: Rua Tabatinga -> Rua Iririú).
        val offer = RideOffer(
            platform = "99",
            price = 5.10,
            distanceKm = 2.82,
            durationMinutes = 94,
            ratePerKm = 1.81,
            pickupAddress = "Rua Tabatinga, 2077, Iririú",
            destinationAddress = "Rua Iririú, 3220, Iririú"
        )
        // HUD já navegando para a NOVA oferta (o mapa ainda mostra POIs da corrida anterior).
        val hudDaNovaOferta = listOf(
            "Corrida aceita",
            "R. Tabatinga",
            "43",
            "km/h",
            "596",
            "m",
            "Rua Tabatinga, 2077, Iririú",
            "R. das Cegonhas"
        )
        val rastroAnterior = listOf(
            "R. Frontin",
            "Odontologia Dra.Gelly Betina S.Tomaselli, em frente a Joci Modas - R. Frontin, 1797 - Sl 03 - Jardim"
        )

        assertTrue(
            AcceptanceRules.doesHudBelongToPendingOffer(
                texts = hudDaNovaOferta,
                pendingOffer = offer,
                previousRideHudTexts = rastroAnterior
            )
        )
        // HUD da nova oferta vence mesmo quando o mapa ainda exibe o POI da corrida anterior.
        val hudComPoiAnterior = hudDaNovaOferta + rastroAnterior
        assertTrue(
            AcceptanceRules.doesHudBelongToPendingOffer(
                texts = hudComPoiAnterior,
                pendingOffer = offer,
                previousRideHudTexts = rastroAnterior
            )
        )
        assertTrue(
            AcceptanceRules.canCommitOnConsecutivePickupTransition(
                hasPending = true,
                pendingTimestamp = 10_000_000L,
                now = 10_000_000L + 30_000,
                offerArrivedDuringRide = true,
                activeRideSeenDropoffPhase = true,
                lastAcceptedTimestamp = 10_000_000L - 600_000,
                samePlatform = true,
                isPickupPhase = ParsingEngine.isPickupPhaseScreen(hudDaNovaOferta),
                hudMatchesPendingOffer = AcceptanceRules.doesHudBelongToPendingOffer(
                    texts = hudDaNovaOferta,
                    pendingOffer = offer,
                    previousRideHudTexts = rastroAnterior
                )
            )
        )
    }

    @Test
    fun dedupNaoEscondeOfertaComMesmoPrecoEMesmaDistanciaMasRotaDiferente() {
        val primeira = RideOffer(
            platform = "99", price = 8.50, distanceKm = 3.0, durationMinutes = 12, ratePerKm = 2.83,
            pickupAddress = "Rua Blumenau, 100", destinationAddress = "Rua São Marcos, 45"
        )
        val outraRota = primeira.copy(
            pickupAddress = "Rua Blumenau, 100", destinationAddress = "Rua Guanabara, 910"
        )
        val mesmaOfertaComOcrDiferente = primeira.copy(
            destinationAddress = "R. Sao Marcos, 45"
        )

        assertTrue(AcceptanceRules.isSameOffer(primeira, primeira))
        assertTrue(
            "variação de OCR da MESMA rota continua sendo a mesma oferta",
            AcceptanceRules.isSameOffer(primeira, mesmaOfertaComOcrDiferente)
        )
        assertFalse(
            "mesma faixa de preço com OUTRA rota precisa reavaliar (recusa automática não pode ser pulada)",
            AcceptanceRules.isSameOffer(primeira, outraRota)
        )
        assertFalse(AcceptanceRules.isSameOffer(null, primeira))
    }
}
