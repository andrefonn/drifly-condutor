package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG B: a tela de conclusão da 99 é a de avaliação (sem tarifa) e o banco fica com o preço da OFERTA.
 * A home da 99 traz "Valor da última corrida" com o valor real.
 */
class RevenueReconcilerTest {

    private val now = 1_700_000_000_000L

    private fun dentroDaJanela(offsetMs: Long = 60_000L) = now - offsetMs

    @Test
    fun reconciliaOsTresDeltasReaisDeCampo() {
        // 10:03 oferta 6,20 -> 99 6,35
        assertTrue(
            RevenueReconciler.deveReconciliar(
                platform = "99",
                rideTimestamp = dentroDaJanela(),
                now = now,
                status = RideRecord.STATUS_CONCLUIDO,
                offerPrice = 6.20,
                observedValue = 6.35
            )
        )
        assertEquals(6.35, RevenueReconciler.valorFinal(6.20, 6.35)!!, 0.001)

        // 11:08 oferta 5,60 -> 5,75
        assertTrue(
            RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, RideRecord.STATUS_CONCLUIDO, 5.60, 5.75)
        )
        assertEquals(5.75, RevenueReconciler.valorFinal(5.60, 5.75)!!, 0.001)

        // 11:35 oferta 7,70 -> 8,00
        assertTrue(
            RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, RideRecord.STATUS_CONCLUIDO, 7.70, 8.00)
        )
        assertEquals(8.00, RevenueReconciler.valorFinal(7.70, 8.00)!!, 0.001)
    }

    @Test
    fun rejeitaValoresAbsurdosForaDaFaixa() {
        val status = RideRecord.STATUS_CONCLUIDO
        // "Ganhos desta semana" lido no lugar da última corrida.
        assertFalse(RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, status, 5.10, 42.80))
        assertFalse(RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, status, 5.10, 6.46 + 5.0))
        // Valor muito abaixo da oferta (recibo de outra corrida).
        assertFalse(RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, status, 20.00, 3.00))
        // Zero/negativo nunca reconcilia.
        assertFalse(RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, status, 5.10, 0.0))
        assertFalse(RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, status, 5.10, -3.0))

        assertNull(RevenueReconciler.valorFinal(5.10, 42.80))
        assertNull(RevenueReconciler.valorFinal(5.10, 0.0))
        assertNull(RevenueReconciler.valorFinal(0.0, 5.10))
    }

    @Test
    fun ignoraDiferencaDeCentavosNulaEEstadoNaoConcluido() {
        // Idempotência: o mesmo valor relido na home não gera nova gravação.
        assertFalse(
            RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, RideRecord.STATUS_CONCLUIDO, 5.10, 5.10)
        )
        assertEquals(null, RevenueReconciler.valorFinal(5.10, null))

        // Corrida cancelada nunca entra em faturamento por reconciliação.
        assertFalse(
            RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, RideRecord.STATUS_CANCELADO, 5.10, 5.75)
        )
        assertFalse(
            RevenueReconciler.deveReconciliar(
                "99", dentroDaJanela(), now, RideRecord.STATUS_CANCELADO_TAXA, 5.10, 5.75
            )
        )
        // ESTIMADO também não: ainda está em andamento.
        assertFalse(
            RevenueReconciler.deveReconciliar("99", dentroDaJanela(), now, RideRecord.STATUS_ESTIMADO, 5.10, 5.75)
        )
    }

    @Test
    fun soReconcilia99DentroDeDezMinutos() {
        val status = RideRecord.STATUS_CONCLUIDO
        assertFalse(
            "Uber não tem o card \"Valor da última corrida\"",
            RevenueReconciler.deveReconciliar("UBER", dentroDaJanela(), now, status, 5.10, 5.75)
        )
        assertTrue(
            "idade 0 (card logo após a corrida) é a situação ideal",
            RevenueReconciler.deveReconciliar("99", now, now, status, 5.10, 5.75)
        )
        assertTrue(RevenueReconciler.deveReconciliar("99", now - RevenueReconciler.RECONCILE_WINDOW_MS, now, status, 5.10, 5.75))
        assertFalse(
            "fora da janela o valor da home já pode ser de outra corrida",
            RevenueReconciler.deveReconciliar(
                "99", now - RevenueReconciler.RECONCILE_WINDOW_MS - 1, now, status, 5.10, 5.75
            )
        )
        assertFalse(
            "timestamp no futuro (relógio/corrida errada) não reconcilia",
            RevenueReconciler.deveReconciliar("99", now + 60_000, now, status, 5.10, 5.75)
        )
    }
}
