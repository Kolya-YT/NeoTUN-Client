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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject

class NeoTunXrayVpnService : VpnService() {
    private var tunFd: Int = -1
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY

        startForegroundNotification()

        val uri = intent?.getStringExtra(EXTRA_URI)
            ?: getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
                .getString(NeoTunVpnService.KEY_URI, null)

        if (uri.isNullOrBlank()) {
            stopWithError("Нет VLESS-профиля")
            return START_NOT_STICKY
        }

        runCatching {
            startXray(uri)
            getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
                .edit()
                .putString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_XRAY)
                .remove(NeoTunVpnService.KEY_ERROR)
                .apply()
            running = true
        }.onFailure {
            stopWithError(it.message ?: "Не удалось запустить Xray")
        }

        return START_NOT_STICKY
    }

    private fun startXray(uri: String) {
        NeoTunXrayBridge.nativeInit(this)

        val dns = getSystemService(ConnectivityManager::class.java)
            .getLinkProperties(getSystemService(ConnectivityManager::class.java).activeNetwork)
            ?.dnsServers
            ?.firstOrNull()
            ?.hostAddress
            ?: "1.1.1.1"

        val dnsEndpoint = if (dns.contains(":")) "[$dns]:53" else "$dns:53"
        val dnsError = runCatching {
            NeoTunXrayBridge.nativePrepare(dnsEndpoint)
        }.getOrNull()
        if (!dnsError.isNullOrBlank()) {
            throw IllegalStateException("Не удалось настроить DNS Xray: $dnsError")
        }

        val vpnInterface = Builder()
            .setSession("NeoTUN Xray")
            .setMtu(1500)
            .setMetered(false)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(dns)
            .establish()
            ?: error("Не удалось создать Android TUN")

        tunFd = vpnInterface.detachFd()

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

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put(
                "env",
                JSONObject().put("xray.tun.fd", tunFd.toString()),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("port", 0)
                        .put("protocol", "tun")
                        .put(
                            "settings",
                            JSONObject()
                                .put("mtu", 1500)
                                .put("gateway", JSONArray().put("172.19.0.1/30")),
                        ),
                ),
            )
            .put("outbounds", outbounds)

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
        if (!test.optBoolean("success", false)) {
            throw IllegalArgumentException(
                "Некорректная Xray-конфигурация: " +
                    test.optString("error", "неизвестная ошибка"),
            )
        }

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
        if (!run.optBoolean("success", false)) {
            throw IllegalStateException(
                "Xray не запустился: " +
                    run.optString("error", "неизвестная ошибка"),
            )
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
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
            runCatching { android.system.Os.close(tunFd) }
            tunFd = -1
        }

        running = false
        super.onDestroy()
    }

    private fun stopWithError(message: String) {
        running = false
        getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
            .edit()
            .putString(NeoTunVpnService.KEY_ERROR, message)
            .apply()

        if (tunFd >= 0) {
            runCatching { android.system.Os.close(tunFd) }
            tunFd = -1
        }
        NeoTunXrayBridge.nativeResetDns()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundNotification() {
        val channelId = "neotun-xray"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "NeoTUN Xray",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NeoTUN")
            .setContentText("Xray + XHTTP запускается…")
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
        ServiceCompat.startForeground(this, 2, notification, type)
    }

    companion object {
        const val EXTRA_URI = "neotun.xray.uri"
    }
}
