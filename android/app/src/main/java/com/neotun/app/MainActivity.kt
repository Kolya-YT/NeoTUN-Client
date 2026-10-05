package com.neotun.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var updater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = AppUpdater(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }
        val title = TextView(this).apply { text = "NeoTUN"; textSize = 32f }
        status = TextView(this).apply {
            text = "Готов к подключению"
            textSize = 18f
            setPadding(0, 32, 0, 32)
        }
        val connect = Button(this).apply {
            text = "Подключить"
            setOnClickListener { prepareVpn() }
        }
        val update = Button(this).apply {
            text = "Проверить обновления"
            setOnClickListener { checkUpdates() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(connect)
        root.addView(update)
        setContentView(root)

        status.text = "${NeoTunCore.nativeVersion()} • Android"
    }

    private fun checkUpdates() {
        status.text = "Проверяем обновления…"
        updater.checkForUpdates { result ->
            when (result) {
                is UpdateResult.Available -> {
                    status.text = "Доступно обновление ${result.version}"
                    updater.downloadAndInstall(
                        result.apkUrl,
                        result.version,
                        onProgress = { status.text = "Загрузка обновления: $it%" },
                        onError = { status.text = "Ошибка обновления: $it" }
                    )
                }
                is UpdateResult.UpToDate -> status.text = "Установлена последняя версия ${result.version}"
                is UpdateResult.Error -> status.text = "Обновления: ${result.message}"
            }
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, 100)
        else {
            startService(Intent(this, NeoTunVpnService::class.java))
            status.text = "TUN запущен • Core: Rust"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            startService(Intent(this, NeoTunVpnService::class.java))
            status.text = "TUN запущен • Core: Rust"
        }
    }
}
