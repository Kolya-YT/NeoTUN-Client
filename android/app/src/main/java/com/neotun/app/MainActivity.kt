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
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var updater: AppUpdater
    private lateinit var store: ProfileStore
    private lateinit var subscriptions: SubscriptionStore
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var bottomActions: LinearLayout
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
            setPadding(dp(16), dp(12), dp(16), dp(20))
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
        bottomActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(Color.rgb(7, 9, 16))
            visibility = View.GONE
        }
        root.addView(bottomActions, LinearLayout.LayoutParams(-1, -2))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.rgb(12, 15, 25))
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(68)))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(dp(16) + bars.left, dp(10) + bars.top,
                dp(16) + bars.right, dp(18) + bars.bottom)
            nav.setPadding(dp(8) + bars.left, dp(4), dp(8) + bars.right, dp(4))
            nav.layoutParams = nav.layoutParams.apply { height = dp(58) + bars.bottom }
            bottomActions.setPadding(dp(14) + bars.left, dp(6), dp(14) + bars.right, dp(6))
            view.requestLayout()
            insets
        }
        ViewCompat.requestApplyInsets(root)
        setContentView(root)
    }

    private fun showScreen(value: Screen) {
        screen = value
        bottomActions.visibility = if (value == Screen.PROFILES) View.VISIBLE else View.GONE
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
        val error = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
            .getString(NeoTunVpnService.KEY_ERROR, null)?.takeIf { it.isNotBlank() }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(txt("NeoTUN", 23f, Color.WHITE, Typeface.BOLD))
        brand.addView(txt("Подключение к серверам", 11f, Color.rgb(133, 139, 158)), margins(top = 2))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(txt(if (running) "●  В сети" else "○  Не подключено", 11f,
            if (running) Color.rgb(94, 220, 158) else Color.rgb(145, 150, 168), Typeface.BOLD))
        top.addView(iconButton("＋", 23) { showImportMenu() }.apply {
            background = rounded(Color.rgb(27, 30, 46), 13, Color.rgb(43, 47, 68), 1)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(dp(10), 0, 0, 0) })
        content.addView(top)

        if (error != null) {
            val errorCard = card().apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(Color.rgb(45, 25, 35), 13, Color.rgb(112, 54, 72), 1)
            }
            errorCard.addView(txt("Не удалось подключиться", 12f, Color.rgb(255, 176, 190), Typeface.BOLD))
            errorCard.addView(txt(error, 10.5f, Color.rgb(223, 174, 187)).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, margins(top = 4))
            content.addView(errorCard, margins(bottom = 8))
        }

        val connection = card().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(if (running) Color.rgb(17, 42, 36) else Color.rgb(21, 23, 39),
                18, if (running) Color.rgb(39, 91, 72) else Color.rgb(42, 44, 68), 1)
        }
        val stateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stateRow.addView(txt(if (running) "СОЕДИНЕНИЕ АКТИВНО" else "ВЫБРАННЫЙ СЕРВЕР",
            9.5f, if (running) Color.rgb(104, 221, 163) else Color.rgb(158, 148, 238), Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        stateRow.addView(txt(if (running) "●" else "○", 10f,
            if (running) Color.rgb(104, 221, 163) else Color.rgb(130, 135, 154), Typeface.BOLD))
        connection.addView(stateRow)
        connection.addView(txt(selected?.name ?: "Выберите сервер", 17f, Color.WHITE, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 8))
        connection.addView(txt(selected?.let { protocolLabel(it) } ?: "Добавьте ссылку или подписку",
            10.5f, Color.rgb(151, 157, 176)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 3, bottom = 10))
        val connectButton = button(if (running) "Отключиться" else "Подключиться") {
            if (running) disconnect()
            else if (selected == null) showImportMenu() else connect(selected)
        }
        connectButton.textSize = 13f
        connectButton.minHeight = dp(43)
        connectButton.background = rounded(if (running) Color.rgb(30, 75, 59) else Color.rgb(104, 88, 226), 13)
        connection.addView(connectButton, LinearLayout.LayoutParams(-1, dp(43)))
        content.addView(connection, margins(bottom = 10))

        val traffic = readVpnTraffic()
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(9), dp(4), dp(9))
            background = rounded(Color.rgb(16, 19, 31), 15, Color.rgb(35, 39, 58), 1)
        }
        stats.addView(metric("↓", formatBytes(traffic.sessionRx), "ПОЛУЧЕНО"),
            LinearLayout.LayoutParams(0, dp(53), 1f))
        stats.addView(View(this).apply { setBackgroundColor(Color.rgb(38, 42, 60)) },
            LinearLayout.LayoutParams(dp(1), dp(35)))
        stats.addView(metric("↑", formatBytes(traffic.sessionTx), "ОТПРАВЛЕНО"),
            LinearLayout.LayoutParams(0, dp(53), 1f))
        stats.addView(View(this).apply { setBackgroundColor(Color.rgb(38, 42, 60)) },
            LinearLayout.LayoutParams(dp(1), dp(35)))
        val rateText = when {
            !running -> "—"
            traffic.interfaceName == null -> "Нет данных"
            else -> formatRate(maxOf(traffic.rxRate, traffic.txRate))
        }
        stats.addView(metric("↯", rateText, "СКОРОСТЬ"),
            LinearLayout.LayoutParams(0, dp(53), 1f))
        content.addView(stats, margins(bottom = 14))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(txt("Все подключения", 16f, Color.WHITE, Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(txt("${profiles.size}  ›", 11f, Color.rgb(164, 151, 255), Typeface.BOLD).apply {
            setOnClickListener { showScreen(Screen.PROFILES) }
        })
        content.addView(titleRow, margins(bottom = 7))

        if (profiles.isEmpty()) {
            val empty = card().apply { setPadding(dp(15), dp(17), dp(15), dp(17)) }
            empty.addView(txt("Серверов пока нет", 14f, Color.WHITE, Typeface.BOLD))
            empty.addView(txt("Добавьте ссылку или подписку, чтобы начать.", 11f,
                Color.rgb(135, 141, 161)), margins(top = 5, bottom = 10))
            empty.addView(button("＋  Добавить сервер") { showImportMenu() })
            content.addView(empty)
        } else {
            val listCard = card().apply { setPadding(dp(6), dp(4), dp(6), dp(4)) }
            profiles.take(5).forEachIndexed { index, p ->
                val row = serverRow(p, p.id == selected?.id, compact = true)
                row.setOnClickListener {
                    setSelectedProfile(p.id)
                    renderHome()
                }
                listCard.addView(row)
                if (index < minOf(4, profiles.lastIndex)) {
                    listCard.addView(View(this).apply { setBackgroundColor(Color.rgb(35, 39, 57)) },
                        LinearLayout.LayoutParams(-1, dp(1)))
                }
            }
            if (profiles.size > 5) {
                val more = txt("Показать все серверы  →", 11f, Color.rgb(164, 151, 255), Typeface.BOLD, Gravity.CENTER)
                more.setPadding(0, dp(10), 0, dp(8))
                more.setOnClickListener { showScreen(Screen.PROFILES) }
                listCard.addView(more)
            }
            content.addView(listCard)
        }
    }

    private fun renderProfiles() {
        content.removeAllViews()
        addBackHeader("Серверы", store.all().size.toString() + " серверов • " + subscriptions.all().size + " подписок")
        content.addView(button("＋  Импортировать сервер или подписку") { showImportMenu() }, margins(bottom = 14))
        val profiles = store.all()
        val selected = selectedProfileId()
        val subs = subscriptions.all()

        if (subs.isNotEmpty()) {
            val subCard = card()
            subCard.addView(txt("ПОДПИСКИ", 10f, Color.rgb(160, 148, 255), Typeface.BOLD), margins(bottom = 5))
            subs.forEachIndexed { index, sub ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(10), 0, dp(10))
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(txt(sub.name, 14f, Color.WHITE, Typeface.BOLD).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                info.addView(txt(if (sub.lastUpdated > 0)
                    "Обновлено • " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sub.lastUpdated))
                    else "Ожидает первого обновления", 11f, Color.rgb(133, 139, 158)), margins(top = 4))
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(iconButton("↻", 22) { refreshSubscription(sub) }.apply {
                    background = rounded(Color.rgb(29, 31, 51), 13)
                }, LinearLayout.LayoutParams(dp(42), dp(42)))
                subCard.addView(row)
                if (index < subs.lastIndex) subCard.addView(View(this).apply {
                    setBackgroundColor(Color.rgb(35, 39, 58))
                }, LinearLayout.LayoutParams(-1, dp(1)))
            }
            content.addView(subCard, margins(bottom = 14))
        }

        if (profiles.isEmpty()) {
            val empty = card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(26), dp(20), dp(26)) }
            empty.addView(txt("Пока пусто", 21f, Color.WHITE, Typeface.BOLD, Gravity.CENTER))
            empty.addView(txt("Добавьте ссылку на сервер или подписку. NeoTUN сам распознает формат и создаст профиль.",
                13f, Color.rgb(139, 145, 164), Gravity.CENTER).apply { maxLines = 4 },
                margins(top = 8, bottom = 16))
            empty.addView(button("＋  Добавить подключение") { showImportMenu() })
            content.addView(empty)
            return
        }

        content.addView(txt("ВСЕ ПОДКЛЮЧЕНИЯ", 10f, Color.rgb(160, 148, 255), Typeface.BOLD),
            margins(start = 4, bottom = 7))
        val listCard = card().apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        profiles.forEachIndexed { index, p ->
            val row = serverRow(p, p.id == selected, compact = false)
            row.setOnClickListener {
                setSelectedProfile(p.id)
                renderProfiles()
            }
            listCard.addView(row)
            if (index < profiles.lastIndex) listCard.addView(View(this).apply {
                setBackgroundColor(Color.rgb(35, 39, 58))
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        content.addView(listCard, margins(bottom = 12))
        renderProfileBottomActions(profiles.firstOrNull { it.id == selected })
    }

    private fun renderProfileBottomActions(profile: NeoTunProfile?) {
        bottomActions.removeAllViews()
        if (profile == null) {
            bottomActions.visibility = View.GONE
            return
        }
        bottomActions.visibility = View.VISIBLE
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val primary = button(if (isRunning()) "Отключиться" else "Подключиться") {
            if (isRunning()) disconnect() else connect(profile)
        }
        primary.background = rounded(if (isRunning()) Color.rgb(36, 73, 62) else Color.rgb(125, 109, 255), 15)
        row.addView(primary, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(6), 0) })
        row.addView(button("⋯  Действия") { profileActions(profile) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        bottomActions.addView(row)
        bottomActions.addView(txt(profile.name, 10f, Color.rgb(137, 143, 163), Typeface.NORMAL, Gravity.CENTER).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 4))
    }

    private fun serverRow(profile: NeoTunProfile, selected: Boolean, compact: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(5), dp(5))
            background = if (selected) rounded(Color.rgb(29, 30, 49), 13,
                Color.rgb(73, 66, 119), 1) else ColorDrawable(Color.TRANSPARENT)
        }
        val iconSize = if (compact) 38 else 42
        val flag = FrameLayout(this).apply {
            background = rounded(if (selected) Color.rgb(43, 39, 71) else Color.rgb(26, 29, 45), 11)
        }
        flag.addView(txt(countryFlag(profile.name), if (compact) 21f else 23f,
            Color.WHITE, Typeface.NORMAL, Gravity.CENTER), FrameLayout.LayoutParams(-1, -1))
        row.addView(flag, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply {
            setMargins(0, 0, dp(9), 0)
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(txt(profile.name, if (compact) 13f else 14f, Color.WHITE, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val detail = if (compact) maskUri(profile.uri) else protocolLabel(profile) + "  ·  " + maskUri(profile.uri)
        info.addView(txt(detail, 10f, Color.rgb(139, 145, 164)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 3))
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(txt(if (selected) "✓" else "›", if (selected) 18f else 22f,
            if (selected) Color.rgb(103, 222, 160) else Color.rgb(100, 106, 127),
            Typeface.BOLD, Gravity.CENTER), LinearLayout.LayoutParams(dp(25), dp(42)))
        row.minimumHeight = dp(if (compact) 50 else 56)
        return row
    }

    private fun renderSettings() {
        content.removeAllViews()
        addBackHeader("Настройки", "Только параметры, которые реально влияют на NeoTUN")
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)

        val connection = settingsSection("СОЕДИНЕНИЕ")
        connection.addView(settingsRow("🌐 DNS",
            prefs.getString("dns_mode", "Автоматический") ?: "Автоматический",
            "Системный DNS или выбранный сервер") { showDnsSettings() })
        connection.addView(settingsRow("🔌 MTU",
            prefs.getInt("mtu", 1500).toString(),
            "Применяется при следующем подключении") { showMtuSettings() })
        connection.addView(settingsRow("📡 IPv6",
            if (prefs.getBoolean("ipv6_enabled", false)) "Включён" else "Выключен",
            "Добавляет IPv6-адрес и маршрут в TUN") {
            toggleSetting("ipv6_enabled", "IPv6") { renderSettings() }
        })
        content.addView(connection, margins(bottom = 10))

        val subscriptionsSection = settingsSection("ПОДПИСКИ")
        subscriptionsSection.addView(settingsRow("🔄 Автообновление",
            if (prefs.getBoolean("subscriptions_auto_update", true)) "Включено" else "Выключено",
            "Проверять подписки при запуске, не чаще раза в 12 часов") {
            toggleSetting("subscriptions_auto_update", "Автообновление") { renderSettings() }
        })
        subscriptionsSection.addView(settingsRow("🗂️ Серверы и подписки",
            store.all().size.toString() + " / " + subscriptions.all().size,
            "Выбор, удаление и обновление") { showScreen(Screen.PROFILES) })
        content.addView(subscriptionsSection, margins(bottom = 10))

        val tools = settingsSection("СЕРВИС")
        tools.addView(settingsRow("🧪 Диагностика", "Открыть",
            "Последние ошибки TUN, DNS и движка") { diagnostics() })
        tools.addView(settingsRow("⬆️ Обновление", "Проверить",
            "Проверка новой версии приложения") { checkUpdates() })
        tools.addView(settingsRow("♻️ Сброс настроек", "",
            "Профили и подписки останутся на месте") { confirmResetSettings() })
        content.addView(tools, margins(bottom = 10))

        val about = card()
        about.addView(txt("NeoTUN", 19f, Color.WHITE, Typeface.BOLD))
        about.addView(txt("Версия 0.4.5 • Core " + NeoTunCore.nativeVersion(), 12f, Color.rgb(135, 140, 157)),
            margins(top = 5))
        about.addView(txt(
            "Неработающие переключатели убраны. Настройки применяются только там, где их поддерживает текущий движок.",
            11f, Color.rgb(105, 110, 128)
        ), margins(top = 8))
        content.addView(about, margins(bottom = 18))
    }



    private fun settingsSection(title: String): LinearLayout {
        val section = card().apply { setPadding(dp(14), dp(10), dp(14), dp(8)) }
        section.addView(txt(title, 10f, Color.rgb(160, 148, 255), Typeface.BOLD),
            margins(start = 2, bottom = 2))
        return section
    }

    private fun settingsRow(title: String, value: String, summary: String, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(11), 0, dp(11))
            isClickable = true
            setOnClickListener { action() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(txt(title, 14f, Color.WHITE, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(txt(summary, 11f, Color.rgb(124, 129, 147)).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 3))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (value.isNotBlank()) {
            row.addView(txt(value, 11.5f, Color.rgb(174, 161, 255), Typeface.BOLD, Gravity.END).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(112), -2))
        }
        row.addView(txt("›", 23f, Color.rgb(94, 99, 117), Gravity.CENTER),
            LinearLayout.LayoutParams(dp(28), dp(42)))
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
        AlertDialog.Builder(this).setTitle("DNS")
            .setSingleChoiceItems(values, checked) { dialog, which ->
                prefs.edit().putString("dns_mode", values[which]).apply()
                dialog.dismiss()
                toast("DNS сохранён. Переподключитесь для применения.")
                renderSettings()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }



    private fun showMtuSettings() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getSharedPreferences(UI_PREFS, MODE_PRIVATE).getInt("mtu", 1500).toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("MTU")
            .setMessage("Допустимо 1280–1500. Значение применяется при следующем подключении.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in 1280..1500) {
                    toast("MTU должен быть от 1280 до 1500")
                    return@setPositiveButton
                }
                getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putInt("mtu", value).apply()
                toast("MTU сохранён: $value")
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
            inbound.put("auto_route", true)
            inbound.put("dns_mode", "hijack")
            inbound.put("dns_address", JSONArray().put("172.19.0.2"))
        }

        val dnsIp = when {
            dnsMode.contains("1.1.1.1") -> "1.1.1.1"
            dnsMode.contains("8.8.8.8") -> "8.8.8.8"
            dnsMode.contains("9.9.9.9") -> "9.9.9.9"
            else -> null
        }
        val dnsServer = if (dnsIp == null) {
            JSONObject().put("type", "local").put("tag", "system")
        } else {
            JSONObject().put("type", "udp").put("tag", "selected-dns")
                .put("server", dnsIp).put("server_port", 53)
        }
        root.put("dns", JSONObject()
            .put("servers", JSONArray().put(dnsServer))
            .put("final", if (dnsIp == null) "system" else "selected-dns")
            .put("strategy", if (ipv6) "prefer_ipv4" else "ipv4_only"))

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("auto_detect_interface", true)
        route.put("final", "proxy")
        route.remove("rules")
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

    private fun decodeUriValue(value: String): String {
        return runCatching {
            java.net.URLDecoder.decode(value, "UTF-8")
        }.getOrDefault(value)
    }

    private fun uriParam(uri: String, key: String): String? {
        val query = uri.substringAfter('?', "").substringBefore('#')
        return query.split('&')
            .asSequence()
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq) to it.substring(eq + 1)
            }
            .firstOrNull { it.first.equals(key, ignoreCase = true) }
            ?.second
            ?.let(::decodeUriValue)
            ?.substringBefore('#')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun protocolLabel(p: NeoTunProfile): String {
        val scheme = p.uri.substringBefore("://").uppercase().ifBlank { "PROFILE" }
        val transport = uriParam(p.uri, "type")?.uppercase()
        val security = uriParam(p.uri, "security")?.uppercase()
        val core = engineLabel(p.engine)

        // Do not leak the URI fragment/name into the transport label.
        // Some subscription generators percent-encode "#" as %23 inside a value.
        val cleanTransport = transport?.substringBefore('#')?.substringBefore("%23")
        val cleanSecurity = security?.substringBefore('#')?.substringBefore("%23")

        return listOf(scheme, cleanTransport, cleanSecurity, core)
            .filter { !it.isNullOrBlank() }
            .distinct()
            .joinToString("  •  ")
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
        nav.removeAllViews()
        val items = listOf("⌂" to "Главная", "⇄" to "Серверы", "⚙" to "Настройки")
        items.forEachIndexed { index, pair ->
            val target = when (index) { 0 -> Screen.HOME; 1 -> Screen.PROFILES; else -> Screen.SETTINGS }
            val selected = screen == target
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(3))
                background = rounded(if (selected) Color.rgb(36, 33, 66) else Color.TRANSPARENT, 15)
                isClickable = true
                setOnClickListener { showScreen(target) }
            }
            item.addView(txt(pair.first, 21f,
                if (selected) Color.rgb(174, 161, 255) else Color.rgb(117, 123, 145),
                Typeface.BOLD, Gravity.CENTER))
            item.addView(txt(pair.second, 10f,
                if (selected) Color.WHITE else Color.rgb(117, 123, 145),
                if (selected) Typeface.BOLD else Typeface.NORMAL, Gravity.CENTER), margins(top = 3))
            nav.addView(item, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            })
        }
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
        background = rounded(Color.rgb(17, 20, 33), 20, Color.rgb(38, 42, 64), 1)
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
        background = rounded(Color.rgb(32, 35, 57), 14, Color.rgb(65, 61, 100), 1)
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
        val cleanHost = decodeUriValue(host).substringBefore('#').trim()
        val transport = uriParam(uri, "type")?.uppercase()?.substringBefore('#')
        val security = uriParam(uri, "security")?.uppercase()?.substringBefore('#')
        return listOfNotNull(
            cleanHost.takeIf { it.isNotBlank() },
            transport?.takeIf { it.isNotBlank() },
            security?.takeIf { it.isNotBlank() }
        ).distinct().joinToString("  •  ").ifBlank { "Подключение" }
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
            ?: findVpnInterfaceFromSysfs()

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

    private fun findVpnInterfaceFromSysfs(): String? {
        return runCatching {
            java.io.File("/sys/class/net").listFiles()
                ?.map { it.name }
                ?.firstOrNull { it.matches(Regex("(tun|utun|wg)\\d+")) }
        }.getOrNull()
    }

    private fun readInterfaceCounters(interfaceName: String): Pair<Long, Long>? {
        val sysfs = runCatching {
            val root = java.io.File("/sys/class/net/$interfaceName/statistics")
            val rx = java.io.File(root, "rx_bytes").readText().trim().toLong()
            val tx = java.io.File(root, "tx_bytes").readText().trim().toLong()
            rx to tx
        }.getOrNull()
        if (sysfs != null) return sysfs

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
