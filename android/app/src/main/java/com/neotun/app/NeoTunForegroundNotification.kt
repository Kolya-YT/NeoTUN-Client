package com.neotun.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * Shared foreground notification and per-outbound counters.
 * Xray uses its local metrics endpoint; sing-box uses its loopback-only Clash API.
 */
internal object NeoTunForegroundNotification {
    const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "neotun-connection"
    private const val XRAY_METRICS_PORT = 49227
    private const val DEFAULT_SINGBOX_API_PORT = 49228
    private const val REQUEST_TIMEOUT_MS = 650
    private const val STALE_AFTER_MS = 3500L

    private data class Rates(
        val proxyUp: Long = 0L,
        val proxyDown: Long = 0L,
        val directUp: Long = 0L,
        val directDown: Long = 0L,
    )

    private data class Counters(
        val proxyUp: Long,
        val proxyDown: Long,
        val directUp: Long,
        val directDown: Long,
    )

    private data class ConnectionSample(
        val tag: String,
        val upload: Long,
        val download: Long,
    )

    private var currentRates = Rates()
    private var previousXrayCounters: Counters? = null
    private var previousSingBoxConnections: Map<String, ConnectionSample> = emptyMap()
    private var lastSuccessfulSampleAt = 0L

    @Synchronized
    fun startForeground(service: Service, engine: String, state: String, tunFd: Int) {
        ensureChannel(service)
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            build(service, engine, state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    /** Polls core statistics and updates the notification. Call from a worker thread. */
    @Synchronized
    fun tickAndUpdate(context: Context, engine: String, state: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastSuccessfulSampleAt != 0L && now - lastSuccessfulSampleAt > STALE_AFTER_MS) {
            currentRates = Rates()
            previousXrayCounters = null
            previousSingBoxConnections = emptyMap()
            lastSuccessfulSampleAt = 0L
        }
        val elapsedMs = if (lastSuccessfulSampleAt == 0L) 1000L
            else (now - lastSuccessfulSampleAt).coerceAtLeast(1L)
        val measured = when {
            engine.contains("xray", ignoreCase = true) -> readXrayRates(elapsedMs)
            else -> readSingBoxRates(context, elapsedMs)
        }
        if (measured != null) {
            currentRates = measured
            lastSuccessfulSampleAt = now
        } else if (lastSuccessfulSampleAt == 0L || now - lastSuccessfulSampleAt > STALE_AFTER_MS) {
            currentRates = Rates()
        }
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(context, engine, state))
    }

