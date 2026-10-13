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
    @Volatile private var nativeCloseStarted = false
    @Volatile private var disconnectRequested = false
    @Volatile private var startupThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnectRequested = true
            startupGeneration++
            starting = false
            running = false
            isServiceRunning = false
            NeoTunDiagnostics.log(this, "sing-box: disconnect requested; closing native engine off main thread")
            // libbox shutdown can block while draining TUN flows. Do not perform
            // native teardown on Android's main thread; keep this service alive
            // until teardown finishes so the switch waiter cannot race a new TUN.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Wait for an in-flight native startup to unwind before closing the shared
            // CommandServer. Otherwise shutdown may report completion before startup creates it.
            val startupWorker = startupThread
            Thread({
                if (startupWorker != null && startupWorker !== Thread.currentThread()) {
                    NeoTunDiagnostics.log(this, "sing-box: waiting for startup worker before native teardown")
                    runCatching { startupWorker.join() }
                        .onFailure { NeoTunDiagnostics.error(this, "sing-box: waiting for startup worker failed", it) }
                }
                closeNativeResources()
                isEngineBusy = false
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
                NeoTunDiagnostics.log(this, "sing-box: native shutdown completed; stopping service")
                stopSelf(startId)
            }, "NeoTUN-singbox-stop").start()
            return START_NOT_STICKY
        }
        if (running || starting) return START_NOT_STICKY

        disconnectRequested = false
        nativeCloseStarted = false
        isEngineBusy = true
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
        try {
            // Promote the VPN service before doing any native initialization or
            // remote rule-set I/O. Android 14+ can terminate a VPN service that
            // does not enter foreground mode promptly.
            startForegroundNotification()
        } catch (error: Throwable) {
            starting = false
            NeoTunDiagnostics.error(this, "sing-box: failed to enter foreground", error)
            stopWithError(
                "Не удалось запустить службу sing-box: " +
                    (error.message ?: error.javaClass.simpleName ?: "ошибка уведомления")
            )
            return START_NOT_STICKY
        }
        val startupWorker = Thread({
            // Keep this startup's server local so a stale startup can clean up
            // its own native resources without touching a newer connection.
            var startupServer: CommandServer? = null
            try {
                if (generation != startupGeneration) return@Thread
                NeoTunDiagnostics.log(this, "sing-box: initializing native libbox")
                NeoTunApplication.initializeLibbox(this)
                NeoTunDiagnostics.log(this, "sing-box: creating Android platform adapter")
                val newPlatform = NeoTunPlatform(this)
                platform = newPlatform
                NeoTunDiagnostics.log(this, "sing-box: constructing libbox CommandServer")
                val newServer = CommandServer(this, newPlatform)
                startupServer = newServer
                commandServer = newServer
                NeoTunDiagnostics.log(this, "sing-box: starting libbox CommandServer")
                newServer.start()
                NeoTunDiagnostics.log(this, "sing-box: CommandServer started")
                if (generation != startupGeneration) {
                    // During explicit disconnect, the stop worker joins this thread
                    // and owns cleanup, avoiding a double-close race.
                    if (!disconnectRequested) {
                        runCatching { newServer.closeService() }
                        runCatching { newServer.close() }
                    }
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
                    // During explicit disconnect, the stop worker joins this thread
                    // and owns cleanup, avoiding a double-close race.
                    if (!disconnectRequested) {
                        runCatching { newServer.closeService() }
                        runCatching { newServer.close() }
                    }
                    return@Thread
                }
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CONFIG, config)
                    .remove(KEY_ERROR)
                    .putBoolean(KEY_RUNNING, true)
                    .apply()
                running = true
                isServiceRunning = true
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
                    // Disconnect/restart invalidated this generation while native
                    // initialization was in flight. Close only this thread's server;
                    // the shared commandServer field may already point to a new run.
                    if (!disconnectRequested) {
                        startupServer?.let { staleServer ->
                            runCatching { staleServer.closeService() }
                            runCatching { staleServer.close() }
                        }
                    }
                    NeoTunDiagnostics.log(this, "sing-box: startup cancelled; stale native resources cleaned up")
                }
            } finally {
                if (startupThread === Thread.currentThread()) startupThread = null
            }
        }, "NeoTUN-singbox-startup")
        startupThread = startupWorker
        startupWorker.start()
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

    private fun closeNativeResources() {
        synchronized(this) {
            if (nativeCloseStarted) return
            nativeCloseStarted = true
        }
        if (::commandServer.isInitialized) {
            runCatching { commandServer.closeService() }
                .onFailure { NeoTunDiagnostics.error(this, "sing-box: closeService failed", it) }
            runCatching { commandServer.close() }
                .onFailure { NeoTunDiagnostics.error(this, "sing-box: close failed", it) }
        }
    }

    private fun stopServiceInternal() {
        startupGeneration++
        starting = false
        closeNativeResources()
        isEngineBusy = false
        running = false; isServiceRunning = false
        // A previous sing-box service can finish destroying after Xray has
        // already started. Never let that stale teardown overwrite the active
        // engine's shared connection state.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_ENGINE, ENGINE_SING_BOX) == ENGINE_SING_BOX) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun serviceStop() {
        // closeService() can invoke this callback during an intentional disconnect.
        // In that case, let the disconnect worker own final cleanup and stopSelf();
        // publishing an engine failure here can race the selected profile's startup.
        if (disconnectRequested) {
            NeoTunDiagnostics.log(this, "sing-box: ignoring native serviceStop callback during requested disconnect")
            return
        }
        // libbox can request a stop from inside startOrReloadService while the
        // startup thread is still unwinding. Invalidate its generation first so
        // it cannot later overwrite this failure with KEY_RUNNING=true.
        startupGeneration++
        starting = false
        val wasRunning = running
        NeoTunDiagnostics.log(this, "sing-box: libbox requested serviceStop; running=" + wasRunning)
        val message = if (wasRunning) "sing-box остановил соединение" else
            "sing-box остановил сервис во время запуска. Проверьте конфигурацию и логи libbox."
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_ENGINE, ENGINE_SING_BOX) == ENGINE_SING_BOX) {
            prefs.edit().putString(KEY_ERROR, message).putBoolean(KEY_RUNNING, false).apply()
        }
        NeoTunDiagnostics.error(this, message)
        running = false; isServiceRunning = false
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
        val raw = message ?: return
        // libbox adds ANSI colors even when writing to the in-app diagnostic log.
        // Strip them so saved logs are readable and can be filtered reliably.
        val line = raw.replace(Regex("\u001B\\[[;\\d]*m"), "").trim()
        if (line.isEmpty()) return

        val normalized = line.lowercase()
        // These per-flow TRACE/DEBUG events repeat for every short-lived socket and
        // bury actual startup, routing and error information in the diagnostic view.
        // Keep DNS queries, route decisions, warnings and errors; drop routine noise.
        val routineNoise = listOf(
            "connection upload closed",
            "connection download closed",
            "connection upload finished",
            "connection download finished",
            "packet upload closed",
            "packet download closed",
            "packet upload finished",
            "packet download finished",
            "router: found user id:",
            "dns: exchanged",
        ).any(normalized::contains)
        if (routineNoise && !normalized.contains("error") && !normalized.contains("warn")) {
            return
        }

        android.util.Log.d("NeoTUN", line)
        NeoTunDiagnostics.log(this, "libbox: " + line)
    }
    override fun connectSSHAgent(): Int = -1

    private fun stopWithError(message: String) {
        // Cancel any in-flight native startup before publishing the error state.
        startupGeneration++
        starting = false
        NeoTunDiagnostics.error(this, "sing-box stopped: " + message)
        running = false; isServiceRunning = false
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
        @Volatile var isEngineBusy: Boolean = false
        var isServiceRunning: Boolean = false
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
