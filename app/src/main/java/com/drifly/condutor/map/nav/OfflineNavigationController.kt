package com.drifly.condutor.map.nav

import com.drifly.condutor.map.routing.AvoidZone
import com.drifly.condutor.map.routing.ComputedRoute
import com.drifly.condutor.map.routing.GeoPoint
import com.drifly.condutor.map.routing.ManeuverType
import com.drifly.condutor.map.routing.OptimizedRoutePlan
import com.drifly.condutor.map.routing.RouteProfile
import com.drifly.condutor.map.routing.RouteRequest
import com.drifly.condutor.map.routing.RouteResponse
import com.drifly.condutor.map.routing.RouteStep
import com.drifly.condutor.map.routing.RouteWaypoint
import com.drifly.condutor.map.routing.OfflineRouteEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

data class NavLocationFix(
    val point: GeoPoint,
    val speedMps: Float = 0f,
    val bearingDeg: Float = 0f,
    val accuracyMeters: Float = 10f,
    val timestampMs: Long = System.currentTimeMillis(),
    val isSimulated: Boolean = false
) {
    val speedKmh: Float get() = (speedMps * 3.6f).coerceAtLeast(0f)
}

data class CameraGuidanceState(
    val target: GeoPoint,
    val bearingDeg: Float,
    val zoom: Double,
    val tiltDeg: Double,
    val followEnabled: Boolean
)

data class NavigationSnapshot(
    val route: ComputedRoute,
    val snappedPoint: GeoPoint,
    val snappedBearingDeg: Float,
    val routeSegmentIndex: Int,
    val currentStep: RouteStep,
    val nextStep: RouteStep?,
    val distanceToManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val etaEpochMs: Long,
    val currentSpeedKmh: Float,
    val crossTrackMeters: Double,
    val isOffRoute: Boolean,
    val isRecalculating: Boolean,
    val isSimulated: Boolean,
    val isVoiceMuted: Boolean,
    val camera: CameraGuidanceState
)

sealed class NavigationUiState {
    data object Idle : NavigationUiState()

    data class Preview(
        val plan: OptimizedRoutePlan,
        val selectedRouteIndex: Int = 0
    ) : NavigationUiState() {
        val selectedRoute: ComputedRoute
            get() = if (selectedRouteIndex == 0) {
                plan.primary
            } else {
                plan.alternatives.getOrElse(selectedRouteIndex - 1) { plan.primary }
            }
    }

    data class Navigating(val snapshot: NavigationSnapshot) : NavigationUiState()

    data class Arrived(
        val destinationLabel: String,
        val totalDistanceMeters: Double,
        val wasSimulated: Boolean
    ) : NavigationUiState()
}

/**
 * Controlador de navegação Turn-by-Turn 100% offline.
 *
 * Gerencia:
 * - Máquina de estados (`Idle` -> `Preview` -> `Navigating` -> `Arrived`);
 * - Snap em janela local O(1) via [RouteSnapper];
 * - Avanço automático de etapas/manobras e alertas de voz pt-BR sem repetição;
 * - Detecção de fora de rota com histerese (> 40 m contínuos por >= 3 s) e recálculo
 *   local instantâneo com debounce de 5 s;
 * - Câmera 3D adaptativa à velocidade (inclinação 50°, zoom 17.5 -> 14.8, trava/destrava ao arrastar).
 */
