package com.neotun.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

data class NeoTunSubscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
)

class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<NeoTunSubscription> {
        val raw = prefs.getString(KEY_SUBSCRIPTIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val url = o.optString("url").trim()
                    if (url.isBlank()) continue
                    add(NeoTunSubscription(
                        o.optString("id", UUID.randomUUID().toString()),
                        o.optString("name").ifBlank { "Подписка" },
                        url,
                        o.optLong("lastUpdated", 0L),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(subscription: NeoTunSubscription) {
        val list = all().toMutableList()
        val index = list.indexOfFirst { it.id == subscription.id || it.url == subscription.url }
        if (index >= 0) list[index] = subscription else list.add(subscription)
        persist(list)
    }

    fun delete(id: String) = persist(all().filterNot { it.id == id })

    fun refresh(subscription: NeoTunSubscription, profiles: ProfileStore): Result<Int> = runCatching {
        val links = decodeLinks(fetch(subscription.url))
        if (links.isEmpty()) error("Подписка не содержит поддерживаемых ссылок")

        var imported = 0
        links.forEach { uri ->
            if (!uri.startsWith("vless://", true)) return@forEach
            val engine = NeoTunCore.nativeVlessEngine(uri)
            if (engine == "unknown") return@forEach
            profiles.save(NeoTunProfile(
                UUID.randomUUID().toString(),
                ProfileStore.displayNameFromUri(uri),
                uri,
                engine
            ))
            imported++
        }
        if (imported == 0) error("В подписке нет поддерживаемых VLESS-профилей")
        save(subscription.copy(lastUpdated = System.currentTimeMillis()))
        imported
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "NeoTUN/0.3")
            instanceFollowRedirects = true
        }
        return connection.use {
            if (it.responseCode !in 200..299) error("HTTP ${it.responseCode}")
            it.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
        }
    }

    private fun decodeLinks(body: String): List<String> {
        val text = body.trim()
        val candidates = linkedSetOf<String>()

        fun addLines(value: String) {
            value.lines()
                .flatMap { it.trim().split(Regex("[,\\s]+")) }
                .map { it.trim() }
                .filter { it.contains("://") }
                .forEach { candidates.add(it) }
        }

        addLines(text)
        listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP).forEach { flags ->
            if (candidates.isNotEmpty()) return@forEach
            runCatching {
                Base64.decode(text.replace("\\s".toRegex(), ""), flags)
                    .toString(StandardCharsets.UTF_8)
            }.getOrNull()?.let(::addLines)
        }

        return candidates.filter {
            it.startsWith("vless://", true) ||
            it.startsWith("vmess://", true) ||
            it.startsWith("trojan://", true) ||
            it.startsWith("hysteria2://", true) ||
            it.startsWith("hy2://", true) ||
            it.startsWith("tuic://", true) ||
            it.startsWith("ss://", true)
        }
    }

    private fun persist(list: List<NeoTunSubscription>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject()
                .put("id", it.id)
                .put("name", it.name)
                .put("url", it.url)
                .put("lastUpdated", it.lastUpdated))
        }
        prefs.edit().putString(KEY_SUBSCRIPTIONS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "neotun_subscriptions"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
    }
}
