package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão de 21/11: o recibo da corrida anterior (R$ 8,91 em dinheiro) foi aplicado
 * duas vezes — na corrida #248 e, 10 s depois, na #249 (que valia R$ 5,10).
 */
class ReceiptGuardTest {

    private fun signature(price: Double?, cash: Boolean = true, cancelled: Boolean = false) =
        ReceiptGuard.signature(
            platform = "99",
            finalPrice = price,
            cashScreen = cash,
            cancelled = cancelled,
            status = if (cancelled) "CANCELADO" else "CONCLUIDO"
        )

    @Test
    fun `mesma tela relida em dez segundos e ignorada`() {
        val sig = signature(8.91)
        assertTrue(
            ReceiptGuard.isRepeatedScreen(
                lastSignature = sig,
                lastAppliedAt = 1_000_000L,
                currentSignature = sig,
                now = 1_010_000L
            )
        )
    }

    @Test
    fun `mesma tela depois da janela pode ser outro recibo`() {
        val sig = signature(8.91)
        assertFalse(
            ReceiptGuard.isRepeatedScreen(
                lastSignature = sig,
                lastAppliedAt = 1_000_000L,
                currentSignature = sig,
                now = 1_000_000L + ReceiptGuard.REPEAT_WINDOW_MS + 1L
            )
        )
    }

    @Test
    fun `valor diferente na mesma tela passa`() {
        assertFalse(
            ReceiptGuard.isRepeatedScreen(
                lastSignature = signature(8.91),
                lastAppliedAt = 1_000_000L,
                currentSignature = signature(5.10),
                now = 1_005_000L
            )
        )
    }

    @Test
    fun `primeiro recibo nunca e repetido`() {
        assertFalse(
            ReceiptGuard.isRepeatedScreen(
                lastSignature = "",
                lastAppliedAt = 0L,
                currentSignature = signature(5.10),
                now = 1_000_000L
            )
        )
    }

    @Test
    fun `assinatura separa dinheiro de recibo comum`() {
        val cash = ReceiptGuard.signature("99", 8.91, cashScreen = true, cancelled = false, status = "CONCLUIDO")
        val normal = ReceiptGuard.signature("99", 8.91, cashScreen = false, cancelled = false, status = "CONCLUIDO")
        assertFalse(cash == normal)
    }

    @Test
    fun `assinatura sem preco continua estavel`() {
        val a = ReceiptGuard.signature("99", null, cashScreen = false, cancelled = false, status = "CONCLUIDO")
        val b = ReceiptGuard.signature("99", null, cashScreen = false, cancelled = false, status = "CONCLUIDO")
        assertEquals(a, b)
    }

    @Test
    fun `carimbo no futuro nao vale como repeticao negativa`() {
        val sig = signature(8.91)
        // relógio andou para trás: elapsed negativo -> não bloqueia
        assertFalse(
            ReceiptGuard.isRepeatedScreen(
                lastSignature = sig,
                lastAppliedAt = 2_000_000L,
                currentSignature = sig,
                now = 1_000_000L
            )
        )
    }
}
