package com.drifly.condutor.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import com.drifly.condutor.Signal
import com.drifly.condutor.SignalPinFactory
import com.drifly.condutor.SignalTypes
import com.drifly.condutor.map.routing.AvoidZone
import com.drifly.condutor.map.routing.ComputedRoute
import com.drifly.condutor.map.routing.GeoPoint
import com.drifly.condutor.map.routing.RouteWaypoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Controla todas as fontes GeoJSON e camadas visuais do DriFly sobre o [Style] do MapLibre:
 *
 * Ordem de empilhamento (de baixo para cima):
 * 1. Zonas da Lista Negra evitadas (polígono vermelho translúcido);
 * 2. Áreas de sinais colaborativos (`perigo` e `evento` Paga+ em polígonos geodésicos de 36 vértices);
 * 3. Rota alternativa (cinza clicável com contorno);
 * 4. Rota ativa (contorno escuro + miolo laranja DriFly / azul navegação estilo Uber/99);
 * 5. Marcadores numerados de paradas + pino de destino;
 * 6. Pinos de sinais colaborativos (ancorados na ponta inferior);
 * 7. Seta/puck do motorista (rotacionada pelo azimute).
 */
class MapLibreLayersController(private val context: Context) {

    private var style: Style? = null
    private val registeredStopIcons = mutableSetOf<String>()

