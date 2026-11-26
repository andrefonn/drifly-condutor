package com.drifly.condutor.map.routing

import java.util.Locale
import kotlin.math.abs

/**
 * Gerador de manobras Turn-by-Turn em português do Brasil (pt-BR) sobre arestas [DfrgGraph].
 *
 * Responsabilidades:
 * - Colapsar trechos contínuos na mesma via (evitando avisos espúrios em curvas suaves);
 * - Classificar mudanças de azimute (ligeiramente à esquerda/direita, vire à esquerda/direita,
 *   curva acentuada, retorno, mantenha-se);
 * - Contar saídas reais de rotatórias inspecionando arestas não-rotatória nos nós atravessados
 *   (`Na rotatória, pegue a Nª saída para <Rua>`);
 * - Reconhecer acessos/alças rodoviárias (`isLink`) e entradas em vias principais (`MERGE`);
 * - Gerar frases naturais tanto para o banner visual quanto para o [com.drifly.condutor.map.nav.NavigationVoiceEngine]
 *   (sem ponto final, pois o TTS concatena `" agora."` e prefixos de distância).
 */
object ManeuverGeneratorPtBr {

    /**
     * Representa um trecho elementar de uma perna (leg) já mapeado na geometria global da rota.
     * Suporta tanto arestas completas quanto trechos parciais de snap inicial/final.
     */
    internal data class LegEdgeSegment(
        val edgeId: Int,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val geomStartIndex: Int,
        val geomEndIndex: Int
    )

    private data class RawStep(
        val maneuver: ManeuverType,
        val streetName: String,
        var distanceMeters: Double,
        var durationSeconds: Double,
        val geometryStartIndex: Int,
        var geometryEndIndex: Int,
        val roundaboutExit: Int? = null,
        val customInstruction: String? = null
    )

