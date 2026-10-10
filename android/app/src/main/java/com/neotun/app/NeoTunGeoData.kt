package com.neotun.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class NeoTunGeoDataResult(
    val geoIpBytes: Long,
    val geoSiteBytes: Long,
    val updated: Boolean,
)

/** Downloads Xray routing databases into app-private storage and refreshes them daily. */
object NeoTunGeoData {
    private const val TAG = "NeoTUN"
    private const val MAX_BYTES = 100L * 1024L * 1024L
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private const val PREFS = "neotun_geodata"
    private const val GEOIP = "geoip.dat"
    private const val GEOSITE = "geosite.dat"

    private const val DEFAULT_GEOIP_URL =
        "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geoip/release/geoip.dat"
    private const val DEFAULT_GEOSITE_URL =
        "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geosite/release/geosite.dat"

    @Synchronized
    fun ensure(context: Context, directory: File, profile: JSONObject?, forceRefresh: Boolean = false): NeoTunGeoDataResult {
        if (!directory.exists() && !directory.mkdirs()) error("Не удалось создать каталог геоданных")
        val geoIp = File(directory, GEOIP)
        val geoSite = File(directory, GEOSITE)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var changed = false

        val geoIpUrl = profileUrl(profile, listOf("Geoipurl", "GeoIPurl", "GeoipUrl", "GeoIPURL"))
            ?: DEFAULT_GEOIP_URL
        val geoSiteUrl = profileUrl(profile, listOf("Geositeurl", "GeoSiteurl", "GeositeUrl", "GeoSiteURL"))
            ?: DEFAULT_GEOSITE_URL

        if (forceRefresh || needsRefresh(geoIp, prefs.getLong("geoip_updated", 0L))) {
            changed = download(context, geoIpUrl, geoIp, GEOIP) || changed
            if (geoIp.isFile && geoIp.length() > 0L) {
                prefs.edit().putLong("geoip_updated", geoIp.lastModified()).apply()
            }
        }
        if (forceRefresh || needsRefresh(geoSite, prefs.getLong("geosite_updated", 0L))) {
            changed = download(context, geoSiteUrl, geoSite, GEOSITE) || changed
            if (geoSite.isFile && geoSite.length() > 0L) {
                prefs.edit().putLong("geosite_updated", geoSite.lastModified()).apply()
            }
        }

        if (!geoIp.isFile || geoIp.length() < 1024L) {
            error("Не удалось получить geoip.dat. Проверьте интернет и URL GeoIP в профиле маршрутизации.")
        }
        if (!geoSite.isFile || geoSite.length() < 1024L) {
            error("Не удалось получить geosite.dat. Проверьте интернет и URL GeoSite в профиле маршрутизации.")
        }
        return NeoTunGeoDataResult(geoIp.length(), geoSite.length(), changed)
    }

    private fun needsRefresh(file: File, lastUpdated: Long): Boolean =
        !file.isFile || file.length() < 1024L ||
            (lastUpdated > 0L && System.currentTimeMillis() - lastUpdated >= MAX_AGE_MS) ||
            (lastUpdated == 0L && System.currentTimeMillis() - file.lastModified() >= MAX_AGE_MS)

    private fun profileUrl(profile: JSONObject?, keys: List<String>): String? {
        if (profile == null) return null
        for (key in keys) {
            val value = profile.optString(key).trim()
            if (value.startsWith("https://", true)) return value
        }
        return null
    }

    private fun download(context: Context, source: String, target: File, label: String): Boolean {
        require(source.startsWith("https://", true)) { "$label: допускаются только HTTPS URL" }
        val temporary = File(target.parentFile, target.name + ".download")
        var connection: HttpURLConnection? = null
        NeoTunDiagnostics.log(context, "$label: загрузка геобазы")
        try {
            connection = URL(source).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "NeoTUN/0.5.4")
            connection.connect()
            if (connection.responseCode !in 200..299) error("$label: HTTP ${connection.responseCode}")
            if (!connection.url.toString().startsWith("https://", true)) {
                error("$label: переход на URL без HTTPS запрещён")
            }
            val expectedBytes = connection.contentLengthLong
            if (expectedBytes > MAX_BYTES) error("$label: файл слишком большой")

            temporary.delete()
            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        if (downloadedBytes > MAX_BYTES) error("$label: превышен лимит размера")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                    if (downloadedBytes < 1024L) error("$label: загруженный файл слишком мал")
                }
            }
            if (expectedBytes >= 0L && downloadedBytes != expectedBytes) {
                error("$label: загрузка неполная ($downloadedBytes из $expectedBytes байт)")
            }
            // Replace only after the temporary file has passed basic integrity checks.
            // Keep a backup so a failed rename/copy cannot destroy the last usable database.
            val backup = File(target.parentFile, target.name + ".previous")
            backup.delete()
            if (target.isFile && !target.renameTo(backup)) {
                error("$label: не удалось сохранить предыдущую геобазу")
            }
            try {
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = false)
                    temporary.delete()
                }
                if (!target.isFile || target.length() != downloadedBytes) {
                    error("$label: проверка размера после сохранения не пройдена")
                }
                backup.delete()
            } catch (t: Throwable) {
                target.delete()
                if (backup.isFile) backup.renameTo(target)
                throw t
            }
            target.setLastModified(System.currentTimeMillis())
            NeoTunDiagnostics.log(context, "$label: сохранено ${target.length()} байт")
            return true
        } catch (t: Throwable) {
            temporary.delete()
            NeoTunDiagnostics.error(context, "$label: ошибка загрузки", t)
            // If refresh failed, keep the previous working database.
            if (!target.isFile || target.length() < 1024L) throw t
            return false
        } finally {
            connection?.disconnect()
        }
    }
}
