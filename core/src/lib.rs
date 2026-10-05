use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoreInfo {
    pub name: &'static str,
    pub version: &'static str,
    pub protocol: &'static str,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Profile {
    pub protocol: String,
    pub address: String,
    pub port: u16,
    pub name: Option<String>,
    pub uuid: Option<String>,
    pub password: Option<String>,
    pub params: HashMap<String, String>,
}

impl Profile {
    pub fn from_vless_uri(uri: &str) -> Result<Self, String> {
        let uri = uri.trim();
        let rest = uri.strip_prefix("vless://").ok_or("Ожидалась ссылка vless://")?;
        let (main, fragment) = rest.split_once('#').map_or((rest, None), |(a, b)| (a, Some(b)));
        let (authority, query) = main.split_once('?').map_or((main, ""), |(a, b)| (a, b));

        let (userinfo, hostport) = authority.rsplit_once('@').ok_or("В ссылке VLESS отсутствует UUID")?;
        let uuid = percent_decode(userinfo)?;
        let (address, port_str) = if let Some(stripped) = hostport.strip_prefix('[') {
            let (ipv6, rest) = stripped.split_once(']').ok_or("Некорректный IPv6-адрес VLESS")?;
            let port = rest.strip_prefix(':').ok_or("В ссылке VLESS отсутствует порт")?;
            (ipv6, port)
        } else {
            hostport.rsplit_once(':').ok_or("В ссылке VLESS отсутствует порт")?
        };
        let port: u16 = port_str.parse().map_err(|_| "Некорректный порт VLESS")?;

        let mut params = HashMap::new();
        for pair in query.split('&').filter(|x| !x.is_empty()) {
            let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
            params.insert(percent_decode(key)?, percent_decode(value)?);
        }

        let name = fragment.map(percent_decode).transpose()?;

        Ok(Self {
            protocol: "vless".to_string(),
            address: percent_decode(address)?,
            port,
            name,
            uuid: Some(uuid),
            password: None,
            params,
        })
    }
}


impl Profile {
    pub fn to_sing_box_json(&self) -> Result<String, String> {
        if self.protocol != "vless" {
            return Err("Пока реализована генерация sing-box только для VLESS".into());
        }

        let uuid = self.uuid.as_deref().ok_or("Для VLESS требуется UUID")?;
        let security = self.params.get("security").map(String::as_str).unwrap_or("none");
        let transport = self.params.get("type").map(String::as_str).unwrap_or("tcp");
        let engine = self.engine();

        if engine == "xray" {
            return Ok(serde_json::json!({"engine": "xray", "protocol": "vless", "transport": "xhttp"}).to_string());
        }

        let mut vless = serde_json::json!({
            "type": "vless",
            "tag": "proxy",
            "server": self.address,
            "server_port": self.port,
            "uuid": uuid
        });

        if security == "tls" {
            vless["tls"] = serde_json::json!({
                "enabled": true,
                "server_name": self.params.get("sni").or_else(|| self.params.get("host")).cloned()
            });
        } else if security == "reality" {
            vless["tls"] = serde_json::json!({
                "enabled": true,
                "server_name": self.params.get("sni").or_else(|| self.params.get("host")).cloned(),
                "reality": {
                    "enabled": true,
                    "public_key": self.params.get("pbk").cloned().unwrap_or_default(),
                    "short_id": self.params.get("sid").cloned().unwrap_or_default()
                }
            });
        }

        match transport {
            "ws" => {
                vless["transport"] = serde_json::json!({
                    "type": "ws",
                    "path": self.params.get("path").cloned().unwrap_or_else(|| "/".into()),
                    "headers": if let Some(host) = self.params.get("host") {
                        serde_json::json!({"Host": host})
                    } else {
                        serde_json::json!({})
                    }
                });
            }
            "grpc" => {
                vless["transport"] = serde_json::json!({
                    "type": "grpc",
                    "service_name": self.params.get("serviceName").or_else(|| self.params.get("service_name")).cloned().unwrap_or_default()
                });
            }
            _ => {}
        }

        let config = serde_json::json!({
            "log": {"level": "info"},
            "inbounds": [{
                "type": "tun",
                "tag": "tun-in",
                "address": ["172.19.0.1/30"],
                "auto_route": true
            }],
            "outbounds": [
                vless,
                {"type": "direct", "tag": "direct"},
                {"type": "block", "tag": "block"}
            ],
            "route": {
                "auto_detect_interface": true,
                "final": "proxy"
            }
        });

        serde_json::to_string_pretty(&config).map_err(|e| e.to_string())
    }
}

