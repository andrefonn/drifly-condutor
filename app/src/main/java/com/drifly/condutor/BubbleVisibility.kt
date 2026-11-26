package com.drifly.condutor

/**
 * Regra pura de visibilidade da bolinha flutuante.
 *
 * A bolinha flutuante é **exclusiva do DriFly Premium**: no plano básico ela
 * não aparece, nem pelo monitoramento nem pelo atalho da notificação.
 *
 * A bolinha tem duas coisas diferentes:
 *  - a **chave do usuário** (`floatingBubbleEnabled`): ligada por padrão e só
 *    muda nas Configurações;
 *  - o **estado de escondida** (`floatingBubbleHidden`): ligado quando o
 *    motorista arrasta a bolinha até o X. Não é uma configuração, é só "sumiu
 *    agora"; volta ao iniciar o monitoramento, ao ligar a chave ou pelo atalho
 *    "Mostrar bolinha" da notificação.
 *
 * Sem separar as duas coisas, fechar pelo X gravava `enabled = false` e a
 * bolinha não voltava nem no próximo monitoramento.
 */
object BubbleVisibility {

    fun deveMostrar(
        premium: Boolean,
        enabled: Boolean,
        hidden: Boolean,
        overlayPermitido: Boolean
    ): Boolean = premium && enabled && !hidden && overlayPermitido

    /** Iniciar monitoramento (ação do motorista) sempre traz a bolinha de volta. */
    fun aoIniciarMonitoramento(premium: Boolean, enabled: Boolean): Boolean = premium && enabled
}
