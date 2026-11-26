package com.drifly.condutor

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Uma linha de texto lida da tela, com a posição na captura. */
data class LinhaDeTela(val texto: String, val bounds: Rect?)

/**
 * Leitura de texto na tela (OCR).
 *
 * Usa o ML Kit Text Recognition (português/latim), o mesmo motor da fase anterior.
 * É o ponto único de troca: a captura de tela e o fluxo de leitura não sabem qual
 * engine está por trás, só recebem as linhas com posição.
 *
 * O modelo vem embutido no APK (não depende do Google Play Services no aparelho) e
 * funciona sem internet.
 */
object LeitorDeTela {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Devolve as linhas lidas ou `null` quando a imagem não rendeu texto. */
    suspend fun ler(bitmap: Bitmap): List<LinhaDeTela>? {
        val texto = reconhecer(bitmap) ?: return null
        val linhas = texto.textBlocks
            .flatMap { it.lines }
            .mapNotNull { linha ->
                val conteudo = linha.text.trim()
                if (conteudo.isEmpty()) null else LinhaDeTela(conteudo, linha.boundingBox)
            }
            .sortedWith(compareBy({ it.bounds?.top ?: 0 }, { it.bounds?.left ?: 0 }))
        return linhas.ifEmpty { null }
    }

    private suspend fun reconhecer(bitmap: Bitmap): Text? {
        if (bitmap.isRecycled) return null
        return try {
            val imagem = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { cont ->
                recognizer.process(imagem)
                    .addOnSuccessListener { resultado -> if (cont.isActive) cont.resume(resultado) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                    .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
