package com.drifly.condutor

/**
 * Reconciliação do valor FINAL das corridas da 99 a partir da home do motorista.
 *
 * Problema de campo: a tela de conclusão da 99 é a de avaliação ("Como foi sua corrida com X?")
 * e não mostra tarifa, então `RECIBO DETECTADO ... finalPrice=null` e o banco fica com o preço da
 * OFERTA. A 99 cobra alguns centavos a mais (bandeira/tarifa dinâmica residual):
 * 6,20 → 6,35 | 5,60 → 5,75 | 7,70 → 8,00.
 *
 * A home da 99 ("Valor da última corrida | R$X") traz o valor real. Este objeto é PURO (sem Android)
 * e só autoriza a troca quando o valor observado é plausível para AQUELA corrida.
 *
 * Faixa de plausibilidade (documentada de propósito):
 *  - observed ∈ [0,5 × oferta, 1,5 × oferta]  → cobre bandeira mínima e espera/desvio moderado.
 *  - observed ≤ oferta + R$5,00              → teto absoluto: nenhuma corrida curta ganha R$5 de gorjeta
 *                                              silenciosa; acima disso é valor de OUTRA corrida (ou de
 *                                              "Ganhos desta semana").
 *  - |observed − oferta| ≥ R$0,01            → diferença de centavos é o próprio alvo; abaixo disso
 *                                              não há nada para reconciliar.
 */
object RevenueReconciler {

    /** Só a 99 expõe o card "Valor da última corrida" na home. Uber usa outro fluxo. */
    private const val PLATFORM_99 = "99"

    /** A home mostra o card logo após a corrida; passou disso o valor já pode ser de outra corrida. */
    const val RECONCILE_WINDOW_MS: Long = 10 * 60 * 1000L

    /** Piso da faixa relativa: viagem encurtada/cancelamento parcial. */
    const val MIN_RATIO: Double = 0.5

    /** Teto da faixa relativa: destino estendido/espera. */
    const val MAX_RATIO: Double = 1.5

    /** Teto absoluto somado à oferta (ver KDoc). */
    const val MAX_ABSOLUTE_GAIN: Double = 5.0

    /** Abaixo disso não existe reconciliação a fazer. */
    const val MIN_DELTA: Double = 0.01

    private fun isPlatform99(platform: String?): Boolean {
        val normalized = platform?.trim()?.lowercase() ?: return false
        return normalized == PLATFORM_99 || normalized.startsWith("99")
    }

    private fun isCancelled(status: String?): Boolean =
        status == RideRecord.STATUS_CANCELADO ||
            status == RideRecord.STATUS_CANCELADO_TAXA ||
            status == RideRecord.STATUS_DUPLICADO

    /**
     * Autoriza a troca do valor da corrida pelo valor lido na home.
     * Nunca reconcilia corrida que não está CONCLUIDO, corrida antiga, valor fora da faixa
     * ou valor praticamente igual (idempotência: rodar de novo não muda nada).
     */
    fun deveReconciliar(
        platform: String?,
        rideTimestamp: Long,
        now: Long,
        status: String?,
        offerPrice: Double,
        observedValue: Double?
    ): Boolean {
        if (!isPlatform99(platform)) return false
        if (status != RideRecord.STATUS_CONCLUIDO || isCancelled(status)) return false
        if (offerPrice <= 0.0 || observedValue == null || observedValue <= 0.0) return false
        val age = now - rideTimestamp
        if (age !in 0..RECONCILE_WINDOW_MS) return false
        if (!isPlausible(offerPrice, observedValue)) return false
        return Math.abs(observedValue - offerPrice) >= MIN_DELTA
    }

    /** Valor final a gravar, ou null quando o observado não é confiável. */
    fun valorFinal(offerPrice: Double, observedValue: Double?): Double? {
        if (offerPrice <= 0.0 || observedValue == null || observedValue <= 0.0) return null
        if (!isPlausible(offerPrice, observedValue)) return null
        return Math.round(observedValue * 100.0) / 100.0
    }

    private fun isPlausible(offerPrice: Double, observedValue: Double): Boolean {
        val lower = offerPrice * MIN_RATIO
        val upper = Math.min(offerPrice * MAX_RATIO, offerPrice + MAX_ABSOLUTE_GAIN)
        return observedValue in lower..upper
    }
}
