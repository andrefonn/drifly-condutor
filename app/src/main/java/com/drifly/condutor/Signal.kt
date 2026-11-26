package com.drifly.condutor

import java.util.concurrent.TimeUnit

/**
 * Situação de um sinal colaborativo no mapa.
 *
 * O ciclo completo (quem aprova, quem expira, quem apaga) é decidido pelas
 * Cloud Functions na Fase 4. Aqui os estados existem para a interface já falar
 * a mesma língua do servidor.
 */
enum class SignalStatus {
    /** Nasceu agora e ainda não tem confirmações suficientes. */
    PENDENTE,

    /** Confirmado pelos motoristas (ou dica mantida pela maioria). */
    ATIVO,

    /** Dica com menos de 3 votos: aparece como "Em avaliação". */
    EM_AVALIACAO,

    /** Passou da validade do tipo (posto vence em 7 dias, evento em 2 horas...). */
    EXPIRADO,

    /** Os motoristas disseram que não existe mais / que a dica é mentira. */
    REJEITADO
}

/**
 * Um sinal do mapa colaborativo.
 *
 * @param raioM raio em metros do círculo de área (perigo e evento); 0 nos pontos.
 * @param quando texto relativo já formatado ("há 2 dias"), como no protótipo.
 * @param expiraEm instante de validade em epoch millis.
 * @param jaVotei o motorista já votou neste sinal (um voto por sinal).
 * @param ehMeu o sinal foi criado por este motorista (autoria: `criadoPor` == uid).
 * @param curador o sinal é de um curador (dono do app): já nasce ativo e é
 *   "verdade absoluta" — ninguém precisa confirmar.
 */
data class Signal(
    val id: String,
    val tipo: SignalType,
    val lat: Double,
    val lng: Double,
    val raioM: Double = 0.0,
    val status: SignalStatus = SignalStatus.PENDENTE,
    val texto: String? = null,
    val tipoEvento: String? = null,
    val simQtd: Int = 0,
    val naoQtd: Int = 0,
    val quando: String = "agora",
    val expiraEm: Long = 0L,
    val jaVotei: Boolean = false,
    val ehMeu: Boolean = false,
    val curador: Boolean = false
) {
    /** Total de votos já registrados. */
    val totalVotos: Int get() = simQtd + naoQtd

    /** Confiança do sinal em %, como a barra do card do protótipo. */
    val percentualSim: Int
        get() = if (totalVotos == 0) 0 else Math.round(simQtd * 100f / totalVotos)

    /** Ainda precisa de confirmações para aparecer para todos. */
    val pendente: Boolean get() = status == SignalStatus.PENDENTE

    /** É um evento Paga+ (área laranja com prazo em minutos). */
    val ehEvento: Boolean get() = tipo.evento

    /** É uma dica de motorista (tem texto e votação Verdade/Mentira). */
    val ehDica: Boolean get() = tipo.dica

    /** Quanto falta para expirar, em minutos (0 quando já venceu ou não expira). */
    val minutosRestantes: Int
        get() = if (expiraEm <= 0L) 0
        else TimeUnit.MILLISECONDS.toMinutes(expiraEm - System.currentTimeMillis())
            .coerceAtLeast(0L)
            .toInt()

    /**
     * Já passou do prazo (`expiraEm`).
     *
     * O servidor esconde o sinal na função agendada, mas isso roda a cada
     * poucos minutos: o app também descarta por conta própria para o pino não
     * sobreviver ao prazo na tela. Sinais sem prazo (`expiraEm = 0`) nunca vencem.
     */
    val expirou: Boolean
        get() = expiraEm > 0L && expiraEm <= System.currentTimeMillis()

    /** Paga+ nos últimos minutos: o card avisa que está acabando. */
    val terminando: Boolean
        get() = ehEvento && expiraEm > 0L && minutosRestantes in 0..MINUTOS_TERMINANDO

    /** Confirmações que ainda faltam para virar "Confirmado". */
    val confirmacoesFaltando: Int get() = (CONFIRMACOES - simQtd).coerceAtLeast(0)

    companion object {
        /** Confirmações necessárias para um sinal pendente virar ativo (NEED do protótipo). */
        const val CONFIRMACOES = 3

        /** Distância máxima, em metros, para poder votar num sinal (≈50 px do protótipo). */
        const val DISTANCIA_VOTO_M = 150.0

        /** Últimos minutos de um Paga+ em que o chip passa a avisar "Terminando". */
        const val MINUTOS_TERMINANDO = 15
    }
}
