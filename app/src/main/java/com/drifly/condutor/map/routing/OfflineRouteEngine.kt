package com.drifly.condutor.map.routing

import java.io.Closeable
import java.io.File

/**
 * Contrato desacoplado do motor de roteamento 100% offline.
 *
 * Permite trocar ou evoluir a implementação nativa (Valhalla Mobile, GraphHopper
 * ou roteador binário leve) sem tocar no otimizador multi-paradas, na Lista Negra
 * nem no controlador Turn-by-Turn.
 */
interface OfflineRouteEngine : Closeable {
    val engineName: String
    val isReady: Boolean

    /** Inicializa o motor apontando para o diretório do pacote (`filesDir/map_packs/<pack>/`). */
    suspend fun initialize(packDir: File): Boolean

    /** Calcula rota principal e (opcionalmente) rotas alternativas 100% offline. */
    suspend fun computeRoute(request: RouteRequest): RouteResponse

    /**
     * Matriz NxN de tempo de viagem (segundos) entre [points], respeitando [profile] e [avoidZones].
     * Retornar `null` faz o [SmartRouteOptimizer] usar cálculo por pares ou heurística Haversine.
     */
    suspend fun computeCostMatrix(
        points: List<GeoPoint>,
        profile: RouteProfile,
        avoidZones: List<AvoidZone>
    ): Array<DoubleArray>? = null

    override fun close()
}
