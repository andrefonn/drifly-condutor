package com.drifly.condutor

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Recuo das barras do sistema.
 *
 * A partir do Android 15 (API 35) o app é desenhado atrás da barra de status, da
 * barra de navegação e do recorte da câmera, e não existe mais o ajuste
 * automático. Como o `targetSdk` é 36, sem isso o cabeçalho ficaria embaixo do
 * relógio e a barra de abas embaixo do botão de voltar.
 *
 * Nas versões anteriores nada muda: o próprio sistema continua empurrando o
 * conteúdo, então o método sai sem fazer nada.
 */
object BarrasDoSistema {

    /** Aplica o recuo na raiz da tela (chamar no `onCreate`, depois do `setContentView`). */
    fun aplicar(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)

        val conteudo = activity.findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(conteudo) { view, insets ->
            val barras = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Teclado aberto: o recuo de baixo passa a ser o do teclado, senão o
            // campo de digitação fica coberto.
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(
                barras.left,
                barras.top,
                barras.right,
                maxOf(barras.bottom, teclado)
            )
            insets
        }
        ViewCompat.requestApplyInsets(conteudo)
    }
}
