package com.neotun.app

import android.app.Application
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale

class NeoTunApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Preserve Java/Kotlin fatal exceptions for review after the app is restarted.
        // Native SIGSEGV/SIGABRT cannot be handled here, so engine startup breadcrumbs
        // are written synchronously before entering libbox.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                NeoTunDiagnostics.error(
                    this,
                    "FATAL uncaught exception on thread " + thread.name,
                    throwable,
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        NeoTunDiagnostics.log(this, "Application onCreate; process=" + Application.getProcessName())

        // NeoTunXrayVpnService runs in the :xray process. Do not initialize
        // libbox there: libbox and the native Xray engine both embed a Go
        // runtime and must never be loaded into the same Android process.
        if (Application.getProcessName() != packageName) {
            return
        }

        runCatching {
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            val working = getExternalFilesDir(null) ?: filesDir
            NeoTunDiagnostics.log(this, "sing-box: Libbox.setup starting; workingPath=" + working.path)
            Libbox.setup(SetupOptions().apply {
            basePath = filesDir.path
            workingPath = working.path
            tempPath = cacheDir.path
            appVersion = packageManager
                .getPackageInfo(packageName, 0)
                .versionName ?: "0.1.0"
            appMarketingVersion = packageManager
                .getPackageInfo(packageName, 0)
                .versionName ?: "0.1.0"
                debug = true
            })
            NeoTunDiagnostics.log(this, "sing-box: Libbox.setup completed")
        }.onFailure {
            NeoTunDiagnostics.error(this, "sing-box: Libbox.setup failed", it)
            throw it
        }
    }
}
