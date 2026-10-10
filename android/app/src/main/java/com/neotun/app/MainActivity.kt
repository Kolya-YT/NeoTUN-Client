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
import android.net.TrafficStats
import android.os.Process
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
    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.neotun.app.action.WIDGET_TOGGLE"
    }
    private lateinit var updater: AppUpdater
    private lateinit var store: ProfileStore
    private lateinit var subscriptions: SubscriptionStore
    private lateinit var routingStore: RoutingProfileStore
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
    private var lastWidgetRunning: Boolean? = null
    private var shellRoot: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var homeRxValue: TextView? = null
    private var homeTxValue: TextView? = null
    private var homeSpeedValue: TextView? = null
    private var homeConnectionButton: Button? = null
    private var homeConnectionLabel: TextView? = null
    private var homeStatusDot: TextView? = null
    private var homeNetworkState: TextView? = null
    private var homeConnectionCard: LinearLayout? = null
    private var homeErrorText: TextView? = null
    private val pingResults = mutableMapOf<String, String>()
    private val pingInProgress = mutableSetOf<String>()
    private val poll = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                if (screen == Screen.HOME) updateHomeLiveData()
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
        routingStore = RoutingProfileStore(this)
        migrateLegacyProfile()
        if (getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean("subscriptions_auto_update", true)) {
            refreshDueSubscriptions()
        }
        buildShell()
        showScreen(Screen.HOME)
        handleRoutingIntent(intent)
        handleWidgetToggleIntent(intent)
        refreshDueRoutingProfiles()
        handler.post(poll)
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NeoTunDesign.BACKGROUND)
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
            setBackgroundColor(NeoTunDesign.BACKGROUND)
            visibility = View.GONE
        }
        root.addView(bottomActions, LinearLayout.LayoutParams(-1, -2))
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(NeoTunDesign.NAVIGATION)
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(68)))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(dp(16) + bars.left, dp(10) + bars.top,
                dp(16) + bars.right, dp(18) + bars.bottom)
            nav.setPadding(dp(8) + bars.left, dp(4), dp(8) + bars.right, bars.bottom + dp(4))
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
        content.alpha = 0f
        content.translationY = dp(8).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(220L).start()
    }

    private fun renderHome() {
        homeRxValue = null
        homeTxValue = null
        homeSpeedValue = null
        homeConnectionButton = null
        homeConnectionLabel = null
        homeStatusDot = null
        homeNetworkState = null
        homeConnectionCard = null
        homeErrorText = null
        content.removeAllViews()
        val profiles = store.all()
        val selected = selectedProfile(profiles)
        val running = isRunning()
        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))
        }
        val logo = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(NeoTunDesign.BRAND_VIOLET, NeoTunDesign.BRAND_BLUE)
            ).apply { cornerRadius = dp(16).toFloat() }
        }
        logo.addView(txt("N", 23f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD, Gravity.CENTER),
            FrameLayout.LayoutParams(-1, -1))
        top.addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            setMargins(0, 0, dp(10), 0)
        })
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(txt("NeoTUN", 21f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD))
        brand.addView(txt("Подключение и серверы", 11f, NeoTunDesign.TEXT_MUTED), margins(top = 2))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton("＋", 25) { showImportMenu() }.apply {
            background = rounded(NeoTunDesign.BRAND_SOFT, 15, Color.rgb(54, 58, 83), 1)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        content.addView(top)

        val connection = card().apply {
            setPadding(dp(16), dp(15), dp(16), dp(16))
            background = rounded(
                if (running) NeoTunDesign.SUCCESS_SURFACE else NeoTunDesign.SURFACE,
                19,
                if (running) Color.rgb(52, 116, 91) else NeoTunDesign.BORDER,
                1
            )
            elevation = 0f
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        homeConnectionCard = connection
        val statusDot = txt("●", 11f,
            if (running) NeoTunDesign.SUCCESS else Color.rgb(154, 143, 255), Typeface.BOLD)
        homeStatusDot = statusDot
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(18), -2))
        val statusLabel = txt(if (running) "ПОДКЛЮЧЕНО" else "ГОТОВО К ПОДКЛЮЧЕНИЮ",
            10f, if (running) NeoTunDesign.SUCCESS else Color.rgb(183, 173, 255), Typeface.BOLD)
        homeConnectionLabel = statusLabel
        statusRow.addView(statusLabel, LinearLayout.LayoutParams(0, -2, 1f))
        val networkState = txt(if (running) "●  ONLINE" else "○  OFFLINE", 9f,
            if (running) NeoTunDesign.SUCCESS else NeoTunDesign.TEXT_MUTED, Typeface.BOLD)
        homeNetworkState = networkState
        statusRow.addView(networkState)
        connection.addView(statusRow)

        connection.addView(txt("Подключение", 19f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 12))
        connection.addView(txt(
            selected?.let { protocolLabel(it) } ?: "Добавьте сервер или ссылку подписки",
            11f, NeoTunDesign.TEXT_SECONDARY
        ).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 6, bottom = 18))

        val connectButton = button(if (running) "Отключиться" else "Подключиться") {
            if (isRunning()) disconnect()
            else {
                val current = selectedProfile(store.all())
                if (current == null) showImportMenu() else connect(current)
            }
        }.apply {
            textSize = 14f
            minHeight = dp(50)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (running) intArrayOf(Color.rgb(38, 112, 83), Color.rgb(32, 88, 75))
                else intArrayOf(NeoTunDesign.BRAND_VIOLET, NeoTunDesign.BRAND_BLUE)
            ).apply { cornerRadius = dp(17).toFloat() }
        }
        homeConnectionButton = connectButton
        connection.addView(connectButton, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(connection, margins(bottom = 14))

        val errorCard = card().apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(NeoTunDesign.DANGER_SURFACE, 16, Color.rgb(116, 56, 75), 1)
            visibility = View.GONE
        }
        errorCard.addView(txt("Не удалось подключиться", 13f, NeoTunDesign.DANGER, Typeface.BOLD))
        val errorText = txt("", 11f, Color.rgb(225, 181, 192)).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        homeErrorText = errorText
        errorCard.addView(errorText, margins(top = 5))
        content.addView(errorCard, margins(bottom = 12))
        updateHomeError(prefs.getString(NeoTunVpnService.KEY_ERROR, null))

        val traffic = readVpnTraffic()
        val trafficCard = card().apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(NeoTunDesign.SURFACE, 17, NeoTunDesign.BORDER, 1)
        }
        val trafficHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        trafficHeader.addView(txt("Статистика", 15f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        trafficHeader.addView(txt("LIVE", 9f, Color.rgb(107, 224, 169), Typeface.BOLD).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(Color.rgb(24, 55, 46), 8)
        })
        trafficCard.addView(trafficHeader)
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(3))
        }
        val rxMetric = liveMetric("↓", formatBytes(traffic.sessionRx), "ПОЛУЧЕНО")
        val txMetric = liveMetric("↑", formatBytes(traffic.sessionTx), "ОТПРАВЛЕНО")
        val speed = when {
            !running -> "—"
            traffic.interfaceName == null -> "Ожидание"
            else -> formatRate(maxOf(traffic.rxRate, traffic.txRate))
        }
        val speedMetric = liveMetric("↯", speed, "СКОРОСТЬ")
        homeRxValue = rxMetric.second
        homeTxValue = txMetric.second
        homeSpeedValue = speedMetric.second
        metrics.addView(rxMetric.first, LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(View(this).apply { setBackgroundColor(NeoTunDesign.BORDER) },
            LinearLayout.LayoutParams(dp(1), dp(44)))
        metrics.addView(txMetric.first, LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(View(this).apply { setBackgroundColor(NeoTunDesign.BORDER) },
            LinearLayout.LayoutParams(dp(1), dp(44)))
        metrics.addView(speedMetric.first, LinearLayout.LayoutParams(0, -2, 1f))
        trafficCard.addView(metrics)
        content.addView(trafficCard, margins(bottom = 18))

        val sectionTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sectionTitle.addView(txt("Текущий сервер", 17f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD),
            LinearLayout.LayoutParams(0, -2, 1f))
        sectionTitle.addView(txt("Все серверы  ›", 11f, Color.rgb(174, 161, 255), Typeface.BOLD).apply {
            setOnClickListener { showScreen(Screen.PROFILES) }
        })
        content.addView(sectionTitle, margins(bottom = 9))

        if (selected == null) {
            val empty = card().apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = rounded(NeoTunDesign.SURFACE, 19, NeoTunDesign.BORDER, 1)
            }
            empty.addView(txt("Начнём с первого сервера", 15f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD))
            empty.addView(txt("Импортируйте ссылку VLESS или добавьте подписку — NeoTUN создаст профиль автоматически.",
                12f, NeoTunDesign.TEXT_MUTED).apply { maxLines = 3 },
                margins(top = 6, bottom = 13))
            empty.addView(button("＋  Добавить сервер") { showImportMenu() })
            content.addView(empty)
        } else {
            val serverCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(NeoTunDesign.SURFACE, 19, NeoTunDesign.BORDER, 1)
                isClickable = true
                setOnClickListener { showScreen(Screen.PROFILES) }
            }
            val serverIcon = FrameLayout(this).apply {
                background = rounded(Color.rgb(34, 32, 59), 14)
                addView(txt(countryFlag(selected.name), 23f, NeoTunDesign.TEXT_PRIMARY, Typeface.NORMAL, Gravity.CENTER),
                    FrameLayout.LayoutParams(-1, -1))
            }
            serverCard.addView(serverIcon, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(0, 0, dp(12), 0)
            })
            val serverInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            serverInfo.addView(txt(selected.name, 14f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            serverInfo.addView(txt(maskUri(selected.uri), 10.5f, Color.rgb(139, 146, 168)).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, margins(top = 4))
            serverCard.addView(serverInfo, LinearLayout.LayoutParams(0, -2, 1f))
            serverCard.addView(txt(if (running && prefs.getString(NeoTunVpnService.KEY_URI, null) == selected.uri) "●" else "›",
                18f, if (running) Color.rgb(105, 225, 167) else Color.rgb(112, 119, 143),
                Typeface.BOLD, Gravity.CENTER), LinearLayout.LayoutParams(dp(26), dp(42)))
            content.addView(serverCard)
        }
    }

    private fun liveMetric(icon: String, value: String, label: String): Pair<LinearLayout, TextView> {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        item.addView(txt(icon, 18f, Color.rgb(151, 132, 255), Typeface.BOLD, Gravity.CENTER))
        val valueView = txt(value, 14f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD, Gravity.CENTER).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        item.addView(valueView, margins(top = 5))
        item.addView(txt(label, 9f, Color.rgb(130, 137, 159), Typeface.BOLD, Gravity.CENTER).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 4))
        return item to valueView
    }

    private fun updateHomeError(message: String?) {
        val error = message?.takeIf { it.isNotBlank() }
        homeErrorText?.text = error.orEmpty()
        val parent = homeErrorText?.parent as? View
        parent?.visibility = if (error == null) View.GONE else View.VISIBLE
    }

    private fun updateHomeLiveData() {
        if (screen != Screen.HOME || isFinishing) return
        val running = isRunning()
        if (lastWidgetRunning != running) {
            lastWidgetRunning = running
            NeoTunHomeWidget.refreshAll(this)
        }
        val traffic = readVpnTraffic()
        homeRxValue?.text = formatBytes(traffic.sessionRx)
        homeTxValue?.text = formatBytes(traffic.sessionTx)
        homeSpeedValue?.text = when {
            !running -> "—"
            traffic.interfaceName == null -> "Ожидание"
            else -> formatRate(maxOf(traffic.rxRate, traffic.txRate))
        }
        homeConnectionButton?.text = if (running) "Отключиться" else "Подключиться"
        homeConnectionButton?.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (running) intArrayOf(Color.rgb(38, 112, 83), Color.rgb(32, 88, 75))
            else intArrayOf(NeoTunDesign.BRAND_VIOLET, NeoTunDesign.BRAND_BLUE)
        ).apply { cornerRadius = dp(17).toFloat() }
        homeConnectionLabel?.text = if (running) "ПОДКЛЮЧЕНО" else "ГОТОВО К ПОДКЛЮЧЕНИЮ"
        homeConnectionLabel?.setTextColor(if (running) NeoTunDesign.SUCCESS else Color.rgb(183, 173, 255))
        homeStatusDot?.setTextColor(if (running) NeoTunDesign.SUCCESS else Color.rgb(154, 143, 255))
        homeNetworkState?.text = if (running) "●  ONLINE" else "○  OFFLINE"
        homeNetworkState?.setTextColor(if (running) NeoTunDesign.SUCCESS else NeoTunDesign.TEXT_MUTED)
        homeConnectionCard?.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (running) intArrayOf(Color.rgb(18, 55, 46), Color.rgb(17, 29, 37))
            else intArrayOf(Color.rgb(38, 34, 75), Color.rgb(20, 24, 43))
        ).apply {
            cornerRadius = dp(25).toFloat()
            setStroke(dp(1), if (running) Color.rgb(52, 116, 91) else Color.rgb(72, 67, 119))
        }
        updateHomeError(getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
            .getString(NeoTunVpnService.KEY_ERROR, null))
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
                info.addView(txt(sub.name, 14f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD).apply {
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
            empty.addView(txt("Пока пусто", 21f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD, Gravity.CENTER))
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
            NeoTunDesign.TEXT_PRIMARY, Typeface.NORMAL, Gravity.CENTER), FrameLayout.LayoutParams(-1, -1))
        row.addView(flag, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply {
            setMargins(0, 0, dp(9), 0)
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        info.addView(txt(profile.name, if (compact) 13f else 14f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val detail = if (compact) maskUri(profile.uri) else protocolLabel(profile) + "  ·  " + maskUri(profile.uri)
        info.addView(txt(detail, 10f, Color.rgb(139, 145, 164)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, margins(top = 3))
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val pingColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = dp(46)
        }
        val pingLabel = pingResults[profile.id] ?: "Пинг"
        pingColumn.addView(txt(pingLabel, 9f,
            if (pingLabel.endsWith("мс")) Color.rgb(105, 225, 167) else Color.rgb(139, 145, 164),
            Typeface.BOLD, Gravity.CENTER).apply { maxLines = 1 },
            LinearLayout.LayoutParams(-1, dp(15)))
        pingColumn.addView(txt(if (profile.id in pingInProgress) "…" else "◴", 21f,
            Color.rgb(151, 132, 255), Typeface.BOLD, Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Проверить пинг: ${profile.name}"
            setOnClickListener { pingSelected(profile) }
        }, LinearLayout.LayoutParams(-1, dp(27)))
        row.addView(pingColumn, LinearLayout.LayoutParams(dp(48), dp(42)).apply {
            setMargins(dp(3), 0, dp(2), 0)
        })
        row.addView(txt(if (selected) "✓" else "›", if (selected) 18f else 22f,
            if (selected) Color.rgb(103, 222, 160) else Color.rgb(100, 106, 127),
            Typeface.BOLD, Gravity.CENTER), LinearLayout.LayoutParams(dp(20), dp(42)))
        row.minimumHeight = dp(if (compact) 50 else 56)
        return row
    }

    private fun renderSettings() {
        content.removeAllViews()
        addBackHeader("Настройки", "Управление соединением, маршрутами и приложением")
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        val activeRouting = routingStore.active()
        val routeState = when {
            !routingStore.enabled() -> "Выключена"
            activeRouting != null -> activeRouting.name
            else -> "Не настроена"
        }

        val connection = settingsSection("СОЕДИНЕНИЕ")
        connection.addView(settingsRow(
            "🧭 Правила маршрутизации",
            if (activeRouting == null) "Настроить" else activeRouting.name,
            "Домены, IP/CIDR, Proxy / Direct / Block, DNS и Geo-базы"
        ) { openRoutingEditor() })
        connection.addView(settingsRow(
            "📚 Профили маршрутизации",
            routeState,
            "Выбрать профиль, импортировать JSON/INCY или включить маршрутизацию"
        ) { showRoutingSettings() })
        connection.addView(settingsRow(
            "🌐 DNS",
            prefs.getString("dns_mode", "Автоматический") ?: "Автоматический",
            "Резолвер для соединения; применяется при переподключении"
        ) { showDnsSettings() })
        connection.addView(settingsRow(
            "🔌 MTU",
            prefs.getInt("mtu", 1500).toString(),
            "Размер пакета TUN, допустимый диапазон 1280–1500"
        ) { showMtuSettings() })
        connection.addView(settingsRow(
            "📡 IPv6",
            if (prefs.getBoolean("ipv6_enabled", false)) "Включён" else "Выключен",
            "Добавляет IPv6-адрес и маршрут по умолчанию в TUN"
        ) { toggleSetting("ipv6_enabled", "IPv6") { renderSettings() } })
        content.addView(connection, margins(bottom = 12))

        val subscriptionsSection = settingsSection("СЕРВЕРЫ И ПОДПИСКИ")
        subscriptionsSection.addView(settingsRow(
            "🔄 Автообновление подписок",
            if (prefs.getBoolean("subscriptions_auto_update", true)) "Включено" else "Выключено",
            "Проверка при запуске, не чаще одного раза в 12 часов"
        ) { toggleSetting("subscriptions_auto_update", "Автообновление подписок") { renderSettings() } })
        subscriptionsSection.addView(settingsRow(
            "🗂️ Серверы и подписки",
            "${store.all().size} / ${subscriptions.all().size}",
            "Профили, подписки, обновление и удаление"
        ) { showScreen(Screen.PROFILES) })
        content.addView(subscriptionsSection, margins(bottom = 12))

        val tools = settingsSection("ДИАГНОСТИКА И ОБНОВЛЕНИЯ")
        tools.addView(settingsRow(
            "🧪 Диагностика соединения", "Открыть",
            "Журнал запуска, DNS, TUN и ошибок движка"
        ) { diagnostics() })
        tools.addView(settingsRow(
            "⬆️ Обновление NeoTUN", "Проверить",
            "Проверить доступность новой версии приложения"
        ) { checkUpdates() })
        tools.addView(settingsRow(
            "♻️ Сбросить настройки", "",
            "Вернуть значения по умолчанию; серверы и подписки сохранятся"
        ) { confirmResetSettings() })
        content.addView(tools, margins(bottom = 12))

        val about = card().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(NeoTunDesign.SURFACE, 17, NeoTunDesign.BORDER, 1)
        }
        about.addView(txt("NeoTUN", 18f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD))
        val appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        about.addView(txt(
            "Версия $appVersion • Core ${NeoTunCore.nativeVersion()}",
            12f, NeoTunDesign.TEXT_SECONDARY
        ), margins(top = 5))
        about.addView(txt(
            "DNS, MTU и IPv6 применяются при следующем подключении. Правила маршрутизации сохраняются в выбранный профиль и используются при построении конфигурации движка.",
            11f, NeoTunDesign.TEXT_MUTED
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
        texts.addView(txt(title, 14f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD).apply {
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

    private fun connect(profile: NeoTunProfile, skipGeoWarning: Boolean = false) {
        // Happ/INCY geosite/geoip tokens point to Xray .dat geodata. sing-box
        // cannot consume those tokens as plain domains, so don't silently let
        // the user connect with part of their routing policy missing.
        if (profile.engine != NeoTunVpnService.ENGINE_XRAY && !skipGeoWarning) {
            val unsupportedGeo = RoutingProfileStore(this).active()
                ?.let { NeoTunRoutingAdapter.unsupportedSingBoxGeoTokens(it) }
                .orEmpty()
            if (unsupportedGeo.isNotEmpty()) {
                val preview = unsupportedGeo.take(8).joinToString("\n")
                val remainder = if (unsupportedGeo.size > 8) "\n… и ещё ${unsupportedGeo.size - 8}" else ""
                AlertDialog.Builder(this)
                    .setTitle("Часть правил маршрутизации не поддерживается")
                    .setMessage(
                        "Активный профиль содержит geosite/geoip правила, которые sing-box пока не может применить из Xray .dat баз. " +
                            "Если продолжить, эти правила не будут работать:\n\n" + preview + remainder +
                            "\n\nПодключиться всё равно?"
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Продолжить") { _, _ -> connect(profile, skipGeoWarning = true) }
                    .show()
                return
            }
        }

        val prefs = getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
        prefs.edit()
            .putString(NeoTunVpnService.KEY_URI, profile.uri)
            .putString(NeoTunVpnService.KEY_ENGINE, profile.engine)
            .remove(NeoTunVpnService.KEY_ERROR)
            .apply()

        if (profile.engine == NeoTunVpnService.ENGINE_XRAY) {
            prefs.edit().remove(NeoTunVpnService.KEY_CONFIG).apply()
        } else {
            val rawConfig = runCatching {
                NeoTunDiagnostics.log(this, "Building sing-box config for protocol=" +
                    profile.uri.substringBefore("://").lowercase() + ", engine=" + profile.engine)
                NeoTunCore.nativeShareConfig(profile.uri)
            }.getOrElse { error ->
                NeoTunDiagnostics.error(this, "Не удалось собрать конфигурацию профиля", error)
                prefs.edit()
                    .putString(NeoTunVpnService.KEY_ERROR,
                        "Ошибка конфигурации: " + (error.message ?: error.javaClass.simpleName))
                    .apply()
                renderHome()
                return
            }
            if (rawConfig.isBlank()) {
                NeoTunDiagnostics.error(this, "Core returned an empty config for protocol=" +
                    profile.uri.substringBefore("://").lowercase())
                prefs.edit()
                    .putString(NeoTunVpnService.KEY_ERROR,
                        "Ядро не смогло разобрать профиль. Откройте «Диагностика» для подробностей.")
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

        // Use DNS endpoints from the active profile only when the user/imported profile
        // explicitly defines them; otherwise retain the app's selected resolver.
        val routingProfile = RoutingProfileStore(this).active()
        // Happ uses mixed-case keys (RemoteDns/Geositeurl), while older INCY
        // profiles commonly use RemoteDNS/Geositeurl. JSONObject lookups are
        // case-sensitive, so normalize aliases here rather than losing settings.
        fun routingValue(vararg keys: String): String? {
            val json = routingProfile?.json ?: return null
            for (key in keys) {
                val value = json.optString(key).trim()
                if (value.isNotEmpty() && value != "null") return value
                val actual = json.keys().asSequence().firstOrNull { it.equals(key, true) }
                if (actual != null) {
                    val matched = json.optString(actual).trim()
                    if (matched.isNotEmpty() && matched != "null") return matched
                }
            }
            return null
        }
        fun dnsEndpoint(prefix: String): String? {
            routingValue(prefix)?.let { return it }
            val type = routingValue("${prefix}Type")?.uppercase()
            val domain = routingValue("${prefix}Domain")
            val ip = routingValue("${prefix}IP", "${prefix}Ip")
            return when (type) {
                "DOH" -> domain?.let { if (it.startsWith("http", true)) it else "https://$it" }
                "DOT" -> domain
                "DOU", "UDP" -> ip ?: domain
                else -> domain?.takeIf { it.startsWith("http", true) } ?: ip ?: domain
            }
        }
        val configuredRemoteDns = dnsEndpoint("RemoteDNS")
            ?: dnsEndpoint("RemoteDns")
        val configuredDomesticDns = dnsEndpoint("DomesticDNS")
            ?: dnsEndpoint("DomesticDns")
        val dnsIp = when {
            dnsMode.contains("8.8.8.8") -> "8.8.8.8"
            dnsMode.contains("9.9.9.9") -> "9.9.9.9"
            else -> "1.1.1.1"
        }
        fun dnsServer(tag: String, endpoint: String?, fallbackIp: String): JSONObject {
            val value = endpoint.orEmpty()
            if (value.startsWith("https://", true)) {
                val uri = java.net.URI(value)
                val host = uri.host ?: throw IllegalArgumentException("Некорректный DoH URL")
                val tlsServerName = when (host.lowercase()) {
                    "8.8.8.8", "8.8.4.4" -> "dns.google"
                    "77.88.8.8", "77.88.8.2" -> "common.dot.dns.yandex.net"
                    "1.1.1.1", "1.0.0.1" -> "cloudflare-dns.com"
                    "9.9.9.9", "149.112.112.112" -> "dns.quad9.net"
                    else -> host
                }
                return JSONObject()
                    .put("type", "https")
                    .put("tag", tag)
                    .put("server", host)
                    .put("server_port", if (uri.port > 0) uri.port else 443)
                    .put("path", (uri.rawPath?.takeIf { it.isNotBlank() } ?: "/dns-query") +
                        (uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""))
                    .put("tls", JSONObject().put("enabled", true).put("server_name", tlsServerName))
            }
            val serverIp = value.takeIf {
                it.matches(Regex("[0-9a-fA-F:.]+")) && (it.contains('.') || it.contains(':'))
            } ?: fallbackIp
            return JSONObject().put("type", "udp").put("tag", tag)
                .put("server", serverIp).put("server_port", 53)
        }
        val dnsServers = JSONArray().put(dnsServer("remote-dns", configuredRemoteDns, dnsIp))
        val dnsConfig = JSONObject()
            .put("servers", dnsServers)
            .put("final", "remote-dns")
            .put("strategy", if (ipv6) "prefer_ipv4" else "ipv4_only")
        val domesticDomains = routingProfile?.values("DomesticDNSDomains")
            ?.ifEmpty { routingProfile.values("DomesticDnsDomains") }.orEmpty()
        if (configuredDomesticDns != null && domesticDomains.isNotEmpty()) {
            dnsServers.put(dnsServer("domestic-dns", configuredDomesticDns, dnsIp))
            val suffixes = JSONArray()
            domesticDomains.forEach { suffixes.put(it.removePrefix("domain-suffix:").removePrefix("domain:")) }
            dnsConfig.put("rules", JSONArray().put(JSONObject()
                .put("domain_suffix", suffixes)
                .put("action", "route")
                .put("server", "domestic-dns")))
        }
        root.put("dns", dnsConfig)

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("auto_detect_interface", true)
        val routing = RoutingProfileStore(this).active()
        route.put("final", if (routing?.globalProxy != false) "proxy" else "direct")
        if (routing != null) {
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
            fun ensureOutbound(tag: String, type: String) {
                val exists = (0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == tag }
                if (!exists) outbounds.put(JSONObject().put("type", type).put("tag", tag))
            }
            ensureOutbound("direct", "direct")
            ensureOutbound("block", "block")
        }
        // DNS interception must remain first. User policy rules must precede
        // native catch-all rules (libbox often emits route(proxy) early); otherwise
        // Hysteria2 silently sends everything to the proxy before the profile matches.
        val routeRules = JSONArray().put(JSONObject().put("port", 53).put("action", "hijack-dns"))
        val nativeRules = route.optJSONArray("rules")
        if (routing != null) {
            // TUN packets commonly arrive with only a destination IP. Sniff TLS SNI,
            // HTTP Host and supported transport metadata before matching domain/GeoSite
            // rules; without this, traffic silently falls through to route.final=proxy.
            routeRules.put(JSONObject().put("action", "sniff"))
            NeoTunDiagnostics.log(this, "Routing: domain sniffing enabled before profile rules")
            val profileRules = NeoTunRoutingAdapter.singBoxRules(routing)
            for (i in 0 until profileRules.length()) routeRules.put(profileRules.getJSONObject(i))
            val profileRuleSets = NeoTunRoutingAdapter.singBoxRuleSets(routing)
            val nativeRuleSets = route.optJSONArray("rule_set") ?: JSONArray()
            val existingTags = (0 until nativeRuleSets.length()).mapNotNull {
                nativeRuleSets.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank)
            }.toMutableSet()
            for (i in 0 until profileRuleSets.length()) {
                val ruleSet = profileRuleSets.getJSONObject(i)
                if (existingTags.add(ruleSet.optString("tag"))) nativeRuleSets.put(ruleSet)
            }
            if (nativeRuleSets.length() > 0) route.put("rule_set", nativeRuleSets)

            val unsupportedGeo = NeoTunRoutingAdapter.unsupportedSingBoxGeoTokens(routing)
            if (unsupportedGeo.isNotEmpty()) {
                NeoTunDiagnostics.log(this, "Routing: unsupported sing-box GeoSite/GeoIP tokens: " + unsupportedGeo.joinToString(", "))
            }
            NeoTunDiagnostics.log(this, "Routing: profile rules=" + profileRules.length() +
                ", remote rule-sets=" + profileRuleSets.length() + "; applied before native fallback")
        }
        if (nativeRules != null) {
            for (i in 0 until nativeRules.length()) {
                val rule = nativeRules.optJSONObject(i) ?: continue
                if (rule.optString("action") == "hijack-dns") continue
                routeRules.put(rule)
            }
        }
        route.put("rules", routeRules)

        // Persist remote .srs rule-sets between app launches to avoid re-downloading
        // the same geodata every time the Hysteria2/sing-box engine starts.
        val experimental = root.optJSONObject("experimental") ?: JSONObject().also { root.put("experimental", it) }
        val cacheFile = experimental.optJSONObject("cache_file") ?: JSONObject().also { experimental.put("cache_file", it) }
        cacheFile.put("enabled", true)
        if (!cacheFile.has("path")) cacheFile.put("path", java.io.File(filesDir, "sing-box-cache.db").absolutePath)
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
        NeoTunHomeWidget.refreshAll(this)
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

        // Do not call stopService immediately after sending the command: that
        // races onStartCommand and can interrupt native TUN teardown.
        getSharedPreferences(NeoTunVpnService.PREFS, MODE_PRIVATE)
            .edit().putBoolean(NeoTunVpnService.KEY_RUNNING, false).apply()
        resetTrafficCounters()
        renderHome()
        NeoTunHomeWidget.refreshAll(this)
        handler.postDelayed({
            if (!isFinishing && screen == Screen.HOME) renderHome()
        }, 500L)
    }

    private fun openRoutingEditor() {
        startActivity(Intent(this, RoutingSettingsActivity::class.java))
    }

    private fun showRoutingSettings() {
        val profiles = routingStore.all()
        val labels = profiles.map { profile ->
            if (routingStore.active()?.id == profile.id) "✓  ${profile.name}" else profile.name
        }.toMutableList()
        val createIndex = labels.size
        labels += "＋  Создать профиль"
        val active = routingStore.active()
        val deleteIndex = if (active != null) labels.size else -1
        if (deleteIndex >= 0) labels += "⌫  Удалить активный профиль"
        val importIndex = labels.size
        labels += "⇩  Импортировать профиль"
        val toggleIndex = labels.size
        labels += if (routingStore.enabled()) "⏻  Выключить маршрутизацию" else "⏻  Включить маршрутизацию"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Профили маршрутизации")
            .setItems(labels.toTypedArray()) { _, index ->
                when {
                    index < profiles.size -> {
                        routingStore.select(profiles[index].id)
                        routingStore.setEnabled(true)
                        toast("Активен профиль: ${profiles[index].name}. Переподключитесь для применения.")
                        renderSettings()
                    }
                    index == createIndex -> {
                        startActivity(Intent(this, RoutingSettingsActivity::class.java)
                            .putExtra(RoutingSettingsActivity.EXTRA_NEW_PROFILE, true))
                    }
                    index == deleteIndex && deleteIndex >= 0 -> {
                        val target = routingStore.active() ?: return@setItems
                        AlertDialog.Builder(this)
                            .setTitle("Удалить профиль?")
                            .setMessage("Профиль «${target.name}» будет удалён. Остальные профили и серверы останутся на месте.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить") { _, _ ->
                                routingStore.delete(target.id)
                                toast("Профиль удалён")
                                renderSettings()
                            }.show()
                    }
                    index == importIndex -> {
                        val input = EditText(this).apply {
                            hint = "JSON или incy://routing/onadd/BASE64"
                            minLines = 4
                            gravity = Gravity.TOP
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            setTextColor(NeoTunDesign.TEXT_PRIMARY)
                            setHintTextColor(NeoTunDesign.TEXT_MUTED)
                        }
                        val importDialog = AlertDialog.Builder(this)
                            .setTitle("Импорт профиля")
                            .setMessage("Вставьте JSON-профиль или ссылку INCY/Happ. Импорт не удаляет остальные профили.")
                            .setView(input)
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Импортировать") { _, _ ->
                                val raw = input.text.toString().trim()
                                if (raw.contains("://routing/off", true) || raw.equals("off", true)) {
                                    routingStore.setEnabled(false)
                                    toast("Маршрутизация выключена")
                                    renderSettings()
                                } else {
                                    val json = RoutingProfileStore.decode(raw)
                                    if (json == null) {
                                        toast("Не удалось прочитать профиль. Проверьте JSON или Base64-ссылку.")
                                    } else {
                                        val saved = routingStore.save(json, activate = true)
                                        routingStore.setEnabled(true)
                                        toast("Профиль «${saved.name}» импортирован")
                                        renderSettings()
                                    }
                                }
                            }.create()
                        importDialog.setOnShowListener { styleDialog(importDialog) }
                        importDialog.show()
                    }
                    index == toggleIndex -> {
                        routingStore.setEnabled(!routingStore.enabled())
                        toast(if (routingStore.enabled()) "Маршрутизация включена" else "Маршрутизация выключена")
                        renderSettings()
                    }
                }
            }.create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
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
                // Refresh first; SubscriptionStore persists the new subscription only
                // after the feed has yielded at least one supported profile.
                val existing = subscriptions.all().firstOrNull {
                    it.url.trim().trimEnd('/') == source.trimEnd('/')
                }
                val subscription = existing?.copy(
                    name = name.text.toString().trim().ifBlank { existing.name }
                ) ?: NeoTunSubscription(
                    UUID.randomUUID().toString(),
                    name.text.toString().trim().ifBlank { "Подписка" },
                    source,
                )
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

        val existing = subscriptions.all().firstOrNull { it.url.trim().trimEnd('/') == url.trim().trimEnd('/') }
        // Do not persist a new subscription until its contents have been validated.
        val subscription = existing ?: NeoTunSubscription(
            UUID.randomUUID().toString(),
            name,
            url,
        )

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
        val routingText = raw.trim()
        if (routingText.equals("off", true) || routingText.contains("://routing/off", true)) {
            routingStore.setEnabled(false)
            toast("Встроенная маршрутизация выключена")
            showScreen(Screen.SETTINGS)
            return
        }
        if (routingText.contains("://routing/", true) || routingText.contains("://autorouting/", true) ||
            routingText.startsWith("{")) {
            val routing = RoutingProfileStore.decode(routingText)
            if (routing != null && (routing.has("Name") || routing.has("GlobalProxy") ||
                    routing.has("DirectSites") || routing.has("ProxySites") || routing.has("BlockSites") ||
                    routing.has("DirectIp") || routing.has("ProxyIp") || routing.has("BlockIp"))) {
                val isOnAdd = routingText.contains("://routing/onadd/", true) ||
                    routingText.contains("://autorouting/", true)
                val shouldActivate = isOnAdd || routingStore.all().isEmpty()
                val saved = routingStore.save(routing, activate = shouldActivate)
                if (shouldActivate) routingStore.setEnabled(true)
                toast(if (shouldActivate) {
                    "Маршрутизация «${saved.name}» импортирована и активирована"
                } else {
                    "Профиль «${saved.name}» добавлен. Активный профиль не изменён"
                })
                showScreen(Screen.SETTINGS)
                return
            }
        }
        val candidates = linkedSetOf<String>()
        fun collect(value: String) {
            // Keep commas inside Hysteria2 port-hopping parameters.
            val linkPattern = Regex("(?i)(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://.*?(?=(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://|\\s|$)")
            value.lines().forEach { line ->
                linkPattern.findAll(line).forEach { match ->
                    val link = match.value.trim().trimEnd(',', ';', '"', '\'')
                    if (link.isNotBlank()) candidates.add(link)
                }
            }
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
        dialog.window?.setBackgroundDrawable(ColorDrawable(NeoTunDesign.SURFACE))
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
            toast("Для проверки отключите текущее соединение")
            return
        }
        if (!pingInProgress.add(profile.id)) return
        pingResults[profile.id] = "…"
        if (screen == Screen.PROFILES) renderProfiles()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Проверка сервера")
            .setMessage("Проверяем доступность ${profile.name}…")
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
                pingInProgress.remove(profile.id)
                pingResults[profile.id] = if (pingResult >= 0L) "${pingResult}мс" else "Ошибка"
                if (dialog.isShowing) dialog.dismiss()
                if (screen == Screen.PROFILES) renderProfiles()
                if (pingResult >= 0L) {
                    toast("${profile.name}: ${pingResult} мс")
                } else {
                    toast("Пинг не пройден: ${profile.name}. Проверьте профиль и доступность сервера.")
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
                is UpdateResult.Available -> {
                    val layout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(24), dp(12), dp(24), dp(8))
                    }
                    val status = txt("Подготовка загрузки…", 13f, Color.rgb(170, 174, 192))
                    val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        this.progress = 0
                    }
                    layout.addView(status, LinearLayout.LayoutParams(-1, -2))
                    layout.addView(progress, LinearLayout.LayoutParams(-1, dp(28)))
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("NeoTUN " + result.version)
                        .setView(layout)
                        .setCancelable(false)
                        .create()
                    dialog.show()
                    updater.downloadAndInstall(
                        result.apkUrl,
                        result.version,
                        result.sha256,
                        result.sizeBytes,
                        { percent ->
                            progress.progress = percent
                            val mb = result.sizeBytes / (1024.0 * 1024.0)
                            status.text = if (percent >= 100) "Проверка APK…" else
                                "Загрузка: " + percent + "% · " + String.format(java.util.Locale.US, "%.1f", mb) + " МБ"
                            if (percent >= 100 && dialog.isShowing) dialog.dismiss()
                        },
                        { message ->
                            if (dialog.isShowing) dialog.dismiss()
                            toast("Ошибка обновления: " + message)
                        },
                    )
                }
                is UpdateResult.UpToDate -> toast("Установлена последняя версия " + result.version)
                is UpdateResult.Error -> toast("Обновления: " + result.message)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRoutingIntent(intent)
        handleWidgetToggleIntent(intent)
    }

    private fun handleWidgetToggleIntent(intent: Intent?) {
        if (intent?.action != ACTION_WIDGET_TOGGLE) return
        intent.action = null
        // Let the initial screen finish before toggling the active profile.
        handler.post {
            if (isFinishing) return@post
            if (isRunning()) {
                disconnect()
            } else {
                val profile = selectedProfile(store.all())
                if (profile == null) showImportMenu() else connect(profile)
            }
            NeoTunHomeWidget.refreshAll(this)
        }
    }

    private fun handleRoutingIntent(intent: Intent?) {
        val link = intent?.dataString?.trim().orEmpty()
        if (link.isBlank()) return
        if (link.contains("://routing/off", true)) {
            routingStore.setEnabled(false)
            toast("Маршрутизация выключена")
            showScreen(Screen.SETTINGS)
            return
        }
        val activate = link.contains("/onadd/", true)
        val json = RoutingProfileStore.decode(link)
        if (json != null) {
            val saved = routingStore.save(json, activate = activate)
            routingStore.setEnabled(true)
            toast("Профиль маршрутизации: ${saved.name}")
            showScreen(Screen.SETTINGS)
            return
        }
        val isRoutingUrl = link.contains("://routing/", true) || link.contains("://autorouting/", true)
        if (!isRoutingUrl) return
        val url = when {
            link.contains("://autorouting/", true) ->
                link.substringAfter("://autorouting/", "").substringAfter('/', "")
            link.contains("://routing/", true) ->
                link.substringAfter("://routing/", "").substringAfter('/', "")
            else -> ""
        }.let { normalizeRoutingUrl(it) }
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            toast("Ссылка маршрутизации некорректна")
            return
        }
        toast("Загружаем профиль маршрутизации…")
        Thread {
            val result = runCatching {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", "NeoTUN/0.5")
                }
                try {
                    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.mapCatching { body ->
                RoutingProfileStore.decode(body) ?: error("Сервер вернул не JSON/Base64 профиль")
            }
            runOnUiThread {
                result.onSuccess { profile ->
                    val saved = routingStore.save(profile, sourceUrl = if (link.contains("://autorouting/", true)) url else null, activate = activate)
                    routingStore.setEnabled(true)
                    toast("Профиль «${saved.name}» импортирован")
                    showScreen(Screen.SETTINGS)
                }.onFailure { toast("Не удалось загрузить маршрутизацию: ${it.message}") }
            }
        }.start()
    }

    private fun normalizeRoutingUrl(value: String): String =
        value.replace("https://github.com/", "https://raw.githubusercontent.com/")
            .replace("/blob/", "/")

    private fun refreshDueRoutingProfiles() {
        val prefs = getSharedPreferences("neotun_routing", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        routingStore.all().filter { !it.sourceUrl.isNullOrBlank() }.forEach { profile ->
            val key = "last_checked_" + profile.id
            val lastChecked = prefs.getLong(key, 0L)
            if (now - lastChecked < 24L * 60L * 60L * 1000L) return@forEach
            prefs.edit().putLong(key, now).apply()
            Thread {
                val result = runCatching {
                    val url = normalizeRoutingUrl(profile.sourceUrl!!)
                    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 12_000
                        readTimeout = 20_000
                        setRequestProperty("User-Agent", "NeoTUN/0.5")
                    }
                    try {
                        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                }.mapCatching { body ->
                    RoutingProfileStore.decode(body) ?: error("Некорректный профиль")
                }
                result.onSuccess { updated ->
                    routingStore.save(updated, sourceUrl = profile.sourceUrl, activate = false)
                }.onFailure {
                    // Retry on the next app launch; don't interrupt an active connection.
                    prefs.edit().putLong(key, 0L).apply()
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.SETTINGS -> renderSettings()
            Screen.PROFILES -> Unit
        }
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
        textBox.addView(txt(title, 24f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD))
        textBox.addView(txt(subtitle, 12f, Color.rgb(132, 137, 155)), margins(top = 2))
        row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row, margins(bottom = 16))
    }

    private fun iconButton(symbol: String, size: Int, action: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = size.toFloat()
        gravity = Gravity.CENTER
        setTextColor(NeoTunDesign.TEXT_PRIMARY)
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

        // Missing type means native TCP for VLESS/VMess. "raw" is a common alias.
        val rawTransport = transport?.substringBefore('#')?.substringBefore("%23")
        val cleanTransport = when (rawTransport) {
            "RAW" -> "TCP"
            null, "" -> if (scheme == "VLESS" || scheme == "VMESS") "TCP" else null
            else -> rawTransport
        }
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
        val items = listOf("⌂" to "Главная", "▤" to "Серверы", "⚙" to "Настройки")
        items.forEachIndexed { index, pair ->
            val target = when (index) { 0 -> Screen.HOME; 1 -> Screen.PROFILES; else -> Screen.SETTINGS }
            val selected = screen == target
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
                background = rounded(
                    if (selected) NeoTunDesign.BRAND_SOFT else Color.TRANSPARENT,
                    13,
                    if (selected) Color.rgb(69, 59, 113) else null,
                    if (selected) 1 else 0
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { showScreen(target) }
            }
            item.addView(txt(
                pair.first, 19f,
                if (selected) Color.rgb(190, 178, 255) else NeoTunDesign.TEXT_MUTED,
                Typeface.BOLD, Gravity.CENTER
            ))
            item.addView(txt(
                pair.second, 10f,
                if (selected) NeoTunDesign.TEXT_PRIMARY else NeoTunDesign.TEXT_MUTED,
                if (selected) Typeface.BOLD else Typeface.NORMAL,
                Gravity.CENTER
            ), margins(top = 1))
            nav.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(dp(4), dp(1), dp(4), dp(1))
            })
        }
    }

    private fun header(title: String, subtitle: String) {
        content.addView(txt(
            title,
            if (resources.displayMetrics.widthPixels < dp(360)) 22f else 25f,
            NeoTunDesign.TEXT_PRIMARY,
            Typeface.BOLD
        ))
        content.addView(txt(subtitle, 12f, NeoTunDesign.TEXT_SECONDARY).apply {
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
        setTextColor(NeoTunDesign.TEXT_PRIMARY)
        minHeight = dp(48)
        minimumWidth = 0
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(8), 0, dp(8), 0)
        background = rounded(Color.rgb(32, 35, 57), 14, Color.rgb(65, 61, 100), 1)
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(80L).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
            false
        }
        setOnClickListener { action() }
    }

    private fun metric(icon: String, value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(txt(icon, 17f, Color.rgb(125, 108, 255), Typeface.BOLD, Gravity.CENTER))
        addView(txt(value, 15f, NeoTunDesign.TEXT_PRIMARY, Typeface.BOLD, Gravity.CENTER).apply {
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
        val vpnInterfaces = connectivity.allNetworks.asSequence()
            .mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                connectivity.getLinkProperties(network)?.interfaceName
            }
            .filterNot { it.isNullOrBlank() }
            .filterNotNull()
            .toList()

        // Some Android builds expose the VPN Network but do not allow reading its
        // counters under that interface name. Try the actual TUN interface as fallback.
        val javaInterfaces = if (isRunning()) runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.map { it.name }
                ?.filter { it.matches(Regex("(tun|utun|wg|vpn|tap)\\d*")) }
                .orEmpty()
        }.getOrDefault(emptyList()) else emptyList()
        val candidates = (vpnInterfaces +
            if (isRunning()) findVpnInterfacesFromSysfs() + javaInterfaces else emptyList())
            .distinct()
        val interfaceName = candidates.firstOrNull { readInterfaceCounters(it) != null }

        // Android 14/15/16 vendors sometimes hide TUN interface counters from
        // /sys and /proc. In that case, use this app's UID counters as a fallback
        // so the dashboard still reports real tunnel socket traffic instead of
        // remaining permanently at 0 B. These are wire bytes, not exact payload bytes.
        val selectedSource: String?
        val counters: Pair<Long, Long>?
        if (!interfaceName.isNullOrBlank()) {
            selectedSource = interfaceName
            counters = readInterfaceCounters(interfaceName)
        } else {
            val uidRx = TrafficStats.getUidRxBytes(Process.myUid())
            val uidTx = TrafficStats.getUidTxBytes(Process.myUid())
            if (isRunning() && uidRx >= 0L && uidTx >= 0L) {
                selectedSource = "uid:" + Process.myUid()
                counters = uidRx to uidTx
            } else {
                selectedSource = null
                counters = null
            }
        }

        if (selectedSource == null || counters == null) {
            resetTrafficCounters()
            return TrafficSnapshot(null, 0L, 0L, 0L, 0L, false)
        }
        val now = android.os.SystemClock.elapsedRealtime()

        if (trafficInterface != selectedSource || trafficBaseRx < 0L || trafficBaseTx < 0L) {
            trafficInterface = selectedSource
            trafficBaseRx = counters.first
            trafficBaseTx = counters.second
            trafficLastRx = counters.first
            trafficLastTx = counters.second
            trafficLastAt = now
            return TrafficSnapshot(selectedSource, 0L, 0L, 0L, 0L, false)
        }

        val elapsedMs = (now - trafficLastAt).coerceAtLeast(1L)
        val rxDelta = (counters.first - trafficLastRx).coerceAtLeast(0L)
        val txDelta = (counters.second - trafficLastTx).coerceAtLeast(0L)
        trafficLastRx = counters.first
        trafficLastTx = counters.second
        trafficLastAt = now

        return TrafficSnapshot(
            selectedSource,
            (counters.first - trafficBaseRx).coerceAtLeast(0L),
            (counters.second - trafficBaseTx).coerceAtLeast(0L),
            rxDelta * 1000L / elapsedMs,
            txDelta * 1000L / elapsedMs,
            rxDelta > 0L || txDelta > 0L,
        )
    }

    private fun findVpnInterfacesFromSysfs(): List<String> {
        val fromSysfs = runCatching {
            java.io.File("/sys/class/net").listFiles()
                ?.map { it.name }
                ?.filter { it.matches(Regex("(tun|utun|wg|vpn)\\d*")) }
                .orEmpty()
        }.getOrDefault(emptyList())
        // Some Android/libbox builds expose the TUN interface through /proc/net/dev
        // even when /sys/class/net is restricted or its listing is incomplete.
        val fromProc = runCatching {
            java.io.File("/proc/net/dev").useLines { lines ->
                lines.drop(2).mapNotNull { line ->
                    line.substringBefore(":").trim().takeIf {
                        it.matches(Regex("(tun|utun|wg|vpn)\\d*"))
                    }
                }.toList()
            }
        }.getOrDefault(emptyList())
        return (fromSysfs + fromProc).distinct()
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
