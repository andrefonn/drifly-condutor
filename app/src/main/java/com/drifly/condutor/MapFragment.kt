package com.drifly.condutor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.drifly.condutor.map.HybridStyleController
import com.drifly.condutor.map.MapLibreLayersController
import com.drifly.condutor.map.MapVisualMode
import com.drifly.condutor.map.geocoder.GeocoderPlace
import com.drifly.condutor.map.geocoder.OfflineGeocoder
import com.drifly.condutor.map.nav.NavLocationFix
import com.drifly.condutor.map.nav.NavigationSnapshot
import com.drifly.condutor.map.nav.NavigationUiState
import com.drifly.condutor.map.nav.NavigationVoiceEngine
import com.drifly.condutor.map.nav.OfflineNavigationController
import com.drifly.condutor.map.pack.OfflineMapPackManager
import com.drifly.condutor.map.pack.PackInfo
import com.drifly.condutor.map.pack.PackState
import com.drifly.condutor.map.pack.RegionCatalog
import com.drifly.condutor.map.pack.RegionCatalogSnapshot
import com.drifly.condutor.map.pack.RegionPack
import com.drifly.condutor.map.routing.DfrgRouteEngine
import com.drifly.condutor.map.routing.GeoPoint
import com.drifly.condutor.map.routing.ManeuverType
import com.drifly.condutor.map.routing.OptimizedRoutePlan
import com.drifly.condutor.map.routing.RouteProfile
import com.drifly.condutor.map.routing.RouteWaypoint
import com.drifly.condutor.map.routing.SmartRouteOptimizer
import com.drifly.condutor.map.tiles.LocalMapTileServer
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Aba Mapa & Navegação 100% Offline do DriFly (MapLibre Native Android).
 *
 * Unifica:
 * - Mapa vetorial 100% offline (PMTiles + `LocalMapTileServer` em `127.0.0.1` + glifos PBF);
 * - Todos os sinais colaborativos do DriFly (pinos, áreas, chips, card, votos, denúncia, editor, `abrirSinal`);
 * - Busca offline de ruas/números/bairros/POIs (`OfflineGeocoder` FTS5);
 * - Roteador DFRG v1 (~4 MB RAM) + otimizador multi-paradas TSP + evasão automática da Lista Negra;
 * - Navegação Turn-by-Turn 3D com voz pt-BR, recálculo local e modo Demo/Simulação em Joinville.
 */
class MapFragment : Fragment() {

    // ----------------------------------------------------------------------
    // Views agrupadas
    // ----------------------------------------------------------------------

    private class Cartao(raiz: View) {
        val root: View = raiz.findViewById(R.id.signalCard)
        val iconeBox: View = raiz.findViewById(R.id.cardIconBox)
        val icone: ImageView = raiz.findViewById(R.id.cardIcon)
        val titulo: TextView = raiz.findViewById(R.id.cardTitle)
        val subtitulo: TextView = raiz.findViewById(R.id.cardSubtitle)
        val fechar: View = raiz.findViewById(R.id.cardClose)
        val dica: TextView = raiz.findViewById(R.id.cardTip)
        val chip: TextView = raiz.findViewById(R.id.cardStatusChip)
        val trilha: View = raiz.findViewById(R.id.cardBarTrack)
        val preenchimento: View = raiz.findViewById(R.id.cardBarFill)
        val pct: TextView = raiz.findViewById(R.id.cardBarPct)
        val votos: View = raiz.findViewById(R.id.cardVoteRow)
        val pergunta: TextView = raiz.findViewById(R.id.cardQuestion)
        val votoSim: TextView = raiz.findViewById(R.id.cardVoteYes)
        val votoNao: TextView = raiz.findViewById(R.id.cardVoteNo)
        val trava: View = raiz.findViewById(R.id.cardLockRow)
        val travaIcone: View = raiz.findViewById(R.id.cardLockIcon)
        val travaTexto: TextView = raiz.findViewById(R.id.cardLockText)
        val navegarDrifly: View = raiz.findViewById(R.id.cardNavigateDrifly)
        val maps: View = raiz.findViewById(R.id.cardMaps)
        val waze: View = raiz.findViewById(R.id.cardWaze)
        val denunciar: TextView = raiz.findViewById(R.id.cardReport)
        val remover: TextView = raiz.findViewById(R.id.cardRemove)
    }

    private class Editor(raiz: View) {
        val scrim: View = raiz.findViewById(R.id.editorScrim)
        val sheet: View = raiz.findViewById(R.id.editorSheet)
        val fechar: View = raiz.findViewById(R.id.editorClose)
        val abaPlace: TextView = raiz.findViewById(R.id.editorTabLugar)
        val abaTip: TextView = raiz.findViewById(R.id.editorTabDica)
        val abaEvento: TextView = raiz.findViewById(R.id.editorTabEvento)
        val placePanel: View = raiz.findViewById(R.id.editorPlacePanel)
        val places: LinearLayout = raiz.findViewById(R.id.editorPlaces)
        val sendPlace: TextView = raiz.findViewById(R.id.editorSendPlace)
        val tipPanel: View = raiz.findViewById(R.id.editorTipPanel)
        val tipText: EditText = raiz.findViewById(R.id.editorTipText)
        val tipCount: TextView = raiz.findViewById(R.id.editorTipCount)
        val tipError: TextView = raiz.findViewById(R.id.editorTipError)
        val sendTip: TextView = raiz.findViewById(R.id.editorSendTip)
        val eventPanel: View = raiz.findViewById(R.id.editorEventPanel)
        val eventRow1: LinearLayout = raiz.findViewById(R.id.editorEventRow1)
        val eventRow2: LinearLayout = raiz.findViewById(R.id.editorEventRow2)
        val sendEvent: TextView = raiz.findViewById(R.id.editorSendEvent)
    }

    private class SearchViews(raiz: View) {
        val topControls: View = raiz.findViewById(R.id.mapTopControls)
        val input: EditText = raiz.findViewById(R.id.etMapSearch)
        val clearBtn: View = raiz.findViewById(R.id.btnMapSearchClear)
        val packStatusPill: TextView = raiz.findViewById(R.id.btnMapPackStatus)
        val resultsCard: View = raiz.findViewById(R.id.mapSearchResultsCard)
        val resultsList: LinearLayout = raiz.findViewById(R.id.mapSearchResultsList)
    }

    private class PreviewViews(raiz: View) {
        val card: View = raiz.findViewById(R.id.routePreviewCard)
        val duration: TextView = raiz.findViewById(R.id.tvPreviewDuration)
        val distance: TextView = raiz.findViewById(R.id.tvPreviewDistance)
        val fuelCost: TextView = raiz.findViewById(R.id.tvPreviewFuelCost)
        val destination: TextView = raiz.findViewById(R.id.tvPreviewDestination)
        val closeBtn: View = raiz.findViewById(R.id.btnPreviewClose)
        val blacklistChip: TextView = raiz.findViewById(R.id.chipPreviewBlacklistToggle)
        val profileChip: TextView = raiz.findViewById(R.id.chipPreviewProfile)
        val optimizedChip: TextView = raiz.findViewById(R.id.chipPreviewOptimized)
        val warning: TextView = raiz.findViewById(R.id.tvPreviewWarning)
        val alternativesScroll: View = raiz.findViewById(R.id.previewAlternativesScroll)
        val alternativesRow: LinearLayout = raiz.findViewById(R.id.previewAlternativesRow)
        val stopsScroll: View = raiz.findViewById(R.id.previewStopsScroll)
        val stopsContainer: LinearLayout = raiz.findViewById(R.id.previewStopsContainer)
        val addStopBtn: View = raiz.findViewById(R.id.btnPreviewAddStop)
        val simulateBtn: View = raiz.findViewById(R.id.btnPreviewSimulate)
        val startBtn: View = raiz.findViewById(R.id.btnPreviewStartNav)
    }

    private class NavViews(raiz: View) {
        val root: View = raiz.findViewById(R.id.navOverlayRoot)
        val maneuverIcon: ImageView = raiz.findViewById(R.id.ivNavManeuverIcon)
        val maneuverDist: TextView = raiz.findViewById(R.id.tvNavManeuverDist)
        val simBadge: TextView = raiz.findViewById(R.id.tvNavSimBadge)
        val streetName: TextView = raiz.findViewById(R.id.tvNavStreetName)
        val nextStepBar: View = raiz.findViewById(R.id.navNextStepBar)
        val nextStepText: TextView = raiz.findViewById(R.id.tvNavNextStep)
        val recenterBtn: View = raiz.findViewById(R.id.btnNavRecenter)
        val voiceBtn: ImageButton = raiz.findViewById(R.id.btnNavVoiceToggle)
        val remainingTime: TextView = raiz.findViewById(R.id.tvNavRemainingTime)
        val remainingMeta: TextView = raiz.findViewById(R.id.tvNavRemainingMeta)
        val stopBtn: View = raiz.findViewById(R.id.btnNavStop)
    }

    private class PackSheetViews(raiz: View) {
        val scrim: View = raiz.findViewById(R.id.offlinePackScrim)
        val closeBtn: View = raiz.findViewById(R.id.btnPackSheetClose)
        val subtitle: TextView = raiz.findViewById(R.id.tvPackStatusSubtitle)
        val freeSpace: TextView = raiz.findViewById(R.id.tvPackFreeSpace)
        val progressBox: View = raiz.findViewById(R.id.layoutPackProgress)
        val progress: ProgressBar = raiz.findViewById(R.id.pbPackProgress)
        val progressLabel: TextView = raiz.findViewById(R.id.tvPackProgressLabel)
        val cancelBtn: View = raiz.findViewById(R.id.btnPackCancelDownload)
        val errorText: TextView = raiz.findViewById(R.id.tvPackError)
        val installedEmpty: View = raiz.findViewById(R.id.tvInstalledPacksEmpty)
        val installedList: LinearLayout = raiz.findViewById(R.id.llInstalledPacks)
        val availableList: LinearLayout = raiz.findViewById(R.id.llAvailablePacks)
        val setupScroll: View = raiz.findViewById(R.id.mapSetupScroll)
        val setupDownloadBtn: View = raiz.findViewById(R.id.btnMapSetupDownload)
        val setupCloseBtn: View = raiz.findViewById(R.id.btnMapSetupClose)
        val onlineAttribution: TextView = raiz.findViewById(R.id.tvMapOnlineAttribution)
        val regionBanner: View = raiz.findViewById(R.id.mapRegionBanner)
        val regionBannerTitle: TextView = raiz.findViewById(R.id.tvRegionBannerTitle)
        val regionBannerSubtitle: TextView = raiz.findViewById(R.id.tvRegionBannerSubtitle)
        val regionBannerAction: TextView = raiz.findViewById(R.id.btnRegionBannerAction)
        val regionBannerClose: View = raiz.findViewById(R.id.btnRegionBannerClose)
    }

    // ----------------------------------------------------------------------
    // Serviços e Estado
    // ----------------------------------------------------------------------

    // O mapa colaborativo ainda não tem servidor nesta fase: o repositório
    // devolve lista vazia e informa que está offline, sem inventar pinos.
    private val repositorio: SignalRepository = SinalIndisponivelRepository
    private val prefs: PreferencesManager by lazy { PreferencesManager(requireContext()) }
    private val db: DatabaseHelper by lazy { DatabaseHelper(requireContext()) }
    private val packManager: OfflineMapPackManager by lazy { OfflineMapPackManager(requireContext()) }
    private val regionCatalog: RegionCatalog by lazy {
        RegionCatalog(File(requireContext().filesDir, "map_packs"))
    }
    private val hybridController = HybridStyleController()

    private var catalogSnapshot: RegionCatalogSnapshot = RegionCatalog.embeddedBrazilFallback()
    private var currentSuggestedPack: RegionPack? = null
    private var dismissedBannerPackId: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var cameraIdleDebounceJob: Job? = null

    private var mapView: MapView? = null
    private var mapLibreMap: MapLibreMap? = null
    private var layersController: MapLibreLayersController? = null
    private var tileServer: LocalMapTileServer? = null
    private var geocoder: OfflineGeocoder? = null
    private var routeEngine: DfrgRouteEngine? = null
    private var routeOptimizer: SmartRouteOptimizer? = null
    private var voiceEngine: NavigationVoiceEngine? = null
    private var navController: OfflineNavigationController? = null

