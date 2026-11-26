package com.drifly.condutor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Fluxo único de localização do aplicativo.
 *
 * Tanto o odômetro do turno ([TripOdometerManager]) quanto o velocímetro da
 * bolinha ([GpsSpeedMonitor]) assinam este mesmo fluxo. Assim existe apenas
 * UMA solicitação de GPS ativa no processo, com o menor intervalo pedido
 * pelos assinantes (o velocímetro pede ~1 s, o odômetro aceita 4 s).
 *
 * Usa o LocationManager do próprio Android: nada de Google Play Services, que
 * saíram do projeto no rebranding para o DriFly Condutor.
 *
 * As entregas acontecem na thread principal, como no restante do app.
 */
object GpsLocationStream {

    private const val TAG = "GpsLocationStream"
    private const val MIN_INTERVAL_MS = 250L

    fun interface Consumer {
        fun onLocation(location: Location)
    }

    private class Entry(
        val consumer: Consumer,
        val intervalMs: Long,
        val minDistanceM: Float
    )

    private val entries = mutableListOf<Entry>()

    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    private var activeIntervalMs = -1L
    private var activeMinDistanceM = -1f

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasAnyLocationPermission(context: Context): Boolean =
        hasLocationPermission(context) || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Assina o fluxo. Retorna `false` quando não há permissão de localização
     * ou quando o aparelho está sem provedor de GPS/rede ligado.
     */
    @Synchronized
    fun subscribe(
        context: Context,
        consumer: Consumer,
        intervalMs: Long,
        minDistanceM: Float
    ): Boolean {
        val appContext = context.applicationContext
        entries.removeAll { it.consumer === consumer }
        entries.add(
            Entry(
                consumer = consumer,
                intervalMs = intervalMs.coerceAtLeast(MIN_INTERVAL_MS),
                minDistanceM = minDistanceM.coerceAtLeast(0f)
            )
        )

        if (!hasAnyLocationPermission(appContext)) {
            stopUpdatesLocked()
            return false
        }

        val interval = entries.minOf { it.intervalMs }
        val minDistance = entries.minOf { it.minDistanceM }
        if (listener != null && interval == activeIntervalMs && minDistance == activeMinDistanceM) {
            return true
        }

        stopUpdatesLocked()
        return startUpdatesLocked(appContext, interval, minDistance)
    }

    @Synchronized
    fun unsubscribe(consumer: Consumer) {
        entries.removeAll { it.consumer === consumer }
        if (entries.isEmpty()) stopUpdatesLocked()
    }

    @Synchronized
    fun isActive(): Boolean = listener != null

    @SuppressLint("MissingPermission")
    private fun startUpdatesLocked(
        context: Context,
        intervalMs: Long,
        minDistanceM: Float
    ): Boolean {
        val service = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        manager = service

        val provider = when {
            service.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            service.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                AppLogger.w(TAG, "Nenhum provedor de localização ligado no aparelho")
                return false
            }
        }

        val newListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                deliver(location)
            }
        }

        return try {
            service.requestLocationUpdates(
                provider,
                intervalMs,
                minDistanceM,
                newListener,
                Looper.getMainLooper()
            )
            service.getLastKnownLocation(provider)?.let { deliver(it) }
            listener = newListener
            activeIntervalMs = intervalMs
            activeMinDistanceM = minDistanceM
            AppLogger.d(
                TAG,
                "GPS ativo ($provider): intervalo=${intervalMs}ms, distância mínima=${minDistanceM}m"
            )
            true
        } catch (error: Exception) {
            AppLogger.w(TAG, "Falha ao solicitar GPS: ${error.message}", error)
            false
        }
    }

    private fun stopUpdatesLocked() {
        val service = manager
        val current = listener
        listener = null
        activeIntervalMs = -1L
        activeMinDistanceM = -1f
        if (service != null && current != null) {
            try {
                service.removeUpdates(current)
            } catch (_: Exception) {
            }
        }
    }

    private fun deliver(location: Location) {
        val targets = synchronized(this) { entries.map { it.consumer } }
        targets.forEach { consumer ->
            try {
                consumer.onLocation(location)
            } catch (error: Exception) {
                AppLogger.w(TAG, "Assinante de GPS falhou: ${error.message}", error)
            }
        }
    }
}
