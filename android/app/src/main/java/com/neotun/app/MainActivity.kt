package com.neotun.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var profileInput: EditText
    private lateinit var updater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = AppUpdater(this)

        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        val title = TextView(this).apply {
            text = "NeoTUN"
            textSize = 32f
        }

        status = TextView(this).apply {
            text = "Готов к подключению"
            textSize = 18f
            setPadding(0, 24, 0, 24)
        }

        profileInput = EditText(this).apply {
            hint = "vless://..."
            setText(prefs.getString(NeoTunVpnService.KEY_URI, ""))
            minLines = 3
            maxLines = 5
            setPadding(24, 16, 24, 16)
        }

        val connect = Button(this).apply {
            text = "Подключить"
            setOnClickListener { saveAndConnect() }
        }

        val update = Button(this).apply {
            text = "Проверить обновления"
            setOnClickListener { checkUpdates() }
        }

        root.addView(title)
        root.addView(status)
        root.addView(profileInput)
        root.addView(connect)
        root.addView(update)
        setContentView(root)

        status.text = NeoTunCore.nativeVersion() + " • Android"
        showSavedServiceState()

        updater.checkForUpdates { result ->
            if (result is UpdateResult.Available) {
                status.text = "Доступно обновление " + result.version
            }
        }
    }

    private fun saveAndConnect() {
        val uri = profileInput.text.toString().trim()
        if (!uri.startsWith("vless://")) {
            status.text = "Поддерживается VLESS-ссылка vless://..."
            return
        }

        val engine = NeoTunCore.nativeVlessEngine(uri)
        if (engine == "unknown") {
            status.text = "Не удалось разобрать VLESS-ссылку"
            return
        }

        status.text = if (engine == NeoTunVpnService.ENGINE_XRAY) {
            "VLESS XHTTP → Xray"
        } else {
            "VLESS → sing-box"
        }

        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        val editor = prefs.edit()
            .putString(NeoTunVpnService.KEY_URI, uri)
            .putString(NeoTunVpnService.KEY_ENGINE, engine)

        if (engine == NeoTunVpnService.ENGINE_XRAY) {
            editor.remove(NeoTunVpnService.KEY_CONFIG)
        } else {
            val config = NeoTunCore.nativeVlessConfig(uri)
            if (config.isBlank()) {
                status.text = "Не удалось собрать конфигурацию sing-box"
                return
            }
            editor.putString(NeoTunVpnService.KEY_CONFIG, config)
        }
        editor.remove(NeoTunVpnService.KEY_ERROR).apply()

        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQUEST_VPN)
        } else {
            startVpnFromPrefs()
        }
    }

    private fun startVpnFromPrefs() {
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        val engine = prefs.getString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_SING_BOX)
        if (engine == NeoTunVpnService.ENGINE_XRAY) {
            val uri = prefs.getString(NeoTunVpnService.KEY_URI, null)
            if (uri.isNullOrBlank()) {
                status.text = "Нет VLESS-профиля"
                return
            }
            val serviceIntent = Intent(this, NeoTunXrayVpnService::class.java)
                .putExtra(NeoTunXrayVpnService.EXTRA_URI, uri)
            ContextCompat.startForegroundService(this, serviceIntent)
            status.text = "Запускаем Xray + XHTTP TUN…"
        } else {
            val config = prefs.getString(NeoTunVpnService.KEY_CONFIG, null)
            if (config.isNullOrBlank()) {
                status.text = "Нет конфигурации sing-box"
                return
            }
            val serviceIntent = Intent(this, NeoTunVpnService::class.java)
                .putExtra(NeoTunVpnService.EXTRA_CONFIG, config)
            ContextCompat.startForegroundService(this, serviceIntent)
            status.text = "Запускаем sing-box TUN…"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) showSavedServiceState()
    }

    private fun showSavedServiceState() {
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        val error = prefs.getString(NeoTunVpnService.KEY_ERROR, null)
        if (!error.isNullOrBlank()) {
            status.text = "Ошибка подключения: $error"
            return
        }

        if (prefs.getBoolean(NeoTunVpnService.KEY_RUNNING, false)) {
            val engine = prefs.getString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_SING_BOX)
            status.text = if (engine == NeoTunVpnService.ENGINE_XRAY) {
                "Подключено • Xray + XHTTP"
            } else {
                "Подключено • sing-box"
            }
        }
    }

    private fun checkUpdates() {
        status.text = "Проверяем обновления…"
        updater.checkForUpdates { result ->
            when (result) {
                is UpdateResult.Available -> {
                    status.text = "Доступно обновление " + result.version
                    updater.downloadAndInstall(
                        result.apkUrl,
                        result.version,
                        onProgress = { status.text = "Загрузка обновления: " + it + "%" },
                        onError = { status.text = "Ошибка обновления: " + it }
                    )
                }
                is UpdateResult.UpToDate -> status.text = "Установлена последняя версия " + result.version
                is UpdateResult.Error -> status.text = "Обновления: " + result.message
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startVpnFromPrefs()
        }
    }

    companion object {
        private const val REQUEST_VPN = 100
    }
}
