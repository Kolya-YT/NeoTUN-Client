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
                            name = item.optString("name").ifBlank { "VLESS" },
                            uri = uri,
                            engine = item.optString("engine", NeoTunVpnService.ENGINE_SING_BOX),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(profile: NeoTunProfile) {
        val profiles = all().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id || it.uri == profile.uri }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        persist(profiles)
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
                    .put("engine", profile.engine),
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "neotun_profiles"
        private const val KEY_PROFILES = "profiles"

        fun displayNameFromUri(uri: String): String {
            val fragment = uri.substringAfter('#', "")
            if (fragment.isNotBlank()) {
                return runCatching {
                    URLDecoder.decode(fragment, StandardCharsets.UTF_8.name())
                }.getOrDefault(fragment)
                    .ifBlank { "VLESS" }
            }

            val authority = uri.substringAfter("://", "").substringBefore('?').substringBefore('#')
            val host = authority.substringAfter('@', authority).substringBeforeLast(':')
            return host.ifBlank { "VLESS" }
        }
    }
}
