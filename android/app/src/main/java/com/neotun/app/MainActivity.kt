package com.neotun.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var updater: AppUpdater
    private lateinit var store: ProfileStore
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private var screen = Screen.HOME
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                if (screen == Screen.HOME) renderHome()
                handler.postDelayed(this, 1000)
            }
        }
    }
    private enum class Screen { HOME, PROFILES, SETTINGS }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        updater = AppUpdater(this)
        store = ProfileStore(this)
        migrateLegacyProfile()
        buildShell()
        showScreen(Screen.HOME)
        handler.post(poll)
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 10, 14))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.rgb(15, 16, 22))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(76)))
        setContentView(root)
    }

    private fun showScreen(value: Screen) {
        screen = value
        when (value) {
            Screen.HOME -> renderHome()
            Screen.PROFILES -> renderProfiles()
            Screen.SETTINGS -> renderSettings()
        }
        renderNavigation()
    }

    private fun renderHome() {
        content.removeAllViews()
        val profiles = store.all()
        val selected = selectedProfile(profiles)
        header("NeoTUN", if (isRunning()) "Защищённое соединение активно" else "Быстрое и простое подключение")

        val state = card()
        state.addView(txt(if (isRunning()) "●  ПОДКЛЮЧЕНО" else "○  НЕ ПОДКЛЮЧЕНО", 13f,
            if (isRunning()) Color.rgb(72, 211, 130) else Color.rgb(160, 164, 175), Typeface.BOLD))
        state.addView(txt(selected?.name ?: "Профиль не выбран", 23f, Color.WHITE, Typeface.BOLD), margins(8))
        state.addView(txt(
            if (selected != null) "VLESS  •  " + engineLabel(selected.engine) else "Добавьте VLESS-профиль, чтобы начать",
            14f, Color.rgb(165, 169, 181)
        ))
        state.addView(button("Выбрать профиль") { showScreen(Screen.PROFILES) }, margins(16))
        content.addView(state, margins(bottom = 12))

        val connect = Button(this).apply {
            text = if (isRunning()) "Отключить" else "Подключить"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(56)
            setBackgroundColor(if (isRunning()) Color.rgb(155, 54, 64) else Color.rgb(96, 78, 220))
            setOnClickListener {
                if (isRunning()) disconnect()
                else if (selected == null) showScreen(Screen.PROFILES)
                else connect(selected)
            }
        }
        content.addView(connect, margins(bottom = 12))

        val stats = card()
        stats.addView(txt("СТАТИСТИКА", 12f, Color.rgb(145, 149, 162), Typeface.BOLD))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(metric("↓", "—", "Получено"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("↑", "—", "Отправлено"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("◌", "—", "Задержка"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(row, margins(top = 14))
        stats.addView(txt("Статистика и ping подключим следующим шагом.", 12f, Color.rgb(125, 129, 141)), margins(top = 10))
        content.addView(stats, margins(bottom = 12))
        content.addView(button("Открыть диагностику") { diagnostics() })
    }

    private fun renderProfiles() {
        content.removeAllViews()
        header("Профили", "Сервера и конфигурации NeoTUN")
        content.addView(button("+  Добавить профиль") { addProfileDialog() }, margins(bottom = 14))
        val selected = selectedProfileId()
        val profiles = store.all()
        if (profiles.isEmpty()) {
            val empty = card()
            empty.gravity = Gravity.CENTER
            empty.addView(txt("Профилей пока нет", 18f, Color.WHITE, Typeface.BOLD))
            empty.addView(txt("Добавьте VLESS-ссылку — она сохранится на устройстве.", 13f, Color.rgb(155, 159, 170), Gravity.CENTER), margins(top = 8))
            content.addView(empty)
            return
        }
        profiles.forEach { p ->
            val item = card()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(txt(p.name, 18f, Color.WHITE, Typeface.BOLD))
            box.addView(txt("VLESS  •  " + engineLabel(p.engine), 12f, Color.rgb(150, 154, 166)), margins(top = 4))
            row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
            if (p.id == selected) row.addView(txt("✓", 20f, Color.rgb(92, 213, 142), Typeface.BOLD))
            item.addView(row)
            item.addView(txt(maskUri(p.uri), 11f, Color.rgb(112, 116, 128)), margins(top = 12))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button(if (p.id == selected) "Выбран" else "Выбрать") { selectProfile(p) },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, dp(12), dp(6), 0) })
            actions.addView(button("⋮") { profileActions(p) },
                LinearLayout.LayoutParams(dp(56), dp(46)).apply { setMargins(dp(6), dp(12), 0, 0) })
            item.addView(actions)
            content.addView(item, margins(bottom = 10))
        }
    }

    private fun renderSettings() {
        content.removeAllViews()
        header("Настройки", "Параметры приложения и диагностика")
        val about = card()
        about.addView(txt("NeoTUN", 21f, Color.WHITE, Typeface.BOLD))
        about.addView(txt(NeoTunCore.nativeVersion() + " • Android", 13f, Color.rgb(155, 159, 170)), margins(top = 5))
        about.addView(txt("Сетевой движок выбирается автоматически по профилю.", 13f, Color.rgb(155, 159, 170)), margins(top = 12))
        content.addView(about, margins(bottom = 12))
        content.addView(button("Проверить обновления") { checkUpdates() }, margins(bottom = 10))
        content.addView(button("Лог подключения") { diagnostics() }, margins(bottom = 10))

        val protocols = card()
        protocols.addView(txt("ПРОТОКОЛЫ", 12f, Color.rgb(145, 149, 162), Typeface.BOLD))
        listOf(
            "VLESS" to "Работает • sing-box / Xray",
            "VMess" to "Следующий этап",
            "Trojan" to "Следующий этап",
            "Hysteria2" to "Следующий этап",
            "TUIC" to "Следующий этап",
            "Shadowsocks" to "Следующий этап",
            "WireGuard / AmneziaWG" to "Следующий этап"
        ).forEach { pair ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            r.addView(txt(pair.first, 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(txt(pair.second, 12f, Color.rgb(145, 149, 162)))
            protocols.addView(r)
        }
        content.addView(protocols)
    }

    private fun connect(profile: NeoTunProfile) {
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        prefs.edit()
            .putString(NeoTunVpnService.KEY_URI, profile.uri)
            .putString(NeoTunVpnService.KEY_ENGINE, profile.engine)
            .remove(NeoTunVpnService.KEY_ERROR)
            .apply()
        if (profile.engine == NeoTunVpnService.ENGINE_XRAY) {
            prefs.edit().remove(NeoTunVpnService.KEY_CONFIG).apply()
        } else {
            val config = NeoTunCore.nativeVlessConfig(profile.uri)
            if (config.isBlank()) {
                prefs.edit().putString(NeoTunVpnService.KEY_ERROR, "Не удалось собрать конфигурацию").apply()
                renderHome()
                return
            }
            prefs.edit().putString(NeoTunVpnService.KEY_CONFIG, config).apply()
        }
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQUEST_VPN) else startVpnFromPrefs()
    }

    private fun startVpnFromPrefs() {
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        val engine = prefs.getString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_SING_BOX)
        if (engine == NeoTunVpnService.ENGINE_XRAY) {
            val uri = prefs.getString(NeoTunVpnService.KEY_URI, null)
            if (uri.isNullOrBlank()) {
                prefs.edit().putString(NeoTunVpnService.KEY_ERROR, "Нет VLESS-профиля").apply()
                renderHome()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, NeoTunXrayVpnService::class.java).putExtra(NeoTunXrayVpnService.EXTRA_URI, uri))
        } else {
            val config = prefs.getString(NeoTunVpnService.KEY_CONFIG, null)
            if (config.isNullOrBlank()) {
                prefs.edit().putString(NeoTunVpnService.KEY_ERROR, "Нет конфигурации sing-box").apply()
                renderHome()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, NeoTunVpnService::class.java).putExtra(NeoTunVpnService.EXTRA_CONFIG, config))
        }
        renderHome()
    }

    private fun disconnect() {
        runCatching { stopService(Intent(this, NeoTunXrayVpnService::class.java)) }
        runCatching { stopService(Intent(this, NeoTunVpnService::class.java)) }
        getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(NeoTunVpnService.KEY_RUNNING, false)
            .remove(NeoTunVpnService.KEY_ERROR)
            .apply()
        renderHome()
    }

    private fun addProfileDialog() {
        val input = EditText(this).apply {
            hint = "vless://..."
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(130)))
        }
        AlertDialog.Builder(this)
            .setTitle("Добавить профиль")
            .setMessage("Вставьте VLESS-ссылку. Название возьмём из #fragment.")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ -> saveProfile(input.text.toString().trim()) }
            .show()
    }

    private fun saveProfile(uri: String) {
        if (!uri.startsWith("vless://", true)) {
            toast("Поддерживается VLESS-ссылка vless://...")
            return
        }
        val engine = NeoTunCore.nativeVlessEngine(uri)
        if (engine == "unknown") {
            toast("Не удалось разобрать VLESS-ссылку")
            return
        }
        val p = NeoTunProfile(java.util.UUID.randomUUID().toString(), ProfileStore.displayNameFromUri(uri), uri, engine)
        store.save(p)
        setSelectedProfile(p.id)
        showScreen(Screen.PROFILES)
    }

    private fun selectProfile(p: NeoTunProfile) {
        setSelectedProfile(p.id)
        showScreen(Screen.HOME)
    }

    private fun profileActions(p: NeoTunProfile) {
        AlertDialog.Builder(this).setTitle(p.name)
            .setItems(arrayOf("Переименовать", "Удалить")) { _, which ->
                if (which == 0) rename(p) else confirmDelete(p)
            }.show()
    }

    private fun rename(p: NeoTunProfile) {
        val input = EditText(this).apply { setText(p.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Переименовать").setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                store.rename(p.id, input.text.toString())
                renderProfiles()
            }.show()
    }

    private fun confirmDelete(p: NeoTunProfile) {
        AlertDialog.Builder(this).setTitle("Удалить профиль?").setMessage(p.name)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                store.delete(p.id)
                if (selectedProfileId() == p.id) {
                    getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().remove(SELECTED).apply()
                }
                renderProfiles()
            }.show()
    }

    private fun diagnostics() {
        val view = TextView(this).apply {
            text = NeoTunDiagnostics.read(this@MainActivity).ifBlank { "Лог пока пуст." }
            textSize = 12f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this).setTitle("Диагностика NeoTUN")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Закрыть", null).show()
    }

    private fun checkUpdates() {
        updater.checkForUpdates { result ->
            when (result) {
                is UpdateResult.Available -> updater.downloadAndInstall(
                    result.apkUrl, result.version,
                    { toast("Загрузка обновления: " + it + "%") },
                    { toast("Ошибка обновления: " + it) }
                )
                is UpdateResult.UpToDate -> toast("Установлена последняя версия " + result.version)
                is UpdateResult.Error -> toast("Обновления: " + result.message)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.HOME) renderHome()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) startVpnFromPrefs()
    }

    private fun renderNavigation() {
        nav.removeAllViews()
        nav.addView(navItem("⌂", "Главная", Screen.HOME))
        nav.addView(navItem("◉", "Профили", Screen.PROFILES))
        nav.addView(navItem("⚙", "Настройки", Screen.SETTINGS))
    }

    private fun navItem(icon: String, label: String, target: Screen) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(2))
        setOnClickListener { showScreen(target) }
        addView(txt(icon, 21f, if (screen == target) Color.rgb(125, 108, 255) else Color.rgb(125, 129, 141), Typeface.BOLD, Gravity.CENTER))
        addView(txt(label, 11f, if (screen == target) Color.WHITE else Color.rgb(125, 129, 141), Typeface.NORMAL, Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
    }

    private fun header(title: String, subtitle: String) {
        content.addView(txt(title, 30f, Color.WHITE, Typeface.BOLD))
        content.addView(txt(subtitle, 14f, Color.rgb(145, 149, 162)), margins(top = 5, bottom = 18))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(Color.rgb(20, 21, 28))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = dp(48)
        setBackgroundColor(Color.rgb(35, 36, 46))
        setOnClickListener { action() }
    }

    private fun metric(icon: String, value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(txt(icon, 17f, Color.rgb(125, 108, 255), Typeface.BOLD, Gravity.CENTER))
        addView(txt(value, 17f, Color.WHITE, Typeface.BOLD, Gravity.CENTER), margins(top = 3))
        addView(txt(label, 10f, Color.rgb(130, 134, 146), Gravity = Gravity.CENTER), margins(top = 2))
    }

    private fun txt(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL, Gravity: Int = Gravity.NO_GRAVITY) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans", style)
            gravity = Gravity
        }

    private fun margins(top: Int = 0, start: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

    private fun engineLabel(engine: String) = if (engine == NeoTunVpnService.ENGINE_XRAY) "Xray" else "sing-box"
    private fun selectedProfile(list: List<NeoTunProfile>) = list.firstOrNull { it.id == selectedProfileId() } ?: list.firstOrNull()
    private fun selectedProfileId() = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(SELECTED, null)
    private fun setSelectedProfile(id: String) = getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(SELECTED, id).apply()
    private fun maskUri(uri: String) = uri.replace(Regex("(?<=://).{0,10}@"), "••••••••@")

    private fun migrateLegacyProfile() {
        if (store.all().isNotEmpty()) return
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        val uri = prefs.getString(NeoTunVpnService.KEY_URI, null)?.trim().orEmpty()
        if (uri.isBlank() || !uri.startsWith("vless://", true)) return
        val engine = NeoTunCore.nativeVlessEngine(uri)
        if (engine == "unknown") return
        val p = NeoTunProfile(java.util.UUID.randomUUID().toString(), ProfileStore.displayNameFromUri(uri), uri, engine)
        store.save(p)
        setSelectedProfile(p.id)
    }

    private fun isRunning() = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE).getBoolean(NeoTunVpnService.KEY_RUNNING, false)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_VPN = 100
        private const val UI_PREFS = "neotun_ui"
        private const val SELECTED = "selected_profile"
    }
}
