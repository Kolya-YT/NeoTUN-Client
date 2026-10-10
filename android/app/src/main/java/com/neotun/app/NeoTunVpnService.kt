package com.neotun.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus

class NeoTunVpnService : VpnService(), CommandServerHandler {
    private lateinit var platform: PlatformInterface
    private lateinit var commandServer: CommandServer
    @Volatile private var running = false
    @Volatile private var starting = false
    @Volatile private var startupGeneration = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            startupGeneration++
            starting = false
            stopServiceInternal()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running || starting) return START_NOT_STICKY

        val generation = ++startupGeneration
        starting = true
        NeoTunDiagnostics.clear(this)
        NeoTunDiagnostics.log(this, "sing-box: onStartCommand; Android SDK=" + Build.VERSION.SDK_INT)
        val config = intent?.getStringExtra(EXTRA_CONFIG)
            ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CONFIG, null)
        if (config.isNullOrBlank()) {
            starting = false
            stopWithError("Нет конфигурации sing-box")
            return START_NOT_STICKY
        }
        val normalizedConfig = config.trim()
        if (!normalizedConfig.startsWith("{")) {
            starting = false
            stopWithError("Некорректная конфигурация sing-box")
            return START_NOT_STICKY
        }

        // Remote sing-box rule sets may need network I/O during startup. Never call
        // startOrReloadService on Android's main thread: it can block long enough
        // to trigger an ANR and freeze the entire app UI.
        NeoTunDiagnostics.log(this, "sing-box: starting foreground service")
        startForegroundNotification()
        Thread({
            try {
                if (generation != startupGeneration) return@Thread
                NeoTunDiagnostics.log(this, "sing-box: creating Android platform adapter")
                val newPlatform = NeoTunPlatform(this)
                platform = newPlatform
                NeoTunDiagnostics.log(this, "sing-box: constructing libbox CommandServer")
                val newServer = CommandServer(this, newPlatform)
                commandServer = newServer
                NeoTunDiagnostics.log(this, "sing-box: starting libbox CommandServer")
                newServer.start()
                NeoTunDiagnostics.log(this, "sing-box: CommandServer started")
                if (generation != startupGeneration) {
                    runCatching { newServer.closeService() }
                    runCatching { newServer.close() }
                    return@Thread
                }

                val configSummary = runCatching {
                    val json = org.json.JSONObject(normalizedConfig)
                    val outbounds = json.optJSONArray("outbounds")
                    val firstOutbound = outbounds?.optJSONObject(0)
                    "inbounds=" + (json.optJSONArray("inbounds")?.length() ?: 0) +
                        ", outbounds=" + (outbounds?.length() ?: 0) +
                        ", firstOutbound=" + (firstOutbound?.optString("type") ?: "unknown")
                }.getOrDefault("config summary unavailable")
                NeoTunDiagnostics.log(this, "sing-box: config accepted; chars=" +
                    normalizedConfig.length + "; " + configSummary)
                NeoTunDiagnostics.log(this, "sing-box: starting engine on background thread")
                newServer.startOrReloadService(normalizedConfig, OverrideOptions())
                if (generation != startupGeneration) {
                    runCatching { newServer.closeService() }
                    runCatching { newServer.close() }
                    return@Thread
                }
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CONFIG, config)
                    .remove(KEY_ERROR)
                    .putBoolean(KEY_RUNNING, true)
                    .apply()
                running = true
                starting = false
                NeoTunDiagnostics.log(this, "sing-box: startup completed")
            } catch (error: Throwable) {
                if (generation == startupGeneration) {
                    starting = false
                    NeoTunDiagnostics.error(this, "sing-box startup failed", error)
                    runOnMainThread {
                        stopWithError(error.message ?: error.javaClass.simpleName ?: "Не удалось запустить sing-box")
                    }
                } else {
                    NeoTunDiagnostics.log(this, "sing-box: startup cancelled")
                }
            }
        }, "NeoTUN-singbox-startup").start()
        return START_NOT_STICKY
    }

    private fun runOnMainThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    override fun onBind(intent: Intent): IBinder =
        super.onBind(intent) ?: error("VPN binder unavailable")

    override fun onDestroy() {
        NeoTunDiagnostics.log(this, "sing-box: service onDestroy; running=" + running +
            "; savedRunning=" + getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false) +
            "; savedError=" + (getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ERROR, null) ?: "none"))
        stopServiceInternal()
        super.onDestroy()
    }

    private fun stopServiceInternal() {
        startupGeneration++
        starting = false
        if (::commandServer.isInitialized) {
            runCatching { commandServer.closeService() }
            runCatching { commandServer.close() }
        }
        running = false
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, false)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun serviceStop() {
        val wasRunning = running
        NeoTunDiagnostics.log(this, "sing-box: libbox requested serviceStop; running=" + wasRunning)
        val message = if (wasRunning) "sing-box остановил соединение" else
            "sing-box остановил сервис во время запуска. Проверьте конфигурацию и логи libbox."
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_ERROR, message)
            .putBoolean(KEY_RUNNING, false)
            .apply()
        NeoTunDiagnostics.error(this, message)
        running = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
    override fun serviceReload() {
        NeoTunDiagnostics.log(this, "sing-box: libbox requested serviceReload")
    }
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        val line = message ?: return
        android.util.Log.d("NeoTUN", line)
        NeoTunDiagnostics.log(this, "libbox: " + line)
    }
    override fun connectSSHAgent(): Int = -1

    private fun stopWithError(message: String) {
        NeoTunDiagnostics.error(this, "sing-box stopped: " + message)
        running = false
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ERROR, message)
            .putBoolean(KEY_RUNNING, false)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundNotification() {
        val channelId = "neotun-service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val enabled = getSharedPreferences("neotun_ui", MODE_PRIVATE)
                .getBoolean("notifications_enabled", true)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "NeoTUN",
                    if (enabled) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NeoTUN")
            .setContentText("Соединение запускается…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(if (getSharedPreferences("neotun_ui", MODE_PRIVATE).getBoolean("notifications_enabled", true))
                NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, 1, notification, type)
    }

    companion object {
        const val EXTRA_CONFIG = "neotun.config"
        const val PREFS = "neotun"
        const val KEY_CONFIG = "config"
        const val KEY_URI = "uri"
        const val KEY_ERROR = "error"
        const val KEY_ENGINE = "engine"
        const val KEY_RUNNING = "running"
        const val ENGINE_SING_BOX = "sing-box"
        const val ENGINE_XRAY = "xray"
        const val ACTION_DISCONNECT = "com.neotun.app.action.DISCONNECT_SINGBOX"
    }
}