    /**
     * Constrói a lista de [RouteStep] para uma perna (`from -> to`) da rota.
     *
     * @param stepIndexStart índice global inicial do primeiro passo (mantém unicidade entre pernas para o TTS).
     * @param waypointNumber 1-based quando [isFinalLeg] é `false` (`Você chegou à parada N`).
     * @param isFinalLeg `true` na última perna (`Você chegou ao seu destino`).
     */
    internal fun generateLegSteps(
        graph: DfrgGraph,
        segments: List<LegEdgeSegment>,
        geometry: List<GeoPoint>,
        destinationLabel: String,
        stepIndexStart: Int,
        waypointNumber: Int,
        isFinalLeg: Boolean
    ): List<RouteStep> {
        val destClean = destinationLabel.trim()
        if (segments.isEmpty()) {
            val endIdx = (geometry.size - 1).coerceAtLeast(0)
            val arriveType = if (isFinalLeg) ManeuverType.ARRIVE else ManeuverType.WAYPOINT_ARRIVE
            val instruction = arrivalInstruction(isFinalLeg, waypointNumber, destClean)
            return listOf(
                RouteStep(
                    index = stepIndexStart,
                    maneuver = arriveType,
                    streetName = destClean,
                    instructionPtBr = instruction,
                    distanceMeters = 0.0,
                    durationSeconds = 0.0,
                    geometryStartIndex = endIdx,
                    geometryEndIndex = endIdx
                )
            )
        }

        val rawSteps = ArrayList<RawStep>(16)

        var idx = 0
        while (idx < segments.size) {
            val seg = segments[idx]
            val edgeId = seg.edgeId

            // Caso especial 1: sequência de arestas em rotatória -> colapsa e conta saídas reais.
            if (graph.isRoundabout(edgeId)) {
                val rStart = idx
                var rEnd = idx
                var rDist = seg.distanceMeters
                var rDur = seg.durationSeconds
                while (rEnd + 1 < segments.size && graph.isRoundabout(segments[rEnd + 1].edgeId)) {
                    rEnd++
                    rDist += segments[rEnd].distanceMeters
                    rDur += segments[rEnd].durationSeconds
                }

                val exitSeg = segments.getOrNull(rEnd + 1)
                val exitCount = countRoundaboutExit(graph, segments, rStart, rEnd, hasExitEdge = exitSeg != null)
                val exitName = if (exitSeg != null) {
                    resolveTargetStreetName(graph, segments, rEnd + 1)
                } else {
                    ""
                }

                val stepGeomStart = segments[rStart].geomStartIndex
                var stepGeomEnd = segments[rEnd].geomEndIndex
                var totalDist = rDist
                var totalDur = rDur

                // Incorpora o primeiro segmento após sair da rotatória ao passo da rotatória
                // (padrão Google/Uber: o motorista vê a distância até a rotatória e depois segue na via de saída).
                var nextIdx = rEnd + 1
                if (exitSeg != null) {
                    totalDist += exitSeg.distanceMeters
                    totalDur += exitSeg.durationSeconds
                    stepGeomEnd = exitSeg.geomEndIndex
                    nextIdx = rEnd + 2
                }

                val instruction = buildRoundaboutInstruction(exitCount, exitName)
                rawSteps.add(
                    RawStep(
                        maneuver = ManeuverType.ROUNDABOUT_ENTER,
                        streetName = exitName,
                        distanceMeters = totalDist,
                        durationSeconds = totalDur,
                        geometryStartIndex = stepGeomStart,
                        geometryEndIndex = stepGeomEnd,
                        roundaboutExit = exitCount,
                        customInstruction = instruction
                    )
                )
                idx = nextIdx
                continue
            }

            val streetName = graph.edgeName(edgeId).trim()

            // Primeiro segmento da perna -> DEPART
            if (rawSteps.isEmpty()) {
                val departName = if (streetName.isEmpty() && graph.isLink(edgeId)) {
                    resolveTargetStreetName(graph, segments, idx)
                } else {
                    streetName
                }
                val departCustom = if (graph.isLink(edgeId) && departName.isNotEmpty()) {
                    "Pegue o acesso para $departName"
                } else {
                    null
                }
                rawSteps.add(
                    RawStep(
                        maneuver = ManeuverType.DEPART,
                        streetName = departName,
                        distanceMeters = seg.distanceMeters,
                        durationSeconds = seg.durationSeconds,
                        geometryStartIndex = seg.geomStartIndex,
                        geometryEndIndex = seg.geomEndIndex,
                        customInstruction = departCustom
                    )
                )
                idx++
                continue
            }

            val prevSeg = segments[idx - 1]
            val prevEdgeId = prevSeg.edgeId
            val inBearing = segmentEndBearing(geometry, prevSeg)
            val outBearing = segmentStartBearing(geometry, seg)
            val deltaDeg = normalizeTurnAngleDeg(outBearing - inBearing)
            val absDelta = abs(deltaDeg)

            val prevIsLink = graph.isLink(prevEdgeId)
            val currIsLink = graph.isLink(edgeId)
            val currClass = graph.roadClass(edgeId)
            val prevClass = graph.roadClass(prevEdgeId)
            val currStep = rawSteps.last()

            // Caso especial 2: entrada em alça/acesso rodoviário (`isLink`).
            if (!prevIsLink && currIsLink) {
                val targetName = resolveTargetStreetName(graph, segments, idx)
                val rampType = if (deltaDeg < -10.0) ManeuverType.RAMP_LEFT else ManeuverType.RAMP_RIGHT
                rawSteps.add(
                    RawStep(
                        maneuver = rampType,
                        streetName = targetName,
                        distanceMeters = seg.distanceMeters,
                        durationSeconds = seg.durationSeconds,
                        geometryStartIndex = seg.geomStartIndex,
                        geometryEndIndex = seg.geomEndIndex,
                        customInstruction = buildRampInstruction(rampType, targetName)
                    )
                )
                idx++
                continue
            }

            // Caso especial 3: saída de alça entrando em via principal (`MERGE`).
            if (prevIsLink && !currIsLink && currClass <= DfrgGraph.ROAD_CLASS_PRIMARY && absDelta < 55.0) {
                rawSteps.add(
                    RawStep(
                        maneuver = ManeuverType.MERGE,
                        streetName = streetName,
                        distanceMeters = seg.distanceMeters,
                        durationSeconds = seg.durationSeconds,
                        geometryStartIndex = seg.geomStartIndex,
                        geometryEndIndex = seg.geomEndIndex,
                        customInstruction = buildMergeInstruction(streetName)
                    )
                )
                idx++
                continue
            }

            // Continuação dentro de alça (`isLink -> isLink`): colapsa sem duplicar avisos.
            if (prevIsLink && currIsLink) {
                currStep.distanceMeters += seg.distanceMeters
                currStep.durationSeconds += seg.durationSeconds
                currStep.geometryEndIndex = seg.geomEndIndex
                idx++
                continue
            }

            // Verifica retorno explícito (voltando pelo nó de origem da aresta anterior ou ângulo >= 155°).
            val isDirectReverse = graph.edgeFrom[prevEdgeId] == graph.edgeTo[edgeId] &&
                graph.edgeTo[prevEdgeId] == graph.edgeFrom[edgeId]
            if (isDirectReverse || absDelta >= 155.0) {
                val uturnType = if (deltaDeg < 0.0) ManeuverType.UTURN_LEFT else ManeuverType.UTURN_RIGHT
                rawSteps.add(
                    RawStep(
                        maneuver = uturnType,
                        streetName = streetName,
                        distanceMeters = seg.distanceMeters,
                        durationSeconds = seg.durationSeconds,
                        geometryStartIndex = seg.geomStartIndex,
                        geometryEndIndex = seg.geomEndIndex
                    )
                )
                idx++
                continue
            }

            // Decisão de colapso vs nova manobra por mudança de azimute / nome da via.
            val sameName = streetName.isNotEmpty() &&
                currStep.streetName.isNotEmpty() &&
                streetName.equals(currStep.streetName, ignoreCase = true)
            val bothUnnamed = streetName.isEmpty() && currStep.streetName.isEmpty()
            val junctionNode = graph.edgeFrom[edgeId]
            val isRealIntersection = graph.outDegree(junctionNode) >= 3 ||
                (graph.outDegree(junctionNode) == 2 && !hasOnlyReverseAlternative(graph, prevEdgeId, junctionNode))

            val shouldCollapse = when {
                // Mesma via em curva suave sem bifurcação forte -> colapsa.
                sameName && absDelta < 50.0 -> true
                // Ambas sem nome em curva leve -> colapsa.
                bothUnnamed && absDelta < 30.0 -> true
                // Seguindo reto (< 22°) sem troca relevante de nome -> colapsa.
                absDelta < 22.0 && (!isRealIntersection || streetName.isEmpty() || sameName || currStep.distanceMeters < 35.0) -> true
                // Mudança leve (< 35°) em nó sem ramificação (apenas geometria/troca de segmento) -> colapsa.
                absDelta < 35.0 && !isRealIntersection && (sameName || streetName.isEmpty() || prevClass == currClass) -> true
                else -> false
            }

            if (shouldCollapse) {
                currStep.distanceMeters += seg.distanceMeters
                currStep.durationSeconds += seg.durationSeconds
                currStep.geometryEndIndex = seg.geomEndIndex
                idx++
                continue
            }

            val maneuverType = classifyTurn(deltaDeg)
            val instruction = if (maneuverType == ManeuverType.SLIGHT_LEFT || maneuverType == ManeuverType.SLIGHT_RIGHT) {
                buildSlightTurnInstruction(maneuverType, streetName, keepMode = isRealIntersection && prevClass <= DfrgGraph.ROAD_CLASS_SECONDARY)
            } else {
                null
            }

            rawSteps.add(
                RawStep(
                    maneuver = maneuverType,
                    streetName = streetName,
                    distanceMeters = seg.distanceMeters,
                    durationSeconds = seg.durationSeconds,
                    geometryStartIndex = seg.geomStartIndex,
                    geometryEndIndex = seg.geomEndIndex,
                    customInstruction = instruction
                )
            )
            idx++
        }

        // Passo final da perna: chegada em parada intermediária ou destino final.
        val finalGeomIdx = segments.last().geomEndIndex
        val arriveType = if (isFinalLeg) ManeuverType.ARRIVE else ManeuverType.WAYPOINT_ARRIVE
        rawSteps.add(
            RawStep(
                maneuver = arriveType,
                streetName = destClean,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                geometryStartIndex = finalGeomIdx,
                geometryEndIndex = finalGeomIdx,
                customInstruction = arrivalInstruction(isFinalLeg, waypointNumber, destClean)
            )
        )

        return rawSteps.mapIndexed { offset, raw ->
            val instruction = raw.customInstruction ?: buildInstructionPtBr(raw.maneuver, raw.streetName, raw.roundaboutExit)
            RouteStep(
                index = stepIndexStart + offset,
                maneuver = raw.maneuver,
                streetName = raw.streetName,
                instructionPtBr = instruction,
                distanceMeters = raw.distanceMeters,
                durationSeconds = raw.durationSeconds,
                geometryStartIndex = raw.geometryStartIndex,
                geometryEndIndex = raw.geometryEndIndex,
                roundaboutExit = raw.roundaboutExit
            )
        }
    }

