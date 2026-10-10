package com.neotun.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/** Editable Happ/INCY-compatible routing profile. */
class RoutingSettingsActivity : Activity() {
    private val store by lazy { RoutingProfileStore(this) }
    private var profileJson = JSONObject()
    private var profileName = "NeoTUN Routing"
    private lateinit var root: LinearLayout
    private lateinit var globalProxy: Switch
    private lateinit var remoteDns: EditText
    private lateinit var domesticDns: EditText
    private lateinit var routeOrder: TextView
    private val orderOptions = arrayOf("block → proxy → direct", "block → direct → proxy", "proxy → block → direct")
    private var orderValue = "block-proxy-direct"
    private val ruleFields = linkedMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = store.active() ?: store.all().firstOrNull()
        profileJson = JSONObject(active?.json?.toString() ?: "{}")
        profileName = profileJson.optString("Name").ifBlank { "NeoTUN Routing" }
        if (active == null) {
            profileJson.put("Name", profileName)
                .put("GlobalProxy", "true")
                .put("RouteOrder", "block-proxy-direct")
                .put("RemoteDNS", "https://8.8.8.8/dns-query")
                .put("DomesticDNS", "https://77.88.8.8/dns-query")
                .put("Geositeurl", "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geosite/release/geosite.dat")
                .put("Geoipurl", "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geoip/release/geoip.dat")
        }
        orderValue = profileJson.optString("RouteOrder", "block-proxy-direct")
        buildUi()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 25, 31))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹", 36) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(text("Правила маршрутизации", 22, Color.WHITE, true))
        heading.addView(text(profileName, 12, Color.LTGRAY), margins(top = 2))
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(header, margins(bottom = 18))

        body.addView(section("НАСТРОЙКИ ПРОКСИ"))
        globalProxy = Switch(this).apply {
            text = "Глобальный прокси"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = profileJson.optString("GlobalProxy", "true").equals("true", true)
        }
        body.addView(globalProxy, margins(top = 8, bottom = 6))
        body.addView(text("Если выключено, трафик по умолчанию идёт напрямую; правила ниже сохраняют приоритет.", 12, Color.LTGRAY), margins(bottom = 16))

        body.addView(section("НАСТРОЙКИ МАРШРУТИЗАЦИИ"))
        addRuleEditor(body, "BlockSites", "Заблокировать домены", "BlockIp", "Заблокировать IP/CIDR")
        addRuleEditor(body, "ProxySites", "Через прокси — домены", "ProxyIp", "Через прокси — IP/CIDR")
        addRuleEditor(body, "DirectSites", "Напрямую — домены", "DirectIp", "Напрямую — IP/CIDR")
        routeOrder = text("", 15, Color.WHITE, true)
        routeOrder.setPadding(0, dp(14), 0, dp(14))
        routeOrder.setOnClickListener { chooseOrder() }
        body.addView(section("ПОРЯДОК МАРШРУТИЗАЦИИ"), margins(top = 14))
        body.addView(routeOrder)
        updateOrderLabel()

        body.addView(section("DNS OVER HTTPS"), margins(top = 14))
        remoteDns = editField(body, "Удалённый DNS (DoH)", profileJson.optString("RemoteDNS", "https://8.8.8.8/dns-query"))
        domesticDns = editField(body, "Домашний DNS (DoH)", profileJson.optString("DomesticDNS", "https://77.88.8.8/dns-query"))
        body.addView(text("Домены .ru, .su и .рф используют домашний DNS; остальные запросы — удалённый. DNS-перехват на порту 53 остаётся первым правилом.", 12, Color.LTGRAY), margins(top = 4, bottom = 16))

        body.addView(section("GEO ФАЙЛЫ"), margins(top = 8))
        editField(body, "URL GeoSite (.dat для Xray)", profileJson.optString("Geositeurl", "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geosite/release/geosite.dat"))
        editField(body, "URL GeoIP (.dat для Xray)", profileJson.optString("Geoipurl", "https://cdn.jsdelivr.net/gh/hydraponique/roscomvpn-geoip/release/geoip.dat"))
        body.addView(text("GeoSite/GeoIP .dat используются Xray. Для sing-box геотокены не превращаются в обычные домены; там работают ручные домены и IP/CIDR.", 11, Color.LTGRAY), margins(top = 4, bottom = 18))

        val save = button("Сохранить настройки", 16) { saveProfile() }.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(125, 109, 255)); cornerRadius = dp(15).toFloat()
            }
            setTextColor(Color.WHITE)
        }
        body.addView(save, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addRuleEditor(parent: LinearLayout, keyA: String, labelA: String, keyB: String, labelB: String) {
        addRuleButton(parent, keyA, labelA)
        addRuleButton(parent, keyB, labelB)
    }

    private fun addRuleButton(parent: LinearLayout, key: String, label: String) {
        val values = readArray(profileJson.optJSONArray(key))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text(label, 14, Color.WHITE, true))
        val summary = text(if (values.isEmpty()) "Не задано" else values.size.toString() + " правил", 11, Color.LTGRAY)
        info.addView(summary, margins(top = 3))
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("Изменить", 13) {
            val input = EditText(this).apply {
                minLines = 5
                maxLines = 12
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setText(values.joinToString("\n"))
                hint = "Один домен или IP/CIDR на строку"
            }
            AlertDialog.Builder(this).setTitle(label).setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить") { _, _ ->
                    val updated = input.text.toString().lineSequence().map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("#") }.distinct().toList()
                    val arr = JSONArray()
                    updated.forEach { arr.put(it) }
                    profileJson.put(key, arr)
                    summary.text = if (updated.isEmpty()) "Не задано" else "${updated.size} правил"
                }.show()
        })
        parent.addView(row)
        parent.addView(View(this).apply { setBackgroundColor(Color.rgb(52, 54, 64)) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun editField(parent: LinearLayout, label: String, value: String): EditText {
        parent.addView(text(label, 13, Color.LTGRAY, true), margins(top = 10, bottom = 4))
        val field = EditText(this).apply {
            setText(value)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            singleLine = true
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(125, 109, 255))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        parent.addView(field, LinearLayout.LayoutParams(-1, dp(48)))
        when (label) {
            "Удалённый DNS (DoH)" -> remoteDns = field
            "Домашний DNS (DoH)" -> domesticDns = field
        }
        if (label.startsWith("URL GeoSite")) ruleFields["Geositeurl"] = field
        if (label.startsWith("URL GeoIP")) ruleFields["Geoipurl"] = field
        return field
    }

    private fun chooseOrder() {
        val options = arrayOf("block-proxy-direct", "block-direct-proxy", "proxy-block-direct")
        val labels = arrayOf("block → proxy → direct", "block → direct → proxy", "proxy → block → direct")
        val selected = options.indexOf(orderValue).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Порядок групп")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                orderValue = options[which]
                updateOrderLabel()
                dialog.dismiss()
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun updateOrderLabel() {
        routeOrder.text = "Порядок: " + when (orderValue) {
            "block-direct-proxy" -> "block → direct → proxy"
            "proxy-block-direct" -> "proxy → block → direct"
            else -> "block → proxy → direct"
        } + "   ⌄"
    }

    private fun saveProfile() {
        val remote = remoteDns.text.toString().trim()
        val domestic = domesticDns.text.toString().trim()
        if (!remote.startsWith("https://", true) || !domestic.startsWith("https://", true)) {
            Toast.makeText(this, "Для обоих DNS укажите HTTPS URL", Toast.LENGTH_LONG).show()
            return
        }
        profileJson.put("Name", profileName)
            .put("GlobalProxy", globalProxy.isChecked.toString())
            .put("RouteOrder", orderValue)
            .put("RemoteDNSType", "DoH")
            .put("DomesticDNSType", "DoH")
            .put("RemoteDNS", remote)
            .put("DomesticDNS", domestic)
        ruleFields.forEach { (key, field) -> profileJson.put(key, field.text.toString().trim()) }
        val saved = store.save(profileJson, activate = true)
        store.setEnabled(true)
        Toast.makeText(this, "Профиль «${saved.name}» сохранён. Переподключитесь для применения.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun readArray(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotBlank() }

    private fun section(label: String): TextView = text(label, 11, Color.rgb(160, 148, 255), true).apply {
        setPadding(0, dp(10), 0, dp(8))
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(label: String, size: Int, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = size.toFloat()
        setOnClickListener { action() }
    }

    private fun margins(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
