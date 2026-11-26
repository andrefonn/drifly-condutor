package com.drifly.condutor

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Estado do Serviço de Acessibilidade do DriFly.
 *
 * O app **não consegue** religar o serviço sozinho (seria preciso
 * WRITE_SECURE_SETTINGS, que só existe com root/adb). Pior: em várias ROMs
 * (confirmado na Motorola) o próprio sistema zera
 * `enabled_accessibility_services` quando o app é forçado a parar ou atualizado.
 *
 * Então o que este helper faz é lembrar que o serviço já esteve ativo, detectar
 * quando ele caiu ([foiPerdida]) e abrir **a página exata do DriFly** nas
 * configurações, para o motorista recuperar em um único toque.
 */
object Acessibilidade {

    private const val PREFS = "drifly_acessibilidade"
    private const val CHAVE_ESTAVA_ATIVA = "a11y_estava_ativa"

    /**
     * `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` é oculto no SDK público,
     * mas existe desde o Android 8. Com `EXTRA_COMPONENT_NAME` ele abre direto a
     * tela do serviço (um toque para ligar), em vez da lista inteira.
     */
    private const val ACTION_DETALHES = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** O serviço do DriFly está ligado agora? */
    fun estaAtiva(context: Context): Boolean {
        val app = context.applicationContext
        val esperado = ComponentName(app, RotaAccessibilityService::class.java)

        // Caminho principal: a lista do AccessibilityManager já respeita o
        // accessibility_enabled (a string de Settings pode conter resto antigo).
        val gerenciador = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val lista = gerenciador?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).orEmpty()
        val porLista = lista.any { info ->
            val servico = info.resolveInfo?.serviceInfo ?: return@any false
            ComponentName(servico.packageName, servico.name) == esperado
        }
        if (porLista) return true

        // Fallback: leitura direta de Settings (algumas ROMs atrasam o manager).
        val ativos = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return ativos.split(":").any {
            it.equals(esperado.flattenToString(), ignoreCase = true) ||
                it.equals(esperado.flattenToShortString(), ignoreCase = true)
        }
    }

    /** Guarda que o serviço já esteve ativo (para detectar quando o sistema derrubar). */
    fun marcarAtiva(context: Context) {
        prefs(context).edit().putBoolean(CHAVE_ESTAVA_ATIVA, true).apply()
    }

    /** O serviço estava ativo em alguma execução anterior e agora não está. */
    fun foiPerdida(context: Context): Boolean {
        // A preferência própria pode não existir ainda logo depois de instalar/atualizar.
        // Se o motorista já usou o monitoramento alguma vez, é seguro inferir que o
        // serviço estava ligado antes — é assim que o aviso de reativação aparece na
        // primeira abertura desta versão, sem esperar um segundo ciclo.
        val prefs = PreferencesManager(context)
        val usouAntes = prefs(context).getBoolean(CHAVE_ESTAVA_ATIVA, false) ||
            prefs.shiftStartTime > 0L ||
            prefs.isMonitoringActive ||
            // Turno já rodou: o motorista usou o app antes desta versão, então o
            // serviço de acessibilidade estava ligado e o sistema derrubou agora.
            // Sem isso, logo após instalar a atualização o app não avisava nada e
            // o motorista só descobria a perda quando nenhuma oferta era lida.
            prefs.shiftDistanceKm > 0f
        return AberturaDecisao.acessibilidadeFoiPerdida(usouAntes, estaAtiva(context))
    }

    /** Esquece o último estado lembrado (quando a perda já foi resolvida/avisada). */
    fun esquecerPerdida(context: Context) {
        prefs(context).edit().putBoolean(CHAVE_ESTAVA_ATIVA, false).apply()
    }

    /**
     * Intent da tela de acessibilidade do DriFly (página do serviço).
     *
     * Não dá para confiar em `resolveActivity` aqui: essa ação é interceptada
     * pelo app de Configurações sem intent-filter público, então ela pode
     * resolver `null` e ainda assim abrir normalmente. Por isso a intent da
     * página do serviço é sempre a primeira tentativa.
     */
    fun intentConfiguracoes(context: Context): Intent {
        val app = context.applicationContext
        val componente = ComponentName(app, RotaAccessibilityService::class.java)
        return Intent(ACTION_DETALHES).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, componente.flattenToString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Lista geral de acessibilidade: saída de emergência quando a ROM ignora a ação acima. */
    fun intentLista(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Abre as configurações na página do DriFly; se a ROM não suportar, abre a
     * lista geral de acessibilidade. Não há reativação automática: o Android
     * exige o toque do usuário no interruptor.
     */
    fun abrirConfiguracoes(context: Context) {
        try {
            context.startActivity(intentConfiguracoes(context))
            return
        } catch (ignored: ActivityNotFoundException) {
        } catch (ignored: SecurityException) {
        }
        try {
            context.startActivity(intentLista())
        } catch (ignored: Exception) {
            AppLogger.w("DriFlyDiag", "A11Y: nenhuma tela de acessibilidade disponível")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
