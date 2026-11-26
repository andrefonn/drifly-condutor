package com.drifly.condutor

/**
 * Regras puras (sem Android) que decidem se uma corrida pode entrar no Faturamento como ACEITA.
 *
 * Princípio: só vira corrida aceita quando existe EVIDÊNCIA de aceite. Oferta que apenas apareceu,
 * expirou, foi recusada ou foi ignorada NUNCA pode ser promovida.
 *
 * Evidências válidas:
 *  1. Clique real do motorista (ou do DriFly) num botão "Aceitar" enquanto a oferta está viva.
 *  2. Toque no overlay cujo clique/gesto foi de fato executado no app da plataforma.
 *  3. A tela mudou para corrida ativa/navegação logo após a oferta (aceite feito dentro do app),
 *     exceto quando a oferta chegou DURANTE outra corrida (a tela de navegação anterior continua lá
 *     mesmo que o motorista ignore a oferta, então ela não prova nada).
 */
object AcceptanceRules {

    /** Ação de automação decidida a partir das configurações da aba Corridas. */
    enum class AutoAction {
        ACCEPT,
        DECLINE,
        IGNORE
    }

    /**
     * Decide aceitar/recusar usando exatamente os critérios configurados pelo motorista:
     * R$/km mínimo, distância máxima, nota mínima, Modo Casa e Lista Negra.
     *
     * Regra: só aceita quando TODOS os critérios passam; só recusa quando algum falha.
     * Se a automação correspondente estiver desligada, não faz nada (IGNORE).
     */
    /**
     * Elegibilidade da oferta pelas regras do motorista — FONTE ÚNICA usada pelo overlay
     * (rótulo/cor/tempo) e pela automação (aceitar/recusar). Antes o overlay tinha regra
     * própria (R$/h fixo de 30 e sem limite de distância) e por isso mostrava "RECUSAR"
     * em corrida que a automação aceitava (e vice-versa).
     */
    fun isEligible(
        offer: RideOffer,
        minRatePerKm: Double,
        maxDistanceKm: Double,
        minPassengerRating: Float,
        modoCasaActive: Boolean
    ): Boolean {
        // Dado incerto (km de trecho ilegível / direção de casa não medida) não pode virar ACEITAR.
        if (isUncertain(offer)) return false
        val withinRate = offer.ratePerKm >= minRatePerKm
        val withinDistance = offer.distanceKm <= maxDistanceKm
        val ratingOk = offer.passengerRating == null || offer.passengerRating >= minPassengerRating
        // Modo Casa usa a mesma regra do resto do app: só é elegível se não aumenta a distância de casa.
        val withinModoCasa = !modoCasaActive || offer.homeDistanceDeltaKm <= 0.0
        return !offer.isBlacklistBlocked && withinRate && withinDistance && ratingOk && withinModoCasa
    }

    /** Oferta com dado que o app não conseguiu medir: o motorista decide, a automação não age. */
    fun isUncertain(offer: RideOffer): Boolean =
        offer.isDistanceUncertain || offer.isModoCasaUncertain

    fun decideAutomation(
        offer: RideOffer,
        minRatePerKm: Double,
        maxDistanceKm: Double,
        minPassengerRating: Float,
        modoCasaActive: Boolean,
        autoAcceptEnabled: Boolean,
        autoDeclineEnabled: Boolean
    ): AutoAction {
        // Dado incerto: não aceita e também não recusa sozinho (uma boa corrida não pode ser
        // perdida por causa de um km ilegível). O overlay fica na tela para o motorista decidir.
        if (isUncertain(offer)) return AutoAction.IGNORE

        val eligible = isEligible(
            offer = offer,
            minRatePerKm = minRatePerKm,
            maxDistanceKm = maxDistanceKm,
            minPassengerRating = minPassengerRating,
            modoCasaActive = modoCasaActive
        )

        return when {
            eligible && autoAcceptEnabled -> AutoAction.ACCEPT
            !eligible && autoDeclineEnabled -> AutoAction.DECLINE
            else -> AutoAction.IGNORE
        }
    }

    /** Tempo máximo que uma oferta pendente continua elegível a virar aceita. */
    const val PENDING_OFFER_VALIDITY_MS: Long = 45_000L

    fun isPendingStillValid(pendingTimestamp: Long, now: Long): Boolean =
        (now - pendingTimestamp) in 0..PENDING_OFFER_VALIDITY_MS

