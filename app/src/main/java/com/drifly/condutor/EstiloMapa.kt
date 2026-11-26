package com.drifly.condutor

import java.io.File

/**
 * Estilo MapLibre v8 100% offline do DriFly.
 *
 * Prioriza os estilos completos do pacote (`style_day.json` / `style_night.json`,
 * 70 camadas Protomaps v4 sem URLs externas), injetando a porta efêmera ativa do
 * [com.drifly.condutor.map.tiles.LocalMapTileServer] no lugar do placeholder `http://127.0.0.1:8765`.
 * Caso o arquivo de estilo não esteja presente num pacote sideloaded mínimo, gera um
 * estilo vetorial limpo para direção (ruas + rótulos + parques + água, sem ruído comercial).
 */
object EstiloMapa {

    private const val PACK_STYLE_PLACEHOLDER = "http://127.0.0.1:8765"

    /** Estilo vetorial online gratuito (sem chave, sem limites) para cidades fora de pacotes baixados. */
    const val OPENFREEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    const val OPENFREEMAP_DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

    fun openFreeMapStyleUrl(nightMode: Boolean = false): String =
        if (nightMode) OPENFREEMAP_DARK_STYLE_URL else OPENFREEMAP_STYLE_URL

    /** Atribuição obrigatória exibida sempre que o estilo online do OpenFreeMap estiver ativo. */
    const val OPENFREEMAP_ATTRIBUTION = "OpenFreeMap © OpenMapTiles · Data from OpenStreetMap"

    fun buildStyleJson(packDir: File, baseUrl: String, nightMode: Boolean = false): String {
        val fileName = if (nightMode) "style_night.json" else "style_day.json"
        val styleFile = File(packDir, fileName).takeIf { it.isFile && it.length() > 0L }
            ?: File(packDir, "style_day.json").takeIf { it.isFile && it.length() > 0L }

        if (styleFile != null) {
            return styleFile.readText(Charsets.UTF_8)
                .replace(PACK_STYLE_PLACEHOLDER, baseUrl.trimEnd('/'))
        }
        return buildFallbackDrivingStyle(baseUrl.trimEnd('/'), nightMode)
    }

    /** Estilo vetorial mínimo compatível com Protomaps v4 (`earth`, `water`, `landuse`, `buildings`, `roads`, `places`). */
    fun buildFallbackDrivingStyle(baseUrl: String, nightMode: Boolean = false): String {
        val bg = if (nightMode) "#101724" else "#F3F5F9"
        val water = if (nightMode) "#182A44" else "#CFE4FC"
        val park = if (nightMode) "#162B24" else "#D9EEDB"
        val building = if (nightMode) "#1B2538" else "#E5E9F0"
        val roadMinor = if (nightMode) "#2A364B" else "#FFFFFF"
        val roadMajor = if (nightMode) "#3B4B66" else "#FFF8E1"
        val roadHighway = if (nightMode) "#B45309" else "#FFD166"
        val roadCasing = if (nightMode) "#0C121D" else "#CBD5E1"
        val labelColor = if (nightMode) "#E2E8F0" else "#1E293B"
        val haloColor = if (nightMode) "#0F172A" else "#FFFFFF"

        return """
        {
          "version": 8,
          "name": "DriFly Offline",
          "sources": {
            "protomaps": {
              "type": "vector",
              "tiles": ["$baseUrl/tiles/{z}/{x}/{y}.mvt"],
              "minzoom": 0,
              "maxzoom": 15
            }
          },
          "glyphs": "$baseUrl/glyphs/{fontstack}/{range}.pbf",
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "$bg" } },
            { "id": "landuse-park", "type": "fill", "source": "protomaps", "source-layer": "landuse",
              "filter": ["in", "kind", "park", "forest", "grass", "recreation_ground"],
              "paint": { "fill-color": "$park" } },
            { "id": "water", "type": "fill", "source": "protomaps", "source-layer": "water",
              "paint": { "fill-color": "$water" } },
            { "id": "buildings", "type": "fill", "source": "protomaps", "source-layer": "buildings",
              "minzoom": 13, "paint": { "fill-color": "$building", "fill-opacity": 0.7 } },
            { "id": "roads-casing", "type": "line", "source": "protomaps", "source-layer": "roads",
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "$roadCasing",
                "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 1.2, 14, 6.5, 17, 16.0]
              }
            },
            { "id": "roads-minor", "type": "line", "source": "protomaps", "source-layer": "roads",
              "filter": ["!", ["in", "kind_detail", "motorway", "trunk", "primary", "secondary"]],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "$roadMinor",
                "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 11, 0.8, 14, 4.5, 17, 13.0]
              }
            },
            { "id": "roads-major", "type": "line", "source": "protomaps", "source-layer": "roads",
              "filter": ["in", "kind_detail", "primary", "secondary"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "$roadMajor",
                "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 1.5, 14, 6.0, 17, 15.0]
              }
            },
            { "id": "roads-highway", "type": "line", "source": "protomaps", "source-layer": "roads",
              "filter": ["in", "kind_detail", "motorway", "trunk"],
              "layout": { "line-cap": "round", "line-join": "round" },
              "paint": {
                "line-color": "$roadHighway",
                "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 8, 2.0, 14, 7.5, 17, 17.0]
              }
            },
            { "id": "road-labels", "type": "symbol", "source": "protomaps", "source-layer": "roads",
              "minzoom": 13,
              "layout": {
                "symbol-placement": "line",
                "text-field": ["coalesce", ["get", "name:pt"], ["get", "name"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10.5, 17, 13.5]
              },
              "paint": { "text-color": "$labelColor", "text-halo-color": "$haloColor", "text-halo-width": 1.5 }
            },
            { "id": "place-labels", "type": "symbol", "source": "protomaps", "source-layer": "places",
              "layout": {
                "text-field": ["coalesce", ["get", "name:pt"], ["get", "name"]],
                "text-font": ["Noto Sans Medium"],
                "text-size": ["interpolate", ["linear"], ["zoom"], 10, 11, 14, 14]
              },
              "paint": { "text-color": "$labelColor", "text-halo-color": "$haloColor", "text-halo-width": 1.8 }
            }
          ]
        }
        """.trimIndent()
    }
}
