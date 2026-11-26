package com.drifly.condutor

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Entrada rápida por digital/rosto.
 *
 * O motorista abre o app dezenas de vezes no dia; digitar conta toda vez não
 * funciona. Como a sessão do Google já fica salva no aparelho, a biometria serve
 * como uma **fechadura local**: confirma que é o dono do celular antes de mostrar
 * o app. Se não houver digital cadastrada, cai para o PIN/padrão do aparelho.
 */
object Desbloqueio {

    private const val PREFS = "drifly_seguranca"
    private const val CHAVE_ATIVA = "biometria_ativa"

    /** `true` quando o motorista escolheu entrar com biometria. */
    fun ativa(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(CHAVE_ATIVA, false)

    fun definir(context: Context, ativo: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CHAVE_ATIVA, ativo)
            .apply()
    }

    /** Existe digital/rosto (ou senha do aparelho) configurada? */
    fun disponivel(context: Context): Boolean {
        val gerenciador = BiometricManager.from(context)
        return gerenciador.canAuthenticate(permitidos()) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Pede a biometria. [aoOk] só é chamado quando o aparelho confirma o dono;
     * em erro/cancelamento chamamos [aoFalhar].
     */
    fun pedir(
        activity: FragmentActivity,
        titulo: String = "Entrar no DriFly",
        subtitulo: String = "Confirme sua digital para abrir",
        aoOk: () -> Unit,
        aoFalhar: (codigo: Int, mensagem: String) -> Unit
    ) {
        if (!disponivel(activity)) {
            aoFalhar(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, "Biometria não configurada neste aparelho.")
            return
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult) {
                    aoOk()
                }

                override fun onAuthenticationError(codigo: Int, mensagem: CharSequence) {
                    aoFalhar(codigo, mensagem.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(titulo)
            .setSubtitle(subtitulo)
            .setAllowedAuthenticators(permitidos())
            .build()
        prompt.authenticate(info)
    }

    /** Digital/rosto forte, com a senha do aparelho como saída de emergência. */
    private fun permitidos(): Int =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
