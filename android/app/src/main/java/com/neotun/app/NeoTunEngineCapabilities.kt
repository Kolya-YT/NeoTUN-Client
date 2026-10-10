package com.neotun.app

/**
 * Describes what NeoTUN currently wires up for a profile.
 *
 * This is deliberately a client capability model, not a claim that every protocol
 * option is available in every native engine. Update it when a setting is actually
 * passed into the selected engine configuration.
 */
data class NeoTunEngineCapabilities(
    val engineId: String,
    val engineName: String,
    val protocol: String,
    val supportsTun: Boolean,
    val supportsDnsSettings: Boolean,
    val supportsRoutingProfiles: Boolean,
    val supportsMtu: Boolean,
    val supportsAppExclusions: Boolean,
    val notes: List<String>,
)

object NeoTunEngineCapabilityRegistry {
    fun forProfile(profile: NeoTunProfile): NeoTunEngineCapabilities {
        val scheme = profile.uri.substringBefore("://", "").lowercase()
        val protocol = when (scheme) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "hysteria2", "hy2" -> "Hysteria2"
            "tuic" -> "TUIC"
            "ss" -> "Shadowsocks"
            "socks" -> "SOCKS"
            else -> scheme.uppercase().ifBlank { "Неизвестный" }
        }
        val engine = profile.engine.lowercase()
        val xray = engine.contains("xray")
        val singBox = engine.contains("sing") || engine.contains("box")
        val engineName = when {
            xray -> "Xray"
            singBox -> "sing-box"
            else -> profile.engine.ifBlank { "Не определено" }
        }
        val notes = buildList {
            add("Протокол определяется из исходной ссылки: $protocol.")
            add("DNS, MTU и профиль маршрутизации задаются в общих настройках NeoTUN и применяются при следующем подключении.")
            if (xray) {
                add("Для этого профиля используется Xray. Правила маршрутизации преобразуются адаптером Xray; доступность отдельных типов правил зависит от их формата.")
            } else if (singBox) {
                add("Для этого профиля используется sing-box. Правила маршрутизации преобразуются адаптером sing-box.")
            } else {
                add("Движок не распознан как sing-box или Xray. Перед подключением проверьте поддержку протокола.")
            }
        }
        return NeoTunEngineCapabilities(
            engineId = profile.engine,
            engineName = engineName,
            protocol = protocol,
            supportsTun = xray || singBox,
            supportsDnsSettings = xray || singBox,
            supportsRoutingProfiles = xray || singBox,
            supportsMtu = xray || singBox,
            supportsAppExclusions = xray || singBox,
            notes = notes,
        )
    }
}
