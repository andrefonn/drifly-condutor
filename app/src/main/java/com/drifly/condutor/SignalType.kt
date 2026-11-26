package com.drifly.condutor

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/** Identificador estável do tipo "dica de motorista" (usado em várias telas). */
const val TIPO_DICA = "dica"

/** Identificador estável do tipo "evento Paga+". */
const val TIPO_EVENTO = "evento"

/**
 * Configuração de um tipo de sinal colaborativo do mapa.
 *
 * É o equivalente do objeto `STYPE` do protótipo: cada tipo carrega o nome
 * longo (título do card), o rótulo curto do chip de filtro, o rótulo da grade
 * do editor, a pergunta de voto, a cor, o prazo de validade e o ícone.
 *
 * Para acrescentar um tipo novo (carro, moto...) basta adicionar uma entrada em
 * [SignalTypes.TODOS] apontando para o drawable do ícone: nada mais precisa ser
 * tocado no mapa, nos chips, na legenda ou no editor.
 */
data class SignalType(
    /** Id estável: é o que vai para o servidor na Fase 4 e nunca deve mudar. */
    val id: String,
    /** Nome completo, usado como título do card. */
    val nome: String,
    /** Rótulo curto do chip de filtro ("Banheiro", "Perigo", "Paga+"). */
    val rotuloChip: String,
    /** Rótulo da grade de lugares do editor ("Almoço barato", "Posto barato"). */
    val rotuloEditor: String,
    /** Pergunta de voto quando o sinal já está confirmado. */
    val pergunta: String,
    /** Cor do tipo (mesmo valor de `@color/signal_*`). */
    @ColorInt val cor: Int,
    /** Validade em minutos (dias convertidos; evento usa 120 minutos). */
    val validadeMinutos: Int,
    /** Ícone vetorial branco do tipo. */
    @DrawableRes val icone: Int,
    /** `true` quando o sinal desenha um círculo de área (perigo e evento). */
    val area: Boolean = false,
    /** `true` para o evento Paga+ (laranja, prazo curto, pergunta de demanda). */
    val evento: Boolean = false
) {
    /** Validade em dias (0 para o evento, que vive em minutos). */
    val validadeDias: Int get() = validadeMinutos / MINUTOS_POR_DIA

    /** Atalho: é o tipo "dica de motorista". */
    val dica: Boolean get() = id == TIPO_DICA

    private companion object {
        const val MINUTOS_POR_DIA = 24 * 60
    }
}

/**
 * Catálogo dos tipos de sinal — a lista de configuração do mapa.
 *
 * Ordem = ordem dos chips na tela e da legenda, igual ao protótipo.
 */
object SignalTypes {

    /** Todos os tipos, na ordem em que aparecem nos chips de filtro. */
    val TODOS: List<SignalType> = listOf(
        SignalType(
            id = "banheiro",
            nome = "Banheiro externo",
            rotuloChip = "Banheiro",
            rotuloEditor = "Banheiro",
            pergunta = "Ainda existe?",
            cor = 0xFF2563EB.toInt(),
            validadeMinutos = 180 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_banheiro
        ),
        SignalType(
            id = "agua",
            nome = "Bebedouro",
            rotuloChip = "Bebedouro",
            rotuloEditor = "Bebedouro",
            pergunta = "Ainda existe?",
            cor = 0xFF0891B2.toInt(),
            validadeMinutos = 180 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_agua
        ),
        SignalType(
            id = "perigo",
            nome = "Bairro perigoso",
            rotuloChip = "Perigo",
            rotuloEditor = "Bairro perigoso",
            pergunta = "Ainda é perigoso?",
            cor = 0xFFD62839.toInt(),
            validadeMinutos = 60 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_perigo,
            area = true
        ),
        SignalType(
            id = "descanso",
            nome = "Lugar para descansar",
            rotuloChip = "Descanso",
            rotuloEditor = "Descanso",
            pergunta = "Ainda é bom para parar?",
            cor = 0xFF0B8A54.toInt(),
            validadeMinutos = 180 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_descanso
        ),
        SignalType(
            id = "almoco",
            nome = "Almoço bom e barato",
            rotuloChip = "Almoço",
            rotuloEditor = "Almoço barato",
            pergunta = "Recomenda?",
            cor = 0xFF7C3AED.toInt(),
            validadeMinutos = 90 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_almoco
        ),
        SignalType(
            id = "posto",
            nome = "Posto de gasolina barato",
            rotuloChip = "Posto",
            rotuloEditor = "Posto barato",
            pergunta = "Preço ainda baixo?",
            cor = 0xFFDB2777.toInt(),
            validadeMinutos = 7 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_posto
        ),
        SignalType(
            id = "mecanico",
            nome = "Mecânico confiável",
            rotuloChip = "Mecânico",
            rotuloEditor = "Mecânico",
            pergunta = "Recomenda?",
            cor = 0xFF475569.toInt(),
            validadeMinutos = 180 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_mecanico
        ),
        SignalType(
            id = "lanche",
            nome = "Lanche bom e barato",
            rotuloChip = "Lanche",
            rotuloEditor = "Lanche barato",
            pergunta = "Recomenda?",
            cor = 0xFFB45309.toInt(),
            validadeMinutos = 90 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_lanche
        ),
        SignalType(
            id = "borracharia",
            nome = "Borracharia",
            rotuloChip = "Borracha",
            rotuloEditor = "Borracharia",
            pergunta = "Ainda existe?",
            cor = 0xFF4D7C0F.toInt(),
            validadeMinutos = 180 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_borracharia
        ),
        SignalType(
            id = TIPO_DICA,
            nome = "Dica de motorista",
            rotuloChip = "Dica",
            rotuloEditor = "Dica",
            pergunta = "Essa dica é verdadeira?",
            cor = 0xFFCA8A04.toInt(),
            validadeMinutos = 7 * MINUTOS_POR_DIA,
            icone = R.drawable.ic_signal_dica
        ),
        SignalType(
            id = TIPO_EVENTO,
            nome = "Evento Paga+",
            rotuloChip = "Paga+",
            rotuloEditor = "Paga+",
            pergunta = "Tá com muita demanda?",
            cor = 0xFFFF6F00.toInt(),
            validadeMinutos = 120,
            icone = R.drawable.ic_signal_evento,
            area = true,
            evento = true
        )
    )

    /** Tipos que aparecem na grade "Lugar" do editor (todos menos dica e evento). */
    val LUGARES: List<SignalType> = TODOS.filter { !it.dica && !it.evento }

    /** Tipo "dica de motorista". */
    val DICA: SignalType = TODOS.first { it.dica }

    /** Tipo "evento Paga+". */
    val EVENTO: SignalType = TODOS.first { it.evento }

    /** Índice por id, para reconstruir o tipo a partir do sinal salvo. */
    val POR_ID: Map<String, SignalType> = TODOS.associateBy { it.id }

    fun porId(id: String?): SignalType? = if (id == null) null else POR_ID[id]

    /** Opções de "o que está acontecendo?" no Paga+ (objeto EVKINDS do protótipo). */
    val TIPOS_EVENTO: List<String> = listOf(
        "Show ou festa",
        "Jogo",
        "Chuva forte",
        "Horário de pico",
        "Outro"
    )

    private const val MINUTOS_POR_DIA = 24 * 60
}