    /**
     * Conta qual saída da rotatória (1-based) está sendo tomada ao percorrer `segments[rStart..rEnd]`.
     *
     * Percorre cada nó alcançado ao longo da rotatória (`edgeTo[e]`) e soma 1 para cada nó
     * que possua pelo menos uma aresta de saída não-rotatória (saída transitável).
     */
    private fun countRoundaboutExit(
        graph: DfrgGraph,
        segments: List<LegEdgeSegment>,
        rStart: Int,
        rEnd: Int,
        hasExitEdge: Boolean
    ): Int? {
        if (!hasExitEdge) return null
        var exits = 0
        for (i in rStart..rEnd) {
            val node = graph.edgeTo[segments[i].edgeId]
            val isLastRoundaboutNode = (i == rEnd)
            val eStart = graph.firstEdge[node]
            val eEnd = graph.firstEdge[node + 1]
            var hasNonRoundaboutOut = false
            for (e in eStart until eEnd) {
                if (!graph.isRoundabout(e)) {
                    hasNonRoundaboutOut = true
                    break
                }
            }
            if (hasNonRoundaboutOut || isLastRoundaboutNode) {
                exits++
            }
        }
        return exits.coerceAtLeast(1)
    }

    /** Olha adiante pulando alças sem nome para encontrar o nome real da via de destino. */
    private fun resolveTargetStreetName(
        graph: DfrgGraph,
        segments: List<LegEdgeSegment>,
        startIndex: Int
    ): String {
        val maxLook = minOf(segments.size, startIndex + 4)
        for (i in startIndex until maxLook) {
            val e = segments[i].edgeId
            val name = graph.edgeName(e).trim()
            if (name.isNotEmpty()) return name
            if (!graph.isLink(e) && i > startIndex) break
        }
        return ""
    }