    /** Clique no botão Aceitar (real ou disparado pelo DriFly e confirmado pelo sistema). */
    fun canCommitOnAcceptClick(hasPending: Boolean, pendingTimestamp: Long, now: Long): Boolean =
        hasPending && isPendingStillValid(pendingTimestamp, now)

    /**
     * Mesma oferta já pendente?
     *
     * Preço e distância iguais NÃO bastam: a 99 reenvia a mesma faixa de preço com OUTRA rota, e nesse
     * caso a automação precisa rodar de novo (foi assim que uma oferta numa rua bloqueada deixou de ser
     * recusada automaticamente). Se as duas ofertas têm endereço e eles não compartilham nenhuma palavra
     * distintiva, é outra corrida.
     */
    fun isSameOffer(previous: RideOffer?, candidate: RideOffer): Boolean {
        if (previous == null) return false
        if (previous.platform != candidate.platform) return false
        if (Math.abs(previous.price - candidate.price) >= 0.01) return false
        if (Math.abs(previous.distanceKm - candidate.distanceKm) >= 0.1) return false
        return isRouteCompatible(previous, candidate)
    }

    private fun isRouteCompatible(a: RideOffer, b: RideOffer): Boolean {
        val pairs = listOf(a.pickupAddress to b.pickupAddress, a.destinationAddress to b.destinationAddress)
        for ((left, right) in pairs) {
            if (left.isNullOrBlank() || right.isNullOrBlank()) continue
            if (!sharesDistinctiveToken(left, right)) return false
        }
        // Sem endereços comparáveis não dá para distinguir as ofertas: mantém o comportamento antigo.
        return true
    }

    private fun sharesDistinctiveToken(left: String, right: String): Boolean {
        val leftTokens = distinctiveTokens(left)
        val rightTokens = distinctiveTokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return true
        return leftTokens.any { it in rightTokens }
    }

    private fun distinctiveTokens(raw: String): Set<String> =
        normalizeText(raw)
            .split(' ')
            .filter { it.length >= 4 && it !in ADDRESS_STOPWORDS }
            .toSet()

    /**
     * Transição para a tela de corrida ativa.
     * [offerArrivedDuringRide] = a oferta apareceu enquanto o app já mostrava uma corrida em andamento.
     */
    fun canCommitOnActiveRideScreen(
        hasPending: Boolean,
        pendingTimestamp: Long,
        now: Long,
        offerArrivedDuringRide: Boolean
    ): Boolean =
        hasPending && !offerArrivedDuringRide && isPendingStillValid(pendingTimestamp, now)

    /**
     * Corrida consecutiva aceita dentro do app durante a viagem anterior: a única prova aceitável é
     * a tela entrar na FASE DE EMBARQUE da nova corrida. Endereço genérico no HUD da corrida anterior
     * NÃO prova aceite (foi o furo da 1.1.9).
     *
     * [hudMatchesPendingOffer] é a checagem de IDENTIDADE (obrigatória, nunca pode ser fixada em true):
     * o HUD visível precisa mostrar endereço/rota da NOVA oferta. Sem isso, o HUD da corrida anterior
     * (destino que o motorista já estava seguindo) provava um aceite fantasma (caso #259 de 10:14:05).
     */
    fun canCommitOnConsecutivePickupTransition(
        hasPending: Boolean,
        pendingTimestamp: Long,
        now: Long,
        offerArrivedDuringRide: Boolean,
        activeRideSeenDropoffPhase: Boolean,
        lastAcceptedTimestamp: Long,
        samePlatform: Boolean,
        isPickupPhase: Boolean,
        hudMatchesPendingOffer: Boolean
    ): Boolean {
        if (!hasPending || !offerArrivedDuringRide || !activeRideSeenDropoffPhase) return false
        if (!samePlatform || !isPickupPhase) return false
        if (!hudMatchesPendingOffer) return false
        if ((now - pendingTimestamp) !in 0..120_000L) return false
        if ((now - lastAcceptedTimestamp) < 60_000L) return false
        return true
    }

    enum class PostTapOutcome {
        CONFIRMED,
        FAILED,
        RETRY
    }

    private val POST_TAP_FAILURE_MARKERS = listOf(
        "indisponível",
        "indisponivel",
        "outro motorista",
        "expirou",
        "expirada",
        "já foi aceita",
        "ja foi aceita",
        "não está mais disponível",
        "nao esta mais disponivel",
        "algo deu errado",
        "solicitação cancelada",
        "solicitacao cancelada",
        "oferta encerrada",
        "corrida cancelada"
    )

    private val POST_TAP_LOADING_MARKERS = listOf(
        "carregando",
        "aguarde",
        "processando"
    )

