package com.neotun.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
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
import java.security.MessageDigest
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
                val supportedAbis = Build.SUPPORTED_ABIS.toList()
                var selectedAsset: JSONObject? = null
                if (assets != null) {
                    // Prefer the APK compiled for this device, so updates stay small.
                    for (abi in supportedAbis) {
                        val expectedName = "NeoTUN-$abi.apk"
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.optString("name") == expectedName) {
                                selectedAsset = asset
                                break
                            }
                        }
                        if (selectedAsset != null) break
                    }
                    // Backward compatibility with releases created before ABI-specific APKs.
                    if (selectedAsset == null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name")
                            if (name.equals("app-release.apk", true) ||
                                name.equals("NeoTUN-universal.apk", true)) {
                                selectedAsset = asset
                                break
                            }
                        }
                    }
                }
                val apk = selectedAsset?.optString("browser_download_url").orEmpty()
                val digest = selectedAsset?.optString("digest").orEmpty().removePrefix("sha256:").lowercase()
                val size = selectedAsset?.optLong("size", 0L) ?: 0L
                if (latest.isBlank() || apk.isBlank()) {
                    post(onResult, UpdateResult.Error("В последнем релизе нет APK для этого устройства"))
                    return@thread
                }
                if (!digest.matches(Regex("[0-9a-f]{64}")) || size <= 0L) {
                    post(onResult, UpdateResult.Error("У релизного APK отсутствует контрольная сумма или размер"))
                    return@thread
                }
                val current = currentVersion()
                val latestCode = release.optInt("version_code", -1)
                val shouldUpdate = if (latestCode > 0 && current.second > 0) {
                    latestCode > current.second
                } else {
                    compareVersions(latest, current.first) > 0
                }
                post(onResult, if (shouldUpdate)
                    UpdateResult.Available(latest, apk, digest, size) else UpdateResult.UpToDate(current.first))
            } catch (e: Exception) {
                post(onResult, UpdateResult.Error(e.message ?: "Не удалось проверить обновления"))
            }
        }
    }

    fun downloadAndInstall(url: String, version: String, expectedSha256: String, expectedSize: Long, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        thread {
            try {
                val source = URL(url)
                require(source.protocol == "https" && source.host.equals("github.com", true) && source.path.startsWith("/Kolya-YT/NeoTUN-Client/releases/download/")) { "Недоверенный адрес APK" }
                require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}")) && expectedSize > 0L) { "Нет корректной контрольной суммы или размера APK" }
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "NeoTUN-Updater")
                }
                val status = c.responseCode
                if (status !in 200..299) {
                    c.disconnect()
                    throw IllegalStateException("Сервер APK вернул HTTP $status")
                }
                val finalUrl = c.url
                if (finalUrl.protocol != "https" || !(finalUrl.host.equals("github.com", true) || finalUrl.host.endsWith(".githubusercontent.com", true))) {
                    c.disconnect()
                    throw IllegalStateException("Сервер перенаправил загрузку на недоверенный адрес")
                }
                val responseSize = c.contentLengthLong
                val total = expectedSize.takeIf { it > 0L } ?: responseSize
                val file = File(context.cacheDir, "NeoTUN-$version.apk")
                // Keep only the APK currently being prepared; older downloaded
                // releases are no longer needed and otherwise accumulate in cache.
                context.cacheDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("NeoTUN-") && it.name.endsWith(".apk") && it.absolutePath != file.absolutePath }
                    ?.forEach { it.delete() }
                file.delete()
                post { onProgress(0) }
                val sha256 = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            sha256.update(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val p = ((done * 100L) / total).toInt().coerceIn(0, 99)
                                if (p != last) {
                                    last = p
                                    post { onProgress(p) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (!file.isFile || file.length() < 100L * 1024L) {
                    file.delete()
                    throw IllegalStateException("Скачанный APK слишком мал или пуст")
                }
                if (file.length() != expectedSize) {
                    val actualSize = file.length()
                    file.delete()
                    throw IllegalStateException("Размер APK не совпал с релизом: " + actualSize + " из " + expectedSize + " байт")
                }
                val actualSha256 = sha256.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    file.delete()
                    throw IllegalStateException("Контрольная сумма APK не совпала с релизом")
                }
                post { onProgress(100) }
                post {
                    try {
                        validateApkForUpdate(file, version)
                        installApk(file)
                    } catch (e: Exception) {
                        file.delete()
                        onError(e.message ?: "APK нельзя установить поверх текущего приложения")
                    }
                }
            } catch (e: Exception) {
                post { onError(e.message ?: "Ошибка загрузки обновления") }
            }
        }
    }

    private fun validateApkForUpdate(file: File, expectedVersion: String) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalStateException("Не удалось прочитать APK обновления")

        if (archive.packageName != context.packageName) {
            throw IllegalStateException("APK относится к другому приложению")
        }
        if (!archive.versionName.orEmpty().equals(expectedVersion, ignoreCase = true)) {
            throw IllegalStateException("Версия APK не совпала с выбранным релизом: " + archive.versionName + " вместо " + expectedVersion)
        }

        val currentCode = currentVersion().second
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        if (archiveCode <= currentCode) {
            throw IllegalStateException(
                "Версия APK ниже или равна установленной ($archiveCode <= $currentCode)"
            )
        }

        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }

        val sameSigner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val currentSigners = current.signingInfo?.apkContentsSigners ?: emptyArray()
            val archiveSigners = archive.signingInfo?.apkContentsSigners ?: emptyArray()
            currentSigners.contentEquals(archiveSigners)
        } else {
            @Suppress("DEPRECATION")
            current.signatures.contentEquals(archive.signatures)
        }

        if (!sameSigner) {
            throw IllegalStateException(
                "Нельзя обновить установленную версию: APK подписан другим ключом. " +
                    "Настройте постоянный production-keystore для GitHub Actions или удалите старую версию вручную.",
            )
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

    private fun currentVersion(): Pair<String, Long> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return (info.versionName ?: "0.0.0") to code
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
    data class Available(val version: String, val apkUrl: String, val sha256: String, val sizeBytes: Long) : UpdateResult()
    data class UpToDate(val version: String) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}
