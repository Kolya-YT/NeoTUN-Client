package com.neotun.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NeoTunXrayVpnService : VpnService() {
    private var tunFd: Int = -1
    private var running = false
    @Volatile private var starting = false
    @Volatile private var destroyed = false
    @Volatile private var startupGeneration = 0
    @Volatile private var teardownInProgress = false
    @Volatile private var nativeCleanupComplete = false
    private val pendingSwitchStopTokens = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var xrayAccessLog: String = ""
    private var xrayErrorLog: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            intent.getStringExtra(NeoTunVpnService.EXTRA_SWITCH_TOKEN)?.takeIf { it.isNotBlank() }?.let {
                pendingSwitchStopTokens.add(it)
            }
            startupGeneration++
            starting = false
            // ACTION_DISCONNECT may instantiate an otherwise idle service.
            // Keep Xray marked busy until native cleanup has completed, or the
            // switch waiter can start sing-box/Xray while stopTunnel is running.
            isEngineBusy = true
            if (teardownInProgress) {
                NeoTunDiagnostics.log(this, "Xray disconnect already in progress; ignoring duplicate request")
                return START_NOT_STICKY
            }
            teardownInProgress = true
            mainHandler.removeCallbacksAndMessages(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            // Xray native stop can block while releasing its TUN and DNS state.
            // Keep the service alive until the worker completes so a profile switch
            // cannot start another VPN service concurrently.
            Thread({
                var stopSucceeded = false
                try {
                    stopTunnel()
                    nativeCleanupComplete = true
                    isEngineBusy = false
                    stopSucceeded = true
                    NeoTunDiagnostics.log(this, "Xray native teardown completed")
                } catch (error: Throwable) {
                    NeoTunDiagnostics.error(this, "Xray teardown failed", error)
                } finally {
                    pendingSwitchStopTokens.toList().forEach { token ->
                        sendBroadcast(
                            Intent(NeoTunVpnService.ACTION_ENGINE_STOPPED)
                                .setPackage(packageName)
                                .putExtra(NeoTunVpnService.EXTRA_SWITCH_TOKEN, token)
                                .putExtra(NeoTunVpnService.EXTRA_ENGINE_NAME, "xray")
                                .putExtra(NeoTunVpnService.EXTRA_STOP_SUCCESS, stopSucceeded),
                        )
                        pendingSwitchStopTokens.remove(token)
                    }
                    mainHandler.post {
                        teardownInProgress = false
                        // Newer duplicate start commands may advance startId.
                        // stopSelf(startId) would then leave the service alive.
                        stopSelf()
                    }
                }
            }, "NeoTUN-xray-stop").start()
            return START_NOT_STICKY
        }
        if (teardownInProgress || running || starting) return START_NOT_STICKY
        destroyed = false
        nativeCleanupComplete = false
        isEngineBusy = true
        val generation = ++startupGeneration
        starting = true

        NeoTunDiagnostics.clear(this)
        NeoTunDiagnostics.log(this, "Xray service: onStartCommand")
        try {
            startForegroundNotification()
        } catch (error: Throwable) {
            // If Android rejects foreground promotion, an uncaught exception here
            // kills the isolated :xray process before the normal startup diagnostics
            // and error state are published.
            NeoTunDiagnostics.error(this, "Xray foreground promotion failed", error)
            stopWithError(
                "Не удалось запустить службу Xray: " +
                    (error.message ?: error.javaClass.simpleName ?: "ошибка foreground-службы")
            )
            return START_NOT_STICKY
        }

        val uri = intent?.getStringExtra(EXTRA_URI)
            ?: getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
                .getString(NeoTunVpnService.KEY_URI, null)

        if (uri.isNullOrBlank()) {
            stopWithError("Нет VLESS-профиля")
            return START_NOT_STICKY
        }

        NeoTunDiagnostics.log(this, "Профиль получен: VLESS-ссылка (секретные параметры скрыты)")

        // Network I/O must not run on Android main thread.
        Thread({
            try {
                val routing = RoutingProfileStore(this).active()
                val geoDir = java.io.File(filesDir, "geodata")
                NeoTunDiagnostics.log(this, "GeoData: проверка файлов в фоновом потоке")
                val geo = NeoTunGeoData.ensure(this, geoDir, routing?.json)
                NeoTunDiagnostics.log(this, "GeoData: geoip=" + geo.geoIpBytes +
                    " bytes, geosite=" + geo.geoSiteBytes + " bytes, updated=" + geo.updated)
                if (generation != startupGeneration) {
                    NeoTunDiagnostics.log(this, "Xray startup cancelled after GeoData preparation")
                    return@Thread
                }
                mainHandler.post {
                    if (destroyed || !starting || generation != startupGeneration) return@post
                    try {
                        startXray(uri, geoDir.absolutePath)
                        getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_XRAY)
                            .remove(NeoTunVpnService.KEY_ERROR)
                            .putBoolean(NeoTunVpnService.KEY_RUNNING, true)
                            .apply()
                        running = true
                        starting = false
                    } catch (t: Throwable) {
                        // A synchronous native start can fail after the user has
                        // already requested disconnect or selected another engine.
                        // Do not let that stale failure stop or overwrite the new run.
                        if (destroyed || generation != startupGeneration) {
                            NeoTunDiagnostics.log(this, "Ignoring stale Xray startup failure after lifecycle changed")
                            return@post
                        }
                        NeoTunDiagnostics.error(this, "Критическая ошибка запуска Xray", t)
                        if (isGeoDataIntegrityError(t)) {
                            NeoTunDiagnostics.log(this, "Обнаружена ошибка чтения GeoIP/GeoSite. Обновляю геобазы и повторяю запуск один раз.")
                            Thread({
                                try {
                                    val refreshed = NeoTunGeoData.ensure(
                                        this,
                                        geoDir,
                                        routing?.json,
                                        forceRefresh = true,
                                    )
                                    NeoTunDiagnostics.log(this, "GeoData после обновления: geoip=" +
                                        refreshed.geoIpBytes + " bytes, geosite=" + refreshed.geoSiteBytes)
                                    mainHandler.post {
                                        if (destroyed || !starting || generation != startupGeneration) return@post
                                        try {
                                            startXray(uri, geoDir.absolutePath)
                                            getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
                                                .edit()
                                                .putString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_XRAY)
                                                .remove(NeoTunVpnService.KEY_ERROR)
                                                .putBoolean(NeoTunVpnService.KEY_RUNNING, true)
                                                .apply()
                                            running = true
                                            starting = false
                                        } catch (retryError: Throwable) {
                                            if (destroyed || generation != startupGeneration) {
                                                NeoTunDiagnostics.log(this, "Ignoring stale Xray retry failure after lifecycle changed")
                                                return@post
                                            }
                                            NeoTunDiagnostics.error(this, "Повторный запуск Xray после обновления GeoData не удался", retryError)
                                            stopWithError(retryError.message ?: "Xray не запустился после обновления геобаз")
                                        }
                                    }
                                } catch (refreshError: Throwable) {
                                    NeoTunDiagnostics.error(this, "Не удалось обновить повреждённые геобазы", refreshError)
                                    mainHandler.post {
                                        if (!destroyed && generation == startupGeneration) stopWithError(
                                            "Ошибка GeoIP/GeoSite. Обновление не удалось: " +
                                                (refreshError.message ?: "проверьте URL геобаз и интернет")
                                        )
                                    }
                                }
                            }, "NeoTUN-GeoData-Retry").start()
                        } else {
                            stopWithError(t.message ?: "Не удалось запустить Xray")
                        }
                    }
                }
            } catch (t: Throwable) {
                NeoTunDiagnostics.error(this, "Критическая ошибка подготовки GeoData", t)
                mainHandler.post {
                    if (!destroyed && generation == startupGeneration) {
                        stopWithError(t.message ?: "Не удалось подготовить геобазы")
                    }
                }
            }
        }, "NeoTUN-GeoData").start()

        return START_NOT_STICKY
    }

    private fun isGeoDataIntegrityError(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return (message.contains("geoip.dat") || message.contains("geosite.dat")) &&
            (message.contains("eof") || message.contains("failed to check code") ||
                message.contains("invalid field rule"))
    }

    private fun startXray(uri: String, geoDataPath: String) {
        NeoTunDiagnostics.log(this, "Этап 1/8: инициализация JNI/Xray")
        NeoTunXrayBridge.nativeInit(this)
        NeoTunXrayBridge.nativeSetAssetPath(geoDataPath)
        NeoTunDiagnostics.log(this, "JNI/Xray bridge инициализирован; XRAY_LOCATION_ASSET=" + geoDataPath)

        val connectivity = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val systemDns = connectivity.getLinkProperties(activeNetwork)
            ?.dnsServers
            ?.firstOrNull()
            ?.hostAddress
            ?: "1.1.1.1"

        // Android may expose a link-local Wi-Fi DNS (fe80::/10). Do not put
        // that scoped address into the VPN. Use a stable public IPv4 resolver
        // for captured app DNS; Xray DNS is protected separately below.
        val uiPrefs = getSharedPreferences("neotun_ui", MODE_PRIVATE)
        val dnsMode = uiPrefs.getString("dns_mode", "Автоматический") ?: "Автоматический"
        val vpnDns = when {
            dnsMode.contains("1.1.1.1") -> "1.1.1.1"
            dnsMode.contains("8.8.8.8") -> "8.8.8.8"
            dnsMode.contains("9.9.9.9") -> "9.9.9.9"
            else -> systemDns.takeIf { !it.contains(":") } ?: "1.1.1.1"
        }
        val dnsEndpoint = vpnDns + ":53"
        val mtu = uiPrefs.getInt("mtu", 1500).coerceIn(1280, 1500)
        val ipv6 = uiPrefs.getBoolean("ipv6_enabled", false)
        NeoTunDiagnostics.log(
            this,
            "Этап 2/8: системный DNS=$systemDns, VPN DNS=$vpnDns, Xray DNS endpoint=$dnsEndpoint"
        )

        NeoTunDiagnostics.log(this, "Этап 3/8: создание Android VpnService TUN")
        val vpnBuilder = Builder()
            .setMtu(mtu)
            .setMetered(false)
            .setBlocking(true)
            // Keep NeoTUN/Xray process sockets outside its own VPN. The Xray
            // socket controller also calls VpnService.protect() as a second guard.
            .addDisallowedApplication(packageName)
            .apply {
                val excludedApps = uiPrefs.getStringSet("excluded_apps", emptySet()).orEmpty()
                excludedApps.asSequence().filter { it.isNotBlank() && it != packageName }.forEach { excludedPackage ->
                    runCatching { addDisallowedApplication(excludedPackage) }
                        .onFailure { NeoTunDiagnostics.log(this@NeoTunXrayVpnService,
                            "Xray: cannot exclude package $excludedPackage: ${it.message}") }
                }
                if (excludedApps.isNotEmpty()) {
                    NeoTunDiagnostics.log(this@NeoTunXrayVpnService,
                        "Xray: app exclusions requested=" + excludedApps.size)
                }
            }
            .addAddress("172.19.0.1", 30)
            .apply {
                if (ipv6) {
                    addAddress("fdfe:dcba:9876::1", 126)
                    addRoute("::", 0)
                }
            }
            // Start with IPv4-only routing when IPv6 is disabled. Xray receives the Android TUN fd;
            // enabling a parallel IPv6 default route here can blackhole IPv6-first
            // Android connections until IPv6 handling is implemented end-to-end.
            .addRoute("0.0.0.0", 0)
            .addDnsServer(vpnDns)

        setUnderlyingNetworks(activeNetwork?.let { network -> arrayOf(network) })
        val vpnInterface = vpnBuilder.establish()
            ?: error("Не удалось создать Android TUN")

        tunFd = vpnInterface.detachFd()
        NeoTunDiagnostics.log(this, "TUN создан, fd=$tunFd, MTU=$mtu, route=IPv4" + if (ipv6) "+IPv6" else "")

        // The VPN must already be established before VpnService.protect() can
        // protect Xray outbound sockets and the Go DNS resolver.
        val dnsError = runCatching {
            NeoTunXrayBridge.nativePrepare(dnsEndpoint)
        }.fold(
            onSuccess = { it },
            onFailure = { "JNI exception: " + (it.message ?: it::class.java.simpleName) },
        )
        if (!dnsError.isNullOrBlank()) {
            NeoTunDiagnostics.log(this, "DNS ERROR: $dnsError")
            throw IllegalStateException("Не удалось настроить DNS Xray: $dnsError")
        }
        NeoTunDiagnostics.log(this, "DNS Xray настроен и защищён от TUN")

        NeoTunDiagnostics.log(this, "Этап 4/8: разбор VLESS через libXray")
        val converted = NeoTunXrayBridge.nativeInvoke(
            JSONObject()
                .put("apiVersion", 3)
                .put("method", "convertShareLinksToXrayJson")
                .put(
                    "payload",
                    JSONObject().put("text", uri),
                )
                .toString(),
        )

        val convertedResponse = JSONObject(converted)
        NeoTunDiagnostics.log(
            this,
            "convertShareLinksToXrayJson: success=" +
                convertedResponse.optBoolean("success", false)
        )
        if (!convertedResponse.optBoolean("success", false)) {
            throw IllegalArgumentException(
                "Xray не смог разобрать VLESS: " +
                    convertedResponse.optString("error", "неизвестная ошибка"),
            )
        }

        val data = convertedResponse.optJSONObject("data")
            ?: throw IllegalArgumentException("Xray parser не вернул outbound")
        val outbounds = data.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Xray parser не вернул outbounds")
        if (outbounds.length() == 0) {
            throw IllegalArgumentException("VLESS-ссылка не содержит рабочего outbound")
        }
        val firstOutbound = outbounds.optJSONObject(0)
            ?: throw IllegalArgumentException("Xray parser вернул пустой outbound")
        firstOutbound.put("tag", "proxy")
        val streamForLog = firstOutbound.optJSONObject("streamSettings")

        NeoTunDiagnostics.log(
            this,
            "Outbound: protocol=" + firstOutbound.optString("protocol", "?") +
                ", network=" + streamForLog?.optString("network", "?") +
                ", security=" + streamForLog?.optString("security", "?")
        )

        // XHTTP + REALITY: Xray resolves mode=auto to stream-one. Force the
        // effective value explicitly so the generated config cannot regress
        // when the share-link converter or Xray defaults change.
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            val streamSettings = outbound.optJSONObject("streamSettings") ?: continue
            if (streamSettings.optString("network").equals("xhttp", ignoreCase = true) &&
                streamSettings.optString("security").equals("reality", ignoreCase = true)
            ) {
                val xhttpSettings = streamSettings.optJSONObject("xhttpSettings")
                    ?: JSONObject().also { streamSettings.put("xhttpSettings", it) }
                val mode = xhttpSettings.optString("mode", "auto")
                if (mode.isBlank() || mode.equals("auto", ignoreCase = true)) {
                    xhttpSettings.put("mode", "stream-one")
                    NeoTunDiagnostics.log(this, "XHTTP REALITY: mode auto -> stream-one")
                } else {
                    NeoTunDiagnostics.log(this, "XHTTP REALITY: mode=" + mode)
                }
            }
        }

        val routingProfile = RoutingProfileStore(this).active()
        val hasDirect = (0 until outbounds.length()).any {
            outbounds.optJSONObject(it)?.optString("tag") == "direct"
        }
        val hasBlock = (0 until outbounds.length()).any {
            outbounds.optJSONObject(it)?.optString("tag") == "block"
        }
        if (routingProfile != null && !hasDirect) {
            outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        }
        if (routingProfile != null && !hasBlock) {
            outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        }
        val routingRules = JSONArray()
        if (routingProfile != null) {
            val profileRules = NeoTunRoutingAdapter.xrayRules(routingProfile)
            for (index in 0 until profileRules.length()) {
                routingRules.put(profileRules.getJSONObject(index).put("inboundTag", JSONArray().put("tun")))
            }
        }
        // Catch-all comes last so explicit Direct/Proxy/Block rules get a chance to match.
        routingRules.put(
            JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put("tun"))
                .put("outboundTag", if (routingProfile?.globalProxy == false) "direct" else "proxy"),
        )
        // A running Xray instance does not prove packets are flowing. Expose
        // Xray's own traffic counters on loopback so diagnostics can distinguish
        // "core alive" from "TUN/outbound actually carrying traffic".
        val accessLogFile = java.io.File(filesDir, "xray-access.log")
        val errorLogFile = java.io.File(filesDir, "xray-error.log")
        xrayAccessLog = accessLogFile.absolutePath
        xrayErrorLog = errorLogFile.absolutePath
        // Start each session with fresh engine logs. Otherwise a failed current
        // connection can be diagnosed from a previous session's REALITY/transport
        // errors, which is misleading when users switch profiles or engines.
        runCatching { accessLogFile.writeText("", Charsets.UTF_8) }
            .onFailure { NeoTunDiagnostics.error(this, "Не удалось очистить предыдущий Xray access log", it) }
        runCatching { errorLogFile.writeText("", Charsets.UTF_8) }
            .onFailure { NeoTunDiagnostics.error(this, "Не удалось очистить предыдущий Xray error log", it) }
        NeoTunDiagnostics.log(this, "Xray session logs prepared (old session contents cleared where possible)")
        // "running=true" only confirms lifecycle state. Keep Xray's own error and
        // access logs so transport/TLS/REALITY/dial failures are visible on-device.
        val config = JSONObject()
            .put(
                "log",
                JSONObject()
                    .put("loglevel", "info")
                    .put("access", xrayAccessLog)
                    .put("error", xrayErrorLog),
            )
            .put("metrics", JSONObject().put("listen", "127.0.0.1:49227"))
            .put(
                "policy",
                JSONObject().put(
                    "system",
                    JSONObject()
                        .put("statsInboundDownlink", true)
                        .put("statsInboundUplink", true)
                        .put("statsOutboundDownlink", true)
                        .put("statsOutboundUplink", true),
                ),
            )
            .put("stats", JSONObject())
            .put(
                "env",
                JSONObject().put("xray.tun.fd", tunFd.toString()),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "tun")
                        .put("protocol", "tun")
                        .put(
                            "settings",
                            JSONObject()
                                // Android already owns the TUN through VpnService and
                                // passes its fd via xray.tun.fd. The name belongs to
                                // TUN settings (not the inbound object).
                                .put("name", "neotun")
                                .put("mtu", mtu),
                        ),
                ),
            )
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("rules", routingRules))

        NeoTunDiagnostics.log(this, "Этап 6/8: проверка Xray-конфигурации testXray")
        val testConfig = JSONObject().put("outbounds", outbounds)
        val testResponse = NeoTunXrayBridge.nativeInvoke(
            JSONObject()
                .put("apiVersion", 3)
                .put("method", "testXray")
                .put(
                    "payload",
                    JSONObject().put("xrayJson", testConfig.toString()),
                )
                .toString(),
        )
        val test = JSONObject(testResponse)
        NeoTunDiagnostics.log(
            this,
            "testXray: success=" + test.optBoolean("success", false) +
                if (test.optBoolean("success", false)) "" else ", error=" + test.optString("error", "unknown")
        )
        if (!test.optBoolean("success", false)) {
            throw IllegalArgumentException(
                "Некорректная Xray-конфигурация: " +
                    test.optString("error", "неизвестная ошибка"),
            )
        }

        NeoTunDiagnostics.log(this, "Xray routing: profile=" + (routingProfile?.name ?: "default") +
            ", globalProxy=" + (routingProfile?.globalProxy ?: true) + ", rules=" + routingRules.length())
        NeoTunDiagnostics.log(this, "Этап 7/8: запуск Xray instance, TUN fd=$tunFd")
        val runResponse = NeoTunXrayBridge.nativeInvoke(
            JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put(
                    "payload",
                    JSONObject().put("xrayJson", config.toString()),
                )
                .toString(),
        )
        val run = JSONObject(runResponse)
        NeoTunDiagnostics.log(
            this,
            "runXray: success=" + run.optBoolean("success", false) +
                if (run.optBoolean("success", false)) "" else ", error=" + run.optString("error", "unknown")
        )
        if (!run.optBoolean("success", false)) {
            throw IllegalStateException(
                "Xray не запустился: " +
                    run.optString("error", "неизвестная ошибка"),
            )
        }

        NeoTunDiagnostics.log(this, "Этап 8/8: проверка фактического состояния Xray")
        val stateResponse = NeoTunXrayBridge.nativeInvoke(
            JSONObject()
                .put("apiVersion", 3)
                .put("method", "getXrayState")
                .put("payload", JSONObject())
                .toString(),
        )
        val state = JSONObject(stateResponse)
        val runningState = state.optJSONObject("data")?.optBoolean("running", false) ?: false
        NeoTunDiagnostics.log(
            this,
            "getXrayState: success=" + state.optBoolean("success", false) +
                ", running=" + runningState
        )
        if (!state.optBoolean("success", false) || !runningState) {
            throw IllegalStateException(
                "Xray не перешёл в состояние running: " +
                    state.optString("error", "running=false"),
            )
        }
        NeoTunDiagnostics.log(this, "Xray instance running; this confirms process state only, not traffic flow.")
        scheduleXrayTrafficDiagnostics()
    }

    private fun scheduleXrayTrafficDiagnostics() {
        // Android blocks cleartext HTTP by default. Read the local Xray metrics
        // endpoint with a raw loopback socket so this diagnostic doesn't depend
        // on app-wide cleartext network-security settings.
        mainHandler.postDelayed({
            if (destroyed || !running || tunFd < 0) return@postDelayed
            Thread({
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 49227), 1500)
                    socket.soTimeout = 1500
                    socket.getOutputStream().write(
                        "GET /debug/vars HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    )
                    val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                    socket.close()
                    val body = response.substringAfter("\r\n\r\n", response.substringAfter("\n\n", ""))
                    val counters = body.lineSequence()
                        .filter { it.contains("inbound>>>tun>>>traffic") || it.contains("outbound>>>proxy>>>traffic") }
                        .take(12)
                        .joinToString("; ")
                    NeoTunDiagnostics.log(
                        this,
                        if (counters.isBlank()) {
                            "Xray traffic metrics: endpoint answered, but no TUN/proxy counters were exposed. Response: " +
                                response.take(180).replace("\n", " ")
                        } else {
                            "Xray traffic metrics: $counters"
                        },
                    )
                    logXrayFileTail("error", xrayErrorLog)
                    logXrayFileTail("access", xrayAccessLog)
                } catch (error: Throwable) {
                    NeoTunDiagnostics.error(this, "Xray traffic metrics unavailable", error)
                }
            }, "NeoTUN-Xray-Traffic-Metrics").start()
        }, 8000L)
    }

    private fun logXrayFileTail(label: String, path: String) {
        try {
            val file = java.io.File(path)
            if (!file.isFile || file.length() == 0L) {
                NeoTunDiagnostics.log(this, "Xray $label log: empty")
                return
            }
            val lines = file.readLines(Charsets.UTF_8).takeLast(20)
            lines.forEach { line ->
                // Do not dump config or share-link values into diagnostics.
                NeoTunDiagnostics.log(this, "Xray $label: " + line.take(1200))
            }
        } catch (error: Throwable) {
            NeoTunDiagnostics.error(this, "Cannot read Xray $label log", error)
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        destroyed = true
        startupGeneration++
        starting = false
        mainHandler.removeCallbacksAndMessages(null)
        NeoTunDiagnostics.log(this, "Xray service: onDestroy")
        // Expected disconnect/error paths already completed native cleanup on a
        // worker. Repeating native reset from onDestroy would run it on the main
        // thread and can stall the profile switch or trigger an ANR.
        if (!nativeCleanupComplete) {
            runCatching { stopTunnel() }
                .onFailure { NeoTunDiagnostics.error(this, "Xray onDestroy cleanup failed", it) }
        }
        isEngineBusy = false
        super.onDestroy()
    }

    private fun stopTunnel() {
        NeoTunDiagnostics.log(this, "Xray teardown: running=$running, tunFd=$tunFd")
        if (running || tunFd >= 0) {
            runCatching {
                NeoTunXrayBridge.nativeInvoke(
                    JSONObject()
                        .put("apiVersion", 3)
                        .put("method", "stopXray")
                        .put("payload", JSONObject())
                        .toString(),
                )
            }
        }
        NeoTunXrayBridge.nativeResetDns()

        if (tunFd >= 0) {
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
            tunFd = -1
        }

        runCatching { NeoTunXrayBridge.nativeInit(null) }

        running = false
        starting = false
        // Only Xray may clear the shared running flag while Xray is the
        // selected engine. A late stop from an old Xray instance must not
        // mark a newer sing-box session offline.
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        if (prefs.getString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_SING_BOX)
                == NeoTunVpnService.ENGINE_XRAY) {
            prefs.edit().putBoolean(NeoTunVpnService.KEY_RUNNING, false).apply()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun stopWithError(message: String) {
        // Invalidate every outstanding GeoData/start/retry callback before publishing
        // the failure. Native shutdown is kept off the main thread just like disconnect.
        startupGeneration++
        NeoTunDiagnostics.error(this, "Остановка с ошибкой: $message")
        running = false
        starting = false
        if (teardownInProgress) {
            NeoTunDiagnostics.log(this, "Xray error cleanup skipped because teardown is already in progress")
            return
        }
        teardownInProgress = true
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        if (prefs.getString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_SING_BOX)
                == NeoTunVpnService.ENGINE_XRAY) {
            prefs.edit().putString(NeoTunVpnService.KEY_ERROR, message)
                .putBoolean(NeoTunVpnService.KEY_RUNNING, false).apply()
        }

        Thread({
            try {
                // Stop Xray before releasing its TUN fd.
                runCatching {
                    NeoTunXrayBridge.nativeInvoke(
                        JSONObject()
                            .put("apiVersion", 3)
                            .put("method", "stopXray")
                            .put("payload", JSONObject())
                            .toString(),
                    )
                }
                if (tunFd >= 0) {
                    runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
                    tunFd = -1
                }
                runCatching { NeoTunXrayBridge.nativeResetDns() }
                runCatching { NeoTunXrayBridge.nativeInit(null) }
                nativeCleanupComplete = true
            } catch (error: Throwable) {
                NeoTunDiagnostics.error(this, "Xray error cleanup failed", error)
            } finally {
                // A profile switch may request disconnect while startup-error cleanup
                // is already running. Drain queued cross-process acknowledgements here
                // too, otherwise MainActivity waits until timeout although cleanup ended.
                isEngineBusy = false
                pendingSwitchStopTokens.toList().forEach { token ->
                    sendBroadcast(
                        Intent(NeoTunVpnService.ACTION_ENGINE_STOPPED)
                            .setPackage(packageName)
                            .putExtra(NeoTunVpnService.EXTRA_SWITCH_TOKEN, token)
                            .putExtra(NeoTunVpnService.EXTRA_ENGINE_NAME, "xray")
                            .putExtra(NeoTunVpnService.EXTRA_STOP_SUCCESS, nativeCleanupComplete),
                    )
                    pendingSwitchStopTokens.remove(token)
                }
                mainHandler.post {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    teardownInProgress = false
                    stopSelf()
                }
            }
        }, "NeoTUN-xray-error-stop").start()
    }

    private fun startForegroundNotification() {
        val channelId = "neotun-xray"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val enabled = getSharedPreferences("neotun_ui", MODE_PRIVATE)
                .getBoolean("notifications_enabled", true)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "NeoTUN Xray",
                    if (enabled) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NeoTUN")
            .setContentText("Xray + XHTTP запускается…")
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
        ServiceCompat.startForeground(this, 2, notification, type)
    }

    companion object {
        @Volatile var isEngineBusy: Boolean = false
        const val EXTRA_URI = "neotun.xray.uri"
        const val ACTION_DISCONNECT = "com.neotun.app.action.DISCONNECT_XRAY"
    }
}