    /** Registra todas as imagens, fontes GeoJSON e camadas sobre o estilo recém-carregado. */
    fun attach(loadedStyle: Style) {
        style = loadedStyle
        registeredStopIcons.clear()

        registerBaseImages(loadedStyle)
        ensureSource(loadedStyle, SRC_BLACKLIST)
        ensureSource(loadedStyle, SRC_SIGNAL_AREAS)
        ensureSource(loadedStyle, SRC_ROUTE_ALT)
        ensureSource(loadedStyle, SRC_ROUTE_ACTIVE)
        ensureSource(loadedStyle, SRC_ROUTE_STOPS)
        ensureSource(loadedStyle, SRC_SIGNALS)
        ensureSource(loadedStyle, SRC_DRIVER)

        // 1. Zonas da Lista Negra evitadas
        addLayerIfMissing(
            loadedStyle,
            FillLayer(LYR_BLACKLIST_FILL, SRC_BLACKLIST).withProperties(
                PropertyFactory.fillColor(Color.parseColor("#D62839")),
                PropertyFactory.fillOpacity(0.14f)
            )
        )
        addLayerIfMissing(
            loadedStyle,
            LineLayer(LYR_BLACKLIST_LINE, SRC_BLACKLIST).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#D62839")),
                PropertyFactory.lineWidth(2.0f),
                PropertyFactory.lineOpacity(0.65f),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f))
            )
        )

        // 2. Áreas dos sinais colaborativos (círculos geodésicos)
        addLayerIfMissing(
            loadedStyle,
            FillLayer(LYR_SIGNAL_AREAS_FILL, SRC_SIGNAL_AREAS).withProperties(
                PropertyFactory.fillColor(Expression.get("fillColor")),
                PropertyFactory.fillOpacity(Expression.get("fillOpacity"))
            )
        )
        addLayerIfMissing(
            loadedStyle,
            LineLayer(LYR_SIGNAL_AREAS_LINE, SRC_SIGNAL_AREAS).withProperties(
                PropertyFactory.lineColor(Expression.get("strokeColor")),
                PropertyFactory.lineWidth(Expression.get("strokeWidth")),
                PropertyFactory.lineOpacity(0.75f)
            )
        )

        // 3. Rotas alternativas (cinza estilo Uber/99)
        addLayerIfMissing(
            loadedStyle,
            LineLayer(LYR_ROUTE_ALT_CASING, SRC_ROUTE_ALT).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#64748B")),
                PropertyFactory.lineWidth(9.0f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.55f)
            )
        )
        addLayerIfMissing(
            loadedStyle,
            LineLayer(LYR_ROUTE_ALT_LINE, SRC_ROUTE_ALT).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#CBD5E1")),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.9f)
            )
        )

        // 4. Rota ativa (contorno + miolo vibrante)
        addLayerIfMissing(
            loadedStyle,
            LineLayer(LYR_ROUTE_CASING, SRC_ROUTE_ACTIVE).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#B24D00")),
                PropertyFactory.lineWidth(11.0f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        addLayerIfMissing(
            loadedStyle,
            LineLayer(LYR_ROUTE_LINE, SRC_ROUTE_ACTIVE).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#FF6F00")),
                PropertyFactory.lineWidth(6.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )

        // 5. Paradas numeradas e destino
        addLayerIfMissing(
            loadedStyle,
            SymbolLayer(LYR_ROUTE_STOPS, SRC_ROUTE_STOPS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )

        // 6. Pinos colaborativos
        addLayerIfMissing(
            loadedStyle,
            SymbolLayer(LYR_SIGNALS, SRC_SIGNALS).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(Expression.get("sortKey"))
            )
        )

        // 7. Posição/seta do motorista
        addLayerIfMissing(
            loadedStyle,
            SymbolLayer(LYR_DRIVER, SRC_DRIVER).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    fun detach() {
        style = null
        registeredStopIcons.clear()
    }

    /** Retorna o [Style] somente se ainda estiver 100% carregado (evita race durante troca de estilo). */
    private fun activeStyle(): Style? = style?.takeIf { it.isFullyLoaded }

    /** Atualiza pinos e áreas de sinais colaborativos. */
    fun updateSignals(signals: List<Signal>, selectedId: String?) {
        val s = activeStyle() ?: return
        try {
            val areaFeatures = ArrayList<Feature>()
            val pinFeatures = ArrayList<Feature>(signals.size)

            for (signal in signals) {
                if (signal.tipo.area && signal.raioM > 0.0) {
                    val poly = geodesicCirclePolygon(signal.lat, signal.lng, signal.raioM, CIRCLE_SEGMENTS)
                    val f = Feature.fromGeometry(poly)
                    val hex = colorToHex(signal.tipo.cor)
                    f.addStringProperty("fillColor", hex)
                    f.addNumberProperty("fillOpacity", if (signal.pendente) 0.08 else 0.16)
                    f.addStringProperty("strokeColor", hex)
                    f.addNumberProperty("strokeWidth", if (signal.pendente) 2.0 else 3.2)
                    areaFeatures.add(f)
                }

                val iconKey = SignalPinFactory.iconKey(signal.tipo, signal.pendente)
                if (s.getImage(iconKey) == null) {
                    s.addImage(iconKey, SignalPinFactory.pinBitmap(context, signal.tipo, signal.pendente))
                }
                val pin = Feature.fromGeometry(Point.fromLngLat(signal.lng, signal.lat))
                pin.addStringProperty("signalId", signal.id)
                pin.addStringProperty("icon", iconKey)
                pin.addNumberProperty("sortKey", if (signal.id == selectedId) 10.0 else 1.0)
                pinFeatures.add(pin)
            }

            s.getSourceAs<GeoJsonSource>(SRC_SIGNAL_AREAS)?.setGeoJson(FeatureCollection.fromFeatures(areaFeatures))
            s.getSourceAs<GeoJsonSource>(SRC_SIGNALS)?.setGeoJson(FeatureCollection.fromFeatures(pinFeatures))
        } catch (_: IllegalStateException) {
            // Estilo substituído no meio do frame; reattachAllDriFlyLayers reaplicará ao concluir.
        }
    }

    /** Atualiza a rota ativa, rotas alternativas, paradas numeradas e zonas da Lista Negra. */
    fun updateRouteOverlay(
        activeRoute: ComputedRoute?,
        alternatives: List<ComputedRoute>,
        selectedIndex: Int,
        waypoints: List<RouteWaypoint>,
        avoidZones: List<AvoidZone>,
        isNavigating: Boolean
    ) {
        val s = activeStyle() ?: return
        try {
            // Muda a paleta da linha: laranja vibrante na prévia e azul de alta leitura na condução 3D
            val casingColor = if (isNavigating) Color.parseColor("#1D4ED8") else Color.parseColor("#B24D00")
            val fillColor = if (isNavigating) Color.parseColor("#2563EB") else Color.parseColor("#FF6F00")
            s.getLayerAs<LineLayer>(LYR_ROUTE_CASING)?.setProperties(PropertyFactory.lineColor(casingColor))
            s.getLayerAs<LineLayer>(LYR_ROUTE_LINE)?.setProperties(PropertyFactory.lineColor(fillColor))

            // Zonas da Lista Negra evitadas
            val blFeatures = avoidZones.mapNotNull { zone ->
                if (zone.polygon.size < 4) return@mapNotNull null
                val ring = zone.polygon.map { Point.fromLngLat(it.lng, it.lat) }
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                    addStringProperty("label", zone.label)
                }
            }
            s.getSourceAs<GeoJsonSource>(SRC_BLACKLIST)?.setGeoJson(FeatureCollection.fromFeatures(blFeatures))

            // Rota ativa
            val activeFeatures = if (activeRoute != null && activeRoute.geometry.size >= 2) {
                val pts = activeRoute.geometry.map { Point.fromLngLat(it.lng, it.lat) }
                listOf(Feature.fromGeometry(LineString.fromLngLats(pts)))
            } else {
                emptyList()
            }
            s.getSourceAs<GeoJsonSource>(SRC_ROUTE_ACTIVE)?.setGeoJson(FeatureCollection.fromFeatures(activeFeatures))

            // Rotas alternativas (escondidas durante a navegação ativa para limpar a tela)
            val altFeatures = if (!isNavigating && alternatives.isNotEmpty()) {
                alternatives.mapIndexedNotNull { idx, alt ->
                    val routeIdx = idx + 1
                    if (routeIdx == selectedIndex || alt.geometry.size < 2) return@mapIndexedNotNull null
                    val pts = alt.geometry.map { Point.fromLngLat(it.lng, it.lat) }
                    Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                        addNumberProperty("routeIndex", routeIdx)
                    }
                }
            } else {
                emptyList()
            }
            s.getSourceAs<GeoJsonSource>(SRC_ROUTE_ALT)?.setGeoJson(FeatureCollection.fromFeatures(altFeatures))

            // Paradas e destino
            val stopFeatures = waypoints.mapIndexed { idx, wp ->
                val isLast = idx == waypoints.lastIndex
                val iconId = if (isLast) IMG_DEST_PIN else ensureStopBadgeIcon(s, idx + 1)
                Feature.fromGeometry(Point.fromLngLat(wp.point.lng, wp.point.lat)).apply {
                    addStringProperty("icon", iconId)
                    addStringProperty("label", wp.label)
                }
            }
            s.getSourceAs<GeoJsonSource>(SRC_ROUTE_STOPS)?.setGeoJson(FeatureCollection.fromFeatures(stopFeatures))
        } catch (_: IllegalStateException) {
        }
    }

    /** Limpa rotas, paradas e áreas de lista negra do mapa. */
    fun clearRoutes() {
        updateRouteOverlay(null, emptyList(), 0, emptyList(), emptyList(), isNavigating = false)
    }

    /** Atualiza o puck/seta do motorista. */
    fun updateDriverPuck(point: GeoPoint?, bearingDeg: Float, isNavigating: Boolean) {
        val s = activeStyle() ?: return
        try {
            val features = if (point != null) {
                val f = Feature.fromGeometry(Point.fromLngLat(point.lng, point.lat))
                f.addStringProperty("icon", if (isNavigating) IMG_DRIVER_CHEVRON else IMG_DRIVER_DOT)
                f.addNumberProperty("bearing", if (isNavigating) bearingDeg.toDouble() else 0.0)
                listOf(f)
            } else {
                emptyList()
            }
            s.getSourceAs<GeoJsonSource>(SRC_DRIVER)?.setGeoJson(FeatureCollection.fromFeatures(features))
        } catch (_: IllegalStateException) {
        }
    }

    /** Retorna o ID do sinal colaborativo sob o toque na tela, ou `null`. */
    fun hitTestSignalId(map: MapLibreMap, screenPoint: PointF): String? {
        val features = map.queryRenderedFeatures(screenPoint, LYR_SIGNALS)
        return features.firstOrNull()?.getStringProperty("signalId")
    }

    /** Retorna o índice da rota alternativa sob o toque (1..N), ou `null`. */
    fun hitTestAlternativeIndex(map: MapLibreMap, screenPoint: PointF): Int? {
        val features = map.queryRenderedFeatures(screenPoint, LYR_ROUTE_ALT_LINE, LYR_ROUTE_ALT_CASING)
        val num = features.firstOrNull()?.getNumberProperty("routeIndex") ?: return null
        return num.toInt()
    }

    private fun registerBaseImages(s: Style) {
        // Pré-registra ícones essenciais (puck, chevron, destino) e os tipos de sinal.
        s.addImage(IMG_DRIVER_DOT, createDriverDotBitmap())
        s.addImage(IMG_DRIVER_CHEVRON, createDriverChevronBitmap())
        s.addImage(IMG_DEST_PIN, createDestinationPinBitmap())
        SignalTypes.TODOS.forEach { tipo ->
            s.addImage(SignalPinFactory.iconKey(tipo, false), SignalPinFactory.pinBitmap(context, tipo, false))
            s.addImage(SignalPinFactory.iconKey(tipo, true), SignalPinFactory.pinBitmap(context, tipo, true))
        }
    }

    private fun ensureStopBadgeIcon(s: Style, number: Int): String {
        val id = "drifly_stop_$number"
        if (registeredStopIcons.add(id)) {
            s.addImage(id, createStopBadgeBitmap(number))
        }
        return id
    }

    private fun ensureSource(s: Style, id: String) {
        if (s.getSource(id) == null) {
            s.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList())))
        }
    }

    private fun addLayerIfMissing(s: Style, layer: org.maplibre.android.style.layers.Layer) {
        if (s.getLayer(layer.id) == null) {
            s.addLayer(layer)
        }
    }

    private fun createDriverDotBitmap(): Bitmap {
        val d = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val size = (26f * d).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Halo azul suave
        p.color = Color.argb(65, 37, 99, 235)
        c.drawCircle(cx, cx, 11.5f * d, p)
        // Borda branca
        p.color = Color.WHITE
        c.drawCircle(cx, cx, 7.5f * d, p)
        // Centro azul DriFly
        p.color = Color.parseColor("#2563EB")
        c.drawCircle(cx, cx, 5.2f * d, p)
        return bmp
    }

    private fun createDriverChevronBitmap(): Bitmap {
        val d = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val size = (42f * d).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Disco branco com sombra leve
        p.color = Color.argb(55, 15, 23, 42)
        c.drawCircle(cx, cy + 1.5f * d, 16f * d, p)
        p.color = Color.WHITE
        c.drawCircle(cx, cy, 15.5f * d, p)

        // Seta de navegação apontando para o Norte (rotacionada pelo bearing)
        val arrow = Path().apply {
            moveTo(cx, cy - 10.5f * d)
            lineTo(cx + 8.5f * d, cy + 8.5f * d)
            lineTo(cx, cy + 4.2f * d)
            lineTo(cx - 8.5f * d, cy + 8.5f * d)
            close()
        }
        p.color = Color.parseColor("#FF6F00")
        c.drawPath(arrow, p)
        return bmp
    }

    private fun createDestinationPinBitmap(): Bitmap {
        val d = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val w = (34f * d).roundToInt()
        val h = (44f * d).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val r = 13f * d
        val cy = r + 2f * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val tail = Path().apply {
            moveTo(cx - 6f * d, cy + r - 2f * d)
            lineTo(cx + 6f * d, cy + r - 2f * d)
            lineTo(cx, h - 2f * d)
            close()
        }
        p.color = Color.parseColor("#162033")
        c.drawPath(tail, p)
        c.drawCircle(cx, cy, r, p)

        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f * d
        c.drawCircle(cx, cy, r - 1.2f * d, p)

        p.style = Paint.Style.FILL
        p.color = Color.parseColor("#FF6F00")
        c.drawCircle(cx, cy, 5f * d, p)
        return bmp
    }

    private fun createStopBadgeBitmap(number: Int): Bitmap {
        val d = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val w = (30f * d).roundToInt()
        val h = (38f * d).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val r = 12f * d
        val cy = r + 2f * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val tail = Path().apply {
            moveTo(cx - 5f * d, cy + r - 2f * d)
            lineTo(cx + 5f * d, cy + r - 2f * d)
            lineTo(cx, h - 2f * d)
            close()
        }
        p.color = Color.parseColor("#2563EB")
        c.drawPath(tail, p)
        c.drawCircle(cx, cy, r, p)

        p.color = Color.WHITE
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        c.drawCircle(cx, cy, r - 1f * d, p)

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.textSize = 12.5f * d
        p.typeface = Typeface.DEFAULT_BOLD
        val baseline = cy - (p.descent() + p.ascent()) / 2f
        c.drawText(number.toString(), cx, baseline, p)
        return bmp
    }

    private fun geodesicCirclePolygon(lat: Double, lng: Double, radiusM: Double, steps: Int): Polygon {
        val latRad = Math.toRadians(lat)
        val dLat = (radiusM / GeoPoint.EARTH_RADIUS_M) * (180.0 / PI)
        val cosLat = cos(latRad).coerceAtLeast(0.01)
        val dLng = dLat / cosLat

        val ring = ArrayList<Point>(steps + 1)
        for (i in 0 until steps) {
            val theta = (2.0 * PI * i) / steps
            ring.add(Point.fromLngLat(lng + dLng * cos(theta), lat + dLat * sin(theta)))
        }
        ring.add(ring[0])
        return Polygon.fromLngLats(listOf(ring))
    }

    private fun colorToHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    companion object {
        private const val CIRCLE_SEGMENTS = 36

        private const val SRC_BLACKLIST = "drifly-blacklist-src"
        private const val SRC_SIGNAL_AREAS = "drifly-signal-areas-src"
        private const val SRC_ROUTE_ALT = "drifly-route-alt-src"
        private const val SRC_ROUTE_ACTIVE = "drifly-route-active-src"
        private const val SRC_ROUTE_STOPS = "drifly-route-stops-src"
        private const val SRC_SIGNALS = "drifly-signals-src"
        private const val SRC_DRIVER = "drifly-driver-src"

        private const val LYR_BLACKLIST_FILL = "drifly-blacklist-fill"
        private const val LYR_BLACKLIST_LINE = "drifly-blacklist-line"
        private const val LYR_SIGNAL_AREAS_FILL = "drifly-signal-areas-fill"
        private const val LYR_SIGNAL_AREAS_LINE = "drifly-signal-areas-line"
        private const val LYR_ROUTE_ALT_CASING = "drifly-route-alt-casing"
        private const val LYR_ROUTE_ALT_LINE = "drifly-route-alt-line"
        private const val LYR_ROUTE_CASING = "drifly-route-casing"
        private const val LYR_ROUTE_LINE = "drifly-route-line"
        private const val LYR_ROUTE_STOPS = "drifly-route-stops-layer"
        private const val LYR_SIGNALS = "drifly-signals-layer"
        private const val LYR_DRIVER = "drifly-driver-layer"

        private const val IMG_DRIVER_DOT = "drifly_driver_dot"
        private const val IMG_DRIVER_CHEVRON = "drifly_driver_chevron"
        private const val IMG_DEST_PIN = "drifly_dest_pin"
    }
}
