package com.drifly.condutor.map

import com.drifly.condutor.EstiloMapa
import com.drifly.condutor.map.pack.PackInfo
import com.drifly.condutor.map.pack.RegionCatalogSnapshot
import com.drifly.condutor.map.pack.RegionPack

/**
 * Modo visual ativo do mapa híbrido do DriFly:
 * - [OfflinePack]: posição dentro de um pacote instalado -> estilo vetorial 100% offline local;
 * - [OnlineOpenFreeMap]: fora de pacotes instalados com internet -> OpenFreeMap sem chave + banner de download;
 * - [OfflineUnavailable]: fora de pacotes instalados e sem internet -> aviso amigável.
 */
sealed class MapVisualMode {
    data class OfflinePack(val pack: PackInfo) : MapVisualMode()

    data class OnlineOpenFreeMap(
        val styleUrl: String = EstiloMapa.OPENFREEMAP_STYLE_URL,
        val suggestedPack: RegionPack?
    ) : MapVisualMode()

    data class OfflineUnavailable(
        val suggestedPack: RegionPack?
    ) : MapVisualMode()

    /** Identificador estável do estilo para evitar recarregamento redundante do MapLibre. */
    val styleKey: String
        get() = when (this) {
            is OfflinePack -> "offline:${pack.packId}"
            is OnlineOpenFreeMap -> "online:$styleUrl"
            is OfflineUnavailable -> "unavailable"
        }
}

/**
 * Controlador de decisão híbrida (Offline Local vs OpenFreeMap Online) com histerese
 * para evitar flicker nas bordas de estados/cidades durante pan/zoom ou condução.
 */
class HybridStyleController(
    private val borderHysteresisDeg: Double = DEFAULT_BORDER_HYSTERESIS_DEG,
    private val minSwitchIntervalMs: Long = DEFAULT_MIN_SWITCH_INTERVAL_MS
) {

    private var currentMode: MapVisualMode? = null
    private var lastStyleSwitchMs: Long = 0L

    fun current(): MapVisualMode? = currentMode

    fun reset() {
        currentMode = null
        lastStyleSwitchMs = 0L
    }

    /**
     * Avalia o modo visual ideal para `(lat, lng)` considerando:
     * 1. Pacotes instalados (escolhe sempre o de MENOR área que contém o ponto; aplica margem de
     *    histerese de borda se o pacote já estiver ativo para evitar oscilações);
     * 2. Conectividade (`isOnline`) -> OpenFreeMap online ou aviso offline;
     * 3. Melhor pacote remoto sugerido no catálogo para o banner "Baixar <Estado>".
     *
     * @param forceImmediate ignora o debounce temporal (ex.: logo após concluir download ou mudança de rede).
     * @return Pair(novoModo, estiloMudou)
     */
    fun evaluate(
        lat: Double,
        lng: Double,
        installedPacks: List<PackInfo>,
        catalog: RegionCatalogSnapshot?,
        isOnline: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        forceImmediate: Boolean = false
    ): Pair<MapVisualMode, Boolean> {
        val current = currentMode

        // 1) Verifica se algum pacote instalado cobre o ponto (menor área vence: cidade > estado).
        val exactMatch = installedPacks
            .filter { info -> info.contains(lat, lng, marginDeg = 0.0, catalogPack = catalog?.findById(info.packId)) }
            .minByOrNull { info -> info.selectionArea(catalog?.findById(info.packId)) }

        val chosenInstalled: PackInfo? = when {
            exactMatch != null -> exactMatch
            current is MapVisualMode.OfflinePack -> {
                // Histerese de borda: mantém o pacote atual se ainda estiver dentro da margem (~2.2 km)
                // e o pacote continuar instalado.
                val stillInstalled = installedPacks.firstOrNull { it.packId == current.pack.packId }
                if (stillInstalled != null &&
                    stillInstalled.contains(lat, lng, marginDeg = borderHysteresisDeg, catalogPack = catalog?.findById(stillInstalled.packId))
                ) {
                    stillInstalled
                } else {
                    null
                }
            }
            else -> null
        }

        val suggestedPack = if (chosenInstalled == null) {
            catalog?.findBestPackFor(lat, lng)
        } else {
            null
        }

        val desired: MapVisualMode = when {
            chosenInstalled != null -> MapVisualMode.OfflinePack(chosenInstalled)
            isOnline -> MapVisualMode.OnlineOpenFreeMap(
                styleUrl = EstiloMapa.OPENFREEMAP_STYLE_URL,
                suggestedPack = suggestedPack
            )
            else -> MapVisualMode.OfflineUnavailable(suggestedPack = suggestedPack)
        }

        // Debounce temporal para transições de estilo (evita flicker em arraste rápido).
        val styleChanged = current == null || current.styleKey != desired.styleKey
        if (styleChanged && current != null && !forceImmediate && (nowMs - lastStyleSwitchMs) < minSwitchIntervalMs) {
            // Mantém o estilo atual mas atualiza o pacote sugerido se estivermos em modo online/offline.
            val kept = when (current) {
                is MapVisualMode.OnlineOpenFreeMap -> current.copy(suggestedPack = suggestedPack)
                is MapVisualMode.OfflineUnavailable -> current.copy(suggestedPack = suggestedPack)
                is MapVisualMode.OfflinePack -> current
            }
            currentMode = kept
            return kept to false
        }

        currentMode = desired
        if (styleChanged) {
            lastStyleSwitchMs = nowMs
        }
        return desired to styleChanged
    }

    companion object {
        /** ~2,2 km de margem de histerese na borda de um pacote ativo. */
        const val DEFAULT_BORDER_HYSTERESIS_DEG = 0.02

        /** Intervalo mínimo entre trocas de estilo MapLibre durante arraste rápido. */
        const val DEFAULT_MIN_SWITCH_INTERVAL_MS = 1_200L
    }
}
