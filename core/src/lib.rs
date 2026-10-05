use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoreInfo {
    pub name: &'static str,
    pub version: &'static str,
    pub protocol: &'static str,
}

#[no_mangle]
pub extern "system" fn Java_com_neotun_app_NeoTunCore_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let value = "NeoTUN Core 0.1.0 • Rust";
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

pub fn supported_protocols() -> Vec<CoreInfo> {
    vec![
        CoreInfo { name: "VLESS", version: "native/adapter", protocol: "vless" },
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
}