    private val ADDRESS_STOPWORDS = setOf(
        "rua", "avenida", "alameda", "travessa", "estrada", "rodovia", "praca",
        "loteamento", "condominio", "residencial", "jardim", "vila", "parque",
        "bairro", "centro", "zona", "norte", "leste", "oeste", "brasil",
        "joinville", "proximo", "perto", "cidade", "estado", "sao", "santa", "santo"
    )

    /** Quantas palavras do endereço precisam casar para a identidade do HUD ser considerada forte. */
    private const val STRONG_IDENTITY_MATCHES = 2

    /**
     * Classifica a tela capturada logo após tocar/deslizar em "Aceitar".
     * Nunca promove para CONFIRMED se o card continuar na tela, se o app voltou para tela
     * ociosa ("Buscando" / "Conectar") / ganhos, se exibiu aviso de corrida indisponível ou
     * se o OCR não trouxe nenhuma evidência até a última tentativa.
     */
    fun classifyPostTapVerification(
        texts: List<String>?,
        offerPrice: Double,
        isIdleScreen: Boolean,
        isEarningsPanel: Boolean,
        isActiveRide: Boolean,
        isDemandHotspot: Boolean = false,
        stillSameOffer: Boolean = false,
        attempt: Int,
        maxAttempts: Int = 2,
        emptyThreshold: Int = 3
    ): PostTapOutcome {
        if (texts == null || texts.size <= emptyThreshold) {
            return if (attempt < maxAttempts) PostTapOutcome.RETRY else PostTapOutcome.FAILED
        }

        val joined = texts.joinToString(" ").lowercase()

        if (POST_TAP_FAILURE_MARKERS.any { joined.contains(it) }) {
            return PostTapOutcome.FAILED
        }

        if (stillSameOffer) {
            return PostTapOutcome.FAILED
        }

        if (isIdleScreen || isEarningsPanel || isDemandHotspot) {
            return PostTapOutcome.FAILED
        }

        if (texts.any { ParsingEngine.isAcceptActionText(it) }) {
            return PostTapOutcome.FAILED
        }

        if (isActiveRide) {
            return PostTapOutcome.CONFIRMED
        }

        if (texts.size <= 6 && POST_TAP_LOADING_MARKERS.any { joined.contains(it) }) {
            return if (attempt < maxAttempts) PostTapOutcome.RETRY else PostTapOutcome.FAILED
        }

        val hasGpsTripTransition = joined.contains("km/h") ||
            joined.contains("chegada prevista") ||
            joined.contains("em viagem") ||
            joined.contains("ir buscar")

        if (hasGpsTripTransition) {
            return PostTapOutcome.CONFIRMED
        }

        return if (attempt < maxAttempts) PostTapOutcome.RETRY else PostTapOutcome.FAILED
    }

