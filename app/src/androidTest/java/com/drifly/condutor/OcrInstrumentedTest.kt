package com.drifly.condutor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica no aparelho que o OCR volta a ler texto de uma captura de tela. */
@RunWith(AndroidJUnit4::class)
class OcrInstrumentedTest {

    @Test
    fun leTextoDaCaptura() {
        val bitmap = Bitmap.createBitmap(900, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 84f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("CONFIRMAR CORRIDA", 450f, 140f, paint)

        val linhas = runBlocking { LeitorDeTela.ler(bitmap) }
        bitmap.recycle()

        assertTrue("OCR não devolveu linhas", !linhas.isNullOrEmpty())
        val texto = linhas!!.joinToString(" ") { it.texto }
        assertTrue("Texto inesperado: $texto", texto.contains("CONFIRMAR", ignoreCase = true))
    }
}
