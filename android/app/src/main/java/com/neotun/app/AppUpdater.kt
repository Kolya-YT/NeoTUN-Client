package com.neotun.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class AppUpdater(private val context: Context) {
    companion object {
        private const val RELEASES_API = "https://api.github.com/repos/Kolya-YT/NeoTUN-Client/releases/latest"
    }

    fun checkForUpdates(onResult: (UpdateResult) -> Unit) {
        thread {
            try {
                val c = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "NeoTUN-Updater")
                }
                val json = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val release = JSONObject(json)
                val latest = release.optString("tag_name").removePrefix("v").trim()
                val assets = release.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", true)) {
                            apk = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (latest.isBlank() || apk.isNullOrBlank()) {
                    post(onResult, UpdateResult.Error("В последнем релизе нет APK"))
                    return@thread
                }
                val current = currentVersionName()
                post(onResult, if (compareVersions(latest, current) > 0)
                    UpdateResult.Available(latest, apk!!) else UpdateResult.UpToDate(current))
            } catch (e: Exception) {
                post(onResult, UpdateResult.Error(e.message ?: "Не удалось проверить обновления"))
            }
        }
    }

    fun downloadAndInstall(url: String, version: String, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        thread {
            try {
                require(url.startsWith("https://github.com/")) { "Недоверенный адрес APK" }
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "NeoTUN-Updater")
                }
                val total = c.contentLengthLong
                val file = File(context.cacheDir, "NeoTUN-$version.apk")
                c.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt()
                                if (p != last) {
                                    last = p
                                    post { onProgress(p) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                post { installApk(file) }
            } catch (e: Exception) {
                post { onError(e.message ?: "Ошибка загрузки обновления") }
            }
        }
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Toast.makeText(
                context,
                "Разрешите NeoTUN устанавливать обновления и проверьте обновления ещё раз.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun currentVersionName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName ?: "0.0.0"
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-", "_").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".", "-", "_").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun post(action: () -> Unit) =
        android.os.Handler(context.mainLooper).post(action)

    private fun <T> post(callback: (T) -> Unit, value: T) =
        post { callback(value) }
}

sealed class UpdateResult {
    data class Available(val version: String, val apkUrl: String) : UpdateResult()
    data class UpToDate(val version: String) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}
