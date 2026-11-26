package com.drifly.condutor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MonitoringService : Service() {

    private lateinit var prefs: PreferencesManager
    private var odometerManager: TripOdometerManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesManager(this)
        createNotificationChannel()

        odometerManager = TripOdometerManager(this) { km, cost ->
            updateNotificationWithOdometer(km, cost)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_MONITORING) {
            stopMonitoringInternal()
            return START_NOT_STICKY
        }

        if (intent == null && !prefs.isMonitoringActive) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Inicia o serviço e fixa a notificação contínua
        prefs.isMonitoringActive = true
        val bolinhaDisponivel = Premium.ativo(this)
        val iniciadoPeloMotorista = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) == true
        if (bolinhaDisponivel && iniciadoPeloMotorista && prefs.floatingBubbleEnabled) {
            // "Iniciar monitoramento" é uma ação do motorista: a bolinha volta a
            // aparecer mesmo que ela tenha sido ocultada pelo X antes.
            prefs.floatingBubbleHidden = false
        }
        if (bolinhaDisponivel && prefs.floatingBubbleEnabled && !prefs.floatingBubbleHidden) {
            FuelBubbleService.start(this)
        }
        if (prefs.shiftStartTime == 0L) {
            prefs.shiftStartTime = System.currentTimeMillis()
        }
        odometerManager?.startTracking()
        notifyStateChanged(true)

        val notification = buildOngoingNotification()

        if (Build.VERSION.SDK_INT >= 34) {
            var fgsType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            val hasLocationPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasLocationPerm) {
                fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    fun stopMonitoringInternal() {
        prefs.isMonitoringActive = false
        odometerManager?.stopTracking()
        notifyStateChanged(false)

        // Encerra imediatamente qualquer overlay flutuante ativo
        BubbleOverlayService.stop(this)

        // Fecha apenas o lembrete visível. O AGENDAMENTO (8h/15h/20h) continua de pé:
        // antes ele era cancelado aqui a cada fim de turno e, por isso, nunca tocava.
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(SupportNotificationWorker.NOTIFICATION_ID)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // Cancela explicitamente TODAS as notificações do app para não deixar nenhum resíduo na barra
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancelAll()
        manager?.cancel(NOTIFICATION_ID)
        manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
        manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)

        stopSelf()
    }

    private fun updateNotificationWithOdometer(km: Double, cost: Double) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notif = buildOngoingNotification(km, cost)
        manager.notify(NOTIFICATION_ID, notif)
    }

    private fun buildOngoingNotification(
        km: Double = prefs.shiftDistanceKm.toDouble(),
        cost: Double = prefs.shiftDistanceKm * prefs.costPerKm
    ): Notification {
        // Ao clicar no corpo da notificação: Abre a tela principal do DriFly
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpenApp = PendingIntent.getActivity(
            this,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Ao clicar no botão da direita (Desligar): Dispara broadcast de desligamento imediato
        val stopIntent = Intent(this, MonitoringActionReceiver::class.java).apply {
            action = ACTION_STOP_MONITORING
        }
        val pStopIntent = PendingIntent.getBroadcast(
            this,
            1002,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentSubtitle = if (km > 0.05) {
            String.format(java.util.Locale("pt", "BR"), "Turno: %.1f km rodados • Combustível: -R$ %.2f", km, cost)
        } else {
            "Calculando rentabilidade e lucro líquido em tempo real"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DriFly Ativo • Monitorando Uber e 99")
            .setContentText(contentSubtitle)
            .setSmallIcon(R.drawable.ic_drifly_logo)
            .setContentIntent(pOpenApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        // Atalho para trazer a bolinha de volta depois de fechada pelo X.
        if (Premium.ativo(this) && prefs.floatingBubbleEnabled && prefs.floatingBubbleHidden) {
            val showIntent = Intent(this, MonitoringActionReceiver::class.java).apply {
                action = ACTION_SHOW_BUBBLE
            }
            val pShowBubble = PendingIntent.getBroadcast(
                this,
                1003,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_fuel, "Mostrar bolinha", pShowBubble)
        }

        return builder
            .addAction(
                R.drawable.ic_power_off,
                "Desligar",
                pStopIntent
            )
            .build()
    }

    private fun notifyStateChanged(isActive: Boolean) {
        val broadcastIntent = Intent(ACTION_MONITORING_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_ACTIVE, isActive)
        }
        sendBroadcast(broadcastIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monitoramento DriFly",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificação contínua indicando que o DriFly está ativo monitorando ofertas de corrida."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Mesmo tirando o app dos recentes, mantém o monitoramento contínuo ativo
        if (prefs.isMonitoringActive) {
            val notification = buildOngoingNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        odometerManager?.stopTracking()
        odometerManager = null
        if (!prefs.isMonitoringActive) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancelAll()
            manager?.cancel(NOTIFICATION_ID)
            manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
            manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)
            BubbleOverlayService.stop(this)
        }
    }

    companion object {
        const val CHANNEL_ID = "drifly_monitoring_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_MONITORING = "com.drifly.condutor.ACTION_STOP_MONITORING"
        const val ACTION_SHOW_BUBBLE = "com.drifly.condutor.ACTION_SHOW_BUBBLE"
        const val ACTION_MONITORING_CHANGED = "com.drifly.condutor.ACTION_MONITORING_CHANGED"
        const val EXTRA_IS_ACTIVE = "EXTRA_IS_ACTIVE"
        const val EXTRA_USER_INITIATED = "EXTRA_USER_INITIATED"

        private var instance: MonitoringService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context, userInitiated: Boolean = false) {
            val intent = Intent(context, MonitoringService::class.java)
                .putExtra(EXTRA_USER_INITIATED, userInitiated)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Redesenha a notificação contínua (ex.: depois de mostrar/ocultar a bolinha). */
        fun refreshNotification() {
            instance?.let { servico ->
                servico.updateNotificationWithOdometer(
                    servico.prefs.shiftDistanceKm.toDouble(),
                    servico.prefs.shiftDistanceKm * servico.prefs.costPerKm
                )
            }
        }

        fun stop(context: Context) {
            val current = instance
            if (current != null) {
                current.stopMonitoringInternal()
            } else {
                val prefs = PreferencesManager(context)
                prefs.isMonitoringActive = false

                // Garante que o overlay flutuante também seja cancelado e fechado
                BubbleOverlayService.stop(context)

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.cancelAll()
                manager?.cancel(NOTIFICATION_ID)
                manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
                manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)

                val broadcastIntent = Intent(ACTION_MONITORING_CHANGED).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_IS_ACTIVE, false)
                }
                context.sendBroadcast(broadcastIntent)
            }
        }
    }
}

class MonitoringActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MonitoringService.ACTION_SHOW_BUBBLE) {
            val prefs = PreferencesManager(context)
            if (Premium.ativo(context) && prefs.floatingBubbleEnabled) {
                prefs.floatingBubbleHidden = false
                FuelBubbleService.mostrarAgora(context)
                MonitoringService.refreshNotification()
                AppLogger.i("MonitoringActionReceiver", "Bolinha mostrada pelo atalho da notificação")
            }
            return
        }
        if (intent.action == MonitoringService.ACTION_STOP_MONITORING) {
            MonitoringService.stop(context)
            BubbleOverlayService.stop(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancelAll()
            manager?.cancel(MonitoringService.NOTIFICATION_ID)
            manager?.cancel(BubbleOverlayService.NOTIFICATION_ID)
            manager?.cancel(SupportNotificationWorker.NOTIFICATION_ID)
        }
    }
}
