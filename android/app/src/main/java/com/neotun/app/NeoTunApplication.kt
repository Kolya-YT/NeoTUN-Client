package com.neotun.app

import android.app.Application
import android.app.ActivityManager
import android.os.Build
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
        val processName = currentProcessName()
        NeoTunDiagnostics.log(this, "Application onCreate; process=" + processName)

        // NeoTunXrayVpnService runs in the :xray process. Do not initialize
        // libbox there: libbox and the native Xray engine both embed a Go
        // runtime and must never be loaded into the same Android process.
        if (processName != packageName) {
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
            // A native-engine setup failure must not crash the entire app before
            // the home screen is shown. The VPN service reports errors on connect.
            NeoTunDiagnostics.error(this, "sing-box: Libbox.setup failed; app launch will continue", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentProcessName(): String {
        // Application.getProcessName() is API 28+. The app supports API 26/27 too.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        return manager?.runningAppProcesses
            ?.firstOrNull { it.pid == android.os.Process.myPid() }
            ?.processName
            ?: packageName
    }
}
