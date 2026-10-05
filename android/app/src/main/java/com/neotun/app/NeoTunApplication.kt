package com.neotun.app

import android.app.Application
import android.os.Build
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
            appVersion = BuildConfig.VERSION_CODE.toString()
            appMarketingVersion = BuildConfig.VERSION_NAME
            platformMetadata = "{\"os\":\"Android\",\"sdk\":" + Build.VERSION.SDK_INT +
                ",\"manufacturer\":\"" + Build.MANUFACTURER + "\",\"model\":\"" + Build.MODEL + "\"}"
            debug = BuildConfig.DEBUG
        })
    }
}
