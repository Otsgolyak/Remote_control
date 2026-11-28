package com.remote.control.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.remote.control.MainActivity
import com.remote.control.R
import com.remote.control.utils.Logger

/**
 * Фоновая служба для удержания активного соединения с телевизором при блокировке экрана
 * и отображения виджета управления громкостью/питанием на экране блокировки смартфона.
 */
class TvRemoteForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "tv_remote_connection_channel"
        const val CHANNEL_NAME = "Пульт управления ТВ"
        const val NOTIFICATION_ID = 2024

        const val ACTION_START = "com.remote.control.service.ACTION_START"
        const val ACTION_STOP = "com.remote.control.service.ACTION_STOP"
        const val ACTION_VOL_UP = "com.remote.control.service.ACTION_VOL_UP"
        const val ACTION_VOL_DOWN = "com.remote.control.service.ACTION_VOL_DOWN"
        const val ACTION_MUTE = "com.remote.control.service.ACTION_MUTE"
        const val ACTION_POWER = "com.remote.control.service.ACTION_POWER"
        const val ACTION_PLAY_PAUSE = "com.remote.control.service.ACTION_PLAY_PAUSE"

        const val EXTRA_TV_NAME = "extra_tv_name"
        const val EXTRA_TV_BRAND = "extra_tv_brand"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var onRemoteAction: ((String) -> Unit)? = null

        fun start(context: Context, tvName: String, tvBrand: String) {
            try {
                val intent = Intent(context, TvRemoteForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_TV_NAME, tvName)
                    putExtra(EXTRA_TV_BRAND, tvBrand)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Logger.log("Ошибка запуска TvRemoteForegroundService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, TvRemoteForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Logger.log("Ошибка остановки TvRemoteForegroundService: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentTvName: String = "Телевизор"
    private var currentTvBrand: String = "TV"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        isRunning = true
        Logger.log("TvRemoteForegroundService создан, удержание Wi-Fi и сокетов активно")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                Logger.log("TvRemoteForegroundService: остановка службы")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                currentTvName = intent.getStringExtra(EXTRA_TV_NAME) ?: currentTvName
                currentTvBrand = intent.getStringExtra(EXTRA_TV_BRAND) ?: currentTvBrand
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_VOL_UP, ACTION_VOL_DOWN, ACTION_MUTE, ACTION_POWER, ACTION_PLAY_PAUSE -> {
                Logger.log("TvRemoteForegroundService действие: $action")
                onRemoteAction?.invoke(action)
            }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteControl:WakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(12 * 60 * 60 * 1000L) // до 12 часов

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "RemoteControl:WifiLock")
            } else {
                @Suppress("DEPRECATION")
                wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteControl:WifiLock")
            }
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            Logger.log("TvRemoteForegroundService: WifiLock и WakeLock зафиксированы")
        } catch (e: Exception) {
            Logger.log("TvRemoteForegroundService ошибка locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Logger.log("TvRemoteForegroundService ошибка освобождения locks: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Удержание связи с ТВ при выключенном экране и управление с экрана блокировки"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun createActionPendingIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(this, TvRemoteForegroundService::class.java).apply {
                this.action = action
            }
            return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remote_notification)
            .setContentTitle("Пульт: $currentTvName ($currentTvBrand)")
            .setContentText("Связь удерживается • Управление на экране блокировки")
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Mute", createActionPendingIntent(ACTION_MUTE, 1))
            .addAction(android.R.drawable.ic_media_previous, "Vol -", createActionPendingIntent(ACTION_VOL_DOWN, 2))
            .addAction(android.R.drawable.ic_media_next, "Vol +", createActionPendingIntent(ACTION_VOL_UP, 3))
            .addAction(android.R.drawable.ic_lock_power_off, "Power", createActionPendingIntent(ACTION_POWER, 4))
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        releaseLocks()
        super.onDestroy()
        Logger.log("TvRemoteForegroundService уничтожен")
    }
}
