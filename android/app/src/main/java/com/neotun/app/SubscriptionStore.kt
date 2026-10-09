package com.neotun.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream

data class NeoTunSubscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
)

class SubscriptionStore(context: Context) {
    private val routingProfiles = RoutingProfileStore(context)
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
                    // Stable migration for older entries that have no persisted ID.
                    val id = o.optString("id").takeIf { it.isNotBlank() }
                        ?: UUID.nameUUIDFromBytes(url.trim().trimEnd('/').toByteArray(StandardCharsets.UTF_8)).toString()
                    add(NeoTunSubscription(
                        id,
                        o.optString("name").ifBlank { "Подписка" },
                        url,
                        o.optLong("lastUpdated", 0L),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(subscription: NeoTunSubscription): NeoTunSubscription {
        val list = all().toMutableList()
        val normalizedUrl = subscription.url.trim()
        val index = list.indexOfFirst {
            it.id == subscription.id || it.url.trim().trimEnd('/') == normalizedUrl.trimEnd('/')
        }
        // Re-adding the same subscription URL must not replace its stable ID.
        // ProfileStore uses this ID to reconcile and remove stale profiles.
        val saved = if (index >= 0) {
            subscription.copy(id = list[index].id, url = normalizedUrl)
        } else {
            subscription.copy(url = normalizedUrl)
        }
        if (index >= 0) list[index] = saved else list.add(saved)
        persist(list)
        return saved
    }

    fun delete(id: String) = persist(all().filterNot { it.id == id })

    @Synchronized
    fun refresh(subscription: NeoTunSubscription, profiles: ProfileStore): Result<Int> = runCatching {
        // Resolve and persist the stable subscription ID before assigning profile ownership.
        val stableSubscription = save(subscription)
        val response = fetch(stableSubscription.url)
        importRoutingFromSubscription(response.body, response.routingHeader, response.autoRoutingHeader)
        val links = decodeLinks(response.body)
        if (links.isEmpty()) error("Подписка не содержит поддерживаемых ссылок")

        val parsed = links.mapNotNull { uri ->
            val engine = NeoTunCore.nativeShareEngine(uri)
            if (engine == "unknown") null else NeoTunProfile(
                UUID.randomUUID().toString(),
                ProfileStore.displayNameFromUri(uri),
                uri,
                engine,
                stableSubscription.id,
            )
        }
        if (parsed.isEmpty()) error("В подписке нет поддерживаемых профилей")
        val imported = profiles.replaceFromSubscription(stableSubscription.id, parsed)
        if (imported == 0) error("В подписке нет поддерживаемых профилей")
        save(stableSubscription.copy(lastUpdated = System.currentTimeMillis()))
        imported
    }

    private data class SubscriptionResponse(
        val body: String,
        val routingHeader: String?,
        val autoRoutingHeader: String?,
    )

    private fun fetch(url: String): SubscriptionResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "NeoTUN/0.4")
            setRequestProperty("Accept", "text/plain, text/*, application/json, */*")
            setRequestProperty("Accept-Encoding", "gzip")
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val rawStream = connection.inputStream
            val stream = if (connection.getHeaderField("Content-Encoding")
                    ?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(rawStream)
            } else {
                rawStream
            }
            val body = stream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
            SubscriptionResponse(body, connection.getHeaderField("routing"), connection.getHeaderField("autorouting"))
        } finally {
            connection.disconnect()
        }
    }

    private fun importRoutingFromSubscription(body: String, routingHeader: String?, autoRoutingHeader: String?) {
        // INCY source priority: autorouting header > autorouting body > routing header > routing body.
        val lines = body.lineSequence().map { it.trim() }.filter {
            it.contains("://autorouting/", true) || it.contains("://routing/", true)
        }.toList()
        val candidate = autoRoutingHeader?.takeIf { it.isNotBlank() }
            ?: lines.firstOrNull { it.contains("://autorouting/", true) }
            ?: routingHeader?.takeIf { it.isNotBlank() }
            ?: lines.firstOrNull { it.contains("://routing/", true) }
            ?: return
        if (candidate.equals("off", true) || candidate.contains("://routing/off", true)) {
            routingProfiles.setEnabled(false)
            return
        }

        val auto = candidate.contains("://autorouting/", true)
        val remoteUrl = if (auto || candidate.contains("://routing/onadd/http", true) ||
            candidate.contains("://routing/add/http", true)) {
            val payload = when {
                candidate.contains("://autorouting/", true) ->
                    candidate.substringAfter("://autorouting/", "").substringAfter('/', "")
                candidate.contains("://routing/", true) ->
                    candidate.substringAfter("://routing/", "").substringAfter('/', "")
                else -> ""
            }
            payload.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                ?.let(::normalizeGitHubRawUrl)
        } else null
        val sourceUrl = remoteUrl.takeIf { auto }

        val profileJson = if (remoteUrl != null) {
            runCatching { NeoTunRoutingProfile.decode(fetch(remoteUrl).body) }.getOrNull()
        } else {
            NeoTunRoutingProfile.decode(candidate)
        } ?: return
        routingProfiles.save(profileJson, sourceUrl = sourceUrl, activate = true)
        routingProfiles.setEnabled(true)
    }

    private fun normalizeGitHubRawUrl(url: String): String =
        url.replace("https://github.com/", "https://raw.githubusercontent.com/")
            .replace("/blob/", "/")

    private fun decodeLinks(body: String): List<String> {
        val text = body.trim()
        val candidates = linkedSetOf<String>()

        fun addLines(value: String) {
            // Commas are valid in Hysteria2 port-hopping ranges, so they are not
            // general-purpose link separators. Stop only at whitespace/new URI.
            val linkPattern = Regex("(?i)(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://.*?(?=(?:vless|vmess|trojan|hysteria2|hy2|tuic|ss)://|\\s|$)")
            value.lines().forEach { line ->
                linkPattern.findAll(line).forEach { match ->
                    val link = match.value.trim().trimEnd(',', ';', '"', '\'')
                    if (link.isNotBlank()) candidates.add(link)
                }
            }
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
