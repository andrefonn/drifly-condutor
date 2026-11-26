package com.drifly.condutor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Desenha os pinos do mapa como bitmap, no mesmo estilo da classe `.pin` do
 * protótipo:
 *
 * - círculo de 34 dp na cor do tipo, borda branca de 2.5 dp e glifo branco;
 * - ponta triangular embaixo (`.pin::after`), que é o ponto ancorado no mapa;
 * - sinal pendente: fundo branco, borda tracejada na cor e glifo na cor (`.pin.pend`);
 * - evento ativo: laranja, com anel externo translúcido (o protótipo pulsa; aqui
 *   o anel é estático, sem animação, para não gastar bateria).
 *
 * O bitmap é rasterizado com densidade (2x a 3x) para ficar nítido em qualquer
 * tela, e os resultados ficam em cache por (tipo, pendente).
 */
object SignalPinFactory {

    private const val DIAMETRO_DP = 34f
    private const val BORDA_DP = 2.5f
    private const val RABICHO_DP = 8f
    private const val RABICHO_BASE_DP = 10f
    private const val ANEL_DP = 7f
    private const val GLIFO_DP = 17f
    private const val FOLGA_DP = 1.5f

    private val bitmapCache = HashMap<String, Bitmap>()

    /** Chave estável de imagem para `Style.addImage(...)` do MapLibre. */
    fun iconKey(tipo: SignalType, pendente: Boolean): String = "pin_${tipo.id}_${if (pendente) "p" else "a"}"

    /** Bitmap rasterizado e em cache do pino para uso direto no MapLibre Native. */
    fun pinBitmap(contexto: Context, tipo: SignalType, pendente: Boolean): Bitmap {
        val chave = iconKey(tipo, pendente)
        bitmapCache[chave]?.takeIf { !it.isRecycled }?.let { return it }
        val bmp = desenhar(contexto, tipo, pendente)
        bitmapCache[chave] = bmp
        return bmp
    }

    private fun desenhar(contexto: Context, tipo: SignalType, pendente: Boolean): Bitmap {
        // O Google Maps desenha o bitmap do marcador em pixels de tela (1:1),
        // então rasterizar na densidade real mantém o pino nos 34 dp do protótipo
        // em qualquer aparelho.
        val escala = contexto.resources.displayMetrics.density.coerceAtLeast(1f)

        fun px(dp: Float): Float = dp * escala

        val raio = px(DIAMETRO_DP) / 2f
        val folga = px(FOLGA_DP)
        // O anel do evento ativo é o que ocupa mais espaço ao redor do círculo.
        val anel = if (tipo.evento && !pendente) px(ANEL_DP) else 0f

        val largura = ((raio + anel + folga) * 2f).roundToInt()
        val altura = ((raio + anel + folga) + raio + px(RABICHO_DP) + folga).roundToInt()
        val bitmap = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val centroX = largura / 2f
        val centroY = raio + anel + folga

        val tinta = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1) Anel externo translúcido do evento ativo.
        if (anel > 0f) {
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = px(2f)
            tinta.color = comAlpha(tipo.cor, 165)
            canvas.drawCircle(centroX, centroY, raio + anel * 0.75f, tinta)
        }

        // 2) Rabicho triangular (a ponta é o ponto ancorado no mapa).
        val rabicho = Path().apply {
            moveTo(centroX - px(RABICHO_BASE_DP) / 2f, centroY + raio - px(1f))
            lineTo(centroX + px(RABICHO_BASE_DP) / 2f, centroY + raio - px(1f))
            lineTo(centroX, centroY + raio + px(RABICHO_DP))
            close()
        }
        tinta.style = Paint.Style.FILL
        tinta.color = tipo.cor
        tinta.shader = null
        canvas.drawPath(rabicho, tinta)

        // 3) Círculo principal.
        tinta.style = Paint.Style.FILL
        if (pendente) {
            tinta.color = Color.WHITE
            tinta.shader = null
        } else if (tipo.evento) {
            // Gradiente laranja do `.pin.ev`.
            tinta.shader = LinearGradient(
                centroX - raio, centroY - raio, centroX + raio, centroY + raio,
                Color.parseColor("#FF9506"), Color.parseColor("#FF6A00"),
                Shader.TileMode.CLAMP
            )
        } else {
            tinta.color = tipo.cor
            tinta.shader = null
        }
        canvas.drawCircle(centroX, centroY, raio, tinta)

        // 4) Borda (branca, ou tracejada na cor do tipo quando pendente).
        tinta.shader = null
        tinta.style = Paint.Style.STROKE
        tinta.strokeWidth = px(BORDA_DP)
        tinta.color = if (pendente) tipo.cor else Color.WHITE
        tinta.pathEffect = if (pendente) {
            DashPathEffect(floatArrayOf(px(3f), px(2.5f)), 0f)
        } else {
            null
        }
        canvas.drawCircle(centroX, centroY, raio - px(BORDA_DP) / 2f, tinta)
        tinta.pathEffect = null

        // 5) Glifo do tipo, centralizado (17 dp, como o svgI(…, 17) do protótipo).
        val glifo = ContextCompat.getDrawable(contexto, tipo.icone)?.mutate()
        if (glifo != null) {
            glifo.setTint(if (pendente) tipo.cor else Color.WHITE)
            val meia = px(GLIFO_DP) / 2f
            glifo.setBounds(
                (centroX - meia).roundToInt(),
                (centroY - meia).roundToInt(),
                (centroX + meia).roundToInt(),
                (centroY + meia).roundToInt()
            )
            glifo.draw(canvas)
        }

        return bitmap
    }

    /** Mesma cor com alpha (0..255) aplicado. */
    private fun comAlpha(cor: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(cor), Color.green(cor), Color.blue(cor))
}