fn percent_decode(input: &str) -> Result<String, String> {
    let mut out = Vec::with_capacity(input.len());
    let bytes = input.as_bytes();
    let mut i = 0;

    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return Err("Некорректное percent-encoding".into());
            }
            let hi = hex(bytes[i + 1]).ok_or("Некорректное percent-encoding")?;
            let lo = hex(bytes[i + 2]).ok_or("Некорректное percent-encoding")?;
            out.push((hi << 4) | lo);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }

    String::from_utf8(out).map_err(|_| "Некорректная UTF-8 строка".into())
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_neotun_app_NeoTunCore_nativeVlessConfig(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let value = env
        .get_string(&uri)
        .ok()
        .and_then(|s| Profile::from_vless_uri(s.to_str().ok()?).ok())
        .and_then(|p| p.to_sing_box_json().ok())
        .unwrap_or_else(|| String::new());

    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_neotun_app_NeoTunCore_nativeVlessEngine(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let value = env
        .get_string(&uri)
        .ok()
        .and_then(|s| Profile::from_vless_uri(s.to_str().ok()?).ok())
        .map(|p| p.engine())
        .unwrap_or("unknown");

    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_neotun_app_NeoTunCore_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = "NeoTUN Core 0.2.0 • Rust";
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

impl Profile {
    pub fn engine(&self) -> &'static str {
        match self.params.get("type").map(String::as_str) {
            Some("xhttp") => "xray",
            _ => "sing-box",
        }
    }
}

pub fn supported_protocols() -> Vec<CoreInfo> {
    vec![
        CoreInfo { name: "VLESS", version: "adapter", protocol: "vless" },
        CoreInfo { name: "VMess", version: "adapter", protocol: "vmess" },
        CoreInfo { name: "Trojan", version: "adapter", protocol: "trojan" },
        CoreInfo { name: "Hysteria2", version: "sing-box", protocol: "hysteria2" },
        CoreInfo { name: "TUIC", version: "sing-box", protocol: "tuic" },
        CoreInfo { name: "Shadowsocks", version: "sing-box", protocol: "shadowsocks" },
        CoreInfo { name: "WireGuard", version: "native/adapter", protocol: "wireguard" },
        CoreInfo { name: "AmneziaWG", version: "amnezia", protocol: "amneziawg" },
        CoreInfo { name: "OpenVPN", version: "native/adapter", protocol: "openvpn" },
        CoreInfo { name: "OpenFlux", version: "adapter", protocol: "openflux" },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocols_are_present() {
        let protocols = supported_protocols();
        assert!(protocols.iter().any(|p| p.protocol == "vless"));
        assert!(protocols.iter().any(|p| p.protocol == "tuic"));
        assert!(protocols.iter().any(|p| p.protocol == "amneziawg"));
    }

    #[test]
    fn parses_vless_uri() {
        let profile = Profile::from_vless_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=ws&path=%2Fneo#My%20Server"
        ).unwrap();

        assert_eq!(profile.protocol, "vless");
        assert_eq!(profile.address, "example.com");
        assert_eq!(profile.port, 443);
        assert_eq!(profile.uuid.as_deref(), Some("123e4567-e89b-12d3-a456-426614174000"));
        assert_eq!(profile.name.as_deref(), Some("My Server"));
        assert_eq!(profile.params.get("security").map(String::as_str), Some("tls"));
        assert_eq!(profile.params.get("path").map(String::as_str), Some("/neo"));
        let config = profile.to_sing_box_json().unwrap();
        assert!(config.contains("\"type\": \"vless\""));
        assert!(config.contains("\"type\": \"tun\""));
        assert!(config.contains("example.com"));
    }

    #[test]
    fn xhttp_selects_xray() {
        let profile = Profile::from_vless_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=xhttp&path=%2Fneo"
        ).unwrap();

        assert_eq!(profile.engine(), "xray");
        let config = profile.to_sing_box_json().unwrap();
        assert!(config.contains("\"engine\":\"xray\""));
        assert!(config.contains("\"transport\":\"xhttp\""));
    }

    #[test]
    fn parses_ipv6_vless_uri() {
        let profile = Profile::from_vless_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@[2001:db8::1]:443?type=tcp"
        ).unwrap();

        assert_eq!(profile.address, "2001:db8::1");
        assert_eq!(profile.port, 443);
    }
}
