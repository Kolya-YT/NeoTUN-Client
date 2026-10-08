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
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var updater: AppUpdater
    private lateinit var store: ProfileStore
    private lateinit var subscriptions: SubscriptionStore
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
    private var shellRoot: LinearLayout? = null
    private var scroll: ScrollView? = null
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
        subscriptions = SubscriptionStore(this)
        migrateLegacyProfile()
        if (getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean("subscriptions_auto_update", true)) {
            refreshDueSubscriptions()
        }
        buildShell()
        showScreen(Screen.HOME)
        handler.post(poll)
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 9, 16))
            clipChildren = true
            clipToPadding = false
        }
        shellRoot = root

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            clipChildren = true
            clipToPadding = true
        }

        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipChildren = true
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // HAPP-inspired navigation: the home screen is the hub, with settings
        // and adding profiles available from the top bar. Keep the old nav
        // object hidden so older code paths remain harmless.
        nav = LinearLayout(this).apply { visibility = android.view.View.GONE }
        root.addView(nav, LinearLayout.LayoutParams(1, 1))

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(
                dp(16) + bars.left,
                dp(12) + bars.top,
                dp(16) + bars.right,
                dp(24) + bars.bottom
            )
            scroll?.layoutParams = (scroll?.layoutParams ?: LinearLayout.LayoutParams(-1, 0, 1f)).apply {
                width = -1
            }
            content.layoutParams = FrameLayout.LayoutParams(-1, -2)
            view.requestLayout()
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
        val running = isRunning()
        val compact = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean("compact_profiles", false) ||
            resources.displayMetrics.widthPixels < dp(360)

        // Top bar — intentionally minimal like HAPP, but with NeoTUN's purple accent.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        top.addView(iconButton("⚙", 30) { showScreen(Screen.SETTINGS) },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        top.addView(txt("NeoTUN", if (compact) 22f else 24f, Color.WHITE, Typeface.BOLD, Gravity.CENTER),
            LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(iconButton("+", 34) { showImportMenu() },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        content.addView(top)

        // Large connection control.
        val connectZone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val powerOuter = FrameLayout(this).apply {
            background = rounded(
                if (running) Color.rgb(42, 66, 62) else Color.rgb(23, 27, 57),
                100,
                if (running) Color.rgb(74, 211, 145) else Color.rgb(76, 69, 176),
                1
            )
            elevation = dp(2).toFloat()
        }
        val ring = FrameLayout(this).apply {
            background = rounded(
                if (running) Color.rgb(42, 104, 83) else Color.rgb(35, 39, 91),
                100,
                if (running) Color.rgb(81, 221, 155) else Color.rgb(93, 82, 226),
                8
            )
        }
        val power = TextView(this).apply {
            text = "⏻"
            textSize = if (compact) 48f else 54f
            gravity = Gravity.CENTER
            setTextColor(if (running) Color.rgb(103, 235, 174) else Color.rgb(151, 141, 255))
            typeface = Typeface.DEFAULT
            setOnClickListener {
                if (running) disconnect()
                else if (selected == null) showScreen(Screen.PROFILES)
                else connect(selected)
            }
        }
        ring.addView(power, FrameLayout.LayoutParams(-1, -1))
        powerOuter.addView(ring, FrameLayout.LayoutParams(dp(if (compact) 188 else 210), dp(if (compact) 188 else 210), Gravity.CENTER))
        connectZone.addView(powerOuter, LinearLayout.LayoutParams(dp(if (compact) 226 else 248), dp(if (compact) 226 else 248)))
        connectZone.addView(txt(
            if (running) "Подключено" else "Не подключено",
            17f, if (running) Color.rgb(91, 224, 154) else Color.rgb(185, 188, 201),
            Typeface.BOLD, Gravity.CENTER
        ), margins(top = 8))
        connectZone.addView(txt(
            selected?.name ?: "Добавьте профиль",
            13f, Color.rgb(128, 133, 151), Typeface.NORMAL, Gravity.CENTER
        ).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            margins(top = 4, bottom = 10))
        content.addView(connectZone)

        // Compact traffic strip.
        val traffic = readVpnTraffic()
        val trafficCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(17, 21, 38), 18, Color.rgb(31, 36, 60), 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        trafficCard.addView(txt("↓ " + formatBytes(traffic.sessionRx), 13f, Color.rgb(205, 208, 220), Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        trafficCard.addView(txt("↑ " + formatBytes(traffic.sessionTx), 13f, Color.rgb(205, 208, 220), Typeface.BOLD, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        trafficCard.addView(txt(
            if (traffic.hasTraffic) formatRate(traffic.rxRate) else "—",
            13f, Color.rgb(143, 132, 255), Typeface.BOLD, Gravity.END
        ), LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(trafficCard, margins(bottom = 12))

        // HAPP-like profile/subscription card. Each local profile is a row.
        val listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(17, 20, 38), 22, Color.rgb(31, 35, 57), 1)
            clipChildren = true
        }

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        listHeader.addView(txt("NeoTUN", 18f, Color.WHITE, Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        listHeader.addView(txt(
            if (profiles.isEmpty()) "0 профилей" else profiles.size.toString() + " профилей",
            12f, Color.rgb(137, 141, 159), Typeface.NORMAL, Gravity.END
        ))
        listCard.addView(listHeader)

        if (profiles.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(20), dp(20), dp(22))
            }
            empty.addView(txt("Профилей пока нет", 16f, Color.WHITE, Typeface.BOLD, Gravity.CENTER))
            empty.addView(txt("Нажмите + и добавьте VLESS-ссылку.", 13f, Color.rgb(140, 145, 162), Gravity.CENTER),
                margins(top = 6))
            empty.addView(button("Добавить профиль") { showImportMenu() }, margins(top = 14))
            listCard.addView(empty)
        } else {
            profiles.forEachIndexed { index, p ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(11), dp(12), dp(11))
                    setBackgroundColor(if (p.id == selected?.id) Color.rgb(27, 31, 57) else Color.TRANSPARENT)
                    setOnClickListener {
                        setSelectedProfile(p.id)
                        renderHome()
                    }
                }
                row.addView(txt(countryFlag(p.name), 24f, Color.WHITE, Typeface.NORMAL, Gravity.CENTER),
                    LinearLayout.LayoutParams(dp(38), dp(48)))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(txt(p.name, 15f, Color.WHITE, Typeface.BOLD).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                info.addView(txt(
                    protocolLabel(p),
                    11f, Color.rgb(130, 135, 154)
                ), margins(top = 3))
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(txt(if (p.id == selected?.id) "✓" else "›", 22f,
                    if (p.id == selected?.id) Color.rgb(121, 222, 158) else Color.rgb(112, 116, 136),
                    Typeface.BOLD, Gravity.CENTER), LinearLayout.LayoutParams(dp(28), dp(48)))
                listCard.addView(row)
                if (index < profiles.lastIndex) {
                    listCard.addView(View(this).apply {
                        setBackgroundColor(Color.rgb(30, 34, 54))
                    }, LinearLayout.LayoutParams(-1, dp(1)))
                }
            }
            val footer = TextView(this).apply {
                text = "Управление профилями"
                textSize = 12f
                setTextColor(Color.rgb(142, 130, 255))
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(14))
                setOnClickListener { showScreen(Screen.PROFILES) }
            }
            listCard.addView(footer)
        }
        content.addView(listCard, margins(bottom = 12))

        if (!traffic.interfaceName.isNullOrBlank()) {
            content.addView(txt("TUN: " + traffic.interfaceName, 10f, Color.rgb(88, 93, 110), Gravity.CENTER),
                margins(bottom = 6))
        }
    }

    private fun renderProfiles() {
        content.removeAllViews()
        addBackHeader("Профили", "Выберите сервер или добавьте новый")
        content.addView(button("+  Добавить профиль") { showImportMenu() }, margins(bottom = 12))

        val subs = subscriptions.all()
        if (subs.isNotEmpty()) {
            val subCard = card()
            subCard.addView(txt("ПОДПИСКИ", 12f, Color.rgb(139, 126, 255), Typeface.BOLD))
            subs.forEach { sub ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(txt(sub.name, 14f, Color.WHITE, Typeface.BOLD))
                info.addView(txt(
                    if (sub.lastUpdated > 0) "Обновлено • " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sub.lastUpdated))
                    else "Ещё не обновлялась",
                    11f, Color.rgb(125, 130, 148)
                ), margins(top = 3))
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("↻") { refreshSubscription(sub) }, LinearLayout.LayoutParams(dp(52), dp(42)))
                subCard.addView(row)
            }
            content.addView(subCard, margins(bottom = 12))
        }
        val selected = selectedProfileId()
        val profiles = store.all()
        if (profiles.isEmpty()) {
            val empty = card()
            empty.addView(txt("Профилей пока нет", 18f, Color.WHITE, Typeface.BOLD))
            empty.addView(txt("Добавьте VLESS-ссылку — она сохранится на устройстве.", 13f, Color.rgb(145, 150, 168)), margins(top = 7))
            content.addView(empty)
            return
        }
        profiles.forEach { p ->
            val item = card()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(txt(countryFlag(p.name), 26f, Color.WHITE, Gravity.CENTER), LinearLayout.LayoutParams(dp(42), dp(48)))
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(txt(p.name, 17f, Color.WHITE, Typeface.BOLD).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            box.addView(txt(protocolLabel(p), 12f, Color.rgb(145, 149, 166)), margins(top = 4))
            row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
            if (p.id == selected) row.addView(txt("✓", 20f, Color.rgb(92, 213, 142), Typeface.BOLD))
            item.addView(row)
            val compactProfiles = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean("compact_profiles", false)
            item.addView(txt(maskUri(p.uri), if (compactProfiles) 10f else 11f, Color.rgb(105, 110, 128)), margins(top = if (compactProfiles) 6 else 10))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val actionHeight = if (getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean("compact_profiles", false)) 40 else 44
            actions.addView(button(if (p.id == selected) "Выбран" else "Выбрать") { selectProfile(p) },
                LinearLayout.LayoutParams(0, dp(actionHeight), 1f).apply { setMargins(0, dp(8), dp(5), 0) })
            actions.addView(button("⋮") { profileActions(p) },
                LinearLayout.LayoutParams(dp(54), dp(44)).apply { setMargins(dp(5), dp(10), 0, 0) })
            item.addView(actions)
            content.addView(item, margins(bottom = 9))
        }
    }

    private fun renderSettings() {
        content.removeAllViews()
        addBackHeader("Настройки", "NeoTUN • Android")
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)

        val about = card()
        about.addView(txt("NeoTUN", 21f, Color.WHITE, Typeface.BOLD))
        about.addView(txt("Core " + NeoTunCore.nativeVersion() + " • Rust", 13f, Color.rgb(145, 150, 168)), margins(top = 5))
        about.addView(txt("MTU, DNS и IPv6 применяются при следующем подключении. Остальные параметры работают сразу.", 13f, Color.rgb(145, 150, 168)), margins(top = 9))
        content.addView(about, margins(bottom = 10))

        val connection = settingsSection("СОЕДИНЕНИЕ")
        connection.addView(settingsRow("⚡ Режим подключения", "Автоматический", "NeoTUN выбирает Xray или sing-box по профилю") { toast("Автоматический выбор движка включён") })
        connection.addView(settingsRow("🌐 DNS", prefs.getString("dns_mode", "Автоматический") ?: "Автоматический", "DNS внутри VPN") { showDnsSettings() })
        connection.addView(settingsRow("📡 IPv6", if (prefs.getBoolean("ipv6_enabled", false)) "Включён" else "Выключен", "IPv6-маршрутизация через TUN") { toggleSetting("ipv6_enabled", "IPv6") { renderSettings() } })
        connection.addView(settingsRow("🔌 MTU", prefs.getInt("mtu", 1500).toString(), "Размер пакета VPN-интерфейса") { showMtuSettings() })
        content.addView(connection, margins(bottom = 10))

        val subscriptionsSection = settingsSection("ПОДПИСКИ")
        subscriptionsSection.addView(settingsRow("🔄 Автообновление", if (prefs.getBoolean("subscriptions_auto_update", true)) "Включено" else "Выключено", "Обновлять подписки при запуске приложения") { toggleSetting("subscriptions_auto_update", "Автообновление") { renderSettings() } })
        subscriptionsSection.addView(settingsRow("📋 Импорт из буфера", "Автоматический", "HTTP(S) → подписка, share-link → профиль") { toast("Импорт из буфера настроен автоматически") })
        subscriptionsSection.addView(settingsRow("🗂️ Управление профилями", "${store.all().size} профилей", "Удаление, переименование и выбор профиля") { showScreen(Screen.PROFILES) })
        content.addView(subscriptionsSection, margins(bottom = 10))

        val appearance = settingsSection("ВНЕШНИЙ ВИД")
        appearance.addView(settingsRow("🎨 Тема", "NeoTUN Dark", "Тёмная тема приложения") { toast("NeoTUN Dark — основной стиль приложения") })
        appearance.addView(settingsRow("📱 Компактный список", if (prefs.getBoolean("compact_profiles", false)) "Включён" else "Выключен", "Уменьшить высоту карточек профилей") { toggleSetting("compact_profiles", "Компактный список") { renderSettings(); showScreen(Screen.PROFILES) } })
        content.addView(appearance, margins(bottom = 10))

        val behavior = settingsSection("ПОВЕДЕНИЕ")
        behavior.addView(settingsRow("🔔 Уведомления", if (prefs.getBoolean("notifications_enabled", true)) "Включены" else "Выключены", "Системное уведомление активного соединения") { toggleSetting("notifications_enabled", "Уведомления") { renderSettings() } })
        behavior.addView(settingsRow("🧪 Диагностика", "Журнал", "Ошибки запуска, TUN, DNS и сетевого движка") { diagnostics() })
        behavior.addView(settingsRow("♻️ Сбросить настройки", "", "Профили и подписки не удаляются") { confirmResetSettings() })
        content.addView(behavior, margins(bottom = 10))

        val protocols = settingsSection("ПРОТОКОЛЫ")
        listOf(
            "VLESS" to "Xray • TCP / WS / gRPC / HTTP / HTTPUpgrade / XHTTP",
            "VMess" to "sing-box • проверяется",
            "Trojan" to "sing-box • проверяется",
            "Hysteria2" to "sing-box • проверяется",
            "TUIC" to "sing-box • проверяется",
            "Shadowsocks" to "sing-box • проверяется",
            "WireGuard / AmneziaWG" to "Следующий этап"
        ).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
            row.addView(txt(pair.first, 14f, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(txt(pair.second, 11f, Color.rgb(137, 142, 160), Gravity.END).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1.25f))
            protocols.addView(row)
        }
        content.addView(protocols, margins(bottom = 12))
        content.addView(button("Проверить обновления") { checkUpdates() }, margins(bottom = 8))
        content.addView(button("Лог подключения") { diagnostics() }, margins(bottom = 18))
    }

    private fun settingsSection(title: String): LinearLayout {
        val section = card()
        section.addView(txt(title, 12f, Color.rgb(139, 126, 255), Typeface.BOLD), margins(bottom = 4))
        return section
    }

    private fun settingsRow(title: String, value: String, summary: String, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener { action() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(txt(title, 14f, Color.WHITE, Typeface.BOLD))
        if (summary.isNotBlank()) texts.addView(txt(summary, 11f, Color.rgb(125, 130, 148)).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 3))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotBlank()) row.addView(txt(value, 12f, Color.rgb(151, 139, 255), textGravity = Gravity.END).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(125), -2))
        row.addView(txt("›", 25f, Color.rgb(100, 102, 120), textGravity = Gravity.CENTER), LinearLayout.LayoutParams(dp(28), dp(42)))
        return row
    }

    private fun toggleSetting(key: String, label: String, after: () -> Unit) {
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        val enabled = !prefs.getBoolean(key, key != "ipv6_enabled")
        prefs.edit().putBoolean(key, enabled).apply()
        toast("$label: " + if (enabled) "включено" else "выключено")
        after()
    }

    private fun showDnsSettings() {
        val values = arrayOf("Автоматический", "Cloudflare • 1.1.1.1", "Google • 8.8.8.8", "Quad9 • 9.9.9.9")
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        val checked = values.indexOf(prefs.getString("dns_mode", values[0])).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("DNS").setSingleChoiceItems(values, checked) { dialog, which ->
            prefs.edit().putString("dns_mode", values[which]).apply()
            dialog.dismiss()
            renderSettings()
        }.setNegativeButton("Отмена", null).show()
    }

    private fun showMtuSettings() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getSharedPreferences(UI_PREFS, MODE_PRIVATE).getInt("mtu", 1500).toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("MTU")
            .setMessage("Рекомендуется 1500. Для проблемных сетей можно попробовать 1280–1500.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val mtu = input.text.toString().toIntOrNull()?.coerceIn(1280, 1500) ?: 1500
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putInt("mtu", mtu).apply()
                toast("MTU: $mtu")
                renderSettings()
            }.show()
    }

    private fun confirmResetSettings() {
        AlertDialog.Builder(this)
            .setTitle("Сбросить настройки?")
            .setMessage("Будут сброшены настройки интерфейса. Профили и подписки не удаляются.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сбросить") { _, _ ->
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().clear().apply()
                toast("Настройки сброшены")
                renderSettings()
            }.show()
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
            val rawConfig = NeoTunCore.nativeShareConfig(profile.uri)
            if (rawConfig.isBlank()) {
                prefs.edit()
                    .putString(NeoTunVpnService.KEY_ERROR, "Не удалось собрать конфигурацию")
                    .apply()
                renderHome()
                return
            }
            val config = runCatching { applyConnectionSettings(rawConfig) }.getOrElse {
                prefs.edit()
                    .putString(NeoTunVpnService.KEY_ERROR, "Ошибка настроек: " + (it.message ?: "некорректная конфигурация"))
                    .apply()
                renderHome()
                return
            }
            prefs.edit().putString(NeoTunVpnService.KEY_CONFIG, config).apply()
        }

        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQUEST_VPN) else startVpnFromPrefs()
    }

    /**
     * Applies the settings screen to the real sing-box configuration.
     * MTU, DNS and IPv6 are written into the config used by libbox.
     */
    private fun applyConnectionSettings(rawConfig: String): String {
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        val root = JSONObject(rawConfig)
        val mtu = prefs.getInt("mtu", 1500).coerceIn(1280, 1500)
        val ipv6 = prefs.getBoolean("ipv6_enabled", false)
        val dnsMode = prefs.getString("dns_mode", "Автоматический") ?: "Автоматический"

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
            inbound.remove("inet4_address")
            inbound.remove("inet6_address")
            inbound.remove("inet4_route_address")
            inbound.remove("inet6_route_address")
            inbound.put("dns_mode", "hijack")
            inbound.put("dns_address", JSONArray().put("172.19.0.2"))
        }

        val dnsIp = when {
            dnsMode.contains("1.1.1.1") -> "1.1.1.1"
            dnsMode.contains("8.8.8.8") -> "8.8.8.8"
            dnsMode.contains("9.9.9.9") -> "9.9.9.9"
            else -> null
        }

        if (dnsIp == null) {
            root.remove("dns")
        } else {
            root.put("dns", JSONObject()
                .put("servers", JSONArray().put(
                    JSONObject()
                        .put("type", "udp")
                        .put("tag", "selected-dns")
                        .put("server", dnsIp)
                        .put("server_port", 53)
                ))
                .put("final", "selected-dns")
                .put("strategy", if (ipv6) "prefer_ipv4" else "ipv4_only")
            )
        }

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("auto_detect_interface", true)
        route.put("final", "proxy")
        if (dnsIp != null) {
            route.put("rules", JSONArray().put(
                JSONObject().put("protocol", JSONArray().put("dns")).put("outbound", "dns-out")
            ))
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
            var hasDnsOutbound = false
            for (i in 0 until outbounds.length()) {
                if (outbounds.optJSONObject(i)?.optString("tag") == "dns-out") {
                    hasDnsOutbound = true
                    break
                }
            }
            if (!hasDnsOutbound) {
                outbounds.put(JSONObject().put("type", "dns").put("tag", "dns-out"))
            }
        } else {
            route.remove("rules")
        }

        return root.toString()
    }

    private fun startVpnFromPrefs() {
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        val engine = prefs.getString(NeoTunVpnService.KEY_ENGINE, NeoTunVpnService.ENGINE_SING_BOX)
        // Reflect the user's action immediately. The service clears this flag if startup fails.
        prefs.edit().putBoolean(NeoTunVpnService.KEY_RUNNING, true).apply()
        if (engine == NeoTunVpnService.ENGINE_XRAY) {
            val uri = prefs.getString(NeoTunVpnService.KEY_URI, null)
            if (uri.isNullOrBlank()) {
                prefs.edit().putString(NeoTunVpnService.KEY_ERROR, "Нет VLESS-профиля")
                    .putBoolean(NeoTunVpnService.KEY_RUNNING, false)
                    .apply()
                renderHome()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, NeoTunXrayVpnService::class.java).putExtra(NeoTunXrayVpnService.EXTRA_URI, uri))
        } else {
            val config = prefs.getString(NeoTunVpnService.KEY_CONFIG, null)
            if (config.isNullOrBlank()) {
                prefs.edit().putString(NeoTunVpnService.KEY_ERROR, "Нет конфигурации sing-box")
                    .putBoolean(NeoTunVpnService.KEY_RUNNING, false)
                    .apply()
                renderHome()
                return
            }
            ContextCompat.startForegroundService(this, Intent(this, NeoTunVpnService::class.java).putExtra(NeoTunVpnService.EXTRA_CONFIG, config))
        }
        renderHome()
    }

    private fun disconnect() {
        // Send the explicit shutdown command first. The services own the
        // actual Xray/sing-box + TUN teardown.
        runCatching {
            startService(
                Intent(this, NeoTunXrayVpnService::class.java)
                    .setAction(NeoTunXrayVpnService.ACTION_DISCONNECT)
            )
        }
        runCatching {
            startService(
                Intent(this, NeoTunVpnService::class.java)
                    .setAction(NeoTunVpnService.ACTION_DISCONNECT)
            )
        }

        // Also stop both services explicitly. This makes the operation
        // idempotent when only one engine was active.
        runCatching { stopService(Intent(this, NeoTunXrayVpnService::class.java)) }
        runCatching { stopService(Intent(this, NeoTunVpnService::class.java)) }

        getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
            .edit().putBoolean(NeoTunVpnService.KEY_RUNNING, false).apply()
        resetTrafficCounters()
        renderHome()
        handler.postDelayed({
            if (!isFinishing && screen == Screen.HOME) renderHome()
        }, 500L)
    }

    private fun showImportMenu() {
        val items = arrayOf("Вставить из буфера обмена", "QR-код", "Ручной ввод", "Импорт JSON")
        AlertDialog.Builder(this).setTitle("Импорт").setItems(items) { _, which ->
            when (which) {
                0 -> importClipboard()
                1 -> toast("QR-сканер добавим следующим этапом")
                2 -> addProfileDialog()
                3 -> importJsonDialog()
            }
        }.show()
    }

    private fun addSubscriptionDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val name = EditText(this).apply { hint = "Название, например NeoTUN.ru" }
        val url = EditText(this).apply {
            hint = "https://example.com/subscription"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(name, LinearLayout.LayoutParams(-1, dp(54)))
        box.addView(url, LinearLayout.LayoutParams(-1, dp(64)))
        val dialog = AlertDialog.Builder(this).setTitle("Добавить подписку")
            .setMessage("NeoTUN будет загружать список серверов из этой ссылки.")
            .setView(box).setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить") { _, _ ->
                val source = url.text.toString().trim()
                if (!source.startsWith("http://", true) && !source.startsWith("https://", true)) {
                    toast("Укажите HTTP(S)-ссылку на подписку")
                    return@setPositiveButton
                }
                val subscription = NeoTunSubscription(UUID.randomUUID().toString(), name.text.toString().trim().ifBlank { "Подписка" }, source)
                subscriptions.save(subscription)
                refreshSubscription(subscription)
            }.create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun refreshSubscription(subscription: NeoTunSubscription) {
        toast("Обновляем ${subscription.name}…")
        Thread {
            val result = subscriptions.refresh(subscription, store)
            runOnUiThread {
                result.onSuccess {
                    if (selectedProfileId() == null) store.all().firstOrNull()?.let { setSelectedProfile(it.id) }
                    showScreen(Screen.HOME)
                    toast("${subscription.name}: импортировано $it профилей")
                }.onFailure { toast("Подписка: ${it.message ?: "ошибка обновления"}") }
            }
        }.start()
    }

    private fun refreshDueSubscriptions() {
        val now = System.currentTimeMillis()
        subscriptions.all().filter { it.lastUpdated == 0L || now - it.lastUpdated >= 12L * 60L * 60L * 1000L }.forEach { sub ->
            Thread { subscriptions.refresh(sub, store) }.start()
        }
    }

    private fun importClipboard() {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
        if (text.isBlank()) {
            toast("Буфер обмена пуст")
            return
        }

        // A single HTTP(S) URL is a subscription. Share links are imported as profiles.
        val firstToken = text.lineSequence()
            .flatMap { it.trim().split(Regex("[,\\s]+")).asSequence() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        if (firstToken.startsWith("http://", true) || firstToken.startsWith("https://", true)) {
            addSubscriptionFromClipboard(firstToken)
            return
        }

        importText(text)
    }

    private fun addSubscriptionFromClipboard(url: String) {
        val name = runCatching {
            java.net.URI(url).host?.takeIf { it.isNotBlank() } ?: "Подписка"
        }.getOrDefault("Подписка")

        val existing = subscriptions.all().firstOrNull { it.url == url }
        val subscription = existing ?: NeoTunSubscription(
            UUID.randomUUID().toString(),
            name,
            url
        )

        subscriptions.save(subscription)
        toast("Импорт подписки: $name…")
        Thread {
            val result = subscriptions.refresh(subscription, store)
            runOnUiThread {
                result.onSuccess {
                    if (selectedProfileId() == null) {
                        store.all().firstOrNull()?.let { setSelectedProfile(it.id) }
                    }
                    showScreen(Screen.HOME)
                    toast("Подписка импортирована: $it профилей")
                }.onFailure {
                    showScreen(Screen.PROFILES)
                    toast("Не удалось импортировать подписку: ${it.message ?: "ошибка"}")
                }
            }
        }.start()
    }

    private fun importJsonDialog() {
        val input = EditText(this).apply { hint = "[{url: vless://...}]"; minLines = 5; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val dialog = AlertDialog.Builder(this).setTitle("Импорт JSON")
            .setMessage("Массив объектов с полями url, uri или link.")
            .setView(input).setNegativeButton("Отмена", null)
            .setPositiveButton("Импортировать") { _, _ -> importJsonText(input.text.toString()) }.create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun importJsonText(raw: String) {
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val uri = o.optString("url").ifBlank { o.optString("uri") }.ifBlank { o.optString("link") }
                if (uri.contains("://")) saveImportedShare(uri)
            }
        }.onSuccess { showScreen(Screen.PROFILES); toast("JSON импортирован") }
         .onFailure { toast("Некорректный JSON") }
    }

    private fun importText(raw: String) {
        val candidates = linkedSetOf<String>()
        fun collect(value: String) {
            value.lines()
                .flatMap { it.trim().split(Regex("[,\\s]+")) }
                .filter { it.contains("://") }
                .forEach { candidates.add(it.trim()) }
        }
        collect(raw)
        if (candidates.none { it.contains("://") }) {
            runCatching {
                android.util.Base64.decode(raw.replace("\\s".toRegex(), ""), android.util.Base64.DEFAULT)
                    .toString(java.nio.charset.StandardCharsets.UTF_8)
            }.getOrNull()?.let(::collect)
        }
        val supported = candidates.filter {
            val s = it.substringBefore("://").lowercase()
            s in setOf("vless", "vmess", "trojan", "hysteria2", "hy2", "tuic", "ss")
        }
        if (supported.isEmpty()) {
            toast("Поддерживаемые ссылки не найдены")
            return
        }
        var imported = 0
        supported.forEach {
            val before = store.all().size
            saveImportedShare(it)
            if (store.all().size > before) imported++
        }
        showScreen(Screen.HOME)
        toast("Импортировано профилей: $imported")
    }

    private fun saveImportedShare(uri: String) {
        val engine = NeoTunCore.nativeShareEngine(uri)
        if (engine == "unknown") return
        store.save(NeoTunProfile(
            UUID.randomUUID().toString(),
            ProfileStore.displayNameFromUri(uri),
            uri,
            engine
        ))
    }

    private fun saveImportedVless(uri: String) {
        val engine = NeoTunCore.nativeShareEngine(uri)
        if (engine == "unknown") return
        store.save(NeoTunProfile(UUID.randomUUID().toString(), ProfileStore.displayNameFromUri(uri), uri, engine))
    }
    private fun addProfileDialog() {
        val input = EditText(this).apply {
            hint = "vless:// / vmess:// / hy2:// / tuic:// / ss:// ..."
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
            .setMessage("Вставьте ссылку VLESS, VMess, Trojan, Hysteria2, TUIC или Shadowsocks.")
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
        val engine = NeoTunCore.nativeShareEngine(uri)
        if (engine == "unknown") {
            toast("Не удалось разобрать ссылку")
            return
        }
        val p = NeoTunProfile(
            java.util.UUID.randomUUID().toString(),
            ProfileStore.displayNameFromUri(uri),
            uri,
            engine
        )
        store.save(p)
        setSelectedProfile(p.id)
        showScreen(Screen.PROFILES)
        toast("Профиль добавлен")
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
        waitForDisconnectAndReconnect(profile, 0)
    }

    private fun waitForDisconnectAndReconnect(profile: NeoTunProfile, attempt: Int) {
        if (isFinishing) return
        if (!isRunning()) {
            connect(profile)
            return
        }
        if (attempt >= 16) {
            toast("Не удалось освободить TUN. Попробуйте ещё раз.")
            return
        }
        handler.postDelayed({
            waitForDisconnectAndReconnect(profile, attempt + 1)
        }, 250L)
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

    private fun addBackHeader(title: String, subtitle: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(iconButton("‹", 38) { showScreen(Screen.HOME) },
            LinearLayout.LayoutParams(dp(48), dp(52)))
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(txt(title, 24f, Color.WHITE, Typeface.BOLD))
        textBox.addView(txt(subtitle, 12f, Color.rgb(132, 137, 155)), margins(top = 2))
        row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, margins(bottom = 16))
    }

    private fun iconButton(symbol: String, size: Int, action: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = size.toFloat()
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setOnClickListener { action() }
        background = rounded(Color.TRANSPARENT, 18)
    }

    private fun protocolLabel(p: NeoTunProfile): String {
        val scheme = p.uri.substringBefore("://").uppercase().ifBlank { "PROFILE" }
        val transport = Regex("(?:^|&)type=([^&]+)").find(p.uri.substringAfter("?", ""))?.groupValues?.getOrNull(1)
        val security = Regex("(?:^|&)security=([^&]+)").find(p.uri.substringAfter("?", ""))?.groupValues?.getOrNull(1)
        val core = engineLabel(p.engine)
        return listOf(scheme, transport?.uppercase(), security?.uppercase(), core)
            .filter { !it.isNullOrBlank() }.distinct().joinToString("  •  ")
    }

    private fun countryFlag(name: String): String {
        val n = name.lowercase()
        return when {
            "нидерланд" in n || "netherland" in n -> "🇳🇱"
            "финлянд" in n || "finland" in n -> "🇫🇮"
            "герман" in n || "german" in n || "ютуб" in n -> "🇩🇪"
            "швед" in n || "sweden" in n -> "🇸🇪"
            "франц" in n || "france" in n -> "🇫🇷"
            "сша" in n || "usa" in n -> "🇺🇸"
            "британ" in n || "uk" in n -> "🇬🇧"
            else -> "🌐"
        }
    }

    private fun renderNavigation() {
        // Navigation is intentionally replaced by the HAPP-style top controls.
        nav.visibility = android.view.View.GONE
    }

    private fun header(title: String, subtitle: String) {
        content.addView(txt(
            title,
            if (resources.displayMetrics.widthPixels < dp(360)) 26f else 30f,
            Color.WHITE,
            Typeface.BOLD
        ))
        content.addView(txt(subtitle, 14f, Color.rgb(145, 149, 162)).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 5, bottom = 18))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Color.rgb(15, 18, 30), 20, Color.rgb(35, 39, 61), 1)
        elevation = dp(1).toFloat()
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
        minimumWidth = 0
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(Color.rgb(30, 34, 55), 14, Color.rgb(62, 59, 103), 1)
        setOnClickListener { action() }
    }

    private fun metric(icon: String, value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(txt(icon, 17f, Color.rgb(125, 108, 255), Typeface.BOLD, Gravity.CENTER))
        addView(txt(value, 15f, Color.WHITE, Typeface.BOLD, Gravity.CENTER).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 3))
        addView(txt(label, 10f, Color.rgb(130, 134, 146), textGravity = Gravity.CENTER).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 2))
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
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(start), dp(top), dp(end), dp(bottom))
        }

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
        val interfaceName = connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                connectivity.getLinkProperties(network)?.interfaceName
            }
            .firstOrNull { !it.isNullOrBlank() }

        if (interfaceName.isNullOrBlank()) {
            resetTrafficCounters()
            return TrafficSnapshot(null, 0L, 0L, 0L, 0L, false)
        }

        val counters = readInterfaceCounters(interfaceName)
            ?: return TrafficSnapshot(interfaceName, 0L, 0L, 0L, 0L, false)
        val now = android.os.SystemClock.elapsedRealtime()

        if (trafficInterface != interfaceName || trafficBaseRx < 0L || trafficBaseTx < 0L) {
            trafficInterface = interfaceName
            trafficBaseRx = counters.first
            trafficBaseTx = counters.second
            trafficLastRx = counters.first
            trafficLastTx = counters.second
            trafficLastAt = now
            return TrafficSnapshot(interfaceName, 0L, 0L, 0L, 0L, false)
        }

        val elapsedMs = (now - trafficLastAt).coerceAtLeast(1L)
        val rxDelta = (counters.first - trafficLastRx).coerceAtLeast(0L)
        val txDelta = (counters.second - trafficLastTx).coerceAtLeast(0L)
        trafficLastRx = counters.first
        trafficLastTx = counters.second
        trafficLastAt = now

        return TrafficSnapshot(
            interfaceName,
            (counters.first - trafficBaseRx).coerceAtLeast(0L),
            (counters.second - trafficBaseTx).coerceAtLeast(0L),
            rxDelta * 1000L / elapsedMs,
            txDelta * 1000L / elapsedMs,
            rxDelta > 0L || txDelta > 0L,
        )
    }

    private fun readInterfaceCounters(interfaceName: String): Pair<Long, Long>? {
        return runCatching {
            java.io.File("/proc/net/dev").useLines { lines ->
                val line = lines.firstOrNull {
                    it.trimStart().startsWith(interfaceName + ":")
                } ?: return@useLines null
                val data = line.substringAfter(":").trim().split(Regex("\\s+"))
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
        return getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
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
