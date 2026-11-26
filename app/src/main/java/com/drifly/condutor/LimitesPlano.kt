package com.drifly.condutor

/**
 * Limites do plano gratuito.
 *
 * O DriFly tem **um** plano pago, sem níveis: o gratuito continua completo nos
 * recursos do dia a dia (overlay, notificações das ofertas que tocaram, filtros
 * de valor por km, distância e nota, e a meta de ganhos) e limita apenas o
 * lançamento de despesas. No pacote pago nada tem limite.
 *
 * A regra fica aqui, separada da interface, para poder ser testada sem Android.
 */
object LimitesPlano {

    /** Quantas despesas o motorista do plano gratuito lança por dia. */
    const val DESPESAS_POR_DIA = 10

    /** Quantas despesas cabem hoje: sem limite no plano pago. */
    fun limiteDespesas(premium: Boolean): Int =
        if (premium) Int.MAX_VALUE else DESPESAS_POR_DIA

    /** `true` quando o motorista ainda pode lançar uma despesa hoje. */
    fun podeLancarDespesa(lancadasHoje: Int, premium: Boolean): Boolean =
        lancadasHoje.coerceAtLeast(0) < limiteDespesas(premium)

    /** Quantas despesas ainda cabem hoje (ou [Int.MAX_VALUE] no plano pago). */
    fun restantesHoje(lancadasHoje: Int, premium: Boolean): Int {
        val limite = limiteDespesas(premium)
        if (limite == Int.MAX_VALUE) return Int.MAX_VALUE
        return (limite - lancadasHoje.coerceAtLeast(0)).coerceAtLeast(0)
    }

    /** Texto curto mostrado na tela de despesas. */
    fun resumoDoDia(lancadasHoje: Int, premium: Boolean): String {
        if (premium) return "Despesas ilimitadas"
        val restantes = restantesHoje(lancadasHoje, premium)
        return when (restantes) {
            0 -> "Limite de $DESPESAS_POR_DIA despesas por dia atingido"
            1 -> "Resta 1 despesa hoje (o plano Premium não tem limite)"
            else -> "Restam $restantes despesas hoje"
        }
    }
}