    /** Immediate state-only refresh; does not perform socket I/O on the main thread. */
    @Synchronized
    fun updateStatus(context: Context, engine: String, state: String) {
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(context, engine, state))
    }

    @Synchronized
    fun resetRates() {
        currentRates = Rates()
        previousXrayCounters = null
        previousSingBoxConnections = emptyMap()
        lastSuccessfulSampleAt = 0L
    }

    private fun build(context: Context, engine: String, state: String): Notification {
        val status = when (state) {
            "starting" -> engine + " запускается…"
            "stopping" -> "Соединение отключается…"
            "error" -> "Ошибка соединения"
            else -> engine + " • соединение активно"
        }
        val speeds = "proxy: " + formatRate(currentRates.proxyUp) + "↑ " +
            formatRate(currentRates.proxyDown) + "↓  direct: " +
            formatRate(currentRates.directUp) + "↑ " + formatRate(currentRates.directDown) + "↓"
        val enabled = context.getSharedPreferences("neotun_ui", Context.MODE_PRIVATE)
            .getBoolean("notifications_enabled", true)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NeoTUN")
            .setContentText(status + "\n" + speeds)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status + "\n" + speeds))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(if (enabled) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val enabled = context.getSharedPreferences("neotun_ui", Context.MODE_PRIVATE)
            .getBoolean("notifications_enabled", true)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "NeoTUN — состояние соединения",
                if (enabled) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN,
            ),
        )
    }

    private fun readXrayRates(elapsedMs: Long): Rates? {
        val body = readLoopback(XRAY_METRICS_PORT, "/debug/vars") ?: return null
        val vars = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val current = Counters(
            proxyUp = findCounter(vars, "outbound>>>proxy>>>traffic>>>uplink") ?: return null,
            proxyDown = findCounter(vars, "outbound>>>proxy>>>traffic>>>downlink") ?: return null,
            directUp = findCounter(vars, "outbound>>>direct>>>traffic>>>uplink") ?: 0L,
            directDown = findCounter(vars, "outbound>>>direct>>>traffic>>>downlink") ?: 0L,
        )
        val old = previousXrayCounters
        previousXrayCounters = current
        previousSingBoxConnections = emptyMap()
        if (old == null) return Rates()
        return Rates(
            proxyUp = toRate(positiveDelta(current.proxyUp, old.proxyUp), elapsedMs),
            proxyDown = toRate(positiveDelta(current.proxyDown, old.proxyDown), elapsedMs),
            directUp = toRate(positiveDelta(current.directUp, old.directUp), elapsedMs),
            directDown = toRate(positiveDelta(current.directDown, old.directDown), elapsedMs),
        )
    }

    private fun readSingBoxRates(context: Context, elapsedMs: Long): Rates? {
        val config = context.getSharedPreferences(NeoTunVpnService.PREFS, Context.MODE_PRIVATE)
            .getString(NeoTunVpnService.KEY_CONFIG, null) ?: return null
        val api = runCatching {
            JSONObject(config).optJSONObject("experimental")?.optJSONObject("clash_api")
        }.getOrNull() ?: return null
        val controller = api.optString("external_controller", "127.0.0.1:" + DEFAULT_SINGBOX_API_PORT)
        val port = loopbackPort(controller) ?: return null
        val secret = api.optString("secret").takeIf { it.isNotBlank() }
        val body = readLoopback(port, "/connections", secret) ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val connections = root.optJSONArray("connections") ?: JSONArray()
        val current = LinkedHashMap<String, ConnectionSample>()
        val oldConnections = previousSingBoxConnections
        var proxyUp = 0L
        var proxyDown = 0L
        var directUp = 0L
        var directDown = 0L

        for (i in 0 until connections.length()) {
            val connection = connections.optJSONObject(i) ?: continue
            val id = connection.optString("id").takeIf { it.isNotBlank() } ?: continue
            val chains = connection.optJSONArray("chains") ?: JSONArray()
            val tag = (0 until chains.length()).mapNotNull { n ->
                chains.optString(n).takeIf { it.isNotBlank() }
            }.lastOrNull() ?: continue
            val normalizedTag = tag.lowercase()
            if (normalizedTag.contains("block") || normalizedTag.contains("reject") ||
                normalizedTag.contains("dns")) continue

            val sample = ConnectionSample(
                tag = tag,
                upload = connection.optLong("upload", 0L).coerceAtLeast(0L),
                download = connection.optLong("download", 0L).coerceAtLeast(0L),
            )
            val old = oldConnections[id]
            if (old != null && old.tag == sample.tag) {
                val up = positiveDelta(sample.upload, old.upload)
                val down = positiveDelta(sample.download, old.download)
                if (normalizedTag.contains("direct")) {
                    directUp += up
                    directDown += down
                } else {
                    proxyUp += up
                    proxyDown += down
                }
            }
            current[id] = sample
        }
        previousSingBoxConnections = current
        previousXrayCounters = null
        return Rates(
            proxyUp = toRate(proxyUp, elapsedMs),
            proxyDown = toRate(proxyDown, elapsedMs),
            directUp = toRate(directUp, elapsedMs),
            directDown = toRate(directDown, elapsedMs),
        )
    }

    private fun readLoopback(port: Int, path: String, secret: String? = null): String? {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress("127.0.0.1", port), REQUEST_TIMEOUT_MS)
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val authorization = secret?.let { "Authorization: Bearer " + it + "\r\n" }.orEmpty()
            val request = "GET " + path + " HTTP/1.0\r\nHost: 127.0.0.1\r\n" +
                authorization + "Connection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val crlfSplit = response.indexOf("\r\n\r\n")
            val lfSplit = response.indexOf("\n\n")
            val split = if (crlfSplit >= 0) crlfSplit else lfSplit
            if (split < 0) return null
            val header = response.substring(0, split)
            if (!header.lineSequence().firstOrNull().orEmpty().contains(" 200 ")) return null
            val bodyStart = if (crlfSplit >= 0) crlfSplit + 4 else split + 2
            response.substring(bodyStart).trim().takeIf { it.startsWith("{") }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun loopbackPort(controller: String): Int? {
        val address = controller.trim()
        val host: String
        val portText: String
        if (address.startsWith("[")) {
            val end = address.indexOf(']')
            if (end < 0) return null
            host = address.substring(1, end)
            portText = address.substring(end + 1).removePrefix(":")
        } else {
            val idx = address.lastIndexOf(':')
            if (idx < 0) return null
            host = address.substring(0, idx)
            portText = address.substring(idx + 1)
        }
        if (host !in setOf("127.0.0.1", "localhost", "::1")) return null
        return portText.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun findCounter(value: JSONObject, name: String): Long? {
        if (value.has(name)) {
            val entry = value.opt(name)
            if (entry is Number) return entry.toLong().coerceAtLeast(0L)
            if (entry is JSONObject) {
                val v = entry.opt("value")
                if (v is Number) return v.toLong().coerceAtLeast(0L)
            }
        }
        val keys = value.keys()
        while (keys.hasNext()) {
            val child = value.optJSONObject(keys.next()) ?: continue
            findCounter(child, name)?.let { return it }
        }
        return null
    }

    private fun positiveDelta(now: Long, old: Long): Long =
        (now - old).takeIf { it >= 0L } ?: 0L

    private fun toRate(bytes: Long, elapsedMs: Long): Long =
        bytes.coerceAtLeast(0L) * 1000L / elapsedMs.coerceAtLeast(1L)

    private fun formatRate(bytesPerSecond: Long): String {
        val rate = bytesPerSecond.coerceAtLeast(0L).toDouble()
        return when {
            rate >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB/s", rate / (1024.0 * 1024.0))
            rate >= 1024.0 -> String.format(Locale.US, "%.1f KB/s", rate / 1024.0)
            else -> bytesPerSecond.coerceAtLeast(0L).toString() + " B/s"
        }
    }
}