    private fun normalizeText(raw: String): String {
        val decomposed = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Verifica se o HUD nativo de GPS da 99 (sem texto Flutter de "Aceitar"/"Cheguei") bate com os
     * endereços de embarque/destino da oferta pendente, confirmando transição para corrida ativa
     * desde que não seja navegação livre recente para zona de demanda.
     */
    fun doesGpsHudMatchPendingOffer(
        texts: List<String>,
        pendingOffer: RideOffer?,
        hasRecentDemandNavigation: Boolean
    ): Boolean {
        if (pendingOffer == null || hasRecentDemandNavigation || texts.isEmpty()) return false
        if (ParsingEngine.isIdleDriverScreen(texts) ||
            ParsingEngine.isEarningsPanelScreen(texts) ||
            ParsingEngine.isDemandHotspotScreen(texts)
        ) {
            return false
        }

        val joinedNorm = normalizeText(texts.joinToString(" "))
        if (!joinedNorm.contains("km h") && !joinedNorm.contains("chegada prevista")) {
            return false
        }

        val addresses = listOfNotNull(pendingOffer.pickupAddress, pendingOffer.destinationAddress)
            .filter { it.isNotBlank() }
        if (addresses.isEmpty()) return false

        val hudWords = joinedNorm.split(' ').filter { it.isNotEmpty() }.toSet()

        for (addr in addresses) {
            val tokens = normalizeText(addr)
                .split(' ')
                .filter { it.length >= 3 && it !in ADDRESS_STOPWORDS }
                .distinct()
            if (tokens.isEmpty()) continue

            val matched = tokens.filter { it in hudWords }
            val hasDistinctiveWord = matched.any { token ->
                token.length >= 5 && token.any { it.isLetter() }
            }
            if (hasDistinctiveWord || matched.size >= 2) {
                return true
            }
        }
        return false
    }

    /**
     * Quantas palavras distintivas de [address] aparecem no HUD [texts] (0 = nenhuma).
     * Usado como medida de identidade entre o HUD nativo e a oferta pendente.
     */
    fun countDistinctiveAddressMatches(texts: List<String>, address: String?): Int {
        if (texts.isEmpty() || address.isNullOrBlank()) return 0
        val hudWords = normalizeText(texts.joinToString(" "))
            .split(' ')
            .filter { it.isNotEmpty() }
            .toSet()
        return normalizeText(address)
            .split(' ')
            .filter { it.length >= 3 && it !in ADDRESS_STOPWORDS }
            .distinct()
            .count { it in hudWords }
    }

    /**
     * Identidade do HUD para confirmar um aceite de corrida CONSECUTIVA.
     *
     * Verdadeiro só quando o HUD atual (a) é um HUD de navegação válido ("km/h" / "Chegada prevista"),
     * (b) contém palavra distintiva de um dos endereços da NOVA oferta e (c) quando a identificação é
     * FRACA (só uma palavra casada) não pode casar melhor com o rastro de HUD da corrida anterior
     * ([previousRideHudTexts]).
     *
     * Em 10:14:05 o HUD mostrava "R. Frontin / Odontologia Dra.Gelly ..." (destino da corrida #258):
     * nenhuma palavra da nova oferta (Rua Sen. Rodrigo Lobo / Rua Rocha Pombo, 1115) aparecia, então
     * o aceite era fantasma. Uma oferta real casa 2+ palavras (rua + número), o que basta mesmo que o
     * mapa ainda mostre POIs/destino da corrida anterior.
     */
    fun doesHudBelongToPendingOffer(
        texts: List<String>,
        pendingOffer: RideOffer?,
        previousRideHudTexts: List<String> = emptyList(),
        hasRecentDemandNavigation: Boolean = false
    ): Boolean {
        if (pendingOffer == null || texts.isEmpty()) return false
        if (!doesGpsHudMatchPendingOffer(texts, pendingOffer, hasRecentDemandNavigation)) return false

        val pendingBest = listOfNotNull(pendingOffer.pickupAddress, pendingOffer.destinationAddress)
            .maxOfOrNull { countDistinctiveAddressMatches(texts, it) } ?: 0
        if (pendingBest <= 0) return false

        // Rua + número (ou duas palavras distintivas) já identificam a nova corrida sem ambiguidade.
        if (pendingBest >= STRONG_IDENTITY_MATCHES) return true
        if (previousRideHudTexts.isEmpty()) return true

        // Identificação fraca: se o HUD casa MAIS com o rastro da corrida anterior do que com a nova
        // oferta, não há como provar o aceite da nova corrida (HUD congelado da corrida anterior).
        val previousBest = previousRideHudTexts
            .filter { it.isNotBlank() }
            .maxOfOrNull { countDistinctiveAddressMatches(texts, it) } ?: 0
        return pendingBest >= previousBest
    }

    /**
     * Quando uma oferta chega com o motorista ocioso ("Buscando", fora de corrida ativa e fora de
     * navegação livre para zona de demanda) e logo em seguida a tela da 99 muda para o HUD de rota
     * ("km/h" + "Chegada prevista"), confirma que o motorista aceitou a corrida mesmo que o banner
     * superior mostre uma rua intermediária da manobra em vez do endereço final.
     */
    fun isGpsHudTransitionFromFreshOffer(
        texts: List<String>,
        pendingOffer: RideOffer?,
        offerArrivedDuringRide: Boolean,
        offerArrivedDuringFreeGps: Boolean,
        hasRecentDemandNavigation: Boolean
    ): Boolean {
        if (pendingOffer == null || offerArrivedDuringRide || offerArrivedDuringFreeGps || hasRecentDemandNavigation) {
            return false
        }
        if (texts.isEmpty()) return false
        if (ParsingEngine.isIdleDriverScreen(texts) ||
            ParsingEngine.isEarningsPanelScreen(texts) ||
            ParsingEngine.isDemandHotspotScreen(texts) ||
            texts.any { ParsingEngine.isAcceptActionText(it) }
        ) {
            return false
        }
        val joinedNorm = normalizeText(texts.joinToString(" "))
        return joinedNorm.contains("chegada prevista") &&
            (joinedNorm.contains("km h") || joinedNorm.contains(" min "))
    }
}