    private var activePackInfo: PackInfo? = null
    private var isMapTabVisible = true
    private var isMapViewResumed = false
    private var savedInstanceStateForMap: Bundle? = null

    // Estado de sinais colaborativos
    private var filtroTipo: String? = null
    private var selecionadoId: String? = null
    private var sinalAberto: Signal? = null
    private var posicaoAtual: GeoPoint? = null
    private var ultimoBearingGps: Float = 0f
    private var centrouNoUsuario = false

    // Estado de planejamento de rota
    private val plannedWaypoints = mutableListOf<RouteWaypoint>()
    private var addingExtraStop = false
    private var avoidBlacklistInRoute = true
    private var routeProfile: RouteProfile = RouteProfile.CAR_FASTEST
    private var simSpeedMultiplier = 1.0
    private var suppressSearchWatcher = false
    private var searchJob: Job? = null
    private var routingJob: Job? = null
    private var packDownloadJob: Job? = null

    // Referências de Views
    private var cartao: Cartao? = null
    private var editor: Editor? = null
    private var searchViews: SearchViews? = null
    private var previewViews: PreviewViews? = null
    private var navViews: NavViews? = null
    private var packSheet: PackSheetViews? = null
    private var chipsContainer: LinearLayout? = null
    private var legenda: View? = null
    private var faixaLocalizacao: View? = null
    private var fabCentralizar: View? = null
    private var fabSinalizar: View? = null

    private var tipoSelecionadoId: String = SignalTypes.LUGARES.first().id
    private var tipoEventoSelecionado: String = SignalTypes.TIPOS_EVENTO.first()
    private var abaEditor: Int = ABA_LUGAR
    private var votoEmAndamento = false
    private val votados = mutableSetOf<String>()
    private var remocaoEmAndamento = false

    private val relogioHandler = Handler(Looper.getMainLooper())
    private val etaTimeFormat = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"))

    private val relogioSinais = object : Runnable {
        override fun run() {
            renderizarSinais()
            atualizarCardAberto()
            atualizarAvisoVazio(repositorio.listar().count { !it.expirou })
            relogioHandler.postDelayed(this, INTERVALO_RELOGIO_MS)
        }
    }

    private val observadorSinais = object : SignalRepository.Ouvinte {
        override fun aoMudarSinais(sinais: List<Signal>) {
            renderizarSinais()
            atualizarCardAberto()
            atualizarAvisoVazio(sinais.size)
        }

        override fun aoFalhar(mensagem: String) {
            avisar(mensagem, erro = true)
        }
    }

    private val consumidorGps = GpsLocationStream.Consumer { localizacao ->
        aplicarLocalizacao(localizacao)
    }

    private val atualizadorDePlano: (Boolean) -> Unit = { aplicarPlanoMapa() }

    // ----------------------------------------------------------------------
    // Ciclo de vida
    // ----------------------------------------------------------------------

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        MapLibre.getInstance(requireContext().applicationContext)
        savedInstanceStateForMap = savedInstanceState

        val raiz = inflater.inflate(R.layout.fragment_map, container, false)

        chipsContainer = raiz.findViewById(R.id.mapChips)
        legenda = raiz.findViewById(R.id.mapLegendScroll)
        faixaLocalizacao = raiz.findViewById(R.id.mapLocationBanner)
        fabCentralizar = raiz.findViewById(R.id.btnMapCenter)
        fabSinalizar = raiz.findViewById(R.id.btnMapAdd)
        cartao = Cartao(raiz)
        editor = Editor(raiz)
        searchViews = SearchViews(raiz)
        previewViews = PreviewViews(raiz)
        navViews = NavViews(raiz)
        packSheet = PackSheetViews(raiz)

        avoidBlacklistInRoute = prefs.blacklistEnabled
        routeProfile = if (prefs.isMotorcycleMode) RouteProfile.MOTO_FASTEST else RouteProfile.CAR_FASTEST

        montarChips()
        configurarLegenda(raiz)
        configurarCartao()
        configurarEditor()
        configurarBusca()
        configurarPreviewRota()
        configurarNavOverlay()
        configurarPackSheet()
        configurarBotoes(raiz)

