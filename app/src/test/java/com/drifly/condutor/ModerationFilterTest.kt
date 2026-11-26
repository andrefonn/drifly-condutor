package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A camada 1 do filtro de dicas é a única que roda sem rede, então ela precisa
 * acertar palavrão disfarçado sem bloquear palavra honesta por substring.
 */
class ModerationFilterTest {

    // ---------- Dica válida ----------

    @Test
    fun `dica normal em portugues e aceita`() {
        assertNull(
            ModerationFilter.check(
                "A entrada lateral do shopping é mais rápida para embarque."
            )
        )
    }

    @Test
    fun `dica com acento e pontuacao e aceita`() {
        assertNull(ModerationFilter.check("Almoço bom, R$ 18,00, serve rápido!"))
    }

    // ---------- Limites de tamanho ----------

    @Test
    fun `menos de oito caracteres e recusado`() {
        assertEquals(ModerationFilter.MSG_CURTA, ModerationFilter.check("boa dic"))
    }

    @Test
    fun `oito caracteres e aceito`() {
        assertNull(ModerationFilter.check("boa dica"))
    }

    @Test
    fun `oitenta caracteres e aceito`() {
        val oitenta = "Dica boa " + "a".repeat(80 - "Dica boa ".length)
        assertEquals(80, oitenta.length)
        assertNull(ModerationFilter.check(oitenta))
    }

    @Test
    fun `oitenta e um caracteres e recusado`() {
        val oitentaEUm = "Dica boa " + "a".repeat(81 - "Dica boa ".length)
        assertEquals(81, oitentaEUm.length)
        assertEquals(ModerationFilter.MSG_LONGA, ModerationFilter.check(oitentaEUm))
    }

    @Test
    fun `texto so de espacos conta como curto`() {
        assertEquals(ModerationFilter.MSG_CURTA, ModerationFilter.check("          "))
    }

    // ---------- Palavras proibidas ----------

    @Test
    fun `palavrao com acento e caixa alta e recusado`() {
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("que PORRÁ é essa"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("Que Mérda de trânsito"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("CaRaLhO, parou tudo"))
    }

    @Test
    fun `numeros disfarcando letras sao recusados`() {
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("que p0rra é essa"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("que m3rda hein"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("sua puuuta"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("foda-se o trânsito"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("vai se foder cara"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("que b0sta de fila"))
    }

    @Test
    fun `letras separadas por ponto ou espaco sao recusadas`() {
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("seu p.u.t.a"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("seu p u t a"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("seu p-u-t-a"))
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("seu p_u_t_a"))
    }

    @Test
    fun `palavrao em maiuscula com leet e recusado`() {
        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check("VAI T0MAR NO CU"))
    }

    // ---------- Falsos positivos que precisam passar ----------

    @Test
    fun `palavras honestas parecidas com palavrao passam`() {
        assertNull(ModerationFilter.check("Minha reputação aqui é boa."))
        assertNull(ModerationFilter.check("O Paulo indica esse posto."))
        assertNull(ModerationFilter.check("Uma pausa rápida no acostamento."))
        assertNull(ModerationFilter.check("O custo do pedágio subiu."))
        assertNull(ModerationFilter.check("Sentido Curitiba pela BR-101."))
        assertNull(ModerationFilter.check("Uma disputa justa por corrida."))
        assertNull(ModerationFilter.check("Fiz o percurso em vinte minutos."))
        assertNull(ModerationFilter.check("A pausa do almoço é às onze."))
    }

    // ---------- Links, e-mails e telefones ----------

    @Test
    fun `links e dominios sao recusados`() {
        assertEquals(ModerationFilter.MSG_CONTATO, ModerationFilter.check("veja https://x.com"))
        assertEquals(ModerationFilter.MSG_CONTATO, ModerationFilter.check("acesse www.site.com"))
        assertEquals(ModerationFilter.MSG_CONTATO, ModerationFilter.check("olha no site.com.br"))
    }

    @Test
    fun `email e recusado`() {
        assertEquals(ModerationFilter.MSG_CONTATO, ModerationFilter.check("fala com fulano@gmail.com"))
    }

    @Test
    fun `telefone com separadores e recusado`() {
        assertEquals(ModerationFilter.MSG_CONTATO, ModerationFilter.check("liga 11 98765-4321"))
        assertEquals(ModerationFilter.MSG_CONTATO, ModerationFilter.check("liga 11.98765-4321"))
        assertEquals(ModerationFilter.MSG_CONTATO, ModerationFilter.check("zap 47999998888"))
    }

    // ---------- Placas ----------

    @Test
    fun `placa mercosul e antiga sao recusadas`() {
        assertEquals(ModerationFilter.MSG_PLACA, ModerationFilter.check("a placa ABC1D23 estava lá"))
        assertEquals(ModerationFilter.MSG_PLACA, ModerationFilter.check("a placa ABC-1234 estava lá"))
        assertEquals(ModerationFilter.MSG_PLACA, ModerationFilter.check("a placa ABC1234 estava lá"))
    }

    @Test
    fun `quilometragem nao e confundida com placa`() {
        assertNull(ModerationFilter.check("Pare no km 123 da via"))
        assertNull(ModerationFilter.check("Rua 1234 fica aberta"))
    }

    // ---------- Contrato ----------

    @Test
    fun `isAllowed concorda com check`() {
        val aceita = "Estacionamento grátis por 30 minutos."
        val recusada = "que merda de fila"

        assertNull(ModerationFilter.check(aceita))
        assertTrue(ModerationFilter.isAllowed(aceita))

        assertEquals(ModerationFilter.MSG_PALAVRA, ModerationFilter.check(recusada))
        assertFalse(ModerationFilter.isAllowed(recusada))
    }
}
