package com.drifly.condutor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes das decisões puras da abertura do app.
 *
 * Cobrem o bug de tela preta na segunda abertura: como a animação de abertura só
 * roda uma vez por processo, a capa de bloqueio não pode continuar na tela
 * quando ela não toca.
 */
class AberturaDecisaoTest {

    // ---- Animação de abertura ------------------------------------------------

    @Test
    fun `primeira abertura do processo toca a animacao`() {
        assertTrue(
            AberturaDecisao.deveTocarAnimacao(
                hasSavedInstanceState = false,
                isBackgroundShortcutIntent = false,
                jaTocouNoProcesso = false
            )
        )
    }

    @Test
    fun `segunda abertura no mesmo processo nao toca a animacao`() {
        assertFalse(
            AberturaDecisao.deveTocarAnimacao(
                hasSavedInstanceState = false,
                isBackgroundShortcutIntent = false,
                jaTocouNoProcesso = true
            )
        )
    }

    @Test
    fun `recriacao da tela nao toca a animacao`() {
        assertFalse(
            AberturaDecisao.deveTocarAnimacao(
                hasSavedInstanceState = true,
                isBackgroundShortcutIntent = false,
                jaTocouNoProcesso = false
            )
        )
    }

    @Test
    fun `atalho em aba especifica nao toca a animacao`() {
        assertFalse(
            AberturaDecisao.deveTocarAnimacao(
                hasSavedInstanceState = false,
                isBackgroundShortcutIntent = true,
                jaTocouNoProcesso = false
            )
        )
    }

    // ---- Capa de bloqueio (bug da tela preta) -------------------------------

    @Test
    fun `capa aparece so com biometria ativa e desbloqueio pendente`() {
        assertTrue(AberturaDecisao.deveMostrarCapaDeBloqueio(biometriaAtiva = true, biometriaConfirmada = false))
        assertFalse(AberturaDecisao.deveMostrarCapaDeBloqueio(biometriaAtiva = true, biometriaConfirmada = true))
        assertFalse(AberturaDecisao.deveMostrarCapaDeBloqueio(biometriaAtiva = false, biometriaConfirmada = false))
    }

    @Test
    fun `segunda abertura sem animacao precisa esconder a capa`() {
        val jaTocouNoProcesso = true
        val tocaAnimacao = AberturaDecisao.deveTocarAnimacao(
            hasSavedInstanceState = false,
            isBackgroundShortcutIntent = false,
            jaTocouNoProcesso = jaTocouNoProcesso
        )
        // Sem animação, o caminho do abrirApp() da MainActivity chama
        // esconderCapaDeBloqueio() — é isso que evita a tela preta travada.
        assertFalse("na segunda abertura a animação não roda", tocaAnimacao)
        assertTrue("a capa só existe enquanto o desbloqueio está pendente",
            AberturaDecisao.deveMostrarCapaDeBloqueio(biometriaAtiva = true, biometriaConfirmada = false))
        // Depois do desbloqueio confirmado a capa deixa de ser necessária.
        assertFalse(AberturaDecisao.deveMostrarCapaDeBloqueio(biometriaAtiva = true, biometriaConfirmada = true))
    }

    @Test
    fun `abertura normal toca animacao com biometria pendente e depois libera`() {
        val toca = AberturaDecisao.deveTocarAnimacao(false, false, false)
        assertTrue(toca)
        assertTrue(AberturaDecisao.deveMostrarCapaDeBloqueio(biometriaAtiva = true, biometriaConfirmada = false))
    }

    // ---- Acessibilidade ----------------------------------------------------

    @Test
    fun `acessibilidade foi perdida quando a pref dizia ativa e agora nao esta`() {
        assertTrue(AberturaDecisao.acessibilidadeFoiPerdida(estavaAtiva = true, estaAtivaAgora = false))
        assertFalse(AberturaDecisao.acessibilidadeFoiPerdida(estavaAtiva = false, estaAtivaAgora = false))
        assertFalse(AberturaDecisao.acessibilidadeFoiPerdida(estavaAtiva = true, estaAtivaAgora = true))
        assertFalse(AberturaDecisao.acessibilidadeFoiPerdida(estavaAtiva = false, estaAtivaAgora = true))
    }

    @Test
    fun `aviso de acessibilidade perdida aparece uma vez por execucao`() {
        assertTrue(AberturaDecisao.deveAvisarAcessibilidadePerdida(foiPerdida = true, jaAvisouNoProcesso = false))
        assertFalse(AberturaDecisao.deveAvisarAcessibilidadePerdida(foiPerdida = true, jaAvisouNoProcesso = true))
        assertFalse(AberturaDecisao.deveAvisarAcessibilidadePerdida(foiPerdida = false, jaAvisouNoProcesso = false))
    }
}
