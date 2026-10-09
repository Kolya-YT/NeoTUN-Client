package com.neotun.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class NeoTunProfile(
    val id: String,
    val name: String,
    val uri: String,
    val engine: String,
    val sourceSubscriptionId: String? = null,
)

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<NeoTunProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val uri = item.optString("uri")
                    if (uri.isBlank()) continue
                    add(
                        NeoTunProfile(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            name = decodeDisplayName(item.optString("name").ifBlank { "VLESS" }),
                            uri = uri,
                            engine = item.optString("engine", NeoTunVpnService.ENGINE_SING_BOX),
                            sourceSubscriptionId = item.optString("sourceSubscriptionId").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(profile: NeoTunProfile) {
        val profiles = deduplicate(all()).toMutableList()
        val key = canonicalKey(profile.uri)
        val index = profiles.indexOfFirst { it.id == profile.id || canonicalKey(it.uri) == key }
        if (index >= 0) {
            val previous = profiles[index]
            profiles[index] = profile.copy(
                id = previous.id,
                sourceSubscriptionId = profile.sourceSubscriptionId ?: previous.sourceSubscriptionId,
            )
        } else {
            profiles.add(profile)
        }
        persist(deduplicate(profiles))
    }

    @Synchronized
    fun replaceFromSubscription(subscriptionId: String, incoming: List<NeoTunProfile>): Int {
        val uniqueIncoming = LinkedHashMap<String, NeoTunProfile>()
        incoming.forEach { profile -> uniqueIncoming.putIfAbsent(canonicalKey(profile.uri), profile) }
        if (uniqueIncoming.isEmpty()) return 0

        val existing = deduplicate(all())
        val incomingKeys = uniqueIncoming.keys
        val result = existing.filter {
            it.sourceSubscriptionId != subscriptionId || canonicalKey(it.uri) in incomingKeys
        }.toMutableList()

        uniqueIncoming.forEach { (key, fresh) ->
            val old = existing.firstOrNull { canonicalKey(it.uri) == key }
            val replacement = fresh.copy(
                id = old?.id ?: fresh.id,
                sourceSubscriptionId = subscriptionId,
            )
            val at = result.indexOfFirst { canonicalKey(it.uri) == key }
            if (at >= 0) result[at] = replacement else result.add(replacement)
        }
        persist(deduplicate(result))
        return uniqueIncoming.size
    }

    private fun deduplicate(profiles: List<NeoTunProfile>): List<NeoTunProfile> {
        val unique = LinkedHashMap<String, NeoTunProfile>()
        profiles.forEach { profile ->
            val key = canonicalKey(profile.uri)
            val previous = unique[key]
            if (previous == null || (previous.sourceSubscriptionId == null && profile.sourceSubscriptionId != null)) {
                unique[key] = profile
            }
        }
        return unique.values.toList()
    }

    private fun canonicalKey(uri: String): String {
        val withoutFragment = uri.trim().substringBefore('#')
        val scheme = withoutFragment.substringBefore("://", "").lowercase()
        val rest = withoutFragment.substringAfter("://", withoutFragment)
        val queryAt = rest.indexOf('?')
        if (queryAt < 0) return "$scheme://$rest"
        val authority = rest.substring(0, queryAt)
        val query = rest.substring(queryAt + 1).split('&').filter { it.isNotBlank() }.sorted().joinToString("&")
        return if (query.isBlank()) "$scheme://$authority" else "$scheme://$authority?$query"
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
    }

    fun rename(id: String, name: String) {
        val profiles = all().map {
            if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it
        }
        persist(profiles)
    }

    private fun persist(profiles: List<NeoTunProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("uri", profile.uri)
                    .put("engine", profile.engine)
                    .put("sourceSubscriptionId", profile.sourceSubscriptionId ?: ""),
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    @Synchronized
    fun saveAll(profiles: List<NeoTunProfile>) = persist(deduplicate(profiles))

    companion object {
        private const val PREFS = "neotun_profiles"
        private const val KEY_PROFILES = "profiles"

        private fun decodeDisplayName(value: String): String {
            val decoded = runCatching {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrDefault(value)
            return decoded.substringBefore('#').trim().ifBlank { "VLESS" }
        }

        fun displayNameFromUri(uri: String): String {
            val fragment = uri.substringAfter('#', "")
            if (fragment.isNotBlank()) {
                return decodeDisplayName(fragment)
            }

            val authority = uri.substringAfter("://", "").substringBefore('?').substringBefore('#')
            val host = authority.substringAfter('@', authority).substringBeforeLast(':')
            return decodeDisplayName(host)
        }
    }
}
