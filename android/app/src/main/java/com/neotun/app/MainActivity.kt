package com.neotun.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.LinkProperties
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    private lateinit var updater: AppUpdater
    private lateinit var store: ProfileStore
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private var screen = Screen.HOME
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var trafficInterface: String? = null
    private var trafficBaseRx = -1L
    private var trafficBaseTx = -1L
    private var trafficLastRx = -1L
    private var trafficLastTx = -1L
    private var trafficLastAt = 0L
    private var deviceBaseRx = -1L
    private var deviceBaseTx = -1L
    private var deviceLastRx = -1L
    private var deviceLastTx = -1L
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
            setBackgroundColor(Color.rgb(7, 8, 12))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(14))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(8))
            setBackgroundColor(Color.rgb(15, 16, 22))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(72)))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(dp(18), bars.top + dp(18), dp(18), dp(14))
            nav.setPadding(dp(10), dp(7), dp(10), dp(8) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
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
        val error = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE).getString(NeoTunVpnService.KEY_ERROR, null)
        if (!error.isNullOrBlank() && !isRunning()) {
            val errorCard = card()
            errorCard.background = rounded(Color.rgb(45, 24, 29), 16, Color.rgb(104, 48, 57), 1)
            errorCard.addView(txt("Не удалось подключиться", 14f, Color.rgb(255, 154, 165), Typeface.BOLD))
            errorCard.addView(txt(error, 12f, Color.rgb(210, 165, 171)), margins(top = 5))
            content.addView(errorCard, margins(bottom = 12))
        }

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
            background = rounded(if (isRunning()) Color.rgb(163, 57, 68) else Color.rgb(111, 91, 235), 16)
            setOnClickListener {
                if (isRunning()) disconnect()
                else if (selected == null) showScreen(Screen.PROFILES)
                else connect(selected)
            }
        }
        content.addView(connect, margins(bottom = 12))

        val traffic = readVpnTraffic()
        val stats = card()
        stats.addView(txt("СТАТИСТИКА", 12f, Color.rgb(145, 149, 162), Typeface.BOLD))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(metric("↓", formatBytes(traffic.sessionRx), "Получено"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("↑", formatBytes(traffic.sessionTx), "Отправлено"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(metric("◌", if (traffic.hasTraffic) formatRate(traffic.rxRate) + " / " + formatRate(traffic.txRate) else "—", "Скорость"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(row, margins(top = 14))
        stats.addView(txt(if (traffic.interfaceName != null) "TUN: " + traffic.interfaceName else "Ожидаем активный TUN-интерфейс…", 12f, Color.rgb(125, 129, 141)), margins(top = 10))
        content.addView(stats, margins(bottom = 12))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(
            button("Проверить ping") { pingSelected(selected) },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(0, 0, dp(5), 0) }
        )
        actions.addView(
            button("Переподключить") { reconnectSelected(selected) },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(5), 0, 0, 0) }
        )
        content.addView(actions, margins(bottom = 10))
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
            box.addView(txt(p.name, 18f, Color.WHITE, Typeface.BOLD).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
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
        val xray = Intent(this, NeoTunXrayVpnService::class.java)
            .setAction(NeoTunXrayVpnService.ACTION_DISCONNECT)
        val singBox = Intent(this, NeoTunVpnService::class.java)
            .setAction(NeoTunVpnService.ACTION_DISCONNECT)

        // Let each VPN service receive the explicit disconnect action and
        // perform its own Xray/sing-box shutdown before releasing the TUN.
        runCatching { startService(xray) }
        runCatching { startService(singBox) }

        getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(NeoTunVpnService.KEY_RUNNING, false)
            .remove(NeoTunVpnService.KEY_ERROR)
            .apply()
        resetTrafficCounters()
        handler.postDelayed({ if (!isFinishing && screen == Screen.HOME) renderHome() }, 350)
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("Добавить профиль")
            .setMessage("Вставьте VLESS-ссылку. Название можно изменить позже.")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ -> saveProfile(input.text.toString().trim()) }
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.rgb(24, 25, 32)))
        dialog.window?.setDimAmount(0.72f)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(139, 120, 255))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.rgb(155, 159, 170))
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(Color.rgb(155, 159, 170))
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
        val dialog = AlertDialog.Builder(this).setTitle("Переименовать").setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                store.rename(p.id, input.text.toString())
                renderProfiles()
            }.create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun confirmDelete(p: NeoTunProfile) {
        val dialog = AlertDialog.Builder(this).setTitle("Удалить профиль?").setMessage(p.name)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                store.delete(p.id)
                if (selectedProfileId() == p.id) {
                    getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().remove(SELECTED).apply()
                }
                renderProfiles()
            }.create()
        dialog.setOnShowListener {
            styleDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(255, 102, 118))
        }
        dialog.show()
    }

    private fun diagnostics() {
        val view = TextView(this).apply {
            text = NeoTunDiagnostics.read(this@MainActivity).ifBlank { "Лог пока пуст." }
            textSize = 12f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setTextIsSelectable(true)
        }
        val dialog = AlertDialog.Builder(this).setTitle("Диагностика NeoTUN")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("Закрыть", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun pingSelected(profile: NeoTunProfile?) {
        if (profile == null) {
            toast("Сначала добавьте профиль")
            showScreen(Screen.PROFILES)
            return
        }
        if (isRunning()) {
            toast("Для ping сначала отключите соединение")
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Проверка сервера")
            .setMessage("Проверяем доступность профиля…")
            .setNegativeButton("Отмена", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()

        Thread {
            val pingResult = runCatching {
                val convertedRaw = NeoTunXrayBridge.nativeInvoke(
                    org.json.JSONObject()
                        .put("apiVersion", 3)
                        .put("method", "convertShareLinksToXrayJson")
                        .put("payload", org.json.JSONObject().put("text", profile.uri))
                        .toString()
                )
                val converted = org.json.JSONObject(convertedRaw)
                if (!converted.optBoolean("success", false)) {
                    error(converted.optString("error", "Не удалось разобрать профиль"))
                }
                val data = converted.optJSONObject("data")
                    ?: error("Xray parser не вернул конфигурацию")
                val outbounds = data.optJSONArray("outbounds")
                    ?: error("Xray parser не вернул outbound")
                val pingRaw = NeoTunXrayBridge.nativeInvoke(
                    org.json.JSONObject()
                        .put("apiVersion", 3)
                        .put("method", "pingBatch")
                        .put(
                            "payload",
                            org.json.JSONObject()
                                .put(
                                    "configs",
                                    org.json.JSONArray().put(
                                        org.json.JSONObject().put(
                                            "xrayJson",
                                            org.json.JSONObject().put("outbounds", outbounds).toString()
                                        )
                                    )
                                )
                                .put("timeout", 5)
                                .put("url", "https://cp.cloudflare.com/")
                        )
                        .toString()
                )
                val ping = org.json.JSONObject(pingRaw)
                if (!ping.optBoolean("success", false)) {
                    error(ping.optString("error", "Ping не выполнен"))
                }
                val item = ping.optJSONObject("data")
                    ?.optJSONArray("results")
                    ?.optJSONObject(0)
                    ?: error("Пустой результат ping")
                if (!item.optBoolean("success", false)) {
                    error(item.optString("error", "Сервер недоступен"))
                }
                item.optLong("delay", -1L)
            }.getOrElse { -1L }

            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (pingResult >= 0L) {
                    toast("Ping: ${pingResult} мс")
                } else {
                    toast("Ping не пройден — проверьте сервер или профиль")
                }
            }
        }.start()
    }

    private fun reconnectSelected(profile: NeoTunProfile?) {
        if (profile == null) {
            toast("Сначала добавьте профиль")
            showScreen(Screen.PROFILES)
            return
        }
        if (!isRunning()) {
            connect(profile)
            return
        }

        toast("Переподключение…")
        disconnect()
        handler.postDelayed({
            if (!isFinishing && !isRunning()) connect(profile)
        }, 900L)
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
        content.addView(txt(title, 32f, Color.WHITE, Typeface.BOLD))
        content.addView(txt(subtitle, 14f, Color.rgb(145, 149, 162)), margins(top = 5, bottom = 18))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(Color.rgb(18, 19, 26), 18, Color.rgb(30, 31, 41), 1)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null && strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        minHeight = dp(48)
        background = rounded(Color.rgb(35, 36, 46), 14, Color.rgb(48, 49, 61), 1)
        setOnClickListener { action() }
    }

    private fun metric(icon: String, value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(txt(icon, 17f, Color.rgb(125, 108, 255), Typeface.BOLD, Gravity.CENTER))
        addView(txt(value, 17f, Color.WHITE, Typeface.BOLD, Gravity.CENTER), margins(top = 3))
        addView(txt(label, 10f, Color.rgb(130, 134, 146), textGravity = Gravity.CENTER), margins(top = 2))
    }

    private fun txt(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL, textGravity: Int = android.view.Gravity.NO_GRAVITY) =
        TextView(this).apply {
            includeFontPadding = false
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans", style)
            gravity = textGravity
        }

    private fun margins(top: Int = 0, start: Int = 0, end: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(start), dp(top), dp(end), dp(bottom)) }

    private fun engineLabel(engine: String) = if (engine == NeoTunVpnService.ENGINE_XRAY) "Xray" else "sing-box"
    private fun selectedProfile(list: List<NeoTunProfile>) = list.firstOrNull { it.id == selectedProfileId() } ?: list.firstOrNull()
    private fun selectedProfileId() = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(SELECTED, null)
    private fun setSelectedProfile(id: String) = getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(SELECTED, id).apply()
    private fun maskUri(uri: String): String {
        val authority = uri.substringAfter("://", "").substringBefore('?').substringBefore('#')
        val host = authority.substringAfter('@', authority).substringBeforeLast(':')
        val query = uri.substringAfter('?', "").substringBefore('#')
        val transport = Regex("(?:^|&)type=([^&]+)").find(query)?.groupValues?.getOrNull(1)?.uppercase()
        val security = Regex("(?:^|&)security=([^&]+)").find(query)?.groupValues?.getOrNull(1)?.uppercase()
        return listOfNotNull(host.ifBlank { null }, transport, security).joinToString("  •  ").ifBlank { "VLESS-подключение" }
    }

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

    private data class TrafficSnapshot(
        val interfaceName: String?,
        val sessionRx: Long,
        val sessionTx: Long,
        val rxRate: Long,
        val txRate: Long,
        val hasTraffic: Boolean,
    )

    private fun readVpnTraffic(): TrafficSnapshot {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val vpnNetwork = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val interfaceName = vpnNetwork?.let(connectivity::getLinkProperties)?.interfaceName

        if (interfaceName.isNullOrBlank()) {
            resetTrafficCounters()
            return TrafficSnapshot(null, 0L, 0L, 0L, 0L, false)
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val interfaceCounters = readInterfaceCounters(interfaceName)
        val deviceRx = android.net.TrafficStats.getTotalRxBytes()
        val deviceTx = android.net.TrafficStats.getTotalTxBytes()

        // Some Android builds expose the VPN interface through LinkProperties
        // but do not update /proc/net/dev counters for a userspace TUN fd.
        // Keep interface counters as the primary source and fall back to the
        // device counters so the session never gets stuck at 0 B.
        if (interfaceCounters != null) {
            if (trafficInterface != interfaceName || trafficBaseRx < 0L || trafficBaseTx < 0L) {
                trafficInterface = interfaceName
                trafficBaseRx = interfaceCounters.first
                trafficBaseTx = interfaceCounters.second
                trafficLastRx = interfaceCounters.first
                trafficLastTx = interfaceCounters.second
                trafficLastAt = now
            } else {
                val elapsedMs = (now - trafficLastAt).coerceAtLeast(1L)
                val rxRate = ((interfaceCounters.first - trafficLastRx).coerceAtLeast(0L) * 1000L / elapsedMs)
                val txRate = ((interfaceCounters.second - trafficLastTx).coerceAtLeast(0L) * 1000L / elapsedMs)
                trafficLastRx = interfaceCounters.first
                trafficLastTx = interfaceCounters.second
                trafficLastAt = now
                if (interfaceCounters.first > trafficBaseRx || interfaceCounters.second > trafficBaseTx) {
                    return TrafficSnapshot(
                        interfaceName,
                        (interfaceCounters.first - trafficBaseRx).coerceAtLeast(0L),
                        (interfaceCounters.second - trafficBaseTx).coerceAtLeast(0L),
                        rxRate,
                        txRate,
                        rxRate > 0L || txRate > 0L
                    )
                }
            }
        }

        if (deviceRx < 0L || deviceTx < 0L) {
            return TrafficSnapshot(interfaceName, 0L, 0L, 0L, 0L, false)
        }

        if (deviceBaseRx < 0L || deviceBaseTx < 0L) {
            deviceBaseRx = deviceRx
            deviceBaseTx = deviceTx
            deviceLastRx = deviceRx
            deviceLastTx = deviceTx
            trafficLastAt = now
            return TrafficSnapshot(interfaceName, 0L, 0L, 0L, 0L, false)
        }

        val elapsedMs = (now - trafficLastAt).coerceAtLeast(1L)
        val rxRate = ((deviceRx - deviceLastRx).coerceAtLeast(0L) * 1000L / elapsedMs)
        val txRate = ((deviceTx - deviceLastTx).coerceAtLeast(0L) * 1000L / elapsedMs)
        deviceLastRx = deviceRx
        deviceLastTx = deviceTx
        trafficLastAt = now

        return TrafficSnapshot(
            interfaceName,
            (deviceRx - deviceBaseRx).coerceAtLeast(0L),
            (deviceTx - deviceBaseTx).coerceAtLeast(0L),
            rxRate,
            txRate,
            rxRate > 0L || txRate > 0L
        )
    }

    private fun readInterfaceCounters(interfaceName: String): Pair<Long, Long>? {
        return runCatching {
            java.io.File("/proc/net/dev").useLines { lines ->
                val line = lines.firstOrNull { it.trimStart().startsWith(interfaceName + ":") } ?: return@useLines null
                val data = line.substringAfter(":").trim().split(" ").filter { it.isNotBlank() }
                if (data.size < 9) return@useLines null
                val rx = data[0].toLongOrNull() ?: return@useLines null
                val tx = data[8].toLongOrNull() ?: return@useLines null
                rx to tx
            }
        }.getOrNull()
    }

    private fun resetTrafficCounters() {
        trafficInterface = null
        trafficBaseRx = -1L
        trafficBaseTx = -1L
        trafficLastRx = -1L
        trafficLastTx = -1L
        trafficLastAt = 0L
        deviceBaseRx = -1L
        deviceBaseTx = -1L
        deviceLastRx = -1L
        deviceLastTx = -1L
    }

    private fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble()
        return when {
            value >= 1024.0 * 1024.0 * 1024.0 -> String.format("%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
            value >= 1024.0 * 1024.0 -> String.format("%.1f MB", value / (1024.0 * 1024.0))
            value >= 1024.0 -> String.format("%.0f KB", value / 1024.0)
            else -> bytes.coerceAtLeast(0L).toString() + " B"
        }
    }

    private fun formatRate(bytesPerSecond: Long): String {
        val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
        return when {
            value >= 1024.0 * 1024.0 -> String.format("%.1f MB/s", value / (1024.0 * 1024.0))
            value >= 1024.0 -> String.format("%.0f KB/s", value / 1024.0)
            else -> bytesPerSecond.coerceAtLeast(0L).toString() + " B/s"
        }
    }
    private fun isRunning(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val vpnActive = connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        return vpnActive ||
            getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
                .getBoolean(NeoTunVpnService.KEY_RUNNING, false)
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_VPN = 100
        private const val UI_PREFS = "neotun_ui"
        private const val SELECTED = "selected_profile"
    }
}
