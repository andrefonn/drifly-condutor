package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras do plano gratuito: 10 despesas por dia, sem limite no Premium.
 * O resto do app é livre nos dois planos.
 */
class LimitesPlanoTest {

    @Test
    fun `gratuito permite dez despesas e para na decima primeira`() {
        assertTrue(LimitesPlano.podeLancarDespesa(0, premium = false))
        assertTrue(LimitesPlano.podeLancarDespesa(9, premium = false))
        assertFalse(LimitesPlano.podeLancarDespesa(10, premium = false))
        assertFalse(LimitesPlano.podeLancarDespesa(25, premium = false))
    }

    @Test
    fun `premium nunca bloqueia`() {
        assertTrue(LimitesPlano.podeLancarDespesa(0, premium = true))
        assertTrue(LimitesPlano.podeLancarDespesa(10, premium = true))
        assertTrue(LimitesPlano.podeLancarDespesa(9_999, premium = true))
    }

    @Test
    fun `restantes diminui a cada despesa no gratuito`() {
        assertEquals(10, LimitesPlano.restantesHoje(0, premium = false))
        assertEquals(1, LimitesPlano.restantesHoje(9, premium = false))
        assertEquals(0, LimitesPlano.restantesHoje(10, premium = false))
        assertEquals(0, LimitesPlano.restantesHoje(30, premium = false))
    }

    @Test
    fun `restantes e infinito no premium`() {
        assertEquals(Int.MAX_VALUE, LimitesPlano.restantesHoje(0, premium = true))
        assertEquals(Int.MAX_VALUE, LimitesPlano.restantesHoje(500, premium = true))
    }

    @Test
    fun `contagem negativa nao trava o usuario`() {
        assertTrue(LimitesPlano.podeLancarDespesa(-3, premium = false))
        assertEquals(10, LimitesPlano.restantesHoje(-3, premium = false))
    }

    @Test
    fun `resumo do dia fala a lingua do motorista`() {
        assertEquals("Restam 10 despesas hoje", LimitesPlano.resumoDoDia(0, premium = false))
        assertEquals(
            "Resta 1 despesa hoje (o plano Premium não tem limite)",
            LimitesPlano.resumoDoDia(9, premium = false)
        )
        assertEquals(
            "Limite de 10 despesas por dia atingido",
            LimitesPlano.resumoDoDia(10, premium = false)
        )
        assertEquals("Despesas ilimitadas", LimitesPlano.resumoDoDia(7, premium = true))
    }
}

