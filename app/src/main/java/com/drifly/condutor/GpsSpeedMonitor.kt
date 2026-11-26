package com.drifly.condutor

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Velocímetro reativo da bolinha flutuante com fusão GNSS + IMU.
 *
 * - assina em paralelo o `LocationManager.GPS_PROVIDER` (velocidade Doppler
 *   direta do chip GNSS, menor latência e `speedAccuracyMetersPerSecond`) e o
 *   [GpsLocationStream] (Fused Location Provider como fallback);
 * - quando disponíveis, lê `GAME_ROTATION_VECTOR` + `LINEAR_ACCELERATION` +
 *   `GYROSCOPE` (~50 Hz, sem magnetômetro e sem permissão extra) para projetar
 *   acelerações e frenagens entre os fixes de 1 Hz do GPS;
 * - publica [GpsSpeedState] via [StateFlow] a até 10 Hz; a View só é
 *   invalidada quando o número inteiro realmente muda;
 * - descarta a leitura após ~3 s sem sinal GNSS válido (`--`, nunca 0 inventado).
 */
class GpsSpeedMonitor(
    context: Context,
    private val staleAfterMs: Long = SpeedReadoutEngine.STALE_AFTER_MS
) {
    private val appContext = context.applicationContext
    private val fusion = SpeedFusionEngine(staleAfterMs)

    private val _state = MutableStateFlow(
        SpeedReadoutEngine.initial(GpsLocationStream.hasLocationPermission(appContext))
    )
    val state: StateFlow<GpsSpeedState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null
    private var subscribedFused = false
    private var subscribedDirectGps = false
    private var sensorsActive = false
    private var loggedLowConfidence = false
    private var loggedNoPermission = false
    private var lastDiagLogElapsedMs = 0L

    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sensorManager: SensorManager? =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // Matriz de rotação do GAME_ROTATION_VECTOR (nivelada sem magnetômetro).
    private val rotationMatrix = FloatArray(9)
    @Volatile
    private var hasRotation = false
    @Volatile
    private var lastGyroNormRadS = 0f

    private val fusedConsumer = GpsLocationStream.Consumer { location -> onLocation(location) }

    private val directGpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocation(location)
        }

        @Deprecated("Deprecated in Android API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    hasRotation = true
                }

                Sensor.TYPE_GYROSCOPE -> {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    lastGyroNormRadS = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                }

                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    if (!hasRotation) return
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    // Projeta a aceleração linear do aparelho no plano horizontal nivelado.
                    val hx = rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az
                    val hy = rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az
                    val nowMs = SystemClock.elapsedRealtime()
                    val eventMs = event.timestamp / 1_000_000L
                    // Alguns HALs usam CLOCK_MONOTONIC em vez de CLOCK_BOOTTIME; só usa o timestamp
                    // do sensor quando está alinhado ao elapsedRealtime do GNSS.
                    val alignedMs = if (abs(eventMs - nowMs) < 5_000L) eventMs else nowMs
                    fusion.onImuSample(
                        hx = hx,
                        hy = hy,
                        gyroNormRadS = lastGyroNormRadS,
                        nowElapsedMs = alignedMs
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (subscribedFused || subscribedDirectGps) return

        val granted = GpsLocationStream.hasLocationPermission(appContext)
        _state.value = SpeedReadoutEngine.initial(granted)
        if (!granted) {
            logNoPermission()
            return
        }

        fusion.reset()
        startLocationSources()
        if (!subscribedFused && !subscribedDirectGps) {
            AppLogger.w(TAG, "Nenhum provedor de GPS aceitou a assinatura do velocímetro")
            return
        }

        startImuSensors()

        val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = monitorScope
        ticker = monitorScope.launch {
            while (isActive) {
                delay(TICK_MS)
                publish()
            }
        }
        publish()
    }

    fun stop() {
        stopLocationSources()
        stopImuSensors()
        ticker?.cancel()
        ticker = null
        scope?.cancel()
        scope = null
        fusion.reset()
        loggedLowConfidence = false
        lastDiagLogElapsedMs = 0L
    }

    @SuppressLint("MissingPermission")
    private fun startLocationSources() {
        if (!subscribedFused) {
            subscribedFused = GpsLocationStream.subscribe(
                context = appContext,
                consumer = fusedConsumer,
                intervalMs = UPDATE_INTERVAL_MS,
                minDistanceM = 0f
            )
        }

        if (!subscribedDirectGps) {
            val lm = locationManager
            if (lm != null && lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        DIRECT_GPS_INTERVAL_MS,
                        0f,
                        directGpsListener,
                        Looper.getMainLooper()
                    )
                    subscribedDirectGps = true
                    AppLogger.d(TAG, "GPS_PROVIDER direto ativo para o velocímetro (${DIRECT_GPS_INTERVAL_MS}ms)")
                } catch (error: Exception) {
                    AppLogger.w(TAG, "Falha ao assinar GPS_PROVIDER direto: ${error.message}")
                }
            }
        }
    }

    private fun stopLocationSources() {
        if (subscribedFused) {
            subscribedFused = false
            GpsLocationStream.unsubscribe(fusedConsumer)
        }
        if (subscribedDirectGps) {
            subscribedDirectGps = false
            try {
                locationManager?.removeUpdates(directGpsListener)
            } catch (_: Exception) {
            }
        }
    }

    private fun startImuSensors() {
        if (sensorsActive) return
        val sm = sensorManager ?: return
        val rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return
        val linAcc = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        hasRotation = false
        lastGyroNormRadS = 0f
        val okRot = sm.registerListener(imuListener, rot, SensorManager.SENSOR_DELAY_GAME)
        val okAcc = sm.registerListener(imuListener, linAcc, SensorManager.SENSOR_DELAY_GAME)
        if (okRot && okAcc) {
            if (gyro != null) {
                sm.registerListener(imuListener, gyro, SensorManager.SENSOR_DELAY_GAME)
            }
            sensorsActive = true
            AppLogger.d(TAG, "Fusão IMU ativa (GameRotationVector + LinearAcceleration)")
        } else {
            sm.unregisterListener(imuListener)
        }
    }

    private fun stopImuSensors() {
        if (!sensorsActive) return
        sensorsActive = false
        hasRotation = false
        lastGyroNormRadS = 0f
        try {
            sensorManager?.unregisterListener(imuListener)
        } catch (_: Exception) {
        }
    }

    private fun onLocation(location: Location) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val hasSpeed = try {
            location.hasSpeed()
        } catch (_: Exception) {
            false
        }
        val speedAccuracy = try {
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null
        } catch (_: Exception) {
            null
        }
        val hasBearing = try {
            location.hasBearing()
        } catch (_: Exception) {
            false
        }
        val elapsedMs = try {
            val nanos = location.elapsedRealtimeNanos
            if (nanos > 0L) nanos / 1_000_000L else nowElapsedMs
        } catch (_: Exception) {
            nowElapsedMs
        }

        val fix = GpsFix(
            timeMs = location.time,
            hasSpeed = hasSpeed,
            speedMps = if (hasSpeed) location.speed else 0f,
            speedAccuracyMps = speedAccuracy,
            horizontalAccuracyM = location.accuracy,
            provider = location.provider,
            elapsedRealtimeMs = elapsedMs,
            hasBearing = hasBearing,
            bearingDeg = if (hasBearing) location.bearing else 0f
        )

        if (fusion.onLocationFix(fix, nowElapsedMs)) {
            publish(nowElapsedMs)
            maybeLogDiagnostics(nowElapsedMs)
        }
    }

    private fun publish(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val granted = GpsLocationStream.hasLocationPermission(appContext)
        if (granted) {
            loggedNoPermission = false
            // Se a permissão foi concedida com o indicador já aberto, volta a assinar.
            if (!subscribedFused && !subscribedDirectGps && scope != null) {
                startLocationSources()
                startImuSensors()
            }
        } else {
            logNoPermission()
        }

        val next = fusion.evaluate(
            nowElapsedMs = nowElapsedMs,
            permissionGranted = granted
        )
        if (next != _state.value) {
            _state.value = next
        }

        if (next.lowConfidence != loggedLowConfidence) {
            loggedLowConfidence = next.lowConfidence
            if (next.lowConfidence) {
                AppLogger.w(
                    TAG,
                    "Leitura de velocidade com baixa confiabilidade: ${next.kmh} km/h"
                )
            }
        }
    }

    private fun maybeLogDiagnostics(nowElapsedMs: Long) {
        if (nowElapsedMs - lastDiagLogElapsedMs < DIAG_LOG_INTERVAL_MS) return
        val diag = fusion.diagnostics(nowElapsedMs) ?: return
        if (diag.fusedSpeedKmh < 5f) return
        lastDiagLogElapsedMs = nowElapsedMs
        val accText = diag.speedAccuracyMps?.let { String.format(Locale.US, "%.2f", it) } ?: "?"
        AppLogger.d(
            TAG,
            String.format(
                Locale.US,
                "SPEED_DIAG src=%s raw=%.1f fused=%.1f lat=%dms acc=%sm/s imu=%.2f",
                diag.activeProvider ?: "?",
                diag.rawSpeedKmh,
                diag.fusedSpeedKmh,
                diag.latencyMs,
                accText,
                diag.imuConfidence
            )
        )
    }

    private fun logNoPermission() {
        if (loggedNoPermission) return
        loggedNoPermission = true
        AppLogger.w(TAG, "Permissão de localização precisa negada: velocímetro exibe --")
    }

    companion object {
        private const val TAG = "GpsSpeedMonitor"

        /** Intervalo do Fused Location Provider (fallback). */
        const val UPDATE_INTERVAL_MS = 1_000L

        /** Intervalo pedido ao GPS_PROVIDER direto (chip entrega nativamente a 1 Hz). */
        private const val DIRECT_GPS_INTERVAL_MS = 500L

        /** Atualização da projeção inercial e vencimento a 10 Hz. */
        private const val TICK_MS = 100L

        /** Telemetria compacta em movimento para validação em campo. */
        private const val DIAG_LOG_INTERVAL_MS = 10_000L
    }
}