class OfflineNavigationController(
    private val routeEngine: OfflineRouteEngine,
    private val voiceSpeaker: NavigationVoiceSpeaker?,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val _state = MutableStateFlow<NavigationUiState>(NavigationUiState.Idle)
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()

    private val simulator = RouteSimulator(scope = scope, nowMs = nowMs)

    private var activeRoute: ComputedRoute? = null
    private var activeWaypoints: List<RouteWaypoint> = emptyList()
    private var activeProfile: RouteProfile = RouteProfile.CAR_FASTEST
    private var activeAvoidZones: List<AvoidZone> = emptyList()

    private var lastSegmentIndex: Int = 0
    private var offRouteSinceMs: Long? = null
    private var lastRerouteAttemptMs: Long = 0L
    private var rerouteJob: Job? = null
    private var cameraFollowEnabled: Boolean = true
    private var smoothedBearingDeg: Float? = null
    private var isSimulating: Boolean = false

    /** Bitmask de anúncios já disparados por `step.index` (bit 0=500m, 1=300m, 2=80m, 3=agora). */
    private val announcedStepFlags = HashMap<Int, Int>()

    /** Exibe a prévia de rota (antes de apertar "Iniciar" ou "Simular"). */
    fun showPreview(plan: OptimizedRoutePlan) {
        stopSimulationAndReroute()
        activeRoute = plan.primary
        activeWaypoints = plan.orderedWaypoints
        activeAvoidZones = plan.activeAvoidZones
        _state.value = NavigationUiState.Preview(plan, selectedRouteIndex = 0)
    }

    /** Alterna entre a rota principal (0) e rotas alternativas (1..N) na prévia. */
    fun selectPreviewRoute(index: Int) {
        val current = _state.value as? NavigationUiState.Preview ?: return
        val maxIdx = current.plan.alternatives.size
        val clamped = index.coerceIn(0, maxIdx)
        val chosen = if (clamped == 0) current.plan.primary else current.plan.alternatives[clamped - 1]
        activeRoute = chosen
        _state.value = current.copy(selectedRouteIndex = clamped)
    }

    /** Inicia a navegação Turn-by-Turn real (GPS) ou simulada (Joinville Demo). */
    fun startNavigation(
        route: ComputedRoute,
        waypoints: List<RouteWaypoint>,
        profile: RouteProfile = RouteProfile.CAR_FASTEST,
        avoidZones: List<AvoidZone> = emptyList(),
        initialFix: NavLocationFix? = null,
        simulate: Boolean = false
    ) {
        stopSimulationAndReroute()
        activeRoute = route
        activeWaypoints = waypoints
        activeProfile = profile
        activeAvoidZones = avoidZones
        lastSegmentIndex = 0
        offRouteSinceMs = null
        lastRerouteAttemptMs = 0L
        cameraFollowEnabled = true
        smoothedBearingDeg = null
        announcedStepFlags.clear()
        isSimulating = simulate

        val startPoint = initialFix?.point ?: route.geometry.firstOrNull() ?: GeoPoint(0.0, 0.0)
        val initialBearing = initialFix?.bearingDeg
            ?: if (route.geometry.size >= 2) route.geometry[0].bearingToDegrees(route.geometry[1]) else 0f

        val firstFix = initialFix ?: NavLocationFix(
            point = startPoint,
            speedMps = 0f,
            bearingDeg = initialBearing,
            timestampMs = nowMs(),
            isSimulated = simulate
        )
        processFixInternal(firstFix, allowReroute = false)

        val steps = route.allSteps
        val firstStep = steps.firstOrNull()
        if (firstStep != null) {
            voiceSpeaker?.speak(
                "Iniciando navegação. ${NavigationVoiceEngine.defaultManeuverPhrase(firstStep)}.",
                flush = true
            )
        }

        if (simulate) {
            simulator.start(
                route = route,
                onFix = { simFix -> onLocationUpdate(simFix) }
            )
        }
    }

    /** Alimenta o controlador com um novo ponto GPS (ou simulado). */
    fun onLocationUpdate(fix: NavLocationFix) {
        if (_state.value !is NavigationUiState.Navigating) return
        // Ignora GPS real enquanto a simulação está ativa para não haver conflito de posição.
        if (isSimulating && !fix.isSimulated) return
        processFixInternal(fix, allowReroute = !fix.isSimulated)
    }

    fun setCameraFollow(enabled: Boolean) {
        cameraFollowEnabled = enabled
        val cur = _state.value as? NavigationUiState.Navigating ?: return
        _state.value = cur.copy(
            snapshot = cur.snapshot.copy(
                camera = cur.snapshot.camera.copy(followEnabled = enabled)
            )
        )
    }

    fun toggleVoiceMute(): Boolean {
        val next = !(voiceSpeaker?.isMuted ?: false)
        voiceSpeaker?.isMuted = next
        if (next) voiceSpeaker?.stop()
        val cur = _state.value as? NavigationUiState.Navigating
        if (cur != null) {
            _state.value = cur.copy(snapshot = cur.snapshot.copy(isVoiceMuted = next))
        }
        return next
    }

    fun setSimulationSpeed(multiplier: Double) {
        simulator.speedMultiplier = multiplier
    }

    /** Encerra a navegação e limpa o estado para o modo livre do mapa. */
    fun stopNavigation() {
        stopSimulationAndReroute()
        voiceSpeaker?.stop()
        activeRoute = null
        activeWaypoints = emptyList()
        activeAvoidZones = emptyList()
        announcedStepFlags.clear()
        offRouteSinceMs = null
        isSimulating = false
        _state.value = NavigationUiState.Idle
    }

    private fun processFixInternal(fix: NavLocationFix, allowReroute: Boolean) {
        val route = activeRoute ?: return
        val snap = RouteSnapper.snap(route, fix.point, lastSegmentIndex)
        lastSegmentIndex = snap.segmentIndex

        val destPoint = route.geometry.lastOrNull()
        val distToDestM = destPoint?.distanceToMeters(fix.point) ?: snap.remainingMeters

        // Detecção de chegada ao destino final.
        if (snap.remainingMeters <= ARRIVAL_THRESHOLD_M || distToDestM <= ARRIVAL_THRESHOLD_M) {
            stopSimulationAndReroute()
            voiceSpeaker?.speak("Você chegou ao destino.", flush = true)
            val label = activeWaypoints.lastOrNull()?.label?.ifBlank { "Destino" } ?: "Destino"
            _state.value = NavigationUiState.Arrived(
                destinationLabel = label,
                totalDistanceMeters = route.totalDistanceMeters,
                wasSimulated = isSimulating
            )
            isSimulating = false
            return
        }

        // Histerese de fora de rota: > 40 m contínuos por >= 3 s.
        val now = fix.timestampMs
        var isOffRoute = false
        if (snap.crossTrackMeters > OFF_ROUTE_THRESHOLD_M) {
            val started = offRouteSinceMs ?: now.also { offRouteSinceMs = it }
            if (now - started >= OFF_ROUTE_HOLD_MS) {
                isOffRoute = true
                if (allowReroute) {
                    maybeTriggerReroute(fix, now)
                }
            }
        } else {
            offRouteSinceMs = null
        }

        val steps = route.allSteps
        val (currentStep, nextStep, distToManeuverM) = resolveActiveStep(route, steps, snap)

        // Estima tempo restante proporcional à fração restante da rota.
        val totalDist = route.totalDistanceMeters.coerceAtLeast(1.0)
        val remainingFraction = (snap.remainingMeters / totalDist).coerceIn(0.0, 1.0)
        val remainingDurationSec = route.totalDurationSeconds * remainingFraction

        // Suavização circular do azimute para câmera 3D estável.
        val rawBearing = if (fix.speedKmh >= 5f) fix.bearingDeg else snap.segmentBearingDeg
        val bearing = smoothBearing(smoothedBearingDeg, rawBearing)
        smoothedBearingDeg = bearing

        val camera = CameraGuidanceState(
            target = if (isOffRoute) fix.point else snap.snappedPoint,
            bearingDeg = bearing,
            zoom = speedAdaptiveZoom(fix.speedKmh),
            tiltDeg = NAV_CAMERA_TILT_DEG,
            followEnabled = cameraFollowEnabled
        )

        val snapshot = NavigationSnapshot(
            route = route,
            snappedPoint = if (isOffRoute) fix.point else snap.snappedPoint,
            snappedBearingDeg = bearing,
            routeSegmentIndex = snap.segmentIndex,
            currentStep = currentStep,
            nextStep = nextStep,
            distanceToManeuverMeters = distToManeuverM,
            remainingDistanceMeters = snap.remainingMeters,
            remainingDurationSeconds = remainingDurationSec,
            etaEpochMs = now + (remainingDurationSec * 1000.0).toLong(),
            currentSpeedKmh = fix.speedKmh,
            crossTrackMeters = snap.crossTrackMeters,
            isOffRoute = isOffRoute,
            isRecalculating = rerouteJob?.isActive == true,
            isSimulated = fix.isSimulated,
            isVoiceMuted = voiceSpeaker?.isMuted ?: false,
            camera = camera
        )
        _state.value = NavigationUiState.Navigating(snapshot)

        if (!isOffRoute) {
            maybeAnnounceManeuver(currentStep, distToManeuverM, fix.speedKmh)
        }
    }

    private fun resolveActiveStep(
        route: ComputedRoute,
        steps: List<RouteStep>,
        snap: SnapResult
    ): Triple<RouteStep, RouteStep?, Double> {
        if (steps.isEmpty()) {
            val fallback = RouteStep(
                index = 0,
                maneuver = ManeuverType.ARRIVE,
                streetName = activeWaypoints.lastOrNull()?.label.orEmpty(),
                instructionPtBr = "Siga até o destino",
                distanceMeters = snap.remainingMeters,
                durationSeconds = route.totalDurationSeconds,
                geometryStartIndex = 0,
                geometryEndIndex = (route.geometry.size - 1).coerceAtLeast(0)
            )
            return Triple(fallback, null, snap.remainingMeters)
        }

        val cum = route.cumulativeDistancesM
        // Encontra a primeira manobra futura cujo ponto de execução ainda está à frente.
        var activeIdx = steps.lastIndex
        for (i in steps.indices) {
            val endGeomIdx = steps[i].geometryEndIndex.coerceIn(0, cum.lastIndex)
            if (cum[endGeomIdx] >= snap.alongTrackMeters + 5.0) {
                activeIdx = i
                break
            }
        }

        // O banner mostra a PRÓXIMA conversão que o motorista vai executar (final do step atual,
        // ou o próprio step seguinte se houver manobra à frente).
        val nextIdx = (activeIdx + 1).takeIf { it < steps.size }
        val bannerStep = if (nextIdx != null && steps[activeIdx].maneuver == ManeuverType.DEPART) {
            steps[nextIdx]
        } else {
            steps[activeIdx]
        }
        val afterBanner = steps.getOrNull(steps.indexOf(bannerStep) + 1)
        val maneuverGeomIdx = bannerStep.geometryStartIndex.coerceIn(0, cum.lastIndex)
        val distToManeuver = if (bannerStep.index == steps[activeIdx].index && bannerStep.maneuver != ManeuverType.ARRIVE) {
            val endIdx = bannerStep.geometryEndIndex.coerceIn(0, cum.lastIndex)
            (cum[endIdx] - snap.alongTrackMeters).coerceAtLeast(0.0)
        } else {
            (cum[maneuverGeomIdx] - snap.alongTrackMeters).coerceAtLeast(0.0)
        }

        return Triple(bannerStep, afterBanner, distToManeuver)
    }

    private fun maybeAnnounceManeuver(step: RouteStep, distM: Double, speedKmh: Float) {
        if (voiceSpeaker == null || voiceSpeaker.isMuted) return
        if (step.maneuver == ManeuverType.DEPART) return

        val flags = announcedStepFlags[step.index] ?: 0
        val flagBit: Int = when {
            distM <= 25.0 && (flags and FLAG_NOW) == 0 -> FLAG_NOW
            distM in 26.0..95.0 && (flags and FLAG_80M) == 0 -> FLAG_80M
            distM in 220.0..330.0 && (flags and FLAG_300M) == 0 -> FLAG_300M
            speedKmh >= 65f && distM in 420.0..540.0 && (flags and FLAG_500M) == 0 -> FLAG_500M
            else -> 0
        }
        if (flagBit != 0) {
            announcedStepFlags[step.index] = flags or flagBit
            voiceSpeaker.speak(NavigationVoiceEngine.formatSpokenPrompt(step, distM), flush = true)
        }
    }

    private fun maybeTriggerReroute(fix: NavLocationFix, now: Long) {
        if (rerouteJob?.isActive == true) return
        if (now - lastRerouteAttemptMs < REROUTE_COOLDOWN_MS) return
        if (activeWaypoints.isEmpty()) return

        lastRerouteAttemptMs = now
        voiceSpeaker?.speak("Recalculando rota.", flush = true)

        rerouteJob = scope.launch {
            val req = RouteRequest(
                origin = fix.point,
                waypoints = activeWaypoints,
                profile = activeProfile,
                avoidZones = activeAvoidZones,
                computeAlternatives = false,
                originBearingDeg = fix.bearingDeg
            )
            when (val resp = routeEngine.computeRoute(req)) {
                is RouteResponse.Success -> {
                    activeRoute = resp.primary
                    lastSegmentIndex = 0
                    offRouteSinceMs = null
                    announcedStepFlags.clear()
                    if (_state.value is NavigationUiState.Navigating) {
                        processFixInternal(fix, allowReroute = false)
                    }
                }
                is RouteResponse.Error -> {
                    // Mantém a rota atual e tentará novamente após o cooldown se continuar fora.
                }
            }
        }
    }

    private fun stopSimulationAndReroute() {
        simulator.stop()
        rerouteJob?.cancel()
        rerouteJob = null
    }

    companion object {
        const val OFF_ROUTE_THRESHOLD_M = 40.0
        const val OFF_ROUTE_HOLD_MS = 3_000L
        const val REROUTE_COOLDOWN_MS = 5_000L
        const val ARRIVAL_THRESHOLD_M = 30.0
        const val NAV_CAMERA_TILT_DEG = 50.0

        private const val FLAG_500M = 1 shl 0
        private const val FLAG_300M = 1 shl 1
        private const val FLAG_80M = 1 shl 2
        private const val FLAG_NOW = 1 shl 3

        /** Zoom adaptativo à velocidade (mais fechado em ruas lentas, mais aberto em rodovias). */
        fun speedAdaptiveZoom(speedKmh: Float): Double = when {
            speedKmh < 25f -> 17.5
            speedKmh < 55f -> 16.5
            speedKmh < 85f -> 15.5
            else -> 14.8
        }

        /** Suavização circular exponencial para evitar saltos entre 359° e 1°. */
        internal fun smoothBearing(previousDeg: Float?, targetDeg: Float, alpha: Float = 0.35f): Float {
            if (previousDeg == null) return ((targetDeg % 360f) + 360f) % 360f
            var diff = ((targetDeg - previousDeg + 540f) % 360f) - 180f
            if (abs(diff) < 0.5f) diff = 0f
            return (((previousDeg + alpha * diff) % 360f) + 360f) % 360f
        }
    }
}
