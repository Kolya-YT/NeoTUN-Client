package com.neotun.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** Routing editor using the same NeoTunDesign tokens as the main app. */
class RoutingSettingsActivity : Activity() {
    private val store by lazy { RoutingProfileStore(this) }
    private var profileJson = JSONObject()
    private var profileName = "Маршрутизация"
    private lateinit var profileNameInput: EditText
    private lateinit var globalProxy: Switch
    private lateinit var remoteDns: EditText
    private lateinit var domesticDns: EditText
    private lateinit var orderValue: String
    private lateinit var orderLabel: TextView
    private lateinit var geoSiteUrl: EditText
    private lateinit var geoIpUrl: EditText
    private val ruleSummaries = mutableMapOf<String, TextView>()
    private val domainDnsSummary by lazy { ruleSummaries["DomesticDNSDomains"] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NeoTunDesign.BACKGROUND
        window.navigationBarColor = NeoTunDesign.NAVIGATION
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val createNew = intent.getBooleanExtra(EXTRA_NEW_PROFILE, false)
        val active = if (createNew) null else (store.active() ?: store.all().firstOrNull())
        profileJson = JSONObject(active?.json?.toString() ?: "{}")
        profileName = if (createNew) "Новый профиль" else profileJson.optString("Name").ifBlank { "Мой профиль" }
        orderValue = profileJson.optString("RouteOrder", "block-proxy-direct")
        if (active == null) {
            profileJson.put("Name", profileName)
                .put("GlobalProxy", "true")
                .put("RouteOrder", orderValue)
        }
        // Always expose usable DNS defaults in the editor, even when an imported
        // routing profile omits Happ/INCY DNS keys. Preserve non-empty aliases.
        fun profileValue(vararg aliases: String): String? {
            val key = profileJson.keys().asSequence().firstOrNull { candidate ->
                aliases.any { it.equals(candidate, true) } && profileJson.optString(candidate).isNotBlank()
            } ?: return null
            return profileJson.optString(key).trim().takeIf { it.isNotEmpty() }
        }
        fun hasDomains(): Boolean = profileJson.keys().asSequence().any { key ->
            if (!key.equals("DomesticDNSDomains", true)) false
            else when (val value = profileJson.opt(key)) {
                is JSONArray -> value.length() > 0
                is String -> value.isNotBlank()
                else -> false
            }
        }
        if (profileJson.optString("RemoteDNS").isBlank()) {
            profileJson.put("RemoteDNS", profileValue("RemoteDns", "RemoteDomain", "RemoteIP", "RemoteIp")
                ?: "https://8.8.8.8/dns-query")
        }
        if (profileJson.optString("DomesticDNS").isBlank()) {
            profileJson.put("DomesticDNS", profileValue("DomesticDns", "DomesticDomain", "DomesticIP", "DomesticIp")
                ?: "https://77.88.8.8/dns-query")
        }
        if (!hasDomains()) {
            profileJson.put("DomesticDNSDomains", JSONArray().put("ru").put("su").put("рф"))
        }
        if (profileJson.optString("RemoteDNSType").isBlank()) profileJson.put("RemoteDNSType", "DoH")
        if (profileJson.optString("DomesticDNSType").isBlank()) profileJson.put("DomesticDNSType", "DoH")
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(NeoTunDesign.BACKGROUND)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(iconButton("‹") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titleStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleStack.addView(text("Маршрутизация", 22, NeoTunDesign.TEXT_PRIMARY, true))
        titleStack.addView(text(profileName, 12, NeoTunDesign.TEXT_SECONDARY), params(top = 3))
        header.addView(titleStack, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        body.addView(header, params(bottom = 14))

        body.addView(sectionTitle("ПРОФИЛЬ"))
        val nameCard = card()
        profileNameInput = inputField(profileName, "Название профиля").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        nameCard.addView(text("Название", 13, NeoTunDesign.TEXT_SECONDARY, true))
        nameCard.addView(profileNameInput, params(top = 6))
        body.addView(nameCard, params(bottom = 18))

        body.addView(sectionTitle("ПОВЕДЕНИЕ СОЕДИНЕНИЯ"))
        val proxyCard = card()
        globalProxy = Switch(this).apply {
            text = "Глобальный прокси"
            textSize = 15f
            setTextColor(NeoTunDesign.TEXT_PRIMARY)
            isChecked = profileJson.optString("GlobalProxy", "true").equals("true", true)
            thumbTintList = android.content.res.ColorStateList.valueOf(NeoTunDesign.BRAND_VIOLET)
            trackTintList = android.content.res.ColorStateList.valueOf(NeoTunDesign.BORDER)
        }
        proxyCard.addView(globalProxy)
        proxyCard.addView(text(
            "Включено — соединения по умолчанию идут через прокси. Выключено — напрямую. Явные правила ниже имеют приоритет.",
            12, NeoTunDesign.TEXT_SECONDARY
        ), params(top = 7))
        body.addView(proxyCard, params(bottom = 18))

        body.addView(sectionTitle("ПРАВИЛА"))
        body.addView(text("Каждая строка — отдельный домен, IP или CIDR. Пустой список не добавляет правило.",
            12, NeoTunDesign.TEXT_SECONDARY), params(bottom = 8))
        val rulesCard = card()
        addRuleRow(rulesCard, "BlockSites", "Блокировать домены", "example.org")
        addRuleRow(rulesCard, "BlockIp", "Блокировать IP/CIDR", "192.0.2.0/24")
        addRuleRow(rulesCard, "ProxySites", "Через прокси — домены", "example.org")
        addRuleRow(rulesCard, "ProxyIp", "Через прокси — IP/CIDR", "192.0.2.1")
        addRuleRow(rulesCard, "DirectSites", "Напрямую — домены", "example.org")
        addRuleRow(rulesCard, "DirectIp", "Напрямую — IP/CIDR", "192.0.2.0/24")
        body.addView(rulesCard, params(bottom = 18))

        body.addView(sectionTitle("ПОРЯДОК ПРАВИЛ"))
        val orderCard = card()
        orderLabel = text("", 14, NeoTunDesign.TEXT_PRIMARY, true)
        orderLabel.setPadding(dp(2), dp(4), dp(2), dp(4))
        val orderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(orderLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(
                text("⌄", 22, NeoTunDesign.TEXT_SECONDARY),
                LinearLayout.LayoutParams(dp(28), -2).apply { gravity = Gravity.CENTER_VERTICAL }
            )
            isClickable = true
            setOnClickListener { chooseOrder() }
        }
        orderCard.addView(orderRow)
        orderCard.addView(text("Сначала проверяются правила в выбранном порядке; отсутствующие группы добавляются в конец.",
            12, NeoTunDesign.TEXT_SECONDARY), params(top = 7))
        body.addView(orderCard, params(bottom = 18))
        updateOrderLabel()

        body.addView(sectionTitle("DNS OVER HTTPS"))
        val dnsCard = card()
        dnsCard.addView(text("Удалённый DNS", 13, NeoTunDesign.TEXT_SECONDARY, true))
        remoteDns = inputField(profileJson.optString("RemoteDNS", "https://8.8.8.8/dns-query"), "Не задан — использовать DNS из настроек")
        dnsCard.addView(remoteDns, params(top = 5, bottom = 12))
        dnsCard.addView(text("Домашний DNS", 13, NeoTunDesign.TEXT_SECONDARY, true))
        domesticDns = inputField(profileJson.optString("DomesticDNS", "https://77.88.8.8/dns-query"), "Не задан")
        dnsCard.addView(domesticDns, params(top = 5, bottom = 12))
        addRuleRow(dnsCard, "DomesticDNSDomains", "Домены для домашнего DNS", "ru\\nsu\\nрф")
        dnsCard.addView(text(
            "По умолчанию .ru, .su и .рф используют домашний DNS 77.88.8.8; остальные домены — удалённый DNS 8.8.8.8. Список можно изменить.",
            12, NeoTunDesign.TEXT_SECONDARY
        ), params(top = 8))
        body.addView(dnsCard, params(bottom = 18))

        body.addView(sectionTitle("ГЕОБАЗЫ XRAY"))
        val geoCard = card()
        geoCard.addView(text("GeoSite URL", 13, NeoTunDesign.TEXT_SECONDARY, true))
        geoSiteUrl = inputField(profileJson.optString("Geositeurl", ""), "HTTPS-ссылка на geosite.dat")
        geoCard.addView(geoSiteUrl, params(top = 5, bottom = 12))
        geoCard.addView(text("GeoIP URL", 13, NeoTunDesign.TEXT_SECONDARY, true))
        geoIpUrl = inputField(profileJson.optString("Geoipurl", ""), "HTTPS-ссылка на geoip.dat")
        geoCard.addView(geoIpUrl, params(top = 5, bottom = 12))
        geoCard.addView(text("Загрузка выполняется в фоне. При ошибке предыдущие рабочие файлы сохраняются.",
            12, NeoTunDesign.TEXT_SECONDARY), params(bottom = 10))
        val refreshGeo = Button(this).apply {
            text = "↻  Проверить и обновить геобазы"
            textSize = 13f
            isAllCaps = false
            setTextColor(NeoTunDesign.TEXT_PRIMARY)
            background = GradientDrawable().apply {
                setColor(NeoTunDesign.SURFACE_RAISED)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), NeoTunDesign.BORDER)
            }
            setOnClickListener { refreshGeoData(this) }
        }
        geoCard.addView(refreshGeo, LinearLayout.LayoutParams(-1, dp(46)))
        body.addView(geoCard, params(bottom = 18))

        val save = Button(this).apply {
            text = "Сохранить изменения"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(54)
            background = NeoTunDesign.gradient().apply { cornerRadius = dp(16).toFloat() }
            setOnClickListener { saveProfile() }
        }
        body.addView(save, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })
        body.addView(text("Изменения применятся после следующего подключения.", 12,
            NeoTunDesign.TEXT_MUTED, false).apply { gravity = Gravity.CENTER },
            params(top = 10))