    private fun hasOnlyReverseAlternative(graph: DfrgGraph, prevEdgeId: Int, node: Int): Boolean {
        val prevFrom = graph.edgeFrom[prevEdgeId]
        val start = graph.firstEdge[node]
        val end = graph.firstEdge[node + 1]
        var nonReverseCount = 0
        for (e in start until end) {
            if (graph.edgeTo[e] != prevFrom) {
                nonReverseCount++
            }
        }
        return nonReverseCount <= 1
    }

    private fun segmentStartBearing(geometry: List<GeoPoint>, seg: LegEdgeSegment): Double {
        val start = seg.geomStartIndex.coerceIn(0, geometry.lastIndex)
        val end = seg.geomEndIndex.coerceIn(start, geometry.lastIndex)
        // Avança até ter deslocamento mínimo (~3m) para azimute estável em vértices muito próximos.
        val p0 = geometry[start]
        for (i in (start + 1)..end) {
            if (p0.distanceToMeters(geometry[i]) >= 3.0 || i == end) {
                return p0.bearingToDegrees(geometry[i]).toDouble()
            }
        }
        return 0.0
    }

    private fun segmentEndBearing(geometry: List<GeoPoint>, seg: LegEdgeSegment): Double {
        val start = seg.geomStartIndex.coerceIn(0, geometry.lastIndex)
        val end = seg.geomEndIndex.coerceIn(start, geometry.lastIndex)
        val pEnd = geometry[end]
        for (i in (end - 1) downTo start) {
            if (geometry[i].distanceToMeters(pEnd) >= 3.0 || i == start) {
                return geometry[i].bearingToDegrees(pEnd).toDouble()
            }
        }
        return 0.0
    }

