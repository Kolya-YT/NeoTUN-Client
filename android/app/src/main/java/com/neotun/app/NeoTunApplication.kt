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

        // Keep app launch independent from the native Go/libbox runtime.
        // Initialize libbox lazily in the sing-box foreground service instead.
    }

    /**
     * Must be called only from the sing-box service process, before creating
     * CommandServer. Do not call from NeoTunXrayVpnService's :xray process.
     */
    companion object {
        @Volatile private var libboxReady = false

        @Synchronized
        fun initializeLibbox(context: android.content.Context) {
            if (libboxReady) return
            val app = context.applicationContext
            val working = app.getExternalFilesDir(null) ?: app.filesDir
            NeoTunDiagnostics.log(app, "sing-box: Libbox.setup starting; workingPath=" + working.path)
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
            val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.1.0"
            Libbox.setup(SetupOptions().apply {
                basePath = app.filesDir.path
                workingPath = working.path
                tempPath = app.cacheDir.path
                appVersion = version
                appMarketingVersion = version
                debug = true
            })
            libboxReady = true
            NeoTunDiagnostics.log(app, "sing-box: Libbox.setup completed")
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
