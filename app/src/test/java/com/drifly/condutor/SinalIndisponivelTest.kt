package com.drifly.condutor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sem servidor o mapa tem que ficar vazio e avisar.
 *
 * O app não deve cair em repositório de exemplo com pinos fictícios: sem
 * servidor, a lista fica vazia e o mapa avisa. Estes testes travam esse
 * comportamento honesto — nada de pino inventado.
 */
class SinalIndisponivelTest {

    private val repositorio: SignalRepository = SinalIndisponivelRepository

    @Test
    fun `repositorio sem conexao nao lista sinal nenhum`() {
        assertFalse(repositorio.online)
        assertTrue(repositorio.listar().isEmpty())
    }

    @Test
    fun `observar entrega lista vazia e a mensagem de sem conexao`() {
        var sinaisRecebidos: List<Signal>? = null
        var falha: String? = null

        repositorio.observar(object : SignalRepository.Ouvinte {
            override fun aoMudarSinais(sinais: List<Signal>) {
                sinaisRecebidos = sinais
            }

            override fun aoFalhar(mensagem: String) {
                falha = mensagem
            }
        })

        assertEquals(emptyList<Signal>(), sinaisRecebidos)
        assertEquals(SinalIndisponivelRepository.MENSAGEM, falha)
        assertTrue(SinalIndisponivelRepository.MENSAGEM.contains("sem conexão"))
    }

    @Test
    fun `criar votar denunciar e remover falham com a mensagem do mapa`() {
        val tipo = SignalTypes.LUGARES.first()

        var criacao: ResultadoCriacao? = null
        repositorio.criar(tipo, -26.3, -48.84, null, null) { criacao = it }
        assertFalse(criacao!!.ok)
        assertEquals(SinalIndisponivelRepository.MENSAGEM, criacao!!.mensagem)

        var voto: ResultadoVoto? = null
        repositorio.votar("x", true, 10.0) { voto = it }
        assertFalse(voto!!.aceito)
        assertEquals(SinalIndisponivelRepository.MENSAGEM, voto!!.mensagem)

        var denuncia: Pair<Boolean, String>? = null
        repositorio.denunciar("x", MOTIVO_NAO_EXISTE) { ok, mensagem -> denuncia = ok to mensagem }
        assertEquals(false to SinalIndisponivelRepository.MENSAGEM, denuncia)

        var remocao: Pair<Boolean, String>? = null
        repositorio.remover("x") { ok, mensagem -> remocao = ok to mensagem }
        assertEquals(false to SinalIndisponivelRepository.MENSAGEM, remocao)
    }

    @Test
    fun `atualizar area visivel nao faz nada e nao quebra`() {
        repositorio.atualizarAreaVisivel(
            AreaMapa(norte = -26.0, sul = -27.0, leste = -48.0, oeste = -49.0)
        )
        assertTrue(repositorio.listar().isEmpty())
    }
}
