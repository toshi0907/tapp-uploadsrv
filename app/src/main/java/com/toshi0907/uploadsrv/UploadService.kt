package com.toshi0907.uploadsrv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * アップロードサーバーを常駐させるフォアグラウンドサービス(種別: specialUse)。
 * 通知に接続URLと「停止」ボタンを表示する。
 * 稼働中は Wi-Fi の省電力切断やCPUスリープで受信が止まらないよう WifiLock / WakeLock を保持する。
 */
class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var serverToken: Any? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // ユーザー操作による停止なので、端末起動時の自動起動も行わない
            AppSettings(this).serverEnabled = false
            scope.launch {
                ServerController.stop()
                stopSelfAndForeground()
            }
            return START_NOT_STICKY
        }

        // startForegroundService() から5秒以内に startForeground() を呼ぶ必要がある
        startInForeground(buildNotification("起動中…"))

        val port = AppSettings(this).port
        scope.launch {
            val token = ServerController.start(this@UploadService, port)
            serverToken = token
            if (token == null) {
                stopSelfAndForeground()
                return@launch
            }
            observeState()
        }
        return START_STICKY
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            ServerController.state.collect { state ->
                when (state) {
                    is ServerState.Running -> {
                        acquireLocks()
                        notify(buildNotification(urlText(state.port)))
                    }
                    else -> stopSelfAndForeground()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        // サービスが破棄される場合はサーバーも止める(ソケットを残さない)。
        // メインスレッドを塞がないよう、プロセス全体のスコープで非同期に止める
        ServerController.stopAsync(serverToken)
        super.onDestroy()
    }

    private fun stopSelfAndForeground() {
        stateJob?.cancel()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** サーバー稼働中に保持するロックを取得する(取得済みなら何もしない)。 */
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            // HIGH_PERF は Android 14 で非推奨だが、それ未満では省電力によるWi-Fi切断の抑止に有効
            @Suppress("DEPRECATION")
            val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi?.createWifiLock(mode, LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            // サーバー稼働中はずっと保持し、停止時に必ず解放するためタイムアウトは付けない
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun urlText(port: Int): String {
        val addresses = NetworkUtils.localIpv4Addresses()
        return if (addresses.isEmpty()) {
            "稼働中(ポート$port・IPアドレス不明)"
        } else {
            addresses.joinToString("\n") { "http://$it:$port" }
        }
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel(this)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, UploadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("アップロードサーバー稼働中")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        private const val LOCK_TAG = "uploadsrv:server"
        private const val ACTION_STOP = "com.toshi0907.uploadsrv.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UploadService::class.java).setAction(ACTION_STOP))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "サーバー稼働状況", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
