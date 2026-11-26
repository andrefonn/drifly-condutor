package com.drifly.condutor.map.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.drifly.condutor.map.routing.ManeuverType
import com.drifly.condutor.map.routing.RouteStep
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Abstração de fala para permitir testes unitários determinísticos na JVM. */
interface NavigationVoiceSpeaker : Closeable {
    var isMuted: Boolean
    fun speak(text: String, flush: Boolean = true)
    fun stop()
    override fun close()
}

/**
 * Motor de voz Turn-by-Turn 100% offline usando `TextToSpeech` nativo do Android em `pt-BR`.
 *
 * Solicita foco de áudio transitório (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) para abaixar
 * música/podcasts do motorista durante o comando e devolver o volume logo após terminar.
 */
class NavigationVoiceEngine(context: Context) : NavigationVoiceSpeaker {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val utteranceSeq = AtomicInteger(0)

    private var tts: TextToSpeech? = null
    private var ready = false
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    override var isMuted: Boolean = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(false)
            .build()

        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val ptBr = Locale.forLanguageTag("pt-BR")
                val res = engine.setLanguage(ptBr)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.forLanguageTag("pt"))
                }
                engine.setAudioAttributes(attrs)
                engine.setSpeechRate(1.05f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = abandonFocus()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = abandonFocus()
                    override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
                })
                ready = true
            }
        }
    }

    override fun speak(text: String, flush: Boolean) {
        if (isMuted || !ready || text.isBlank()) return
        val engine = tts ?: return
        requestFocus()
        val id = "drifly_nav_${utteranceSeq.incrementAndGet()}"
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, id)
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        abandonFocus()
    }

    override fun close() {
        stop()
        ready = false
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    private fun requestFocus() {
        val req = focusRequest ?: return
        try {
            audioManager?.requestAudioFocus(req)
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        try {
            audioManager?.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * Constrói frase natural em português do Brasil para o comando de voz.
         * Quando `distanceMeters <= 25`, emite comando imediato ("Vire à direita agora").
         */
        fun formatSpokenPrompt(step: RouteStep, distanceMeters: Double): String {
            if (step.maneuver == ManeuverType.ARRIVE && distanceMeters <= 35.0) {
                return "Você chegou ao destino."
            }
            val base = step.instructionPtBr.trim().ifBlank { defaultManeuverPhrase(step) }
            val lowerBase = base.replaceFirstChar { it.lowercase(Locale.forLanguageTag("pt-BR")) }
            return when {
                distanceMeters <= 25.0 -> "$base agora."
                distanceMeters <= 110.0 -> "Em 80 metros, $lowerBase."
                distanceMeters <= 360.0 -> "Em 300 metros, $lowerBase."
                else -> "Em 500 metros, $lowerBase."
            }
        }

        fun defaultManeuverPhrase(step: RouteStep): String {
            val via = step.streetName.trim()
            val emVia = if (via.isNotEmpty()) " na $via" else ""
            return when (step.maneuver) {
                ManeuverType.DEPART -> "Siga$emVia"
                ManeuverType.STRAIGHT -> "Siga em frente$emVia"
                ManeuverType.SLIGHT_RIGHT -> "Mantenha-se à direita$emVia"
                ManeuverType.RIGHT -> "Vire à direita$emVia"
                ManeuverType.SHARP_RIGHT -> "Faça curva acentuada à direita$emVia"
                ManeuverType.SLIGHT_LEFT -> "Mantenha-se à esquerda$emVia"
                ManeuverType.LEFT -> "Vire à esquerda$emVia"
                ManeuverType.SHARP_LEFT -> "Faça curva acentuada à esquerda$emVia"
                ManeuverType.UTURN_LEFT, ManeuverType.UTURN_RIGHT -> "Faça o retorno$emVia"
                ManeuverType.ROUNDABOUT_ENTER -> {
                    val exit = step.roundaboutExit
                    if (exit != null && exit > 0) {
                        "Na rotatória, pegue a ${exit}ª saída$emVia"
                    } else {
                        "Entre na rotatória$emVia"
                    }
                }
                ManeuverType.ROUNDABOUT_EXIT -> "Saia da rotatória$emVia"
                ManeuverType.MERGE -> "Entre na via$emVia"
                ManeuverType.RAMP_RIGHT -> "Pegue a saída à direita$emVia"
                ManeuverType.RAMP_LEFT -> "Pegue a saída à esquerda$emVia"
                ManeuverType.WAYPOINT_ARRIVE -> "Você chegou à parada"
                ManeuverType.ARRIVE -> "Você chegou ao destino"
            }
        }
    }
}
