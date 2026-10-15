package com.neotun.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.NetworkInterface
import java.util.Locale

/**
 * Shared foreground notification and traffic-rate sampler for NeoTUN's engines.
 * Android does not expose an exact per-app "direct" counter on every device; the
 * direct value is estimated as app-UID counters minus readable TUN interface counters.
 */
internal object NeoTunForegroundNotification {
    const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "neotun-connection"
    private const val STALE_AFTER_MS = 3500L

    private data class Sample(
        val tunRx: Long?,
        val tunTx: Long?,
        val uidRx: Long?,
        val uidTx: Long?,
        val at: Long,
    )

    private var previous: Sample? = null

    @Synchronized
    fun startForeground(service: Service, engine: String, state: String, tunFd: Int) {
        val notification = build(service, engine, state, tunFd)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, type)
    }

    @Synchronized
    fun update(context: Context, engine: String, state: String, tunFd: Int) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(context, engine, state, tunFd))
    }

    @Synchronized
    fun resetRates() {
        previous = null
    }

    private fun build(context: Context, engine: String, state: String, tunFd: Int): Notification {
        ensureChannel(context)
        val (proxyRate, directRate) = sampleRates(context)
        val status = when (state) {
            "starting" -> "$engine запускается…"
            "stopping" -> "Соединение отключается…"
            "error" -> "Ошибка соединения"
            else -> "$engine • соединение активно"
        }
        val speeds = "proxy: ${formatRate(proxyRate)}↑ ${formatRate(proxyRate)}↓  direct: ${formatRate(directRate)}"
        val enabled = context.getSharedPreferences("neotun_ui", Context.MODE_PRIVATE)
            .getBoolean("notifications_enabled", true)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NeoTUN")
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$speeds"))
            .setSubText(speeds)
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

    private fun sampleRates(context: Context): Pair<Long, Long> {
        val now = android.os.SystemClock.elapsedRealtime()
        val tun = readTunCounters(context)
        val uidRx = TrafficStats.getUidRxBytes(Process.myUid()).takeIf { it >= 0L }
        val uidTx = TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it >= 0L }
        val current = Sample(tun?.first, tun?.second, uidRx, uidTx, now)
        val old = previous
        previous = current
        if (old == null || now <= old.at || now - old.at > STALE_AFTER_MS) return 0L to 0L
        val elapsed = (now - old.at).coerceAtLeast(1L)
        val proxyBytes = delta(current.tunRx, old.tunRx) + delta(current.tunTx, old.tunTx)
        val uidBytes = delta(current.uidRx, old.uidRx) + delta(current.uidTx, old.uidTx)
        // Direct traffic is only an estimate; clamp at zero on devices where the
        // UID counter includes TUN sockets or interface counters are partial.
        val directBytes = (uidBytes - proxyBytes).coerceAtLeast(0L)
        return proxyBytes * 1000L / elapsed to directBytes * 1000L / elapsed
    }

    private fun readTunCounters(context: Context): Pair<Long, Long>? {
        val names = mutableListOf<String>()
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.forEach { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@forEach
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    cm.getLinkProperties(network)?.interfaceName?.let(names::add)
                }
            }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.map { it.name }
                ?.filter { it.matches(Regex("(tun|utun|wg|vpn|tap)\\d*")) }?.let(names::addAll)
        }
        runCatching {
            java.io.File("/sys/class/net").listFiles()?.map { it.name }
                ?.filter { it.matches(Regex("(tun|utun|wg|vpn|tap)\\d*")) }?.let(names::addAll)
        }
        runCatching {
            java.io.File("/proc/net/dev").useLines { lines ->
                lines.drop(2).mapNotNull { it.substringBefore(":").trim().takeIf { name ->
                    name.matches(Regex("(tun|utun|wg|vpn|tap)\\d*"))
                } }.forEach(names::add)
            }
        }
        for (name in names.distinct()) {
            val sysfs = runCatching {
                val dir = java.io.File("/sys/class/net/$name/statistics")
                java.io.File(dir, "rx_bytes").readText().trim().toLong() to
                    java.io.File(dir, "tx_bytes").readText().trim().toLong()
            }.getOrNull()
            if (sysfs != null) return sysfs
            val proc = runCatching {
                java.io.File("/proc/net/dev").useLines { lines ->
                    lines.drop(2).firstNotNullOfOrNull { line ->
                        if (line.substringBefore(":").trim() != name) return@firstNotNullOfOrNull null
                        val values = line.substringAfter(":").trim().split(Regex("\\s+"))
                        if (values.size >= 9) values[0].toLong() to values[8].toLong() else null
                    }
                }
            }.getOrNull()
            if (proc != null) return proc
        }
        return null
    }

    private fun delta(now: Long?, old: Long?): Long =
        if (now == null || old == null) 0L else (now - old).coerceAtLeast(0L)

    private fun formatRate(bytesPerSecond: Long): String {
        val rate = bytesPerSecond.coerceAtLeast(0L).toDouble()
        return when {
            rate >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.1f MB/s", rate / (1024.0 * 1024.0))
            rate >= 1024.0 -> String.format(Locale.US, "%.1f KB/s", rate / 1024.0)
            else -> "${bytesPerSecond.coerceAtLeast(0L)} B/s"
        }
    }
}
