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
        if (!running) {
            startForegroundNotification()
            platform = NeoTunPlatform(this)
            commandServer = CommandServer(this, platform)
            commandServer.start()

            val config = intent?.getStringExtra(EXTRA_CONFIG)
                ?: getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_CONFIG, null)

            if (config.isNullOrBlank()) {
                stopWithError("Нет профиля. Добавьте VLESS-ссылку.")
            } else {
                runCatching {
                    commandServer.startOrReloadService(config, OverrideOptions())
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CONFIG, config)
                        .remove(KEY_ERROR)
                        .apply()
                    running = true
                }.onFailure {
                    stopWithError(it.message ?: "Не удалось запустить sing-box")
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder =
        super.onBind(intent) ?: error("VPN binder unavailable")

    override fun onDestroy() {
        if (::commandServer.isInitialized) {
            runCatching { commandServer.closeService() }
            runCatching { commandServer.close() }
        }
        running = false
        super.onDestroy()
    }

    override fun serviceStop() = stopSelf()
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        android.util.Log.d("NeoTUN", message ?: "")
    }
    override fun connectSSHAgent(): Int = -1

    private fun stopWithError(message: String) {
        running = false
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ERROR, message)
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "NeoTUN",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NeoTUN")
            .setContentText("Соединение запускается…")
            .setOngoing(true)
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
        const val ENGINE_SING_BOX = "sing-box"
        const val ENGINE_XRAY = "xray"
    }
}
