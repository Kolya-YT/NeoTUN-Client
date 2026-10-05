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
        let (address, port_str) = hostport.rsplit_once(':').ok_or("В ссылке VLESS отсутствует порт")?;
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
pub extern "system" fn Java_com_neotun_app_NeoTunCore_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = "NeoTUN Core 0.1.0 • Rust";
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
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
    }
}
