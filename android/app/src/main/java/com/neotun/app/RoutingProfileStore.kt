package com.neotun.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Engine-neutral routing profile compatible with the common INCY/Happ JSON fields.
 * Engine adapters consume this model instead of coupling profile storage to a core.
 */
data class NeoTunRoutingProfile(
    val id: String,
    val name: String,
    val json: JSONObject,
    val sourceUrl: String? = null,
    val updatedAt: Long = 0L,
) {
    val globalProxy: Boolean
        get() = json.optString("GlobalProxy", "true").equals("true", true)

    fun values(key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}

class RoutingProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<NeoTunRoutingProfile> = runCatching {
        val array = JSONArray(prefs.getString(KEY_PROFILES, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val raw = item.optJSONObject("profile") ?: continue
                add(NeoTunRoutingProfile(
                    item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    raw.optString("Name").ifBlank { "Routing" },
                    JSONObject(raw.toString()),
                    item.optString("sourceUrl").takeIf { it.isNotBlank() },
                    item.optLong("updatedAt", 0L),
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun active(): NeoTunRoutingProfile? {
        if (!prefs.getBoolean(KEY_ENABLED, true)) return null
        val id = prefs.getString(KEY_ACTIVE, null) ?: return null
        return all().firstOrNull { it.id == id }
    }

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun select(id: String?) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    @Synchronized
    fun save(profileJson: JSONObject, sourceUrl: String? = null, activate: Boolean = true): NeoTunRoutingProfile {
        val name = profileJson.optString("Name").trim().ifBlank { "Routing" }
        val timestamp = profileJson.optLong("LastUpdated", 0L)
        val existing = all().firstOrNull { it.name.equals(name, true) }
        if (existing != null && timestamp > 0 && existing.updatedAt > timestamp) {
            if (activate) select(existing.id)
            return existing
        }
        val saved = NeoTunRoutingProfile(
            existing?.id ?: UUID.randomUUID().toString(),
            name,
            JSONObject(profileJson.toString()),
            sourceUrl ?: existing?.sourceUrl,
            maxOf(timestamp, System.currentTimeMillis() / 1000L),
        )
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == saved.id }
        if (index >= 0) list[index] = saved else list.add(saved)
        persist(list)
        if (activate) select(saved.id)
        return saved
    }

    fun delete(id: String) {
        val remaining = all().filterNot { it.id == id }
        persist(remaining)
        if (prefs.getString(KEY_ACTIVE, null) == id) select(remaining.firstOrNull()?.id)
    }

    private fun persist(profiles: List<NeoTunRoutingProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject()
                .put("id", profile.id)
                .put("profile", profile.json)
                .put("sourceUrl", profile.sourceUrl ?: "")
                .put("updatedAt", profile.updatedAt))
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "neotun_routing"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_ENABLED = "enabled"

        /** Accept JSON, Base64 JSON and INCY/Happ-style routing deeplinks. */
        fun decode(rawValue: String): JSONObject? {
            var value = rawValue.trim()
            if (value.isBlank() || value.equals("off", true) ||
                value.contains("://routing/off", true)) return null

            val routingIndex = value.indexOf("://routing/", ignoreCase = true)
            val autoIndex = value.indexOf("://autorouting/", ignoreCase = true)
            val linkIndex = when {
                autoIndex >= 0 -> autoIndex
                routingIndex >= 0 -> routingIndex
                else -> -1
            }
            if (linkIndex >= 0) {
                val rest = value.substring(linkIndex)
                val path = rest.substringAfter("://", "")
                val mode = path.substringAfter('/', "").substringBefore('/')
                val payload = path.substringAfter('/', "").substringAfter('/', "")
                value = if (payload.startsWith("http://", true) || payload.startsWith("https://", true)) {
                    return null // URL imports are handled by the asynchronous importer.
                } else payload
                if (mode.equals("off", true)) return null
            }

            if (value.startsWith("{")) return runCatching { JSONObject(value) }.getOrNull()
            val candidates = listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)
            for (flags in candidates) {
                val decoded = runCatching {
                    Base64.decode(value.replace("\\s".toRegex(), ""), flags)
                        .toString(StandardCharsets.UTF_8).trim()
                }.getOrNull() ?: continue
                if (decoded.startsWith("{")) {
                    val json = runCatching { JSONObject(decoded) }.getOrNull()
                    if (json != null) return json
                }
            }
            return null
        }
    }
}

/** Builds per-engine route rules from the same normalized profile. */
object NeoTunRoutingAdapter {
    fun singBoxRules(profile: NeoTunRoutingProfile): JSONArray {
        val rules = JSONArray()
        fun add(values: List<String>, isDomain: Boolean, action: String, outbound: String? = null) {
            if (values.isEmpty()) return
            val domains = JSONArray()
            val suffixes = JSONArray()
            val ips = JSONArray()
            values.forEach { value ->
                when {
                    value.startsWith("geosite:", true) -> domains.put(value)
                    value.startsWith("geoip:", true) || value.contains('/') || value.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) -> ips.put(value)
                    isDomain && value.startsWith("domain-suffix:", true) -> suffixes.put(value.substringAfter(':'))
                    isDomain -> domains.put(value.removePrefix("domain:"))
                    else -> ips.put(value)
                }
            }
            val rule = JSONObject().put("action", action)\n            if (outbound != null) rule.put("outbound", outbound)
            if (domains.length() > 0) rule.put("domain", domains)
            if (suffixes.length() > 0) rule.put("domain_suffix", suffixes)
            if (ips.length() > 0) rule.put("ip_cidr", ips)
            if (rule.length() > 1) rules.put(rule)
        }
        add(profile.values("BlockSites"), true, "reject")
        add(profile.values("BlockIp"), false, "reject")
        add(profile.values("DirectSites"), true, "route", "direct")
        add(profile.values("DirectIp"), false, "route", "direct")
        // Proxy rules are explicit for compatibility; unmatched traffic follows final.
        add(profile.values("ProxySites"), true, "route", "proxy")
        add(profile.values("ProxyIp"), false, "route", "proxy")
        return rules
    }

    fun xrayRules(profile: NeoTunRoutingProfile): JSONArray {
        val rules = JSONArray()
        fun add(values: List<String>, domain: Boolean, outbound: String) {
            if (values.isEmpty()) return
            val rule = JSONObject().put("type", "field").put("outboundTag", outbound)
            val geo = values.filter { it.startsWith("geoip:", true) }
            val plain = values.filterNot { it.startsWith("geoip:", true) }
            if (domain && plain.isNotEmpty()) rule.put("domain", JSONArray(plain))
            if (!domain && (plain + geo).isNotEmpty()) rule.put("ip", JSONArray(plain + geo))
            if (rule.length() > 2) rules.put(rule)
        }
        add(profile.values("BlockSites"), true, "block")
        add(profile.values("BlockIp"), false, "block")
        add(profile.values("DirectSites"), true, "direct")
        add(profile.values("DirectIp"), false, "direct")
        add(profile.values("ProxySites"), true, "proxy")
        add(profile.values("ProxyIp"), false, "proxy")
        return rules
    }
}
