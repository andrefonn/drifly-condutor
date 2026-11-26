package com.drifly.condutor

import android.content.Context

/**
 * Estado do plano do DriFly Condutor.
 *
 * Fase de desenvolvimento: ainda não existe cobrança, login nem servidor, então
 * todo recurso fica liberado. A API é a mesma que as telas já consultam, para
 * que a monetização entre depois trocando só esta implementação — as telas não
 * precisam saber como o plano é cobrado.
 */
object Premium {

    private val observadores = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** `true` quando o plano pago está ativo. No desenvolvimento, sempre. */
    @Suppress("UNUSED_PARAMETER")
    fun ativo(contexto: Context): Boolean = true

    /** Registra quem quer saber quando o plano muda (mapa, bolinha, Modo Casa). */
    fun adicionarObservador(observador: (Boolean) -> Unit) {
        if (!observadores.contains(observador)) observadores.add(observador)
        observador(true)
    }

    fun removerObservador(observador: (Boolean) -> Unit) {
        observadores.remove(observador)
    }

    /** Confere o plano ao abrir o app. Ainda não há loja: nada a consultar. */
    @Suppress("UNUSED_PARAMETER")
    fun iniciar(contexto: Context) = Unit

    /** Sincronização com o servidor de contas. Não existe conta nesta fase. */
    @Suppress("UNUSED_PARAMETER")
    fun sincronizarBanco(contexto: Context, aoConcluir: (() -> Unit)? = null) {
        aoConcluir?.invoke()
    }
}
