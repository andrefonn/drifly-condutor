package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Prazos do sinal (Fase 6): o Paga+ vive 2 h e o app precisa largar o pino
 * exatamente na hora do vencimento, sem depender da função agendada.
 */
class SignalExpiryTest {

    private val agora = System.currentTimeMillis()

    private fun sinal(
        tipo: SignalType,
        expiraEm: Long,
        simQtd: Int = 0,
        status: SignalStatus = SignalStatus.ATIVO
    ) = Signal(
        id = "teste",
        tipo = tipo,
        lat = -26.30,
        lng = -48.84,
        status = status,
        simQtd = simQtd,
        naoQtd = 0,
        expiraEm = expiraEm
    )

    @Test
    fun `sinal sem prazo nunca vence`() {
        val semPrazo = sinal(SignalTypes.LUGARES.first(), expiraEm = 0L)
        assertFalse(semPrazo.expirou)
    }

    @Test
    fun `sinal com prazo no futuro esta valido`() {
        val em2h = sinal(SignalTypes.EVENTO, agora + TimeUnit.HOURS.toMillis(2))
        assertFalse(em2h.expirou)
        // `toMinutes` trunca: 2 h menos alguns milissegundos cai para 119.
        assertTrue(em2h.minutosRestantes in 119..120)
    }

    @Test
    fun `sinal com prazo no passado esta vencido`() {
        val vencido = sinal(SignalTypes.EVENTO, agora - 1_000L)
        assertTrue(vencido.expirou)
        assertEquals(0, vencido.minutosRestantes)
    }

    @Test
    fun `paga mais de duas horas aparece como terminado`() {
        // Criado agora, validade de 2 h: começou (0 min restantes é o limite).
        val comPrazo = sinal(SignalTypes.EVENTO, agora + TimeUnit.HOURS.toMillis(2))
        assertFalse(comPrazo.terminando)

        val quaseNoFim = sinal(SignalTypes.EVENTO, agora + TimeUnit.MINUTES.toMillis(8))
        assertTrue(quaseNoFim.terminando)
        assertTrue(quaseNoFim.minutosRestantes in 7..8)
    }

    @Test
    fun `aviso de terminando e so para evento`() {
        val banheiroNoFim = sinal(SignalTypes.LUGARES.first(), agora + TimeUnit.MINUTES.toMillis(5))
        assertFalse("lugar não tem contagem regressiva", banheiroNoFim.terminando)
    }

    @Test
    fun `confirmacoes faltando para virar ativo`() {
        assertEquals(3, sinal(SignalTypes.EVENTO, agora + 1000L).confirmacoesFaltando)
        assertEquals(1, sinal(SignalTypes.EVENTO, agora + 1000L, simQtd = 2).confirmacoesFaltando)
        assertEquals(0, sinal(SignalTypes.EVENTO, agora + 1000L, simQtd = 5).confirmacoesFaltando)
    }

    @Test
    fun `validade do tipo evento e de duas horas`() {
        assertEquals(120, SignalTypes.EVENTO.validadeMinutos)
        assertEquals(0, SignalTypes.EVENTO.validadeDias)
    }
}
