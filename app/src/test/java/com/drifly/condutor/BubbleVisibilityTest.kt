package com.drifly.condutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regras da bolinha: é exclusiva do Premium, e fechar pelo X esconde sem
 * desligar a chave.
 */
class BubbleVisibilityTest {

    @Test
    fun `premium com chave ligada e overlay aparece`() {
        assertTrue(
            BubbleVisibility.deveMostrar(
                premium = true,
                enabled = true,
                hidden = false,
                overlayPermitido = true
            )
        )
    }

    @Test
    fun `plano basico nunca mostra a bolinha`() {
        assertFalse(
            BubbleVisibility.deveMostrar(
                premium = false,
                enabled = true,
                hidden = false,
                overlayPermitido = true
            )
        )
    }

    @Test
    fun `fechada pelo X some mesmo com a chave ligada`() {
        assertFalse(
            BubbleVisibility.deveMostrar(
                premium = true,
                enabled = true,
                hidden = true,
                overlayPermitido = true
            )
        )
    }

    @Test
    fun `desligada nas configuracoes nao aparece`() {
        assertFalse(
            BubbleVisibility.deveMostrar(
                premium = true,
                enabled = false,
                hidden = false,
                overlayPermitido = true
            )
        )
    }

    @Test
    fun `sem permissao de sobreposicao nao aparece`() {
        assertFalse(
            BubbleVisibility.deveMostrar(
                premium = true,
                enabled = true,
                hidden = false,
                overlayPermitido = false
            )
        )
    }

    @Test
    fun `iniciar monitoramento traz de volta quando e premium e a chave esta ligada`() {
        assertTrue(BubbleVisibility.aoIniciarMonitoramento(premium = true, enabled = true))
        assertFalse(BubbleVisibility.aoIniciarMonitoramento(premium = true, enabled = false))
        assertFalse(BubbleVisibility.aoIniciarMonitoramento(premium = false, enabled = true))
    }
}