        aplicarPlanoMapa()
        return raiz
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repositorio.observar(observadorSinais)
        observarEstadosOffline()
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
        registrarObservadorRede()
    }

    override fun onResume() {
        super.onResume()
        Premium.adicionarObservador(atualizadorDePlano)
        aplicarPlanoMapa()
        atualizarPermissaoLocalizacao()
        if (isMapTabVisible) {
            retomarMapView()
        }
        inscreverGps()
        pararRelogio()
        relogioHandler.postDelayed(relogioSinais, INTERVALO_RELOGIO_MS)
    }

    /** Notificado pela MainActivity quando o motorista entra ou sai da aba Mapa. */
    fun onTabVisibilityChanged(visible: Boolean) {
        isMapTabVisible = visible
        val navigating = navController?.state?.value is NavigationUiState.Navigating
        if (visible) {
            retomarMapView()
            inscreverGps()
        } else {
            if (!navigating) {
                pausarMapView()
                GpsLocationStream.unsubscribe(consumidorGps)
            }
        }
    }

    override fun onPause() {
        Premium.removerObservador(atualizadorDePlano)
        pararRelogio()
        val navigating = navController?.state?.value is NavigationUiState.Navigating
        if (!navigating) {
            GpsLocationStream.unsubscribe(consumidorGps)
        }
        pausarMapView()
        super.onPause()
    }

    override fun onStop() {
        desregistrarObservadorRede()
        mapView?.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        pararRelogio()
        searchJob?.cancel()
        routingJob?.cancel()
        packDownloadJob?.cancel()
        cameraIdleDebounceJob?.cancel()
        desregistrarObservadorRede()
        GpsLocationStream.unsubscribe(consumidorGps)
        repositorio.pararDeObservar(observadorSinais)
        navController?.stopNavigation()
        navController = null
        voiceEngine?.close()
        voiceEngine = null
        routeEngine?.close()
        routeEngine = null
        geocoder?.close()
        geocoder = null
        tileServer?.close()
        tileServer = null
        layersController?.detach()
        layersController = null
        mapView?.onDestroy()
        mapView = null
        mapLibreMap = null
        isMapViewResumed = false
        cartao = null
        editor = null
        searchViews = null
        previewViews = null
        navViews = null
        packSheet = null
        chipsContainer = null
        legenda = null
        faixaLocalizacao = null
        fabCentralizar = null
        fabSinalizar = null
        super.onDestroyView()
    }

    // ----------------------------------------------------------------------
    // Inicialização MapLibre + Pacote Offline + Motor DFRG
    // ----------------------------------------------------------------------

    private fun aplicarPlanoMapa() {
        val raiz = view ?: return
        val trava = raiz.findViewById<View>(R.id.mapLock) ?: return
        val liberado = Premium.ativo(requireContext())
        trava.visibility = if (liberado) View.GONE else View.VISIBLE
        if (liberado && mapView == null) {
            montarMapLibre(raiz)
        }
    }

    private fun montarMapLibre(raiz: View) {
        val container = raiz.findViewById<FrameLayout>(R.id.mapContainer) ?: return
        val mv = MapView(requireContext())
        mv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.removeAllViews()
        container.addView(mv)
        mv.onCreate(savedInstanceStateForMap)
        mapView = mv
        if (isResumed && isMapTabVisible) {
            mv.onStart()
            retomarMapView()
        }

        layersController = MapLibreLayersController(requireContext().applicationContext)
        voiceEngine = NavigationVoiceEngine(requireContext().applicationContext)
        val engine = DfrgRouteEngine().also { routeEngine = it }
        navController = OfflineNavigationController(
            routeEngine = engine,
            voiceSpeaker = voiceEngine,
            scope = viewLifecycleOwner.lifecycleScope
        )
        observarNavegacao()

        mv.getMapAsync { map ->
            mapLibreMap = map
            configurarInteracoesMapa(map)
            inicializarPacoteOffline()
        }
    }

    private fun configurarInteracoesMapa(map: MapLibreMap) {
        map.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = false
            isTiltGesturesEnabled = true
            isRotateGesturesEnabled = true
        }

        // Posição inicial: Joinville Centro (ou GPS real quando disponível)
        val inicio = posicaoReferencia()
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(inicio.lat, inicio.lng))
                    .zoom(JOINVILLE_DEFAULT_ZOOM)
                    .tilt(0.0)
                    .bearing(0.0)
                    .build()
            )
        )

        map.addOnMapClickListener { latLng ->
            fecharBusca()
            val screenPt = map.projection.toScreenLocation(latLng)
            val signalId = layersController?.hitTestSignalId(map, screenPt)
            if (signalId != null) {
                abrirCard(signalId)
                return@addOnMapClickListener true
            }
            val altIdx = layersController?.hitTestAlternativeIndex(map, screenPt)
            if (altIdx != null) {
                navController?.selectPreviewRoute(altIdx)
                return@addOnMapClickListener true
            }
            fecharCard()
            false
        }

        map.addOnMapLongClickListener { latLng ->
            val state = navController?.state?.value
            if (state is NavigationUiState.Navigating) return@addOnMapLongClickListener false
            tratarToqueLongoNoMapa(GeoPoint(latLng.latitude, latLng.longitude))
            true
        }

        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                if (navController?.state?.value is NavigationUiState.Navigating) {
                    navController?.setCameraFollow(false)
                }
            }
        }

        map.addOnCameraIdleListener {
            atualizarAreaVisivel()
            agendarAvaliacaoHibridaPorCamera()
        }
    }

    private fun inicializarPacoteOffline() {
        // 1) Carrega catálogo em cache (ou fallback 27 estados) instantaneamente sem bloquear a UI.
        catalogSnapshot = regionCatalog.loadCached() ?: RegionCatalog.embeddedBrazilFallback()

        // 2) Avalia imediatamente o modo visual híbrido (offline se pacote instalado cobrir o ponto,
        //    senão OpenFreeMap online sem chave, senão aviso offline).
        reavaliarModoHibrido(forceImmediate = true)

        // 3) Atualiza catálogo remoto em background (`catalog.json` com fallback `manifest.json`).
        viewLifecycleOwner.lifecycleScope.launch {
            val remote = regionCatalog.fetchRemoteOrCached()
            if (!isAdded || remote == null) return@launch
            catalogSnapshot = remote
            reavaliarModoHibrido(forceImmediate = false)
            if (packSheet?.scrim?.visibility == View.VISIBLE) {
                preencherListasPackSheet()
            }
        }
    }

    private fun agendarAvaliacaoHibridaPorCamera() {
        // Durante navegação ativa não trocamos o estilo em movimento.
        if (navController?.state?.value is NavigationUiState.Navigating) return
        cameraIdleDebounceJob?.cancel()
        cameraIdleDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500L)
            if (isAdded) reavaliarModoHibrido(forceImmediate = false)
        }
    }

    private fun reavaliarModoHibrido(forceImmediate: Boolean) {
        val map = mapLibreMap ?: return
        val camTarget = map.cameraPosition.target
        val refPoint = when {
            camTarget != null && (camTarget.latitude != 0.0 || camTarget.longitude != 0.0) ->
                GeoPoint(camTarget.latitude, camTarget.longitude)
            else -> posicaoReferencia()
        }

        val installed = packManager.listInstalledPacks()
        val (mode, styleChanged) = hybridController.evaluate(
            lat = refPoint.lat,
            lng = refPoint.lng,
            installedPacks = installed,
            catalog = catalogSnapshot,
            isOnline = isDeviceOnline(),
            forceImmediate = forceImmediate
        )

        when (mode) {
            is MapVisualMode.OfflinePack -> {
                currentSuggestedPack = null
                packSheet?.onlineAttribution?.visibility = View.GONE
                packSheet?.regionBanner?.visibility = View.GONE
                packSheet?.setupScroll?.visibility = View.GONE
                if (styleChanged || activePackInfo?.packId != mode.pack.packId || tileServer == null) {
                    ativarPacoteOffline(mode.pack)
                } else {
                    atualizarBadgePacote(PackState.Ready(mode.pack))
                }
            }

            is MapVisualMode.OnlineOpenFreeMap -> {
                currentSuggestedPack = mode.suggestedPack
                packSheet?.onlineAttribution?.text = EstiloMapa.OPENFREEMAP_ATTRIBUTION
                packSheet?.onlineAttribution?.visibility = View.VISIBLE
                packSheet?.setupScroll?.visibility = View.GONE
                atualizarBadgeModoOnline(online = true)
                atualizarBannerRegiao(mode.suggestedPack, online = true)
                if (styleChanged) {
                    carregarEstiloOnline(mode.styleUrl)
                }
            }

            is MapVisualMode.OfflineUnavailable -> {
                currentSuggestedPack = mode.suggestedPack
                packSheet?.onlineAttribution?.visibility = View.GONE
                packSheet?.setupScroll?.visibility = View.GONE
                atualizarBadgeModoOnline(online = false)
                atualizarBannerRegiao(mode.suggestedPack, online = false)
                if (styleChanged) {
                    carregarEstiloVazio()
                }
            }
        }
    }

    private fun carregarEstiloOnline(styleUrl: String) {
        mapLibreMap?.setStyle(Style.Builder().fromUri(styleUrl)) { loadedStyle ->
            reattachAllDriFlyLayers(loadedStyle)
        }
    }

    private fun reattachAllDriFlyLayers(loadedStyle: Style) {
        layersController?.attach(loadedStyle)
        renderizarSinais()
        val navState = navController?.state?.value
        when (navState) {
            is NavigationUiState.Preview -> {
                layersController?.updateRouteOverlay(
                    activeRoute = navState.selectedRoute,
                    alternatives = navState.plan.alternatives,
                    selectedIndex = navState.selectedRouteIndex,
                    waypoints = navState.plan.orderedWaypoints,
                    avoidZones = navState.plan.activeAvoidZones,
                    isNavigating = false
                )
                layersController?.updateDriverPuck(posicaoAtual, ultimoBearingGps, isNavigating = false)
            }
            is NavigationUiState.Navigating -> {
                layersController?.updateRouteOverlay(
                    activeRoute = navState.snapshot.route,
                    alternatives = emptyList(),
                    selectedIndex = 0,
                    waypoints = plannedWaypoints,
                    avoidZones = emptyList(),
                    isNavigating = true
                )
                layersController?.updateDriverPuck(
                    point = navState.snapshot.snappedPoint,
                    bearingDeg = navState.snapshot.snappedBearingDeg,
                    isNavigating = true
                )
            }
            else -> {
                layersController?.updateDriverPuck(posicaoAtual, ultimoBearingGps, isNavigating = false)
            }
        }
        atualizarAreaVisivel()
    }

    private fun ativarPacoteOffline(info: PackInfo) {
        activePackInfo = info
        packSheet?.setupScroll?.visibility = View.GONE
        packSheet?.onlineAttribution?.visibility = View.GONE
        packSheet?.regionBanner?.visibility = View.GONE
        atualizarBadgePacote(PackState.Ready(info))

        viewLifecycleOwner.lifecycleScope.launch {
            val serverHandle = withContext(Dispatchers.IO) {
                tileServer?.close()
                val appAssets = requireContext().applicationContext.assets
                val srv = LocalMapTileServer(
                    packDir = info.packDir,
                    pmTilesFile = info.pmTilesFile,
                    styleProvider = { baseUrl -> EstiloMapa.buildStyleJson(info.packDir, baseUrl, nightMode = false) },
                    assetGlyphProvider = { fontstack, range ->
                        try {
                            appAssets.open("glyphs/$fontstack/$range.pbf").use { it.readBytes() }
                        } catch (_: Exception) {
                            null
                        }
                    }
                )
                tileServer = srv
                srv.start()
            }

            if (!isAdded) return@launch
            mapLibreMap?.setStyle(Style.Builder().fromUri(serverHandle.styleUrl)) { loadedStyle ->
                reattachAllDriFlyLayers(loadedStyle)
            }

            // Inicializa geocoder e motor de rotas DFRG v1 em background (~20 ms)
            val (geoOk, routerOk) = withContext(Dispatchers.IO) {
                geocoder?.close()
                val geo = info.geocoderDbFile?.let { OfflineGeocoder(it).apply { open() } }
                geocoder = geo
                val engine = routeEngine
                val rOk = engine?.initialize(info.packDir) == true
                routeOptimizer = engine?.let { SmartRouteOptimizer(it, neighborhoodLookup = geo) }
                (geo?.isOpen == true) to rOk
            }

            if (!isAdded) return@launch
            if (routerOk) {
                avisar("Mapa de ${info.displayName} pronto!", erro = false)
            } else if (geoOk) {
                avisar("Mapa carregado (arquivo de rotas não encontrado).", erro = true)
            }
        }
    }

    private fun carregarEstiloVazio() {
        val emptyStyle = """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#F3F5F9"}}]}"""
        mapLibreMap?.setStyle(Style.Builder().fromJson(emptyStyle)) { loadedStyle ->
            reattachAllDriFlyLayers(loadedStyle)
        }
    }

    private fun isDeviceOnline(): Boolean {
        val ctx = context ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    private fun registrarObservadorRede() {
        if (networkCallback != null) return
        val ctx = context ?: return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    relogioHandler.post {
                        if (isAdded) {
                            reavaliarModoHibrido(forceImmediate = true)
                            atualizarAreaVisivel()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    relogioHandler.post {
                        if (isAdded) reavaliarModoHibrido(forceImmediate = true)
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun desregistrarObservadorRede() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun observarEstadosOffline() {
        viewLifecycleOwner.lifecycleScope.launch {
            packManager.state.collect { state ->
                if (!isAdded) return@collect
                atualizarBadgePacote(state)
                atualizarPackSheetUi(state)
            }
        }
    }

    private fun observarNavegacao() {
        val ctrl = navController ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            ctrl.state.collect { uiState ->
                if (!isAdded) return@collect
                aplicarEstadoNavegacao(uiState)
            }
        }
    }

    // ----------------------------------------------------------------------
    // Busca Offline (FTS5) + Toque Longo + Multi-Paradas
    // ----------------------------------------------------------------------

    private fun configurarBusca() {
        val sv = searchViews ?: return
        sv.packStatusPill.setOnClickListener { abrirPackSheet() }
        sv.clearBtn.setOnClickListener {
            setSearchTextSilently("")
            if (sv.input.hasFocus()) mostrarAtalhosRapidosBusca() else fecharBusca()
        }
        sv.input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && sv.input.text.isNullOrBlank()) {
                mostrarAtalhosRapidosBusca()
            }
        }
        sv.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                executarBuscaOffline(sv.input.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        sv.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchWatcher) return
                val query = s?.toString().orEmpty()
                sv.clearBtn.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                searchJob?.cancel()
                if (query.trim().length < 2) {
                    if (sv.input.hasFocus()) mostrarAtalhosRapidosBusca() else fecharBusca()
                    return
                }
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(150L)
                    executarBuscaOffline(query)
                }
            }
        })
    }

    private fun setSearchTextSilently(text: String) {
        val sv = searchViews ?: return
        searchJob?.cancel()
        suppressSearchWatcher = true
        try {
            sv.input.setText(text)
            sv.clearBtn.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        } finally {
            suppressSearchWatcher = false
        }
    }

    private fun executarBuscaOffline(query: String) {
        val sv = searchViews ?: return
        val geo = geocoder
        if (geo == null || !geo.isOpen) {
            sv.resultsCard.visibility = View.VISIBLE
            sv.resultsList.removeAllViews()
            sv.resultsList.addView(
                criarLinhaBusca(
                    titulo = "Baixe o mapa offline de Joinville",
                    subtitulo = "Toque para instalar busca de ruas e navegação sem internet"
                ) {
                    fecharBusca()
                    abrirPackSheet()
                }
            )
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) { geo.search(query, limit = 10) }
            if (!isAdded) return@launch
            sv.resultsList.removeAllViews()
            sv.resultsCard.visibility = View.VISIBLE
            if (hits.isEmpty()) {
                sv.resultsList.addView(
                    criarLinhaBusca(
                        titulo = "Nenhum endereço encontrado",
                        subtitulo = "Tente rua, bairro ou ponto de referência em Joinville"
                    ) { fecharBusca() }
                )
                return@launch
            }
            for (place in hits) {
                sv.resultsList.addView(
                    criarLinhaBusca(place.displayTitle, place.displaySubtitle) {
                        selecionarDestino(RouteWaypoint(place.point, place.displayTitle))
                    }
                )
            }
        }
    }

    private fun mostrarAtalhosRapidosBusca() {
        val sv = searchViews ?: return
        sv.resultsList.removeAllViews()
        sv.resultsCard.visibility = View.VISIBLE

        if (prefs.homeLatitude != 0f && prefs.homeLongitude != 0f) {
            val casaLabel = prefs.homeAddress.ifBlank { "Casa (Modo Casa)" }
            sv.resultsList.addView(
                criarLinhaBusca("Ir para Casa", casaLabel) {
                    selecionarDestino(
                        RouteWaypoint(
                            point = GeoPoint(prefs.homeLatitude.toDouble(), prefs.homeLongitude.toDouble()),
                            label = "Casa",
                            isLockedOrder = true
                        )
                    )
                }
            )
        }

        // Atalhos de referência em Joinville para navegação/teste instantâneo
        val presets = listOf(
            Triple("Aeroporto de Joinville", "Aventureiro · Joinville", GeoPoint(-26.2245, -48.7974)),
            Triple("Terminal Central", "Centro · Joinville", GeoPoint(-26.3046, -48.8464)),
            Triple("Shopping Mueller", "Centro · Joinville", GeoPoint(-26.3037, -48.8489)),
            Triple("Expoville", "Glória · Joinville", GeoPoint(-26.3055, -48.8794))
        )
        for ((titulo, sub, pt) in presets) {
            sv.resultsList.addView(
                criarLinhaBusca(titulo, sub) {
                    selecionarDestino(RouteWaypoint(pt, titulo))
                }
            )
        }
    }

    private fun criarLinhaBusca(titulo: String, subtitulo: String, aoClicar: () -> Unit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            isClickable = true
            isFocusable = true
            setOnClickListener { aoClicar() }
        }
        row.addView(TextView(ctx).apply {
            text = titulo
            textSize = 13.5f
            typeface = fonte(Typeface.BOLD)
            setTextColor(cor(R.color.drifly_ink))
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (subtitulo.isNotBlank()) {
            row.addView(TextView(ctx).apply {
                text = subtitulo
                textSize = 11.5f
                setTextColor(cor(R.color.drifly_mut))
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        return row
    }

    private fun fecharBusca() {
        searchJob?.cancel()
        searchViews?.resultsCard?.visibility = View.GONE
        esconderTeclado()
    }

    private fun tratarToqueLongoNoMapa(ponto: GeoPoint) {
        viewLifecycleOwner.lifecycleScope.launch {
            val label = withContext(Dispatchers.IO) {
                geocoder?.nearestPlaceLabel(ponto)
            } ?: String.format(Locale.US, "%.4f, %.4f", ponto.lat, ponto.lng)
            if (!isAdded) return@launch

            val opcoes = if (plannedWaypoints.isEmpty()) {
                arrayOf("Navegar até $label", "Sinalizar neste local")
            } else {
                arrayOf("Ir direto para $label", "+ Adicionar parada ($label)")
            }
            AlertDialog.Builder(requireContext())
                .setTitle(label)
                .setItems(opcoes) { _, which ->
                    when {
                        plannedWaypoints.isEmpty() && which == 0 ->
                            selecionarDestino(RouteWaypoint(ponto, label))
                        plannedWaypoints.isEmpty() && which == 1 ->
                            abrirEditor()
                        which == 0 -> {
                            addingExtraStop = false
                            selecionarDestino(RouteWaypoint(ponto, label))
                        }
                        else -> {
                            addingExtraStop = true
                            selecionarDestino(RouteWaypoint(ponto, label))
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun selecionarDestino(waypoint: RouteWaypoint) {
        fecharBusca()
        fecharCard()
        setSearchTextSilently(waypoint.label)
        if (addingExtraStop) {
            plannedWaypoints.add(waypoint)
            addingExtraStop = false
        } else {
            plannedWaypoints.clear()
            plannedWaypoints.add(waypoint)
        }
        recalcularRotaPlanejada()
    }

    // ----------------------------------------------------------------------
    // Planejamento & Prévia de Rota (DFRG + TSP + Lista Negra)
    // ----------------------------------------------------------------------

    private fun configurarPreviewRota() {
        val pv = previewViews ?: return
        pv.closeBtn.setOnClickListener {
            plannedWaypoints.clear()
            addingExtraStop = false
            setSearchTextSilently("")
            fecharBusca()
            navController?.stopNavigation()
            layersController?.clearRoutes()
        }
        pv.blacklistChip.setOnClickListener {
            avoidBlacklistInRoute = !avoidBlacklistInRoute
            recalcularRotaPlanejada()
        }
        pv.profileChip.setOnClickListener {
            routeProfile = when (routeProfile) {
                RouteProfile.CAR_FASTEST -> RouteProfile.CAR_SHORTEST
                RouteProfile.CAR_SHORTEST -> RouteProfile.MOTO_FASTEST
                RouteProfile.MOTO_FASTEST -> RouteProfile.CAR_FASTEST
            }
            recalcularRotaPlanejada()
        }
        pv.addStopBtn.setOnClickListener {
            addingExtraStop = true
            setSearchTextSilently("")
            searchViews?.input?.requestFocus()
            mostrarAtalhosRapidosBusca()
            avisar("Busque ou segure no mapa para adicionar outra parada")
        }
        pv.simulateBtn.setOnClickListener {
            iniciarNavegacaoDaPrevia(simular = true)
        }
        pv.startBtn.setOnClickListener {
            iniciarNavegacaoDaPrevia(simular = false)
        }
    }

    private fun recalcularRotaPlanejada() {
        if (plannedWaypoints.isEmpty()) return
        val optimizer = routeOptimizer
        if (optimizer == null || routeEngine?.isReady != true) {
            avisar("Baixe o pacote offline de Joinville para calcular rotas.", erro = true)
            abrirPackSheet()
            return
        }

        routingJob?.cancel()
        routingJob = viewLifecycleOwner.lifecycleScope.launch {
            val origin = posicaoReferencia()
            val blacklist = withContext(Dispatchers.IO) { db.getAllBlacklistItems() }
            val result = withContext(Dispatchers.Default) {
                optimizer.planRoute(
                    origin = origin,
                    waypoints = plannedWaypoints.toList(),
                    profile = routeProfile,
                    blacklistItems = blacklist,
                    avoidBlacklist = avoidBlacklistInRoute,
                    optimizeStopOrder = plannedWaypoints.size >= 2,
                    lockFinalDestination = plannedWaypoints.lastOrNull()?.isLockedOrder == true,
                    costPerKmBrl = prefs.costPerKm,
                    originBearingDeg = if (posicaoAtual != null) ultimoBearingGps else null,
                    computeAlternatives = plannedWaypoints.size == 1
                )
            }
            if (!isAdded) return@launch
            result.onSuccess { plan ->
                plannedWaypoints.clear()
                plannedWaypoints.addAll(plan.orderedWaypoints)
                navController?.showPreview(plan)
                enquadrarRotaNaCamera(plan.primary.geometry)
            }.onFailure { err ->
                avisar(err.message ?: "Não foi possível calcular a rota offline.", erro = true)
            }
        }
    }

    private fun iniciarNavegacaoDaPrevia(simular: Boolean) {
        val ctrl = navController ?: return
        val preview = ctrl.state.value as? NavigationUiState.Preview ?: return
        simSpeedMultiplier = 1.0
        ctrl.setSimulationSpeed(1.0)
        val initialFix = if (simular) {
            null
        } else {
            posicaoAtual?.let { NavLocationFix(it, speedMps = 0f, bearingDeg = ultimoBearingGps) }
        }
        ctrl.startNavigation(
            route = preview.selectedRoute,
            waypoints = preview.plan.orderedWaypoints,
            profile = routeProfile,
            avoidZones = preview.plan.activeAvoidZones,
            initialFix = initialFix,
            simulate = simular
        )
        inscreverGps()
    }

    private fun enquadrarRotaNaCamera(geometry: List<GeoPoint>) {
        val map = mapLibreMap ?: return
        if (geometry.size < 2) return
        val builder = LatLngBounds.Builder()
        for (pt in geometry) {
            builder.include(LatLng(pt.lat, pt.lng))
        }
        try {
            map.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    builder.build(),
                    dp(48f),
                    dp(120f),
                    dp(48f),
                    dp(260f)
                ),
                450
            )
        } catch (_: Exception) {
        }
    }

    // ----------------------------------------------------------------------
    // Turn-by-Turn Overlay & Estado de Navegação
    // ----------------------------------------------------------------------

    private fun configurarNavOverlay() {
        val nv = navViews ?: return
        nv.recenterBtn.setOnClickListener {
            navController?.setCameraFollow(true)
        }
        nv.voiceBtn.setOnClickListener {
            val muted = navController?.toggleVoiceMute() ?: false
            nv.voiceBtn.setImageResource(if (muted) R.drawable.ic_nav_sound_off else R.drawable.ic_nav_sound_on)
            avisar(if (muted) "Voz silenciada" else "Voz de navegação ativada")
        }
        nv.simBadge.setOnClickListener {
            simSpeedMultiplier = if (simSpeedMultiplier < 1.5) 2.0 else 1.0
            navController?.setSimulationSpeed(simSpeedMultiplier)
            nv.simBadge.text = if (simSpeedMultiplier >= 1.5) "DEMO 2x" else "DEMO 1x"
        }
        nv.stopBtn.setOnClickListener {
            plannedWaypoints.clear()
            navController?.stopNavigation()
            layersController?.clearRoutes()
            inscreverGps()
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(posicaoReferencia().lat, posicaoReferencia().lng))
                        .zoom(JOINVILLE_DEFAULT_ZOOM)
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ),
                400
            )
        }
    }

    private fun aplicarEstadoNavegacao(uiState: NavigationUiState) {
        val pv = previewViews
        val nv = navViews
        val sv = searchViews

        when (uiState) {
            is NavigationUiState.Idle -> {
                pv?.card?.visibility = View.GONE
                nv?.root?.visibility = View.GONE
                sv?.topControls?.visibility = View.VISIBLE
                if (sinalAberto == null) {
                    legenda?.visibility = View.VISIBLE
                    fabCentralizar?.visibility = View.VISIBLE
                    fabSinalizar?.visibility = View.VISIBLE
                }
                layersController?.clearRoutes()
                layersController?.updateDriverPuck(posicaoAtual, ultimoBearingGps, isNavigating = false)
            }

            is NavigationUiState.Preview -> {
                nv?.root?.visibility = View.GONE
                sv?.topControls?.visibility = View.VISIBLE
                legenda?.visibility = View.GONE
                fabCentralizar?.visibility = View.GONE
                fabSinalizar?.visibility = View.GONE
                packSheet?.regionBanner?.visibility = View.GONE
                pv?.card?.visibility = View.VISIBLE
                preencherPreviewCard(uiState)
                layersController?.updateRouteOverlay(
                    activeRoute = uiState.selectedRoute,
                    alternatives = uiState.plan.alternatives,
                    selectedIndex = uiState.selectedRouteIndex,
                    waypoints = uiState.plan.orderedWaypoints,
                    avoidZones = uiState.plan.activeAvoidZones,
                    isNavigating = false
                )
            }

            is NavigationUiState.Navigating -> {
                pv?.card?.visibility = View.GONE
                sv?.topControls?.visibility = View.GONE
                legenda?.visibility = View.GONE
                fabCentralizar?.visibility = View.GONE
                fabSinalizar?.visibility = View.GONE
                packSheet?.regionBanner?.visibility = View.GONE
                fecharCard()
                nv?.root?.visibility = View.VISIBLE
                atualizarHudNavegacao(uiState.snapshot)
                layersController?.updateRouteOverlay(
                    activeRoute = uiState.snapshot.route,
                    alternatives = emptyList(),
                    selectedIndex = 0,
                    waypoints = plannedWaypoints,
                    avoidZones = emptyList(),
                    isNavigating = true
                )
                layersController?.updateDriverPuck(
                    point = uiState.snapshot.snappedPoint,
                    bearingDeg = uiState.snapshot.snappedBearingDeg,
                    isNavigating = true
                )
                atualizarCamera3D(uiState.snapshot)
            }

            is NavigationUiState.Arrived -> {
                nv?.root?.visibility = View.GONE
                pv?.card?.visibility = View.GONE
                sv?.topControls?.visibility = View.VISIBLE
                legenda?.visibility = View.VISIBLE
                fabCentralizar?.visibility = View.VISIBLE
                fabSinalizar?.visibility = View.VISIBLE
                plannedWaypoints.clear()
                layersController?.clearRoutes()
                avisar("Você chegou a ${uiState.destinationLabel}!")
                navController?.stopNavigation()
            }
        }
    }

    private fun preencherPreviewCard(state: NavigationUiState.Preview) {
        val pv = previewViews ?: return
        val route = state.selectedRoute
        val plan = state.plan

        val mins = (route.totalDurationSeconds / 60.0).roundToInt().coerceAtLeast(1)
        pv.duration.text = formatarMinutos(mins)
        pv.distance.text = formatarDistancia(route.totalDistanceMeters)
        val fuel = (route.totalDistanceMeters / 1000.0) * prefs.costPerKm
        pv.fuelCost.text = String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f", fuel)

        val destLabel = plan.orderedWaypoints.lastOrNull()?.label?.ifBlank { "Destino" } ?: "Destino"
        pv.destination.text = if (plan.orderedWaypoints.size > 1) {
            "${plan.orderedWaypoints.size} paradas · final em $destLabel"
        } else {
            destLabel
        }

        // Badge Lista Negra
        if (avoidBlacklistInRoute) {
            val count = plan.activeAvoidZones.size
            pv.blacklistChip.text = if (count > 0) "Evitando $count zona(s) bloqueada(s)" else "Lista Negra ativa"
            pv.blacklistChip.setBackgroundResource(R.drawable.bg_light_pill_green)
            pv.blacklistChip.setTextColor(cor(R.color.drifly_green))
        } else {
            pv.blacklistChip.text = "Lista Negra: desligada"
            pv.blacklistChip.setBackgroundResource(R.drawable.bg_map_chip)
            pv.blacklistChip.setTextColor(cor(R.color.drifly_mut))
        }

        // Perfil de rota
        pv.profileChip.text = when (routeProfile) {
            RouteProfile.CAR_FASTEST -> "Carro · Rápida"
            RouteProfile.CAR_SHORTEST -> "Carro · Curta"
            RouteProfile.MOTO_FASTEST -> "Moto · Rápida"
        }

        // Resumo de otimização multi-paradas
        val optText = SmartRouteOptimizer.formatOptimizationSummary(plan)
        if (optText != null) {
            pv.optimizedChip.text = optText
            pv.optimizedChip.visibility = View.VISIBLE
        } else {
            pv.optimizedChip.visibility = View.GONE
        }

        // Aviso (ex.: destino dentro de bairro da lista negra)
        if (!plan.summaryWarning.isNullOrBlank()) {
            pv.warning.text = plan.summaryWarning
            pv.warning.visibility = View.VISIBLE
        } else {
            pv.warning.visibility = View.GONE
        }

        // Rotas alternativas clicáveis (faixa horizontal sem quebra de linha)
        pv.alternativesRow.removeAllViews()
        if (plan.alternatives.isNotEmpty()) {
            pv.alternativesScroll.visibility = View.VISIBLE
            val allRoutes = listOf(plan.primary) + plan.alternatives
            allRoutes.forEachIndexed { idx, r ->
                val active = idx == state.selectedRouteIndex
                val m = (r.totalDurationSeconds / 60.0).roundToInt().coerceAtLeast(1)
                val chip = TextView(requireContext()).apply {
                    text = "Rota ${idx + 1} · ${formatarMinutos(m)} (${formatarDistancia(r.totalDistanceMeters)})"
                    textSize = 11.5f
                    typeface = fonte(Typeface.BOLD)
                    maxLines = 1
                    isSingleLine = true
                    setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                    setBackgroundResource(if (active) R.drawable.bg_map_chip_on else R.drawable.bg_map_chip)
                    setTextColor(if (active) cor(R.color.drifly_card) else cor(R.color.drifly_mut))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6f) }
                    setOnClickListener { navController?.selectPreviewRoute(idx) }
                }
                pv.alternativesRow.addView(chip)
            }
        } else {
            pv.alternativesScroll.visibility = View.GONE
        }

        // Lista de paradas (quando multi-paradas)
        pv.stopsContainer.removeAllViews()
        if (plan.orderedWaypoints.size >= 2) {
            pv.stopsScroll.visibility = View.VISIBLE
            plan.orderedWaypoints.forEachIndexed { idx, wp ->
                val pill = TextView(requireContext()).apply {
                    text = "${idx + 1}. ${wp.label.ifBlank { "Parada" }} ×"
                    textSize = 11.5f
                    typeface = fonte(Typeface.BOLD)
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    setBackgroundResource(R.drawable.bg_map_chip)
                    setTextColor(cor(R.color.drifly_ink))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6f) }
                    setOnClickListener {
                        if (plannedWaypoints.size > 1 && idx in plannedWaypoints.indices) {
                            plannedWaypoints.removeAt(idx)
                            recalcularRotaPlanejada()
                        }
                    }
                }
                pv.stopsContainer.addView(pill)
            }
        } else {
            pv.stopsScroll.visibility = View.GONE
        }
    }

    private fun atualizarHudNavegacao(snap: NavigationSnapshot) {
        val nv = navViews ?: return
        nv.maneuverIcon.setImageResource(iconeManobra(snap.currentStep.maneuver))
        nv.maneuverDist.text = if (snap.isRecalculating) {
            "Recalculando..."
        } else {
            formatarDistancia(snap.distanceToManeuverMeters)
        }
        nv.streetName.text = snap.currentStep.streetName.ifBlank { snap.currentStep.instructionPtBr }

        nv.simBadge.visibility = if (snap.isSimulated) View.VISIBLE else View.GONE

        val next = snap.nextStep
        if (snap.isOffRoute) {
            nv.nextStepText.text = "Fora da rota — recalculando localmente"
            nv.nextStepBar.visibility = View.VISIBLE
        } else if (next != null && next.maneuver != ManeuverType.ARRIVE) {
            val nextVia = next.streetName.ifBlank { next.instructionPtBr }
            nv.nextStepText.text = "Depois: $nextVia"
            nv.nextStepBar.visibility = View.VISIBLE
        } else {
            nv.nextStepBar.visibility = View.GONE
        }

        val remMins = (snap.remainingDurationSeconds / 60.0).roundToInt().coerceAtLeast(1)
        nv.remainingTime.text = formatarMinutos(remMins)
        val etaStr = etaTimeFormat.format(Date(snap.etaEpochMs))
        val speedStr = if (snap.currentSpeedKmh >= 2f) " · ${snap.currentSpeedKmh.roundToInt()} km/h" else ""
        nv.remainingMeta.text = "${formatarDistancia(snap.remainingDistanceMeters)} · chegada $etaStr$speedStr"

        nv.recenterBtn.visibility = if (snap.camera.followEnabled) View.GONE else View.VISIBLE
        nv.voiceBtn.setImageResource(if (snap.isVoiceMuted) R.drawable.ic_nav_sound_off else R.drawable.ic_nav_sound_on)
    }

    private fun atualizarCamera3D(snap: NavigationSnapshot) {
        val map = mapLibreMap ?: return
        if (!snap.camera.followEnabled) return
        val cam = CameraPosition.Builder()
            .target(LatLng(snap.camera.target.lat, snap.camera.target.lng))
            .zoom(snap.camera.zoom)
            .tilt(snap.camera.tiltDeg)
            .bearing(snap.camera.bearingDeg.toDouble())
            .padding(0.0, dp(220f).toDouble(), 0.0, dp(100f).toDouble())
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 450)
    }

    private fun iconeManobra(type: ManeuverType): Int = when (type) {
        ManeuverType.RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.RAMP_RIGHT -> R.drawable.ic_maneuver_right
        ManeuverType.LEFT, ManeuverType.SHARP_LEFT, ManeuverType.RAMP_LEFT -> R.drawable.ic_maneuver_left
        ManeuverType.SLIGHT_RIGHT -> R.drawable.ic_maneuver_slight_right
        ManeuverType.SLIGHT_LEFT -> R.drawable.ic_maneuver_slight_left
        ManeuverType.UTURN_LEFT, ManeuverType.UTURN_RIGHT -> R.drawable.ic_maneuver_uturn
        ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT -> R.drawable.ic_maneuver_roundabout
        ManeuverType.ARRIVE, ManeuverType.WAYPOINT_ARRIVE -> R.drawable.ic_maneuver_arrive
        else -> R.drawable.ic_maneuver_straight
    }

    // ----------------------------------------------------------------------
    // Painel Multi-Pacote Offline (Brasil · Cidades e Estados)
    // ----------------------------------------------------------------------

    private fun configurarPackSheet() {
        val ps = packSheet ?: return
        ps.scrim.setOnClickListener { fecharPackSheet() }
        ps.closeBtn.setOnClickListener { fecharPackSheet() }
        ps.setupCloseBtn.setOnClickListener { ps.setupScroll.visibility = View.GONE }
        ps.setupDownloadBtn.setOnClickListener {
            ps.setupScroll.visibility = View.GONE
            abrirPackSheet()
        }
        ps.cancelBtn.setOnClickListener {
            packDownloadJob?.cancel()
            packDownloadJob = null
            avisar("Download cancelado.")
        }
        ps.regionBannerClose.setOnClickListener {
            dismissedBannerPackId = currentSuggestedPack?.id ?: "__none__"
            ps.regionBanner.visibility = View.GONE
        }
        ps.regionBannerAction.setOnClickListener {
            val suggested = currentSuggestedPack
            abrirPackSheet()
            if (suggested != null && suggested.available) {
                iniciarDownloadRegionPack(suggested)
            }
        }
    }

    private fun abrirPackSheet() {
        val ps = packSheet ?: return
        preencherListasPackSheet()
        atualizarPackSheetUi(packManager.state.value)
        ps.scrim.visibility = View.VISIBLE
    }

    private fun fecharPackSheet() {
        packSheet?.scrim?.visibility = View.GONE
    }

    private fun atualizarBannerRegiao(suggested: RegionPack?, online: Boolean) {
        val ps = packSheet ?: return
        val navIdle = navController?.state?.value is NavigationUiState.Idle
        if (!navIdle || sinalAberto != null) {
            ps.regionBanner.visibility = View.GONE
            return
        }
        val key = suggested?.id ?: "__none__"
        if (dismissedBannerPackId == key && online) {
            ps.regionBanner.visibility = View.GONE
            return
        }

        ps.regionBanner.visibility = View.VISIBLE
        if (!online) {
            ps.regionBannerTitle.text = if (suggested != null) {
                "Sem internet · ${suggested.name}"
            } else {
                "Sem internet e sem pacote offline nesta área"
            }
            ps.regionBannerSubtitle.text = "Conecte-se para ver o mapa online ou baixar o pacote"
            ps.regionBannerAction.text = "Pacotes"
            return
        }

        when {
            suggested != null && suggested.available -> {
                val mb = if (suggested.sizeBytes > 0L) {
                    String.format(Locale.forLanguageTag("pt-BR"), " (%.0f MB)", suggested.sizeMb)
                } else {
                    ""
                }
                ps.regionBannerTitle.text = "Baixar mapa de ${suggested.name}$mb"
                ps.regionBannerSubtitle.text = if (suggested.isLargeWifiRecommended) {
                    "Recomendado usar Wi-Fi · libera busca e rotas na região"
                } else {
                    "Libera busca de ruas e navegação na região"
                }
                ps.regionBannerAction.text = "Baixar"
            }
            suggested != null -> {
                ps.regionBannerTitle.text = suggested.name
                ps.regionBannerSubtitle.text = "Pacote de rotas desta região em breve"
                ps.regionBannerAction.text = "Pacotes"
            }
            else -> {
                ps.regionBannerTitle.text = "Pacotes de mapa"
                ps.regionBannerSubtitle.text = "Baixe sua região para buscar ruas e traçar rotas"
                ps.regionBannerAction.text = "Pacotes"
            }
        }
    }

    private fun atualizarBadgeModoOnline(online: Boolean) {
        val pill = searchViews?.packStatusPill ?: return
        if (packDownloadJob?.isActive == true) return
        if (online) {
            pill.text = "Mapas"
            pill.setBackgroundResource(R.drawable.bg_badge_status_blue)
            pill.setTextColor(cor(R.color.drifly_blue))
        } else {
            pill.text = "Sem rede"
            pill.setBackgroundResource(R.drawable.bg_light_pill_red)
            pill.setTextColor(cor(R.color.drifly_red))
        }
    }

    private fun iniciarDownloadRegionPack(pack: RegionPack) {
        if (packDownloadJob?.isActive == true) {
            avisar("Já existe um download em andamento.", erro = true)
            return
        }
        packSheet?.errorText?.visibility = View.GONE
        packDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = packManager.downloadRegionPack(pack)
            if (!isAdded) return@launch
            when (result) {
                is PackState.Ready -> {
                    preencherListasPackSheet()
                    // Se o pacote baixado cobre a visão atual, ativa imediatamente.
                    reavaliarModoHibrido(forceImmediate = true)
                    if (activePackInfo?.packId != result.info.packId) {
                        ativarPacoteOffline(result.info)
                        centralizarNoPacote(result.info)
                    }
                    fecharPackSheet()
                }
                is PackState.Error -> {
                    avisar(result.message, erro = true)
                    preencherListasPackSheet()
                }
                is PackState.Cancelled -> {
                    preencherListasPackSheet()
                }
                else -> Unit
            }
        }
    }

    private fun centralizarNoPacote(info: PackInfo) {
        val bbox = info.bbox ?: catalogSnapshot.findById(info.packId)?.bbox ?: return
        val centerLat = (bbox.south + bbox.north) / 2.0
        val centerLng = (bbox.west + bbox.east) / 2.0
        val zoom = if (info.kind == "city") JOINVILLE_DEFAULT_ZOOM else 9.5
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLng), zoom),
            500
        )
    }

    private fun confirmarRemoverPacote(info: PackInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remover ${info.displayName}")
            .setMessage("Apagar o pacote offline (${String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB", info.totalSizeBytes / (1024.0 * 1024.0))}) do aparelho?")
            .setPositiveButton("Remover") { _, _ ->
                if (activePackInfo?.packId == info.packId) {
                    tileServer?.close()
                    tileServer = null
                    geocoder?.close()
                    geocoder = null
                    routeEngine?.close()
                    activePackInfo = null
                }
                packManager.deletePack(info.packId)
                hybridController.reset()
                preencherListasPackSheet()
                reavaliarModoHibrido(forceImmediate = true)
                avisar("Pacote ${info.displayName} removido.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun preencherListasPackSheet() {
        val ps = packSheet ?: return
        val ctx = context ?: return

        val freeGb = packManager.availableStorageBytes() / (1024.0 * 1024.0 * 1024.0)
        ps.freeSpace.text = String.format(Locale.forLanguageTag("pt-BR"), "Espaço livre no aparelho: %.1f GB", freeGb)

        val installed = packManager.listInstalledPacks()
        val installedIds = installed.map { it.packId }.toSet()

        ps.installedList.removeAllViews()
        ps.installedEmpty.visibility = if (installed.isEmpty()) View.VISIBLE else View.GONE
        for (info in installed) {
            ps.installedList.addView(criarCardPacoteInstalado(ctx, info))
        }

        ps.availableList.removeAllViews()
        val suggestedId = currentSuggestedPack?.id
        val sortedCatalog = catalogSnapshot.packs
            .filter { it.id !in installedIds }
            .sortedWith(
                compareByDescending<RegionPack> { it.id == suggestedId }
                    .thenByDescending { it.available }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )

        for (pack in sortedCatalog) {
            ps.availableList.addView(criarCardPacoteDisponivel(ctx, pack, pack.id == suggestedId))
        }
    }

    private fun criarCardPacoteInstalado(ctx: Context, info: PackInfo): View {
        val mb = info.totalSizeBytes / (1024.0 * 1024.0)
        val isActive = activePackInfo?.packId == info.packId
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fundoArredondado(cor(R.color.drifly_field), dp(12f).toFloat(), cor(R.color.drifly_line), dp(1f))
            setPadding(dp(12f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = if (isActive) "${info.displayName} (ativo)" else info.displayName
            textSize = 13.5f
            typeface = fonte(Typeface.BOLD)
            setTextColor(cor(R.color.drifly_ink))
        })
        texts.addView(TextView(ctx).apply {
            val tag = if (info.sideloaded) "local" else "v${info.version}"
            text = String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB · %s · 100%% offline", mb, tag)
            textSize = 11.5f
            setTextColor(cor(R.color.drifly_green))
        })
        row.addView(texts)

        row.addView(TextView(ctx).apply {
            text = "Ir"
            textSize = 12f
            typeface = fonte(Typeface.BOLD)
            setTextColor(cor(R.color.drifly_blue))
            setBackgroundResource(R.drawable.bg_light_btn_secondary)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            setOnClickListener {
                fecharPackSheet()
                centralizarNoPacote(info)
                if (activePackInfo?.packId != info.packId) {
                    ativarPacoteOffline(info)
                }
            }
        })

        row.addView(TextView(ctx).apply {
            text = "Remover"
            textSize = 11.5f
            typeface = fonte(Typeface.BOLD)
            setTextColor(cor(R.color.drifly_red))
            setBackgroundResource(R.drawable.bg_light_pill_red)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6f) }
            setOnClickListener { confirmarRemoverPacote(info) }
        })
        return row
    }

    private fun criarCardPacoteDisponivel(ctx: Context, pack: RegionPack, highlighted: Boolean): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fundoArredondado(
                if (highlighted) cor(R.color.drifly_orange_tint) else cor(R.color.drifly_field),
                dp(12f).toFloat(),
                if (highlighted) cor(R.color.drifly_orange) else cor(R.color.drifly_line),
                dp(1f)
            )
            setPadding(dp(12f), dp(10f), dp(10f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = if (highlighted) "${pack.name} · Sua região" else pack.name
            textSize = 13.5f
            typeface = fonte(Typeface.BOLD)
            setTextColor(cor(R.color.drifly_ink))
        })
        texts.addView(TextView(ctx).apply {
            text = when {
                !pack.available -> "Mapa online disponível · pacote offline em breve"
                pack.isLargeWifiRecommended ->
                    String.format(Locale.forLanguageTag("pt-BR"), "%.0f MB · Wi-Fi recomendado", pack.sizeMb)
                pack.sizeBytes > 0L ->
                    String.format(Locale.forLanguageTag("pt-BR"), "%.1f MB · ruas, busca e rotas offline", pack.sizeMb)
                else -> "Ruas, busca e rotas 100% offline"
            }
            textSize = 11.5f
            setTextColor(cor(R.color.drifly_mut))
        })
        row.addView(texts)

        val actionBtn = TextView(ctx).apply {
            text = if (pack.available) "Baixar" else "Em breve"
            textSize = 12f
            typeface = fonte(Typeface.BOLD)
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            if (pack.available) {
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_btn_primary_gradient)
                setOnClickListener { iniciarDownloadRegionPack(pack) }
            } else {
                setTextColor(cor(R.color.drifly_mut))
                setBackgroundResource(R.drawable.bg_map_chip)
                isEnabled = false
            }
        }
        row.addView(actionBtn)
        return row
    }

    private fun atualizarBadgePacote(state: PackState) {
        val pill = searchViews?.packStatusPill ?: return
        when (state) {
            is PackState.Ready -> {
                pill.text = "Mapas ✓"
                pill.setBackgroundResource(R.drawable.bg_light_pill_green)
                pill.setTextColor(cor(R.color.drifly_green))
            }
            is PackState.Downloading -> {
                pill.text = "${state.percent}%"
                pill.setBackgroundResource(R.drawable.bg_light_pill_orange)
                pill.setTextColor(cor(R.color.drifly_orange_deep))
            }
            is PackState.Verifying, is PackState.Extracting -> {
                pill.text = "Instalando..."
                pill.setBackgroundResource(R.drawable.bg_light_pill_orange)
                pill.setTextColor(cor(R.color.drifly_orange_deep))
            }
            else -> {
                atualizarBadgeModoOnline(isDeviceOnline())
            }
        }
    }

    private fun atualizarPackSheetUi(state: PackState) {
        val ps = packSheet ?: return
        when (state) {
            is PackState.Downloading -> {
                val mbDone = state.bytesDone / (1024.0 * 1024.0)
                val mbTotal = if (state.bytesTotal > 0) state.bytesTotal / (1024.0 * 1024.0) else 0.0
                ps.progressBox.visibility = View.VISIBLE
                ps.errorText.visibility = View.GONE
                ps.progress.isIndeterminate = false
                ps.progress.progress = state.percent
                ps.progressLabel.text = if (mbTotal > 0) {
                    String.format(
                        Locale.forLanguageTag("pt-BR"),
                        "Baixando %s: %.1f / %.1f MB (%d%%)...",
                        state.packId.uppercase(Locale.ROOT),
                        mbDone,
                        mbTotal,
                        state.percent
                    )
                } else {
                    String.format(Locale.forLanguageTag("pt-BR"), "Baixando %.1f MB...", mbDone)
                }
            }
            is PackState.Verifying -> {
                ps.progressBox.visibility = View.VISIBLE
                ps.errorText.visibility = View.GONE
                ps.progress.isIndeterminate = true
                ps.progressLabel.text = "Verificando integridade SHA-256..."
            }
            is PackState.Extracting -> {
                ps.progressBox.visibility = View.VISIBLE
                ps.errorText.visibility = View.GONE
                ps.progress.isIndeterminate = false
                ps.progress.progress = state.percent
                ps.progressLabel.text = "Extraindo pacote offline (${state.percent}%)..."
            }
            is PackState.Error -> {
                ps.progressBox.visibility = View.GONE
                ps.errorText.text = state.message
                ps.errorText.visibility = View.VISIBLE
            }
            is PackState.Ready, is PackState.Cancelled, is PackState.NotInstalled -> {
                ps.progressBox.visibility = View.GONE
                ps.errorText.visibility = View.GONE
                if (ps.scrim.visibility == View.VISIBLE) {
                    preencherListasPackSheet()
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // GPS & Câmera
    // ----------------------------------------------------------------------

    private fun inscreverGps() {
        val ctx = context ?: return
        if (!GpsLocationStream.hasAnyLocationPermission(ctx)) return
        val navAtiva = navController?.state?.value is NavigationUiState.Navigating
        val intervalo = if (navAtiva) INTERVALO_GPS_NAV_MS else INTERVALO_GPS_MS
        GpsLocationStream.subscribe(ctx, consumidorGps, intervalo, 0f)
    }

    private fun aplicarLocalizacao(localizacao: Location) {
        val pt = GeoPoint(localizacao.latitude, localizacao.longitude)
        posicaoAtual = pt
        if (localizacao.hasBearing() && localizacao.speed > 1.2f) {
            ultimoBearingGps = localizacao.bearing
        }

        val ctrl = navController
        val isNavigating = ctrl?.state?.value is NavigationUiState.Navigating
        if (isNavigating) {
            ctrl?.onLocationUpdate(
                NavLocationFix(
                    point = pt,
                    speedMps = if (localizacao.hasSpeed()) localizacao.speed else 0f,
                    bearingDeg = ultimoBearingGps,
                    accuracyMeters = localizacao.accuracy,
                    timestampMs = System.currentTimeMillis(),
                    isSimulated = false
                )
            )
            return
        }

        layersController?.updateDriverPuck(pt, ultimoBearingGps, isNavigating = false)
        if (!centrouNoUsuario) {
            centrouNoUsuario = true
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(pt.lat, pt.lng), JOINVILLE_DEFAULT_ZOOM),
                450
            )
            reavaliarModoHibrido(forceImmediate = true)
        }
    }

    private fun irParaMinhaPosicao() {
        val map = mapLibreMap ?: return
        val posicao = posicaoAtual
        if (posicao == null) {
            if (GpsLocationStream.hasAnyLocationPermission(requireContext())) {
                avisar("Ainda não recebi sua posição. Centralizando em Joinville.", erro = false)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(JOINVILLE_CENTER_LAT, JOINVILLE_CENTER_LNG), JOINVILLE_DEFAULT_ZOOM),
                    400
                )
            } else {
                avisar("Ative a localização para centralizar em você.", erro = true)
            }
            return
        }
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(posicao.lat, posicao.lng), JOINVILLE_DEFAULT_ZOOM),
            400
        )
        avisar("Mapa centralizado em você")
    }

    @SuppressLint("MissingPermission")
    private fun atualizarPosicaoConhecida() {
        if (!isAdded) return
        if (!GpsLocationStream.hasAnyLocationPermission(requireContext())) return
        val gerenciador = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return
        val provedor = when {
            gerenciador.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            gerenciador.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        val localizacao = try {
            gerenciador.getLastKnownLocation(provedor)
        } catch (_: Exception) {
            null
        }
        if (localizacao != null && isAdded) aplicarLocalizacao(localizacao)
    }

    private fun atualizarPermissaoLocalizacao() {
        val contexto = context ?: return
        val temPermissao = GpsLocationStream.hasAnyLocationPermission(contexto)
        faixaLocalizacao?.visibility = if (temPermissao) View.GONE else View.VISIBLE
        if (temPermissao) {
            atualizarPosicaoConhecida()
        } else {
            posicaoAtual = null
        }
    }

    @Suppress("DEPRECATION")
    private fun pedirPermissaoLocalizacao() {
        requestPermissions(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            PEDIDO_LOCALIZACAO
        )
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PEDIDO_LOCALIZACAO || !isAdded) return
        if (GpsLocationStream.hasAnyLocationPermission(requireContext())) {
            atualizarPermissaoLocalizacao()
            inscreverGps()
        } else {
            avisar("Sem a localização o mapa usa Joinville como referência.", erro = true)
        }
    }

    private fun retomarMapView() {
        val mv = mapView ?: return
        if (!isMapViewResumed) {
            mv.onResume()
            isMapViewResumed = true
        }
    }

    private fun pausarMapView() {
        val mv = mapView ?: return
        if (isMapViewResumed) {
            mv.onPause()
            isMapViewResumed = false
        }
    }

    // ----------------------------------------------------------------------
    // Sinais Colaborativos (100% preservados)
    // ----------------------------------------------------------------------

    private fun atualizarAreaVisivel() {
        val map = mapLibreMap ?: return
        val bounds = map.projection.visibleRegion.latLngBounds
        repositorio.atualizarAreaVisivel(
            AreaMapa(
                norte = bounds.latitudeNorth,
                sul = bounds.latitudeSouth,
                leste = bounds.longitudeEast,
                oeste = bounds.longitudeWest
            )
        )
    }

    private fun atualizarAvisoVazio(quantidade: Int) {
        val aviso = view?.findViewById<View>(R.id.mapEmptyHint) ?: return
        if (!repositorio.online || quantidade > 0 || navController?.state?.value !is NavigationUiState.Idle) {
            aviso.visibility = View.GONE
            return
        }
        if (aviso.tag == true) return
        aviso.tag = true
        aviso.postDelayed({
            aviso.tag = null
            if (isAdded && repositorio.online && repositorio.listar().isEmpty()) {
                aviso.visibility = View.VISIBLE
            }
        }, 1500L)
    }

    private fun renderizarSinais() {
        val sinais = repositorio.listar()
            .filter { !it.expirou }
            .filter { filtroTipo == null || it.tipo.id == filtroTipo }
        layersController?.updateSignals(sinais, selecionadoId)
    }

    private fun montarChips() {
        val container = chipsContainer ?: return
        container.removeAllViews()
        container.addView(criarChip("Todos", null, filtroTipo == null))
        SignalTypes.TODOS.forEach { tipo ->
            container.addView(criarChip(tipo.rotuloChip, tipo, filtroTipo == tipo.id))
        }
    }

    private fun criarChip(texto: String, tipo: SignalType?, ativo: Boolean): View {
        val chip = TextView(requireContext())
        chip.text = texto
        chip.textSize = 12f
        chip.typeface = fonte(Typeface.BOLD)
        chip.maxLines = 1
        chip.isSingleLine = true
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setTextColor(if (ativo) cor(R.color.drifly_card) else cor(R.color.drifly_mut))
        chip.setBackgroundResource(if (ativo) R.drawable.bg_map_chip_on else R.drawable.bg_map_chip)
        chip.setPadding(dp(11f), dp(7f), dp(11f), dp(7f))
        if (!ativo) chip.elevation = dp(2f).toFloat()

        if (tipo != null) {
            val ponto = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(tipo.cor)
                setSize(dp(9f), dp(9f))
            }
            chip.setCompoundDrawables(ponto, null, null, null)
            chip.compoundDrawablePadding = dp(6f)
        }

        chip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6f) }

        chip.setOnClickListener { alternarFiltro(tipo?.id) }
        return chip
    }

    private fun alternarFiltro(tipoId: String?) {
        filtroTipo = when {
            tipoId == null -> null
            filtroTipo == tipoId -> null
            else -> tipoId
        }
        fecharCard()
        montarChips()
        renderizarSinais()
    }

    private fun configurarLegenda(raiz: View) {
        val cinza = cor(R.color.drifly_mut)
        val vermelho = cor(R.color.signal_perigo)
        raiz.findViewById<View>(R.id.legendDotOk)?.background = circulo(cor(R.color.drifly_green))
        raiz.findViewById<View>(R.id.legendDotPending)?.background =
            circulo(cor(R.color.drifly_card), cinza, dp(2f), tracejado = true)
        raiz.findViewById<View>(R.id.legendDotArea)?.background =
            circulo(comAlpha(vermelho, 77), vermelho, dp(2f))
        raiz.findViewById<View>(R.id.legendDotEvent)?.background = circulo(cor(R.color.drifly_orange))
        raiz.findViewById<View>(R.id.legendDotMe)?.background = circulo(cor(R.color.drifly_blue))
    }

    // ----------------------------------------------------------------------
    // Card do pino (com botão "Navegar no DriFly (Offline)")
    // ----------------------------------------------------------------------

    private fun configurarCartao() {
        val c = cartao ?: return
        c.fechar.setOnClickListener { fecharCard() }

        c.dica.background = fundoArredondado(
            Color.parseColor("#FFF8E1"),
            dp(12f).toFloat(),
            Color.parseColor("#F3DC9C"),
            dp(1f)
        )
        c.trilha.background = fundoArredondado(Color.parseColor("#EAEDF2"), dp(3f).toFloat())
        c.preenchimento.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(cor(R.color.drifly_orange_2), Color.parseColor("#FF7302"))
        ).apply { cornerRadius = dp(3f).toFloat() }

        estilizarBotaoVoto(c.votoSim, sim = true)
        estilizarBotaoVoto(c.votoNao, sim = false)

        c.votoSim.setOnClickListener { votar(sim = true) }
        c.votoNao.setOnClickListener { votar(sim = false) }

        c.navegarDrifly.setOnClickListener {
            val sinal = sinalAberto ?: return@setOnClickListener
            val nome = if (sinal.tipo.evento) "Paga+ · ${sinal.tipoEvento ?: "Evento"}" else sinal.tipo.nome
            addingExtraStop = false
            selecionarDestino(RouteWaypoint(GeoPoint(sinal.lat, sinal.lng), nome))
        }
        c.maps.setOnClickListener { abrirNavegacaoExterna(waze = false) }
        c.waze.setOnClickListener { abrirNavegacaoExterna(waze = true) }

        c.denunciar.paintFlags = c.denunciar.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        c.denunciar.setOnClickListener { denunciar() }

        c.remover.paintFlags = c.remover.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        c.remover.setOnClickListener { confirmarRemocao() }
    }

    /** Abre o card de um sinal específico (usado pelo balão da bolinha). */
    fun abrirSinal(id: String, tentativa: Int = 0) {
        if (id.isBlank()) return
        val sinal = repositorio.listar().firstOrNull { it.id == id }
        if (sinal == null) {
            if (tentativa >= TENTATIVAS_ABRIR_SINAL) return
            view?.postDelayed({ abrirSinal(id, tentativa + 1) }, 700L)
            return
        }
        mapLibreMap?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(sinal.lat, sinal.lng), 16.5),
            450
        )
        abrirCard(sinal.id)
    }

    private fun abrirCard(id: String) {
        val sinal = repositorio.listar().firstOrNull { it.id == id } ?: return
        val c = cartao ?: return

        sinalAberto = sinal
        selecionadoId = id

        val precisaAnimar = c.root.visibility != View.VISIBLE
        c.root.visibility = View.VISIBLE
        preencherCard(sinal, c)

        if (precisaAnimar) {
            c.root.post {
                c.root.translationY = c.root.height.toFloat()
                c.root.animate()
                    .translationY(0f)
                    .setDuration(260L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        legenda?.visibility = View.GONE
        fabCentralizar?.visibility = View.GONE
        fabSinalizar?.visibility = View.GONE
        renderizarSinais()
    }

    private fun fecharCard() {
        val c = cartao ?: return
        sinalAberto = null
        selecionadoId = null
        if (navController?.state?.value is NavigationUiState.Idle) {
            legenda?.visibility = View.VISIBLE
            fabCentralizar?.visibility = View.VISIBLE
            fabSinalizar?.visibility = View.VISIBLE
        }
        renderizarSinais()

        if (c.root.visibility != View.VISIBLE) return
        c.root.animate()
            .translationY(c.root.height.toFloat())
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { c.root.visibility = View.GONE }
            .start()
    }

    private fun atualizarCardAberto() {
        val c = cartao ?: return
        val aberto = sinalAberto ?: return
        val atual = repositorio.listar().firstOrNull { it.id == aberto.id }
        if (atual == null) {
            fecharCard()
            return
        }
        sinalAberto = atual
        if (c.root.visibility == View.VISIBLE) preencherCard(atual, c)
    }

    private fun preencherCard(sinal: Signal, c: Cartao) {
        val tipo = sinal.tipo
        val pendente = sinal.status == SignalStatus.PENDENTE

        c.iconeBox.backgroundTintList = ColorStateList.valueOf(tipo.cor)
        c.icone.setImageResource(tipo.icone)
        c.titulo.text = if (tipo.evento) "Paga+ · ${sinal.tipoEvento ?: "Evento"}" else tipo.nome

        when {
            tipo.evento && pendente -> {
                c.subtitulo.text = "${sinal.simQtd} de ${Signal.CONFIRMACOES} confirmações · ${sinal.quando}"
                aplicarChip(c.chip, R.color.drifly_amber_tint, R.color.drifly_amber, "A confirmar")
            }
            tipo.evento && sinal.terminando -> {
                c.subtitulo.text = "Alta demanda · termina em ~${formatarMinutos(sinal.minutosRestantes)}"
                aplicarChip(c.chip, R.color.drifly_amber_tint, R.color.drifly_amber, "Terminando")
            }
            tipo.evento -> {
                c.subtitulo.text = "Alta demanda · termina em ~${formatarMinutos(sinal.minutosRestantes)}"
                aplicarChip(c.chip, R.color.drifly_orange_tint, R.color.drifly_orange_deep, "Alta demanda")
            }
            tipo.dica -> {
                c.subtitulo.text = "👍🏻 ${sinal.simQtd} · 👎🏻 ${sinal.naoQtd} · ${sinal.quando}"
                if (sinal.totalVotos < Signal.CONFIRMACOES) {
                    aplicarChip(c.chip, R.color.drifly_amber_tint, R.color.drifly_amber, "Em avaliação")
                } else {
                    aplicarChip(c.chip, R.color.drifly_green_tint, R.color.drifly_green, "Mantida")
                }
            }
            pendente -> {
                c.subtitulo.text = "${sinal.simQtd} de ${Signal.CONFIRMACOES} confirmações · ${sinal.quando}"
                aplicarChip(c.chip, R.color.drifly_amber_tint, R.color.drifly_amber, "A confirmar")
            }
            else -> {
                c.subtitulo.text = "Confirmado por ${sinal.simQtd} motoristas · ${sinal.quando}"
                aplicarChip(c.chip, R.color.drifly_green_tint, R.color.drifly_green, "Confirmado")
            }
        }

        val textoDica = sinal.texto
        if (tipo.dica && !textoDica.isNullOrBlank()) {
            c.dica.text = "“$textoDica”"
            c.dica.visibility = View.VISIBLE
        } else {
            c.dica.visibility = View.GONE
        }

        c.pergunta.text = when {
            tipo.dica -> "Essa dica é verdadeira?"
            tipo.evento -> "Tá com muita demanda?"
            pendente -> "Tem isso aqui?"
            else -> tipo.pergunta
        }
        c.votoSim.text = if (tipo.dica) "👍🏻 Verdade" else "Sim"
        c.votoNao.text = if (tipo.dica) "👎🏻 Mentira" else "Não"

        val distancia = distanciaM(sinal)
        val perto = distancia <= Signal.DISTANCIA_VOTO_M
        val jaVotou = sinal.jaVotei || votados.contains(sinal.id)
        val podeVotar = !jaVotou && perto
        c.votos.visibility = if (podeVotar) View.VISIBLE else View.GONE
        c.trava.visibility = if (podeVotar) View.GONE else View.VISIBLE
        c.travaIcone.visibility = if (jaVotou) View.GONE else View.VISIBLE
        c.travaTexto.text = when {
            jaVotou -> "✓ Você já votou neste sinal"
            else -> "Chegue a menos de 150 m para votar (${distancia.roundToInt()} m)"
        }

        val pct = sinal.percentualSim
        c.pct.text = "$pct%"
        c.trilha.post {
            val largura = c.trilha.width
            val params = c.preenchimento.layoutParams
            params.width = (largura * pct / 100.0).roundToInt()
            c.preenchimento.layoutParams = params
        }

        val podeRemover = podeRemoverSinal(sinal)
        c.remover.visibility = if (podeRemover) View.VISIBLE else View.GONE
        c.denunciar.visibility = if (podeRemover) View.GONE else View.VISIBLE
    }

    private fun aplicarChip(chip: TextView, fundoTint: Int, textoCor: Int, texto: String) {
        chip.text = texto
        chip.background = fundoArredondado(cor(fundoTint), dp(99f).toFloat())
        chip.setTextColor(cor(textoCor))
        chip.textSize = 11.5f
        chip.typeface = fonte(Typeface.BOLD)
        chip.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
    }

    private fun estilizarBotaoVoto(botao: TextView, sim: Boolean) {
        val borda = if (sim) Color.parseColor("#B7E3CD") else Color.parseColor("#F5C2C8")
        botao.background = fundoArredondado(
            cor(if (sim) R.color.drifly_green_tint else R.color.drifly_red_tint),
            dp(99f).toFloat(),
            borda,
            dp(1.5f)
        )
        botao.setTextColor(cor(if (sim) R.color.drifly_green else R.color.drifly_red))
        botao.textSize = 12.5f
        botao.typeface = fonte(Typeface.BOLD)
        botao.setPadding(dp(16f), dp(7f), dp(16f), dp(7f))
    }

    private fun votar(sim: Boolean) {
        val sinal = sinalAberto ?: return
        if (votoEmAndamento) return
        votoEmAndamento = true

        repositorio.votar(sinal.id, sim, distanciaM(sinal)) concluido@{ resultado ->
            votoEmAndamento = false
            if (!isAdded) return@concluido
            if (resultado.aceito) votados.add(sinal.id)
            avisar(resultado.mensagem, erro = !resultado.aceito)
            atualizarCardAberto()
        }
    }

    private fun denunciar() {
        val sinal = sinalAberto ?: return
        val opcoes = arrayOf("Não existe mais / não vi", "Conteúdo impróprio")
        AlertDialog.Builder(requireContext())
            .setTitle("Denunciar sinal")
            .setItems(opcoes) { _, escolha ->
                val motivo = if (escolha == 1) MOTIVO_OFENSIVO else MOTIVO_NAO_EXISTE
                enviarDenuncia(sinal.id, motivo)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarDenuncia(id: String, motivo: String) {
        repositorio.denunciar(id, motivo) entao@{ ok, mensagem ->
            if (!isAdded) return@entao
            avisar(mensagem, erro = !ok)
            atualizarCardAberto()
        }
    }

    private fun confirmarRemocao() {
        val sinal = sinalAberto ?: return
        if (remocaoEmAndamento || !podeRemoverSinal(sinal)) return
        AlertDialog.Builder(requireContext())
            .setTitle("Remover sinal")
            .setMessage("Remover este sinal do mapa?")
            .setPositiveButton("Remover") { _, _ -> removerSinal(sinal.id) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun removerSinal(id: String) {
        if (remocaoEmAndamento) return
        remocaoEmAndamento = true
        repositorio.remover(id) removido@{ ok, mensagem ->
            remocaoEmAndamento = false
            if (!isAdded) return@removido
            avisar(mensagem, erro = !ok)
            if (ok) {
                fecharCard()
                renderizarSinais()
            } else {
                atualizarCardAberto()
            }
        }
    }

    private fun abrirNavegacaoExterna(waze: Boolean) {
        val sinal = sinalAberto ?: return
        val lat = String.format(Locale.US, "%.5f", sinal.lat)
        val lng = String.format(Locale.US, "%.5f", sinal.lng)
        val url = if (waze) {
            "https://waze.com/ul?ll=$lat,$lng&navigate=yes"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=$lat,$lng"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (waze) intent.setPackage("com.waze")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                avisar("Nenhum app de mapas externo disponível.", erro = true)
            }
        }
    }

    // ----------------------------------------------------------------------
    // Painel "Sinalizar no mapa"
    // ----------------------------------------------------------------------

    private fun configurarEditor() {
        val e = editor ?: return
        e.scrim.setOnClickListener { fecharEditor() }
        e.fechar.setOnClickListener { fecharEditor() }
        e.abaPlace.setOnClickListener { definirAba(ABA_LUGAR) }
        e.abaTip.setOnClickListener { definirAba(ABA_DICA) }
        e.abaEvento.setOnClickListener { definirAba(ABA_EVENTO) }
        e.sendPlace.setOnClickListener { enviarLugar() }
        e.sendTip.setOnClickListener { enviarDica() }
        e.sendEvent.setOnClickListener { enviarEvento() }

        e.tipText.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(ModerationFilter.MAX_LEN))
        e.tipText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val tamanho = s?.length ?: 0
                e.tipCount.text = "$tamanho/${ModerationFilter.MAX_LEN}"
                e.tipError.visibility = View.GONE
            }
        })

        montarGradeLugares()
        montarChipsEvento()
        definirAba(ABA_LUGAR)
    }

    private fun definirAba(aba: Int) {
        abaEditor = aba
        val e = editor ?: return
        e.placePanel.visibility = if (aba == ABA_LUGAR) View.VISIBLE else View.GONE
        e.tipPanel.visibility = if (aba == ABA_DICA) View.VISIBLE else View.GONE
        e.eventPanel.visibility = if (aba == ABA_EVENTO) View.VISIBLE else View.GONE
        estilizarAba(e.abaPlace, aba == ABA_LUGAR)
        estilizarAba(e.abaTip, aba == ABA_DICA)
        estilizarAba(e.abaEvento, aba == ABA_EVENTO)
    }

    private fun estilizarAba(aba: TextView, ativo: Boolean) {
        aba.setBackgroundResource(if (ativo) R.drawable.bg_map_chip_on else R.drawable.bg_map_chip)
        aba.setTextColor(if (ativo) cor(R.color.drifly_card) else cor(R.color.drifly_mut))
    }

    private fun montarGradeLugares() {
        val e = editor ?: return
        e.places.removeAllViews()
        val lugares = SignalTypes.LUGARES
        var indice = 0
        while (indice < lugares.size) {
            val linha = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
            }
            linha.addView(criarItemLugar(lugares[indice]))
            if (indice + 1 < lugares.size) {
                linha.addView(criarItemLugar(lugares[indice + 1]))
            } else {
                linha.addView(View(requireContext()), LinearLayout.LayoutParams(0, dp(1f), 1f))
            }
            e.places.addView(linha)
            indice += 2
        }
    }

    private fun criarItemLugar(tipo: SignalType): View {
        val contexto = requireContext()
        val selecionado = tipo.id == tipoSelecionadoId
        val item = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = fundoArredondado(
                if (selecionado) cor(R.color.drifly_card) else cor(R.color.drifly_field),
                dp(14f).toFloat(),
                if (selecionado) tipo.cor else cor(R.color.drifly_line),
                if (selecionado) dp(1.5f) else dp(1f)
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8f) }
        }
        val caixa = LinearLayout(contexto).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = fundoArredondado(tipo.cor, dp(9f).toFloat())
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
        }
        caixa.addView(ImageView(contexto).apply {
            setImageResource(tipo.icone)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        item.addView(caixa)
        item.addView(TextView(contexto).apply {
            text = tipo.rotuloEditor
            textSize = 12.5f
            setTextColor(cor(R.color.drifly_ink))
            typeface = fonte(Typeface.BOLD)
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        item.setOnClickListener {
            tipoSelecionadoId = tipo.id
            montarGradeLugares()
        }
        return item
    }

    private fun montarChipsEvento() {
        val e = editor ?: return
        e.eventRow1.removeAllViews()
        e.eventRow2.removeAllViews()
        SignalTypes.TIPOS_EVENTO.forEachIndexed { indice, nome ->
            val chip = TextView(requireContext()).apply {
                text = nome
                textSize = 12f
                typeface = fonte(Typeface.BOLD)
                maxLines = 1
                isSingleLine = true
                setPadding(dp(11f), dp(7f), dp(11f), dp(7f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6f) }
                setOnClickListener {
                    tipoEventoSelecionado = nome
                    montarChipsEvento()
                }
            }
            estilizarChipEvento(chip, nome == tipoEventoSelecionado)
            val destino = if (indice < 3) e.eventRow1 else e.eventRow2
            destino.addView(chip)
        }
    }

    private fun estilizarChipEvento(chip: TextView, ativo: Boolean) {
        chip.setBackgroundResource(if (ativo) R.drawable.bg_map_chip_on else R.drawable.bg_map_chip)
        chip.setTextColor(if (ativo) cor(R.color.drifly_card) else cor(R.color.drifly_mut))
        if (!ativo) chip.elevation = dp(1f).toFloat()
    }

    private fun abrirEditor() {
        val e = editor ?: return
        fecharCard()
        definirAba(abaEditor)
        legenda?.visibility = View.GONE
        fabCentralizar?.visibility = View.GONE
        fabSinalizar?.visibility = View.GONE
        e.scrim.visibility = View.VISIBLE
        e.scrim.alpha = 0f
        e.scrim.animate().alpha(1f).setDuration(180L).start()
        e.sheet.post {
            e.sheet.translationY = e.sheet.height.toFloat()
            e.sheet.animate()
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun fecharEditor() {
        val e = editor ?: return
        if (e.scrim.visibility != View.VISIBLE) return
        esconderTeclado()
        e.sheet.animate()
            .translationY(e.sheet.height.toFloat())
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { e.scrim.visibility = View.GONE }
            .start()
        e.scrim.animate().alpha(0f).setDuration(200L).start()
        if (navController?.state?.value is NavigationUiState.Idle) {
            fabCentralizar?.visibility = View.VISIBLE
            fabSinalizar?.visibility = View.VISIBLE
            if (sinalAberto == null) legenda?.visibility = View.VISIBLE
        }
    }

    private fun enviarLugar() {
        val tipo = SignalTypes.porId(tipoSelecionadoId) ?: return
        criarSinal(tipo, texto = null, tipoEvento = null)
    }

    private fun enviarDica() {
        val e = editor ?: return
        val texto = e.tipText.text?.toString()?.trim().orEmpty()
        val erro = ModerationFilter.check(texto)
        if (erro != null) {
            e.tipError.text = erro
            e.tipError.visibility = View.VISIBLE
            return
        }
        criarSinal(SignalTypes.DICA, texto = texto, tipoEvento = null)
        e.tipText.setText("")
    }

    private fun enviarEvento() {
        criarSinal(SignalTypes.EVENTO, texto = null, tipoEvento = tipoEventoSelecionado)
    }

    private fun criarSinal(tipo: SignalType, texto: String?, tipoEvento: String?) {
        val posicao = posicaoReferencia()
        fecharEditor()
        repositorio.criar(tipo, posicao.lat, posicao.lng, texto, tipoEvento) concluido@{ resultado ->
            if (!isAdded) return@concluido
            avisar(resultado.mensagem, erro = !resultado.ok)
        }
    }

    // ----------------------------------------------------------------------
    // Utilidades
    // ----------------------------------------------------------------------

    private fun configurarBotoes(raiz: View) {
        raiz.findViewById<View>(R.id.btnMapCenter)?.setOnClickListener { irParaMinhaPosicao() }
        raiz.findViewById<View>(R.id.btnMapAdd)?.setOnClickListener { abrirEditor() }
        raiz.findViewById<View>(R.id.mapLocationAction)?.setOnClickListener { pedirPermissaoLocalizacao() }
    }

    private fun pararRelogio() {
        relogioHandler.removeCallbacks(relogioSinais)
    }

    private fun esconderTeclado() {
        val foco = view?.findFocus() ?: return
        val teclado = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        teclado?.hideSoftInputFromWindow(foco.windowToken, 0)
        foco.clearFocus()
    }

    private fun avisar(mensagem: String, erro: Boolean = false) {
        val atividade = activity
        if (atividade is MainActivity) {
            atividade.showNotificationBanner(
                mensagem,
                if (erro) MainActivity.BannerType.WARNING else MainActivity.BannerType.SUCCESS
            )
        } else if (isAdded) {
            Toast.makeText(requireContext(), mensagem, Toast.LENGTH_LONG).show()
        }
    }

    /** Posição de referência: GPS real ou Centro de Joinville, SC. */
    private fun posicaoReferencia(): GeoPoint =
        posicaoAtual ?: GeoPoint(JOINVILLE_CENTER_LAT, JOINVILLE_CENTER_LNG)

    private fun distanciaM(sinal: Signal): Double =
        posicaoReferencia().distanceToMeters(GeoPoint(sinal.lat, sinal.lng))

    private fun formatarMinutos(minutos: Int): String =
        if (minutos >= 60) {
            String.format(Locale.US, "%dh%02d", minutos / 60, minutos % 60)
        } else {
            "$minutos min"
        }

    private fun formatarDistancia(metros: Double): String =
        if (metros >= 1000.0) {
            String.format(Locale.forLanguageTag("pt-BR"), "%.1f km", metros / 1000.0)
        } else {
            val arred = ((metros / 10.0).roundToInt() * 10).coerceAtLeast(10)
            "$arred m"
        }

    private fun dp(valor: Float): Int = (valor * resources.displayMetrics.density).roundToInt()

    private fun cor(id: Int): Int = ContextCompat.getColor(requireContext(), id)

    private fun fonte(estilo: Int): Typeface? =
        try {
            ResourcesCompat.getFont(requireContext(), R.font.plus_jakarta_sans)
                ?.let { Typeface.create(it, estilo) }
        } catch (_: Exception) {
            null
        }

    private fun fundoArredondado(
        preenchimento: Int,
        raio: Float,
        borda: Int? = null,
        larguraBorda: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(preenchimento)
        cornerRadius = raio
        if (borda != null && larguraBorda > 0) setStroke(larguraBorda, borda)
    }

    private fun circulo(
        preenchimento: Int,
        borda: Int? = null,
        larguraBorda: Int = 0,
        tracejado: Boolean = false
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(preenchimento)
        if (borda != null && larguraBorda > 0) {
            if (tracejado) {
                setStroke(larguraBorda, borda, dp(2f).toFloat(), dp(1.5f).toFloat())
            } else {
                setStroke(larguraBorda, borda)
            }
        }
    }

    private fun comAlpha(cor: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(cor), Color.green(cor), Color.blue(cor))

    private companion object {
        const val JOINVILLE_CENTER_LAT = -26.3044
        const val JOINVILLE_CENTER_LNG = -48.8487
        const val JOINVILLE_DEFAULT_ZOOM = 13.8

        const val PEDIDO_LOCALIZACAO = 4411
        const val TENTATIVAS_ABRIR_SINAL = 10
        const val INTERVALO_GPS_MS = 4_000L
        const val INTERVALO_GPS_NAV_MS = 1_000L
        const val INTERVALO_RELOGIO_MS = 30_000L
        const val ABA_LUGAR = 0
        const val ABA_DICA = 1
        const val ABA_EVENTO = 2
    }
}
