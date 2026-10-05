package com.neotun.app

import android.app.Application
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale

class NeoTunApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Libbox.setLocale(Locale.getDefault().toLanguageTag())
        val working = getExternalFilesDir(null) ?: filesDir
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
            debug = false
        })
    }
}
