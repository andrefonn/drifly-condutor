package com.drifly.condutor

/**
 * Decisões puras da abertura do app (sem nenhuma dependência do Android).
 *
 * Ficam aqui para poderem ser testadas na JVM: a capa preta de bloqueio e a
 * animação de abertura já causaram tela preta travada na segunda abertura do
 * app, então a regra que decide "toca a animação?" e "mostra/limpa a capa?"
 * precisa ser determinística e verificável fora do aparelho.
 */
object AberturaDecisao {

    /**
     * A animação cinematográfica roda **uma vez por processo**, e nunca quando a
     * tela está sendo recriada (rotação/tema) nem quando o app foi aberto direto
     * em uma aba (atalho, balão, notificação).
     *
     * Se o processo já tocou a animação, a abertura é instantânea — e aí a capa
     * de bloqueio precisa ser escondida explicitamente, porque não existe
     * callback da animação para fazer isso.
     */
    fun deveTocarAnimacao(
        hasSavedInstanceState: Boolean,
        isBackgroundShortcutIntent: Boolean,
        jaTocouNoProcesso: Boolean
    ): Boolean = !jaTocouNoProcesso && !hasSavedInstanceState && !isBackgroundShortcutIntent

    /**
     * A capa preta só pode existir enquanto existir desbloqueio pendente.
     * Sem biometria pendente ela nunca deve ser mostrada (senão vira tela preta).
     */
    fun deveMostrarCapaDeBloqueio(biometriaAtiva: Boolean, biometriaConfirmada: Boolean): Boolean =
        biometriaAtiva && !biometriaConfirmada

    /** Pref dizia "acessibilidade ativa" e agora o serviço está desligado: o sistema derrubou. */
    fun acessibilidadeFoiPerdida(estavaAtiva: Boolean, estaAtivaAgora: Boolean): Boolean =
        estavaAtiva && !estaAtivaAgora

    /** O aviso de acessibilidade perdida aparece no máximo uma vez por execução do app. */
    fun deveAvisarAcessibilidadePerdida(foiPerdida: Boolean, jaAvisouNoProcesso: Boolean): Boolean =
        foiPerdida && !jaAvisouNoProcesso
}
