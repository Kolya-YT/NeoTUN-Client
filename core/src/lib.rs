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
        let uri = normalize_vless_uri(uri)?;
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


    pub fn from_share_uri(uri: &str) -> Result<Self, String> {
        let normalized = uri.trim();
        if normalized.to_ascii_lowercase().starts_with("vless://") {
            return Self::from_vless_uri(normalized);
        }
        if normalized.to_ascii_lowercase().starts_with("trojan://") {
            return Self::from_trojan_uri(normalized);
        }
        if normalized.to_ascii_lowercase().starts_with("hysteria2://") || normalized.to_ascii_lowercase().starts_with("hy2://") {
            return Self::from_hysteria2_uri(normalized);
        }
        if normalized.to_ascii_lowercase().starts_with("tuic://") {
            return Self::from_tuic_uri(normalized);
        }
        if normalized.to_ascii_lowercase().starts_with("ss://") {
            return Self::from_shadowsocks_uri(normalized);
        }
        if normalized.to_ascii_lowercase().starts_with("vmess://") {
            return Self::from_vmess_uri(normalized);
        }
        Err("Неподдерживаемая ссылка".into())
    }

    fn from_authority_uri(uri: &str, scheme: &str) -> Result<(&str, &str, &str), String> {
        let rest = uri.strip_prefix(scheme).ok_or("Некорректная схема")?;
        let (main, _) = rest.split_once('#').map_or((rest, ""), |(a,b)|(a,b));
        let (authority, query) = main.split_once('?').map_or((main, ""), |(a,b)|(a,b));
        let (user, hostport) = authority.rsplit_once('@').ok_or("В ссылке отсутствуют учётные данные")?;
        Ok((user, hostport, query))
    }

    fn split_hostport(hostport: &str) -> Result<(String, u16), String> {
        if let Some(stripped) = hostport.strip_prefix('[') {
            let (host, rest) = stripped.split_once(']').ok_or("Некорректный IPv6")?;
            let port: u16 = rest.strip_prefix(':').ok_or("Порт отсутствует")?.parse().map_err(|_| "Некорректный порт")?;
            return Ok((host.to_string(), port));
        }
        let (host, port) = hostport.rsplit_once(':').ok_or("Порт отсутствует")?;
        Ok((percent_decode(host)?, port.parse().map_err(|_| "Некорректный порт")?))
    }

    fn from_trojan_uri(uri: &str) -> Result<Self, String> {
        let (user, hostport, query) = Self::from_authority_uri(uri, "trojan://")?;
        let (address, port) = Self::split_hostport(hostport)?;
        let mut params = HashMap::new();
        for pair in query.split('&').filter(|x| !x.is_empty()) {
            let (k,v)=pair.split_once('=').unwrap_or((pair,""));
            params.insert(percent_decode(k)?, percent_decode(v)?);
        }
        Ok(Self { protocol:"trojan".into(), address, port, name: uri.split_once('#').and_then(|(_,x)| percent_decode(x).ok()), uuid:None, password:Some(percent_decode(user)?), params })
    }

    fn from_hysteria2_uri(uri: &str) -> Result<Self, String> {
        let scheme = if uri.to_ascii_lowercase().starts_with("hy2://") {"hy2://"} else {"hysteria2://"};
        let (user, hostport, query) = Self::from_authority_uri(uri, scheme)?;
        let (address, port) = Self::split_hostport(hostport)?;
        let mut params=HashMap::new();
        for pair in query.split('&').filter(|x| !x.is_empty()) {
            let (k,v)=pair.split_once('=').unwrap_or((pair,""));
            params.insert(percent_decode(k)?, percent_decode(v)?);
        }
        Ok(Self { protocol:"hysteria2".into(), address, port, name: uri.split_once('#').and_then(|(_,x)| percent_decode(x).ok()), uuid:None, password:Some(percent_decode(user)?), params })
    }

    fn from_tuic_uri(uri: &str) -> Result<Self, String> {
        let (user, hostport, query) = Self::from_authority_uri(uri, "tuic://")?;
        let (uuid, password) = user.split_once(':').ok_or("TUIC требует UUID:пароль")?;
        let (address, port) = Self::split_hostport(hostport)?;
        let mut params=HashMap::new();
        for pair in query.split('&').filter(|x| !x.is_empty()) {
            let (k,v)=pair.split_once('=').unwrap_or((pair,""));
            params.insert(percent_decode(k)?, percent_decode(v)?);
        }
        Ok(Self { protocol:"tuic".into(), address, port, name: uri.split_once('#').and_then(|(_,x)| percent_decode(x).ok()), uuid:Some(percent_decode(uuid)?), password:Some(percent_decode(password)?), params })
    }

    fn from_shadowsocks_uri(uri: &str) -> Result<Self, String> {
        let raw = uri.strip_prefix("ss://").ok_or("Ожидалась ss://")?;
        let (main, _) = raw.split_once('#').map_or((raw,""), |(a,b)|(a,b));
        let (encoded, query) = main.split_once('?').map_or((main,""), |(a,b)|(a,b));
        let decoded = percent_decode(encoded)?;
        let decoded = if decoded.contains('@') { decoded } else {
            let bytes = base64_decode(&decoded)?;
            String::from_utf8(bytes).map_err(|_| "Некорректный Shadowsocks")?
        };
        let (userinfo, hostport)=decoded.rsplit_once('@').ok_or("Shadowsocks: отсутствует сервер")?;
        let (method,password)=userinfo.split_once(':').ok_or("Shadowsocks: отсутствует пароль")?;
        let (address,port)=Self::split_hostport(hostport)?;
        let mut params=HashMap::new();
        params.insert("method".into(),method.into());
        params.insert("password".into(),password.into());
        if !query.is_empty() { params.insert("plugin".into(),query.into()); }
        Ok(Self { protocol:"shadowsocks".into(), address, port, name: uri.split_once('#').and_then(|(_,x)| percent_decode(x).ok()), uuid:None, password:Some(password.into()), params })
    }

    fn from_vmess_uri(uri: &str) -> Result<Self, String> {
        let raw=uri.strip_prefix("vmess://").ok_or("Ожидалась vmess://")?;
        let bytes=base64_decode(raw)?;
        let value: serde_json::Value=serde_json::from_slice(&bytes).map_err(|_| "Некорректный VMess JSON")?;
        let address=value.get("add").and_then(|v|v.as_str()).ok_or("VMess: нет add")?;
        let port=value.get("port").and_then(|v|v.as_str()).and_then(|v|v.parse().ok()).or_else(||value.get("port").and_then(|v|v.as_u64()).map(|v|v as u16)).ok_or("VMess: нет port")?;
        let uuid=value.get("id").and_then(|v|v.as_str()).map(str::to_string);
        let mut params=HashMap::new();
        for (key,target) in [("net","type"),("host","host"),("path","path"),("tls","security"),("sni","sni"),("scy","encryption")] {
            if let Some(v)=value.get(key).and_then(|v|v.as_str()) { params.insert(target.into(),v.into()); }
        }
        Ok(Self { protocol:"vmess".into(), address:address.into(), port, name:value.get("ps").and_then(|v|v.as_str()).map(str::to_string), uuid, password:None, params })
    }

    pub fn to_generic_sing_box_json(&self) -> Result<String, String> {
        let outbound = match self.protocol.as_str() {
            "vless" => serde_json::from_str::<serde_json::Value>(&self.to_sing_box_json()?).unwrap()["outbounds"][0].clone(),
            "trojan" => {
                let mut o=serde_json::json!({"type":"trojan","tag":"proxy","server":self.address,"server_port":self.port,"password":self.password.clone().unwrap_or_default()});
                add_tls(&mut o,&self.params);
                o
            },
            "hysteria2" => {
                let mut o=serde_json::json!({"type":"hysteria2","tag":"proxy","server":self.address,"server_port":self.port,"password":self.password.clone().unwrap_or_default()});
                add_tls(&mut o,&self.params); o
            },
            "tuic" => serde_json::json!({"type":"tuic","tag":"proxy","server":self.address,"server_port":self.port,"uuid":self.uuid.clone().unwrap_or_default(),"password":self.password.clone().unwrap_or_default(),"tls":{"enabled":true,"server_name":self.params.get("sni").or_else(||self.params.get("sni")).cloned()}}),
            "shadowsocks" => serde_json::json!({"type":"shadowsocks","tag":"proxy","server":self.address,"server_port":self.port,"method":self.params.get("method").cloned().unwrap_or_default(),"password":self.params.get("password").cloned().unwrap_or_default()}),
            "vmess" => serde_json::json!({"type":"vmess","tag":"proxy","server":self.address,"server_port":self.port,"uuid":self.uuid.clone().unwrap_or_default(),"security":self.params.get("encryption").cloned().unwrap_or_else(||"auto".into())}),
            _ => return Err("Протокол не поддерживается".into())
        };
        let config=serde_json::json!({"log":{"level":"info"},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true}],"outbounds":[outbound,{"type":"direct","tag":"direct"},{"type":"block","tag":"block"}],"route":{"auto_detect_interface":true,"final":"proxy"}});
        serde_json::to_string_pretty(&config).map_err(|e|e.to_string())
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