        scroll.clipToPadding = false
        scroll.addView(body)
        setContentView(scroll)
        // Android 15 enforces edge-to-edge for targetSdk 35. Keep content below
        // the status bar and make the final action scroll fully above navigation.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            body.setPadding(dp(20), dp(12) + bars.top, dp(20), dp(28) + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun addRuleRow(parent: LinearLayout, key: String, label: String, hint: String) {
        val values = valuesFor(key)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
        }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(text(label, 14, NeoTunDesign.TEXT_PRIMARY, true))
        val summary = text(summary(values), 12, NeoTunDesign.TEXT_SECONDARY)
        stack.addView(summary, params(top = 4))
        ruleSummaries[key] = summary
        row.addView(stack, LinearLayout.LayoutParams(0, -2, 1f))
        val edit = TextView(this).apply {
            text = "Изменить  ›"
            textSize = 12f
            setTextColor(NeoTunDesign.BRAND_VIOLET)
            setPadding(dp(8), dp(10), dp(2), dp(10))
            setOnClickListener { editList(key, label, hint) }
        }
        row.addView(edit)
        parent.addView(row)
        if (key != "DomesticDNSDomains") parent.addView(separator())
    }

    private fun editList(key: String, title: String, hint: String) {
        val input = EditText(this).apply {
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(valuesFor(key).joinToString("\n"))
            setSelection(text.length)
            setHint(hint)
            setTextColor(NeoTunDesign.TEXT_PRIMARY)
            setHintTextColor(NeoTunDesign.TEXT_MUTED)
            background = inputBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val items = input.text.toString().lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .distinct()
                    .toList()
                profileJson.put(key, JSONArray().apply { items.forEach { put(it) } })
                ruleSummaries[key]?.text = summary(items)
            }.create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun chooseOrder() {
        val options = listOf("block-proxy-direct", "block-direct-proxy", "proxy-block-direct")
        val labels = arrayOf("Блокировать → Прокси → Напрямую",
            "Блокировать → Напрямую → Прокси", "Прокси → Блокировать → Напрямую")
        val selected = options.indexOf(orderValue).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Порядок групп")
            .setSingleChoiceItems(labels, selected) { d, which ->
                orderValue = options[which]
                updateOrderLabel()
                d.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .create()
        dialog.setOnShowListener { styleDialog(dialog) }
        dialog.show()
    }

    private fun saveProfile() {
        val remote = remoteDns.text.toString().trim()
        val domestic = domesticDns.text.toString().trim()
        if (remote.isNotEmpty() && !isDnsEndpoint(remote)) {
            remoteDns.error = "Укажите корректный IP или HTTPS URL"
            return
        }
        if (domestic.isNotEmpty() && !isDnsEndpoint(domestic)) {
            domesticDns.error = "Укажите корректный IP или HTTPS URL"
            return
        }
        val geoSite = geoSiteUrl.text.toString().trim()
        val geoIp = geoIpUrl.text.toString().trim()
        if ((geoSite.isNotEmpty() && !geoSite.startsWith("https://", true)) ||
            (geoIp.isNotEmpty() && !geoIp.startsWith("https://", true))) {
            Toast.makeText(this, "Для геобаз разрешены только HTTPS URL", Toast.LENGTH_LONG).show()
            return
        }
        profileName = profileNameInput.text.toString().trim()
        if (profileName.isBlank()) {
            profileNameInput.error = "Введите название профиля"
            profileNameInput.requestFocus()
            return
        }
        profileJson.put("Name", profileName)
            .put("GlobalProxy", globalProxy.isChecked.toString())
            .put("RouteOrder", orderValue)
            .put("RemoteDNS", remote)
            .put("DomesticDNS", domestic)
            .put("RemoteDNSType", if (remote.startsWith("https://", true)) "DoH" else "UDP")
            .put("DomesticDNSType", if (domestic.startsWith("https://", true)) "DoH" else "UDP")
            .put("Geositeurl", geoSite)
            .put("Geoipurl", geoIp)
        val saved = store.save(profileJson, activate = true)
        store.setEnabled(true)
        Toast.makeText(this, "Профиль «${saved.name}» сохранён", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun refreshGeoData(button: Button) {
        val siteUrl = geoSiteUrl.text.toString().trim()
        val ipUrl = geoIpUrl.text.toString().trim()
        if ((siteUrl.isNotEmpty() && !siteUrl.startsWith("https://", true)) ||
            (ipUrl.isNotEmpty() && !ipUrl.startsWith("https://", true))) {
            Toast.makeText(this, "Для геобаз разрешены только HTTPS URL", Toast.LENGTH_LONG).show()
            return
        }
        profileJson.put("Geositeurl", siteUrl).put("Geoipurl", ipUrl)
        store.save(profileJson, activate = true)
        val original = button.text
        button.isEnabled = false
        button.text = "Обновление…"
        Thread {
            val result = runCatching {
                NeoTunGeoData.ensure(this, java.io.File(filesDir, "geodata"), profileJson, forceRefresh = true)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                button.isEnabled = true
                button.text = original
                result.onSuccess {
                    Toast.makeText(this,
                        "Геобазы обновлены: GeoSite ${it.geoSiteBytes / 1024} КБ, GeoIP ${it.geoIpBytes / 1024} КБ",
                        Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, "Не удалось обновить геобазы: ${it.message ?: "ошибка сети"}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun isDnsEndpoint(value: String): Boolean {
        if (value.startsWith("https://", true)) {
            val uri = runCatching { java.net.URI(value) }.getOrNull()
            return uri?.host?.isNotBlank() == true
        }
        return value.matches(Regex("[0-9a-fA-F:.]+")) && value.any { it == '.' || it == ':' }
    }

    private fun valuesFor(key: String): List<String> {
        val value = profileJson.opt(key)
        return when (value) {
            is JSONArray -> (0 until value.length()).map { value.optString(it).trim() }.filter { it.isNotEmpty() }
            is String -> value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            else -> emptyList()
        }
    }

    private fun summary(values: List<String>): String =
        if (values.isEmpty()) "Не задано" else "${values.size} правил"

    private fun updateOrderLabel() {
        orderLabel.text = when (orderValue) {
            "block-direct-proxy" -> "Блокировать → Напрямую → Прокси"
            "proxy-block-direct" -> "Прокси → Блокировать → Напрямую"
            else -> "Блокировать → Прокси → Напрямую"
        }
    }

    private fun sectionTitle(label: String) = text(label, 11, NeoTunDesign.BRAND_VIOLET, true).apply {
        setPadding(dp(2), dp(2), dp(2), dp(9))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(NeoTunDesign.SURFACE)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), NeoTunDesign.BORDER)
        }
    }

    private fun inputField(value: String, hintText: String) = EditText(this).apply {
        setText(value)
        hint = hintText
        textSize = 14f
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setTextColor(NeoTunDesign.TEXT_PRIMARY)
        setHintTextColor(NeoTunDesign.TEXT_MUTED)
        background = inputBackground()
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun inputBackground() = GradientDrawable().apply {
        setColor(NeoTunDesign.SURFACE_INPUT)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), NeoTunDesign.BORDER)
    }

    private fun iconButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(NeoTunDesign.TEXT_PRIMARY)
        background = GradientDrawable().apply {
            setColor(NeoTunDesign.SURFACE_RAISED)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), NeoTunDesign.BORDER)
        }
        setOnClickListener { action() }
    }

    private fun separator() = View(this).apply {
        setBackgroundColor(NeoTunDesign.BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(NeoTunDesign.SURFACE)
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), NeoTunDesign.BORDER)
        })
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels - dp(36)).coerceAtMost(dp(520)),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(NeoTunDesign.BRAND_VIOLET)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(NeoTunDesign.TEXT_SECONDARY)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(NeoTunDesign.TEXT_SECONDARY)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun params(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NEW_PROFILE = "neotun.routing.new_profile"
    }
}
