package com.neotun.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Invisible widget action handler. It only exists to request Android's VPN
 * consent when needed; it never renders the NeoTUN app UI.
 */
class NeoTunWidgetToggleActivity : Activity() {
    private var pendingEngine: String? = null
    private var pendingUri: String? = null
    private var pendingConfig: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        if (isFinishing) return
        toggle()
    }

    private fun toggle() {
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(NeoTunVpnService.KEY_RUNNING, false)) {
            disconnect(prefs)
            return
        }

        val profiles = ProfileStore(this).all()
        val selectedId = getSharedPreferences("neotun_ui", MODE_PRIVATE)
            .getString("selected_profile", null)
        val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
        if (profile == null) {
            Toast.makeText(this, "Сначала добавьте сервер в NeoTUN", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val engine = runCatching { NeoTunCore.nativeShareEngine(profile.uri) }.getOrDefault("unknown")
        if (engine.isBlank() || engine == "unknown") {
            Toast.makeText(this, "Не удалось определить ядро профиля", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val config = if (engine == NeoTunVpnService.ENGINE_XRAY) null else runCatching {
            val storedUri = prefs.getString(NeoTunVpnService.KEY_URI, null)
            val storedConfig = prefs.getString(NeoTunVpnService.KEY_CONFIG, null)
            val raw = if (storedUri == profile.uri && !storedConfig.isNullOrBlank()) {
                storedConfig
            } else {
                NeoTunCore.nativeShareConfig(profile.uri)
            }
            if (raw.isBlank()) throw IllegalStateException("Пустая конфигурация")
            applyBasicTunSettings(raw)
        }.getOrElse {
            prefs.edit()
                .putString(NeoTunVpnService.KEY_ERROR, "Не удалось подготовить профиль из виджета: ${it.message ?: "ошибка конфигурации"}")
                .putBoolean(NeoTunVpnService.KEY_RUNNING, false)
                .apply()
            Toast.makeText(this, "Ошибка профиля. Откройте NeoTUN для диагностики.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pendingEngine = engine
        pendingUri = profile.uri
        pendingConfig = config
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQUEST_VPN)
        } else {
            startTunnel()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN) return
        if (resultCode == RESULT_OK) startTunnel()
        else {
            Toast.makeText(this, "Для подключения нужно разрешить VPN-соединение", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun startTunnel() {
        val engine = pendingEngine
        val uri = pendingUri
        if (engine.isNullOrBlank() || uri.isNullOrBlank()) {
            finish()
            return
        }
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        prefs.edit()
            .putString(NeoTunVpnService.KEY_URI, uri)
            .putString(NeoTunVpnService.KEY_ENGINE, engine)
            .remove(NeoTunVpnService.KEY_ERROR)
            .apply()
        try {
            if (engine == NeoTunVpnService.ENGINE_XRAY) {
                prefs.edit().remove(NeoTunVpnService.KEY_CONFIG).apply()
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NeoTunXrayVpnService::class.java)
                        .putExtra(NeoTunXrayVpnService.EXTRA_URI, uri)
                )
            } else {
                val config = pendingConfig
                if (config.isNullOrBlank()) throw IllegalStateException("Нет конфигурации sing-box")
                prefs.edit().putString(NeoTunVpnService.KEY_CONFIG, config).apply()
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NeoTunVpnService::class.java)
                        .putExtra(NeoTunVpnService.EXTRA_CONFIG, config)
                )
            }
            prefs.edit().putBoolean(NeoTunVpnService.KEY_RUNNING, true).apply()
            NeoTunHomeWidget.refreshAll(this)
        } catch (error: Throwable) {
            prefs.edit()
                .putString(NeoTunVpnService.KEY_ERROR, error.message ?: "Не удалось запустить соединение")
                .putBoolean(NeoTunVpnService.KEY_RUNNING, false)
                .apply()
            Toast.makeText(this, "Не удалось подключиться", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun disconnect(prefs: android.content.SharedPreferences) {
        runCatching {
            startService(Intent(this, NeoTunXrayVpnService::class.java)
                .setAction(NeoTunXrayVpnService.ACTION_DISCONNECT))
        }
        runCatching {
            startService(Intent(this, NeoTunVpnService::class.java)
                .setAction(NeoTunVpnService.ACTION_DISCONNECT))
        }
        prefs.edit().putBoolean(NeoTunVpnService.KEY_RUNNING, false).apply()
        NeoTunHomeWidget.refreshAll(this)
        finish()
    }

    private fun applyBasicTunSettings(raw: String): String {
        val root = JSONObject(raw)
        val uiPrefs = getSharedPreferences("neotun_ui", MODE_PRIVATE)
        val mtu = uiPrefs.getInt("mtu", 1500).coerceIn(1280, 1500)
        val ipv6 = uiPrefs.getBoolean("ipv6_enabled", false)
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (!"tun".equals(inbound.optString("type"), true)) continue
            inbound.put("mtu", mtu)
            val addresses = JSONArray().put("172.19.0.1/30")
            val routes = JSONArray().put("0.0.0.0/0")
            if (ipv6) {
                addresses.put("fdfe:dcba:9876::1/126")
                routes.put("::/0")
            }
            inbound.put("address", addresses)
            inbound.put("route_address", routes)
            inbound.put("auto_route", true)
            inbound.put("dns_mode", "hijack")
            inbound.put("dns_address", JSONArray().put("172.19.0.2"))
        }
        return root.toString()
    }

    companion object {
        private const val REQUEST_VPN = 781
    }
}