fn normalize_vless_uri(input: &str) -> Result<String, String> {
    let uri = input.trim();
    if uri.get(..8).is_some_and(|scheme| scheme.eq_ignore_ascii_case("vless://")) {
        let mut normalized = uri.to_string();
        normalized.replace_range(..8, "vless://");
        return Ok(normalized);
    }

    // Some clients/exporters percent-encode the entire share link. Decode it once
    // only when the scheme itself is encoded, so encoded query values such as
    // %26 are not accidentally turned into separators.
    let decoded = percent_decode(uri)?;
    if decoded.get(..8).is_some_and(|scheme| scheme.eq_ignore_ascii_case("vless://")) {
        let mut normalized = decoded;
        normalized.replace_range(..8, "vless://");
        return Ok(normalized);
    }

    Err("Ожидалась ссылка vless://".to_string())
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
    let value = "NeoTUN Core 0.2.3 • Rust";
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

impl Profile {
    pub fn engine(&self) -> &'static str {
        match self.params.get("type").map(|value| value.to_ascii_lowercase()) {
            Some(value) if value == "xhttp" => "xray",
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
    fn parses_percent_encoded_whole_uri() {
        let encoded = "%76%6c%65%73%73%3a%2f%2f123e4567-e89b-12d3-a456-426614174000%40example.com%3a443%3fsecurity%3dtls%26type%3dxhttp%26path%3d%252Fneo";
        let profile = Profile::from_vless_uri(encoded).unwrap();
        assert_eq!(profile.engine(), "xray");
        assert_eq!(profile.params.get("type").map(String::as_str), Some("xhttp"));
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
}

fn add_tls(outbound: &mut serde_json::Value, params: &HashMap<String,String>) {
    let enabled=params.get("security").map(|v| v=="tls" || v=="reality" || v.is_empty()).unwrap_or(true);
    if enabled {
        outbound["tls"]=serde_json::json!({"enabled":true,"server_name":params.get("sni").or_else(||params.get("host")).cloned()});
    }
}
fn base64_decode(input:&str)->Result<Vec<u8>,String>{
    let mut s=input.trim().replace("-","+").replace("_","/");
    while s.len()%4!=0{s.push('=');}
    let table=b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out=Vec::new(); let bytes=s.as_bytes(); let mut val=0u32; let mut bits=0;
    for &b in bytes { if b==b'=' {break;} let idx=table.iter().position(|&x|x==b).ok_or("Некорректный Base64")? as u32; val=(val<<6)|idx; bits+=6; if bits>=8 {bits-=8; out.push(((val>>bits)&255) as u8);} }
    Ok(out)
}

#[no_mangle]
pub extern "system" fn Java_com_neotun_app_NeoTunCore_nativeShareConfig(mut env: JNIEnv,_class: JClass,uri:JString)->jstring{
 let value=env.get_string(&uri).ok().and_then(|s|Profile::from_share_uri(s.to_str().ok()?).ok()).and_then(|p|p.to_generic_sing_box_json().ok()).unwrap_or_default();
 env.new_string(value).map(JString::into_raw).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_neotun_app_NeoTunCore_nativeShareEngine(mut env: JNIEnv,_class:JClass,uri:JString)->jstring{
 let value=env.get_string(&uri).ok().and_then(|s|Profile::from_share_uri(s.to_str().ok()?).ok()).map(|p| if p.protocol=="vless" && p.engine()=="xray" {"xray"} else {"sing-box"}).unwrap_or("unknown");
 env.new_string(value).map(JString::into_raw).unwrap_or(std::ptr::null_mut())
}