    /** Normaliza diferença de ângulo para `(-180°, +180°]` (+ = direita, - = esquerda). */
    internal fun normalizeTurnAngleDeg(diffDeg: Double): Double {
        var d = ((diffDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (d <= -180.0) d += 360.0
        return d
    }

    internal fun classifyTurn(deltaDeg: Double): ManeuverType = when {
        abs(deltaDeg) >= 155.0 -> if (deltaDeg < 0.0) ManeuverType.UTURN_LEFT else ManeuverType.UTURN_RIGHT
        deltaDeg >= 120.0 -> ManeuverType.SHARP_RIGHT
        deltaDeg >= 42.0 -> ManeuverType.RIGHT
        deltaDeg >= 22.0 -> ManeuverType.SLIGHT_RIGHT
        deltaDeg <= -120.0 -> ManeuverType.SHARP_LEFT
        deltaDeg <= -42.0 -> ManeuverType.LEFT
        deltaDeg <= -22.0 -> ManeuverType.SLIGHT_LEFT
        else -> ManeuverType.STRAIGHT
    }

    internal fun buildInstructionPtBr(
        maneuver: ManeuverType,
        streetName: String,
        roundaboutExit: Int? = null
    ): String {
        val via = streetName.trim()
        return when (maneuver) {
            ManeuverType.DEPART ->
                if (via.isNotEmpty()) "Siga ${prepPela(via)}" else "Siga em frente"
            ManeuverType.STRAIGHT ->
                if (via.isNotEmpty()) "Siga em frente ${prepPela(via)}" else "Siga em frente"
            ManeuverType.SLIGHT_RIGHT ->
                buildSlightTurnInstruction(maneuver, via, keepMode = false)
            ManeuverType.RIGHT ->
                if (via.isNotEmpty()) "Vire à direita ${prepNa(via)}" else "Vire à direita"
            ManeuverType.SHARP_RIGHT ->
                if (via.isNotEmpty()) "Faça curva acentuada à direita ${prepNa(via)}" else "Faça curva acentuada à direita"
            ManeuverType.SLIGHT_LEFT ->
                buildSlightTurnInstruction(maneuver, via, keepMode = false)
            ManeuverType.LEFT ->
                if (via.isNotEmpty()) "Vire à esquerda ${prepNa(via)}" else "Vire à esquerda"
            ManeuverType.SHARP_LEFT ->
                if (via.isNotEmpty()) "Faça curva acentuada à esquerda ${prepNa(via)}" else "Faça curva acentuada à esquerda"
            ManeuverType.UTURN_LEFT, ManeuverType.UTURN_RIGHT ->
                if (via.isNotEmpty()) "Faça o retorno ${prepNa(via)}" else "Faça o retorno"
            ManeuverType.ROUNDABOUT_ENTER ->
                buildRoundaboutInstruction(roundaboutExit, via)
            ManeuverType.ROUNDABOUT_EXIT ->
                if (via.isNotEmpty()) "Saia da rotatória para $via" else "Saia da rotatória"
            ManeuverType.MERGE ->
                buildMergeInstruction(via)
            ManeuverType.RAMP_RIGHT, ManeuverType.RAMP_LEFT ->
                buildRampInstruction(maneuver, via)
            ManeuverType.WAYPOINT_ARRIVE ->
                "Você chegou à parada"
            ManeuverType.ARRIVE ->
                "Você chegou ao seu destino"
        }
    }

    private fun buildRoundaboutInstruction(exitCount: Int?, exitStreetName: String): String {
        val via = exitStreetName.trim()
        return if (exitCount != null && exitCount > 0) {
            if (via.isNotEmpty()) {
                "Na rotatória, pegue a ${exitCount}ª saída para $via"
            } else {
                "Na rotatória, pegue a ${exitCount}ª saída"
            }
        } else {
            if (via.isNotEmpty()) "Entre na rotatória em direção a $via" else "Entre na rotatória"
        }
    }

    private fun buildRampInstruction(type: ManeuverType, targetStreetName: String): String {
        val via = targetStreetName.trim()
        val side = if (type == ManeuverType.RAMP_LEFT) "à esquerda" else "à direita"
        return if (via.isNotEmpty()) {
            "Pegue o acesso $side para $via"
        } else {
            "Pegue o acesso $side"
        }
    }

    private fun buildMergeInstruction(streetName: String): String {
        val via = streetName.trim()
        return if (via.isNotEmpty()) "Entre ${prepNa(via)}" else "Entre na via principal"
    }

    private fun buildSlightTurnInstruction(type: ManeuverType, streetName: String, keepMode: Boolean): String {
        val via = streetName.trim()
        val side = if (type == ManeuverType.SLIGHT_LEFT) "à esquerda" else "à direita"
        val verb = if (keepMode) "Mantenha-se $side" else "Vire ligeiramente $side"
        return if (via.isNotEmpty()) "$verb ${prepNa(via)}" else verb
    }

    private fun arrivalInstruction(isFinalLeg: Boolean, waypointNumber: Int, destLabel: String): String {
        return if (isFinalLeg) {
            if (destLabel.isNotEmpty()) "Você chegou ao seu destino: $destLabel" else "Você chegou ao seu destino"
        } else {
            if (destLabel.isNotEmpty()) "Você chegou à parada $waypointNumber: $destLabel" else "Você chegou à parada $waypointNumber"
        }
    }

    private fun isMasculineStreet(name: String): Boolean {
        val lower = name.lowercase(Locale("pt", "BR"))
        return lower.startsWith("beco ") ||
            lower.startsWith("largo ") ||
            lower.startsWith("viaduto ") ||
            lower.startsWith("túnel ") ||
            lower.startsWith("tunel ") ||
            lower.startsWith("acesso ") ||
            lower.startsWith("contorno ") ||
            lower.startsWith("anel ") ||
            lower.startsWith("trevo ") ||
            lower.startsWith("eixo ") ||
            lower.startsWith("corredor ") ||
            lower.startsWith("caminho ") ||
            lower.startsWith("passeio ") ||
            lower.startsWith("calçadão ") ||
            lower.startsWith("calcadao ")
    }

    private fun prepPela(name: String): String =
        if (isMasculineStreet(name)) "pelo $name" else "pela $name"

    private fun prepNa(name: String): String =
        if (isMasculineStreet(name)) "no $name" else "na $name"
}
