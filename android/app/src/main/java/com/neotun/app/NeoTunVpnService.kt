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
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopServiceInternal()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!running) {
            NeoTunDiagnostics.clear(this)
            NeoTunDiagnostics.log(this, "sing-box: onStartCommand; Android SDK=" + Build.VERSION.SDK_INT)
            runCatching {
                NeoTunDiagnostics.log(this, "sing-box: starting foreground service")
                startForegroundNotification()
                NeoTunDiagnostics.log(this, "sing-box: creating Android platform adapter")
                platform = NeoTunPlatform(this)
                NeoTunDiagnostics.log(this, "sing-box: constructing libbox CommandServer")
                commandServer = CommandServer(this, platform)
                NeoTunDiagnostics.log(this, "sing-box: starting libbox CommandServer")
                commandServer.start()
                NeoTunDiagnostics.log(this, "sing-box: CommandServer started")

                val config = intent?.getStringExtra(EXTRA_CONFIG)
                    ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CONFIG, null)
                if (config.isNullOrBlank()) {
                    throw IllegalArgumentException("Нет конфигурации sing-box")
                }
                val normalizedConfig = config.trim()
                if (!normalizedConfig.startsWith("{")) {
                    throw IllegalArgumentException("Некорректная конфигурация sing-box")
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
                NeoTunDiagnostics.log(this, "sing-box: calling startOrReloadService")
                commandServer.startOrReloadService(normalizedConfig, OverrideOptions())
                NeoTunDiagnostics.log(this, "sing-box: startOrReloadService returned successfully")
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CONFIG, config)
                    .remove(KEY_ERROR)
                    .putBoolean(KEY_RUNNING, true)
                    .apply()
                running = true
                NeoTunDiagnostics.log(this, "sing-box: startup completed")
            }.onFailure { error ->
                NeoTunDiagnostics.error(this, "sing-box startup failed", error)
                stopWithError(error.message ?: error.javaClass.simpleName ?: "Не удалось запустить sing-box")
            }
        }
        return START_NOT_STICKY
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
        if (::commandServer.isInitialized) {
            runCatching { commandServer.closeService() }
            runCatching { commandServer.close() }
        }
        running = false
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RUNNING, false)
            .apply()
    }

    override fun serviceStop() {
        NeoTunDiagnostics.log(this, "sing-box: libbox requested serviceStop; running=" + running)
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
