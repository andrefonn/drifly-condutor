package com.drifly.condutor

import java.util.Locale

/**
 * Guarda contra o mesmo recibo fechar duas corridas diferentes.
 *
 * Caso real (21/11): a tela "Cobrar do passageiro" (dinheiro) da corrida X fica aberta
 * até 3 minutos. O DriFly lia, aplicava em X e, segundos depois, relia a mesma tela já
 * com a corrida Y aceita (a próxima) e aplicava R$ 8,91 na Y, que valia R$ 5,10.
 *
 * A identidade do recibo é plataforma + valor + tipo de tela. Repetir a mesma identidade
 * dentro de [REPEAT_WINDOW_MS] é sempre releitura da mesma tela, nunca um recibo novo.
 */
object ReceiptGuard {

    /** Tempo que a tela de pagamento em dinheiro pode ficar visível (QR de 180s + folga). */
    const val REPEAT_WINDOW_MS: Long = 150_000L

    fun signature(
        platform: String?,
        finalPrice: Double?,
        cashScreen: Boolean,
        cancelled: Boolean,
        status: String?
    ): String {
        val price = finalPrice?.let { String.format(Locale.US, "%.2f", it) } ?: "null"
        return "${platform ?: "?"}|$price|$cashScreen|$cancelled|${status ?: "?"}"
    }

    /** true = mesma tela já aplicada; deve ser ignorada. */
    fun isRepeatedScreen(
        lastSignature: String,
        lastAppliedAt: Long,
        currentSignature: String,
        now: Long
    ): Boolean {
        if (lastSignature.isEmpty() || lastAppliedAt <= 0L) return false
        if (currentSignature.isEmpty() || currentSignature != lastSignature) return false
        val elapsed = now - lastAppliedAt
        return elapsed in 0..REPEAT_WINDOW_MS
    }
}
