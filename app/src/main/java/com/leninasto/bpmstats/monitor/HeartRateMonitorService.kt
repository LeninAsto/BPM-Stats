package com.leninasto.bpmstats.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.leninasto.bpmstats.MainActivity
import com.leninasto.bpmstats.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HeartRateMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        HeartRateMonitorRepository.initialize(this)
        createNotificationChannel()
        startMonitorForeground("Preparando monitor cardiaco")

        scope.launch {
            HeartRateMonitorRepository.currentBpm.collect { bpm ->
                val text = if (bpm > 0) "$bpm PPM en vivo" else HeartRateMonitorRepository.connectionState.value
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address != null) HeartRateMonitorRepository.connectToDevice(address)
            }

            ACTION_STOP -> {
                HeartRateMonitorRepository.disconnect()
                PulseOverlayService.stop(this)
                stopSelf()
            }

            ACTION_STOP_WIDGET -> PulseOverlayService.stop(this)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitorForeground(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HeartRateMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopWidgetIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, HeartRateMonitorService::class.java).setAction(ACTION_STOP_WIDGET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ecg_heart)
            .setContentTitle("PPM Stats monitoreando")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_ecg_heart, "Ocultar widget", stopWidgetIntent)
            .addAction(R.drawable.ic_ecg_heart, "Detener", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Monitor cardiaco",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantiene el monitoreo BLE activo en segundo plano."
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "heart_rate_monitor"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "com.leninasto.bpmstats.monitor.CONNECT"
        const val ACTION_STOP = "com.leninasto.bpmstats.monitor.STOP"
        const val ACTION_STOP_WIDGET = "com.leninasto.bpmstats.monitor.STOP_WIDGET"
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        fun start(context: Context, address: String) {
            val intent = Intent(context, HeartRateMonitorService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_DEVICE_ADDRESS, address)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, HeartRateMonitorService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
