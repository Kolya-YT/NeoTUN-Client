
use jni::sys::{jboolean, jint};
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::net::ToSocketAddrs;

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

        // Normalize common REALITY parameter aliases used by subscription
        // exporters. The sing-box adapter consumes canonical VLESS URI keys.
        for (canonical, aliases) in [
            ("sni", &["serverName", "server_name"][..]),
            ("fp", &["fingerprint", "utlsFingerprint"][..]),
            ("pbk", &["publicKey", "public_key"][..]),
            ("sid", &["shortId", "short_id"][..]),
            ("spx", &["spiderX", "spider_x"][..]),
        ] {
            if params.get(canonical).is_none_or(|value| value.trim().is_empty()) {
                if let Some(value) = aliases.iter()
                    .find_map(|alias| params.get(*alias))
                    .filter(|value| !value.trim().is_empty())
                    .cloned()
                {
                    params.insert(canonical.to_string(), value);
                }
            }
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

    fn from_authority_uri<'a>(uri: &'a str, scheme: &str) -> Result<(&'a str, &'a str, &'a str), String> {
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
        let (user, hostport_raw, query) = Self::from_authority_uri(uri, scheme)?;
        let hostport = hostport_raw.trim_end_matches('/');

        // Hysteria 2 supports both a normal port and port-hopping ranges
        // such as 443,5000-6000. sing-box exposes the latter as server_ports.
        let (address, port, server_ports) = Self::split_hysteria_hostport(hostport)?;
        let mut params=HashMap::new();

        for pair in query.split('&').filter(|x| !x.is_empty()) {
            let (k,v)=pair.split_once('=').unwrap_or((pair,""));
            params.insert(percent_decode(k)?, percent_decode(v)?);
        }

        // Common exporters use these aliases. Normalize them once here so
        // the engine adapter stays simple and deterministic.
        if !params.contains_key("server_ports") {
            if let Some(ports) = params.get("mport").or_else(|| params.get("ports")).cloned() {
                params.insert("server_ports".into(), ports);
            } else if let Some(ports) = server_ports {
                params.insert("server_ports".into(), ports);
            }
        }
        if !params.contains_key("hop_interval") {
            if let Some(value) = params.get("mportHopInt").or_else(|| params.get("portHopInt")).cloned() {
                if let Ok(seconds) = value.parse::<u64>() {
                    params.insert("hop_interval".into(), format!("{}s", seconds));
                }
            }
        }
        if let Some(value) = params.get("up").cloned() {
            if !params.contains_key("up_mbps") {
                params.insert("up_mbps".into(), value);
            }
        }
        if let Some(value) = params.get("down").cloned() {
            if !params.contains_key("down_mbps") {
                params.insert("down_mbps".into(), value);
            }
        }

        Ok(Self {
            protocol:"hysteria2".into(),
            address,
            port,
            name: uri.split_once('#').and_then(|(_,x)| percent_decode(x).ok()),
            uuid:None,
            password:Some(percent_decode(user)?),
            params
        })
    }

    fn split_hysteria_hostport(hostport: &str) -> Result<(String, u16, Option<String>), String> {
        if let Some(stripped) = hostport.strip_prefix('[') {
            let (host, rest) = stripped.split_once(']').ok_or("Некорректный IPv6")?;
            let port_part = rest.strip_prefix(':').unwrap_or("");
            if port_part.is_empty() {
                return Ok((host.to_string(), 443, None));
            }
            let first_port = port_part
                .split(',')
                .next()
                .and_then(|v| v.split('-').next())
                .ok_or("Порт отсутствует")?
                .parse::<u16>()
                .map_err(|_| "Некорректный порт")?;
            return Ok((host.to_string(), first_port, Some(port_part.to_string())));
        }

        let (host, port_part) = hostport.rsplit_once(':').unwrap_or((hostport, ""));
        if port_part.is_empty() {
            return Ok((percent_decode(host)?, 443, None));
        }

        let first_port = port_part
            .split(',')
            .next()
            .and_then(|v| v.split('-').next())
            .ok_or("Порт отсутствует")?
            .parse::<u16>()
            .map_err(|_| "Некорректный порт")?;

        let ports = if port_part.contains(',') || port_part.contains('-') {
            Some(port_part.to_string())
        } else {
            None
        };
        Ok((percent_decode(host)?, first_port, ports))
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
        for (key,target) in [("net","network"),("host","host"),("path","path"),("tls","security"),("sni","sni"),("scy","encryption"),("type","network"),("headerType","headerType"),("header_type","header_type"),("serviceName","service_name")] {
            if let Some(v)=value.get(key).and_then(|v|v.as_str()) { params.insert(target.into(),v.into()); }
        }
        Ok(Self { protocol:"vmess".into(), address:address.into(), port, name:value.get("ps").and_then(|v|v.as_str()).map(str::to_string), uuid, password:None, params })
    }

    pub fn to_generic_sing_box_json(&self) -> Result<String, String> {
        // Run VLESS validation even when the profile is routed to Xray. This
        // keeps missing REALITY SNI/public-key errors actionable for every engine.
        if self.protocol == "vless" {
            let _ = self.to_sing_box_json()?;
            if self.engine() == "xray" {
                return Err("VLESS profile uses the Xray runtime and cannot be converted to a sing-box outbound".into());
            }
        }
        let mut outbound = match self.protocol.as_str() {
            "vless" => serde_json::from_str::<serde_json::Value>(&self.to_sing_box_json()?).unwrap()["outbounds"][0].clone(),
            "trojan" => {
                let mut o=serde_json::json!({"type":"trojan","tag":"proxy","server":self.address,"server_port":self.port,"password":self.password.clone().unwrap_or_default()});
                add_tls(&mut o,&self.params);
                o
            },
            "hysteria2" => {
                let ports = self.params.get("server_ports")
                    .filter(|v| !v.trim().is_empty())
                    .map(|ports| ports.split(',')
                        .map(str::trim)
                        .filter(|v| !v.is_empty())
                        .map(|v| {
                            // sing-box represents a port range as "start:end",
                            // not the hyphen notation used by some share links.
                            if let Some((start, end)) = v.split_once('-') {
                                format!("{}:{}", start.trim(), end.trim())
                            } else {
                                v.to_string()
                            }
                        })
                        .collect::<Vec<_>>());

                let mut o=serde_json::json!({
                    "type":"hysteria2",
                    "tag":"proxy",
                    "server":self.address,
                    "password":self.password.clone().unwrap_or_default(),
                    "tls":{"enabled":true}
                });
                if let Some(ports) = ports {
                    // sing-box rejects server_port when server_ports is set.
                    o["server_ports"] = serde_json::json!(ports);
                } else {
                    o["server_port"] = serde_json::json!(self.port);
                }

                if let Some(interval)=self.params.get("hop_interval").filter(|v| !v.is_empty()) {
                    o["hop_interval"]=serde_json::json!(interval);
                }

                if let Some(sni)=self.params.get("sni")
                    .or_else(||self.params.get("peer"))
                    .filter(|v| !v.trim().is_empty()) {
                    o["tls"]["server_name"]=serde_json::json!(sni);
                }

                // Some exporters provide the sing-box-compatible public-key pin
                // as pcs/pinnedPeerCertSha256. This is different from the
                // Hysteria2 pinSHA256 (which is a full certificate fingerprint).
                if let Some(pcs)=self.params.get("pcs")
                    .or_else(||self.params.get("pinnedPeerCertSha256"))
                    .filter(|v| !v.trim().is_empty()) {
                    o["tls"]["certificate_public_key_sha256"] =
                        serde_json::json!([pcs]);
                }

                if self.params.get("insecure")
                    .map(|v| v=="1" || v.eq_ignore_ascii_case("true"))
                    .unwrap_or(false) {
                    o["tls"]["insecure"]=serde_json::json!(true);
                }

                if let Some(alpn)=self.params.get("alpn").filter(|v| !v.trim().is_empty()) {
                    let values: Vec<String> = alpn.split(',')
                        .map(str::trim)
                        .filter(|v| !v.is_empty())
                        .map(str::to_string)
                        .collect();
                    if !values.is_empty() {
                        o["tls"]["alpn"]=serde_json::json!(values);
                    }
                } else {
                    o["tls"]["alpn"]=serde_json::json!(["h3"]);
                }

                // Hysteria 2 exporters may expose a TLS fingerprint as "fp".
                // Keep it compatible with sing-box's uTLS adapter when present.
                if let Some(fp)=self.params.get("fp").filter(|v| !v.trim().is_empty()) {
                    o["tls"]["utls"]=serde_json::json!({"enabled":true,"fingerprint":fp});
                }

                if let Some(obfs)=self.params.get("obfs").map(|v|v.to_ascii_lowercase()) {
                    if obfs=="salamander" || obfs=="gecko" {
                        if let Some(password)=self.params.get("obfs-password")
                            .or_else(||self.params.get("obfs_password"))
                            .filter(|v| !v.is_empty()) {
                            let mut obfs_value=serde_json::json!({"type":obfs,"password":password});
                            if obfs=="gecko" {
                                obfs_value["min_packet_size"]=serde_json::json!(512);
                                obfs_value["max_packet_size"]=serde_json::json!(1200);
                            }
                            o["obfs"]=obfs_value;
                        }
                    }
                }

                let up = self.params.get("up_mbps").and_then(|v|v.parse::<u64>().ok());
                let down = self.params.get("down_mbps").and_then(|v|v.parse::<u64>().ok());
                if let Some(up)=up { o["up_mbps"]=serde_json::json!(up); }
                if let Some(down)=down { o["down_mbps"]=serde_json::json!(down); }

                o
            },
            "tuic" => {
                let mut o = serde_json::json!({
                    "type":"tuic",
                    "tag":"proxy",
                    "server":self.address,
                    "server_port":self.port,
                    "uuid":self.uuid.clone().unwrap_or_default(),
                    "password":self.password.clone().unwrap_or_default(),
                    "congestion_control":self.params.get("congestion_control").cloned().unwrap_or_else(||"cubic".into()),
                    "udp_relay_mode":self.params.get("udp_relay_mode").cloned().unwrap_or_else(||"native".into()),
                    "tls":{"enabled":true}
                });
                if let Some(sni)=self.params.get("sni").cloned() { o["tls"]["server_name"]=serde_json::json!(sni); }
                if self.params.get("allow_insecure").map(|v| v=="1" || v=="true").unwrap_or(false) {
                    o["tls"]["insecure"]=serde_json::json!(true);
                }
                if let Some(alpn)=self.params.get("alpn") {
                    o["tls"]["alpn"]=serde_json::json!(alpn.split(',').map(str::trim).filter(|v|!v.is_empty()).collect::<Vec<_>>());
                }
                o
            },
            "shadowsocks" => serde_json::json!({"type":"shadowsocks","tag":"proxy","server":self.address,"server_port":self.port,"method":self.params.get("method").cloned().unwrap_or_default(),"password":self.params.get("password").cloned().unwrap_or_default()}),
            "vmess" => {
                let mut o=serde_json::json!({
                    "type":"vmess",
                    "tag":"proxy",
                    "server":self.address,
                    "server_port":self.port,
                    "uuid":self.uuid.clone().unwrap_or_default(),
                    "security":self.params.get("encryption").cloned().unwrap_or_else(||"auto".into())
                });
                if self.params.get("security").map(|v|v=="tls").unwrap_or(false) {
                    o["tls"]=serde_json::json!({
                        "enabled":true,
                        "server_name":self.params.get("sni").or_else(||self.params.get("host")).cloned()
                    });
                }
                if let Some(network)=self.params.get("network") {
                    match network.to_ascii_lowercase().as_str() {
                        "ws" => {
                            o["transport"]=serde_json::json!({
                                "type":"ws",
                                "path":self.params.get("path").cloned().unwrap_or_else(||"/".into()),
                                "headers":self.params.get("host").map(|h|serde_json::json!({"Host":h})).unwrap_or_else(||serde_json::json!({}))
                            });
                        },
                        "grpc" => {
                            o["transport"]=serde_json::json!({
                                "type":"grpc",
                                "service_name":self.params.get("service_name").cloned().unwrap_or_default()
                            });
                        },
                        "http" => {
                            o["transport"]=serde_json::json!({
                                "type":"http",
                                "path":self.params.get("path").cloned().unwrap_or_else(||"/".into()),
                                "host":self.params.get("host").map(|h|vec![h.clone()]).unwrap_or_default()
                            });
                        },
                        "httpupgrade" => {
                            o["transport"]=serde_json::json!({
                                "type":"httpupgrade",
                                "path":self.params.get("path").cloned().unwrap_or_else(||"/".into()),
                                "host":self.params.get("host").cloned().unwrap_or_default()
                            });
                        },
                        _ => {}
                    }
                }
                o
            },
            _ => return Err("Протокол не поддерживается".into())
        };


        let config=serde_json::json!({"log":{"level":"info"},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true,"dns_mode":"hijack","dns_address":["172.19.0.2"]}],"outbounds":[outbound,{"type":"direct","tag":"direct"},{"type":"block","tag":"block"}],"dns":{"servers":[{"type":"local","tag":"system"}],"final":"system","strategy":"prefer_ipv4"},"route":{"rules":[{"protocol":"dns","action":"hijack-dns"}],"auto_detect_interface":true,"final":"proxy"}});
        serde_json::to_string_pretty(&config).map_err(|e|e.to_string())
    }

    pub fn to_xray_json(&self) -> Result<String, String> {
        if self.protocol != "vless" { return Err("Xray adapter currently supports VLESS profiles only".into()); }
        let uuid = self.uuid.as_deref().ok_or("Для VLESS требуется UUID")?;
        let requested_network = self.params.get("type").map(|v| v.to_ascii_lowercase()).unwrap_or_else(|| "tcp".into());
        // Xray calls the RAW transport "tcp" in JSON; share links may spell it "raw".
        let network = match requested_network.as_str() { "raw" => "tcp".to_string(), "splithttp" => "xhttp".to_string(), other => other.to_string() };
        let security = self.params.get("security").map(|v| v.to_ascii_lowercase()).unwrap_or_else(|| "none".into());
        let server_name = self.params.get("sni").or_else(|| self.params.get("host")).cloned().unwrap_or_else(|| self.address.clone());
        if security == "reality" && matches!(network.as_str(), "mkcp" | "ws" | "websocket" | "httpupgrade") {
            return Err(format!("VLESS REALITY несовместим с транспортом {network}; выберите RAW/TCP, gRPC или XHTTP."));
        }
        if security == "reality" {
            if self.params.get("sni").or_else(|| self.params.get("host"))
                .is_none_or(|value| value.trim().is_empty()) {
                return Err("VLESS REALITY: отсутствует SNI (параметр sni). Проверь ссылку или подписку.".into());
            }
            if self.params.get("pbk").is_none_or(|value| value.trim().is_empty()) {
                return Err("VLESS REALITY: отсутствует публичный ключ (pbk/publicKey). Проверь ссылку или подписку.".into());
            }
        }
        let mut stream = serde_json::json!({ "network": network, "security": security });
        if security == "tls" {
            stream["tlsSettings"] = serde_json::json!({ "serverName": server_name });
            if let Some(alpn) = self.params.get("alpn") { stream["tlsSettings"]["alpn"] = serde_json::json!(alpn.split(',').map(str::trim).filter(|s| !s.is_empty()).collect::<Vec<_>>()); }
            if self.params.get("allowInsecure").map(|v| v == "1" || v.eq_ignore_ascii_case("true")).unwrap_or(false) { stream["tlsSettings"]["allowInsecure"] = serde_json::json!(true); }
        } else if security == "reality" {
            let mut reality = serde_json::json!({ "serverName": server_name, "publicKey": self.params.get("pbk").cloned().unwrap_or_default(), "shortId": self.params.get("sid").cloned().unwrap_or_default(), "fingerprint": self.params.get("fp").cloned().unwrap_or_else(|| "chrome".into()) });
            if let Some(spider_x) = self.params.get("spx").or_else(|| self.params.get("spiderX")).filter(|v| !v.trim().is_empty()) { reality["spiderX"] = serde_json::json!(spider_x); }
            stream["realitySettings"] = reality;
        }



        let path = self.params.get("path").cloned().unwrap_or_else(|| "/".into());
        let host = self.params.get("host").filter(|v| !v.trim().is_empty()).cloned();
        match network.as_str() {
            "tcp" | "raw" => {
                if self.params.get("headerType").map(|v| v.eq_ignore_ascii_case("http")).unwrap_or(false) {
                    let mut headers = serde_json::json!({});
                    if let Some(host) = host { headers["Host"] = serde_json::json!([host]); }
                    headers["Path"] = serde_json::json!([path]);
                    stream["tcpSettings"] = serde_json::json!({ "header": { "type": "http", "request": { "headers": headers } } });
                }
            }
            "ws" | "websocket" => {
                let mut ws = serde_json::json!({ "path": path });
                if let Some(host) = host { ws["headers"] = serde_json::json!({ "Host": host }); }
                stream["network"] = serde_json::json!("ws");
                stream["wsSettings"] = ws;
            }
            "mkcp" => {
                // Current Xray mKCP supports the transport tuning fields below.
                // Legacy KCP header/seed options were removed upstream, so do not emit them.
                stream["network"] = serde_json::json!("kcp");
                let mut settings = serde_json::json!({});
                for (source, target) in [
                    ("mtu", "mtu"),
                    ("tti", "tti"),
                    ("uplinkCapacity", "uplinkCapacity"),
                    ("downlinkCapacity", "downlinkCapacity"),
                    ("cwndMultiplier", "cwndMultiplier"),
                    ("maxSendingWindow", "maxSendingWindow"),
                ] {
                    let value = self.params.get(source).or_else(|| match source {
                        "uplinkCapacity" => self.params.get("upCap"),
                        "downlinkCapacity" => self.params.get("downCap"),
                        "cwndMultiplier" => self.params.get("cwnd_multiplier"),
                        "maxSendingWindow" => self.params.get("max_sending_window"),
                        _ => None,
                    });
                    if let Some(value) = value.and_then(|v| v.parse::<u32>().ok()) {
                        settings[target] = serde_json::json!(value);
                    }
                }
                stream["kcpSettings"] = settings;
            }
            "xdrive" => {
                // XDrive is an upstream Xray transport, but it needs matching storage
                // service settings on both ends; never silently downgrade it to TCP.
                let service = self.params.get("service").cloned().unwrap_or_default();
                if service.trim().is_empty() {
                    return Err("VLESS XDRIVE: отсутствует параметр service (local, Google Drive или template) в ссылке.".into());
                }
                let mut settings = serde_json::json!({ "service": service });
                if let Some(folder) = self.params.get("remoteFolder").or_else(|| self.params.get("remote_folder")).filter(|v| !v.trim().is_empty()) {
                    settings["remoteFolder"] = serde_json::json!(folder);
                }
                let secrets = self.params.get("secrets").map(|v| {
                    v.split(',').map(str::trim).filter(|part| !part.is_empty()).map(str::to_owned).collect::<Vec<_>>()
                }).filter(|v| !v.is_empty()).or_else(|| {
                    let values = ["secret1", "secret2", "secret3"].iter()
                        .filter_map(|key| self.params.get(*key).cloned())
                        .collect::<Vec<_>>();
                    if values.is_empty() { None } else { Some(values) }
                });
                if let Some(secrets) = secrets { settings["secrets"] = serde_json::json!(secrets); }
                for (source, target) in [
                    ("segmentBytes", "segmentBytes"),
                    ("flushIntervalMs", "flushIntervalMs"),
                    ("pollIntervalMs", "pollIntervalMs"),
                    ("maxPollIntervalMs", "maxPollIntervalMs"),
                    ("sessionTtlSeconds", "sessionTtlSeconds"),
                    ("concurrency", "concurrency"),
                    ("eagerWindowMs", "eagerWindowMs"),
                    ("holeTimeoutMs", "holeTimeoutMs"),
                ] {
                    let snake = match source {
                        "segmentBytes" => "segment_bytes",
                        "flushIntervalMs" => "flush_interval_ms",
                        "pollIntervalMs" => "poll_interval_ms",
                        "maxPollIntervalMs" => "max_poll_interval_ms",
                        "sessionTtlSeconds" => "session_ttl_seconds",
                        "eagerWindowMs" => "eager_window_ms",
                        "holeTimeoutMs" => "hole_timeout_ms",
                        other => other,
                    };
                    if let Some(value) = self.params.get(source).or_else(|| self.params.get(snake)).and_then(|v| v.parse::<u32>().ok()) {
                        settings[target] = serde_json::json!(value);
                    }
                }
                if let Some(template) = self.params.get("template").filter(|v| !v.trim().is_empty()) {
                    // Xray's XDrive Config declares template as a JSON string, not an object.
                    let parsed = serde_json::from_str::<serde_json::Value>(template)
                        .map_err(|_| "VLESS XDRIVE: параметр template должен содержать корректный JSON.".to_string())?;
                    settings["template"] = serde_json::json!(parsed.to_string());
                }
                match service.as_str() {
                    "Google Drive" if settings["secrets"].as_array().is_none_or(|v| v.len() != 3) =>
                        return Err("VLESS XDRIVE: для Google Drive нужны 3 secrets: ClientID, ClientSecret и RefreshToken.".into()),
                    "template" if settings.get("template").is_none() =>
                        return Err("VLESS XDRIVE: для service=template требуется параметр template с JSON.".into()),
                    "local" if settings["remoteFolder"].as_str().is_none_or(|v| v.trim().is_empty()) =>
                        return Err("VLESS XDRIVE: для service=local требуется remoteFolder с общим путём к каталогу.".into()),
                    "template" if settings["remoteFolder"].as_str().is_none_or(|v| v.trim().is_empty()) =>
                        return Err("VLESS XDRIVE: для service=template требуется remoteFolder.".into()),
                    "local" | "Google Drive" | "Yandex Disk" | "template" => {},
                    _ => return Err("VLESS XDRIVE: service должен быть local, Google Drive, Yandex Disk или template.".into()),
                }
                stream["network"] = serde_json::json!("xdrive");
                stream["xdriveSettings"] = settings;
            }
            "grpc" => {
                stream["grpcSettings"] = serde_json::json!({
                    "serviceName": self.params.get("serviceName").or_else(|| self.params.get("service_name")).cloned().unwrap_or_else(|| path.trim_start_matches('/').to_string()),
                    "multiMode": self.params.get("mode").map(|v| v.eq_ignore_ascii_case("multi")).unwrap_or(false)
                });
            }
            "httpupgrade" => {
                let mut settings = serde_json::json!({ "path": path });
                if let Some(host) = host { settings["host"] = serde_json::json!(host); }
                stream["httpupgradeSettings"] = settings;
            }
            "xhttp" | "splithttp" => {
                stream["network"] = serde_json::json!("xhttp");
                let mut settings = serde_json::json!({ "path": path });
                if let Some(host) = host { settings["host"] = serde_json::json!(host); }
                if let Some(mode) = self.params.get("mode").filter(|v| !v.trim().is_empty()) { settings["mode"] = serde_json::json!(mode); }
                // Preserve XHTTP padding from common share-link spellings.
                let padding = self.params.get("x_padding_bytes").or_else(|| self.params.get("xPaddingBytes"))
                    .cloned()
                    .or_else(|| self.params.get("extra").and_then(|raw| serde_json::from_str::<serde_json::Value>(raw).ok()).and_then(|v| v.get("xPaddingBytes").and_then(|v| v.as_str()).map(str::to_owned)));
                if let Some(padding) = padding.filter(|v| !v.trim().is_empty()) { settings["xPaddingBytes"] = serde_json::json!(padding); }
                stream["xhttpSettings"] = settings;
            }
            "http" => {
                let mut settings = serde_json::json!({ "path": path });
                if let Some(host) = host { settings["host"] = serde_json::json!(host); }
                stream["network"] = serde_json::json!("http");
                stream["httpSettings"] = settings;
            }
            other => return Err(format!("Неподдерживаемый транспорт VLESS для Xray: {other}")),
        }
        let mut outbounds = vec![
            serde_json::json!({ "tag": "proxy", "protocol": "vless", "settings": { "vnext": [{ "address": self.address, "port": self.port, "users": [{ "id": uuid, "encryption": "none", "flow": self.params.get("flow").cloned().unwrap_or_default() }] }] }, "streamSettings": stream }),
        ];

        outbounds.push(serde_json::json!({ "tag": "direct", "protocol": "freedom" }));
        Ok(serde_json::json!({
            "log": { "loglevel": "warning" },
            "inbounds": [{ "tag": "socks-in", "listen": "127.0.0.1", "port": 10808, "protocol": "socks", "settings": { "auth": "noauth", "udp": true } }],
            "outbounds": outbounds
        }).to_string())
    }

    pub fn to_windows_runtime_json(&self) -> Result<String, String> {
        if self.engine() == "xray" {
            let xray = serde_json::from_str::<serde_json::Value>(&self.to_xray_json()?).map_err(|e| e.to_string())?;
            // Resolve the remote endpoint before the TUN interface starts. Otherwise,
            // Xray's own connection to the proxy server can be captured by sing-box's
            // default route and loop back into Xray through the local SOCKS inbound.
            let server_cidrs = resolve_server_cidrs(&self.address, self.port)?;
            // Keep the direct endpoint exception ahead of DNS hijacking/default proxy routing.
            let route_rules = vec![
                serde_json::json!({ "ip_cidr": server_cidrs, "outbound": "direct" }),
                serde_json::json!({ "protocol": "dns", "action": "hijack-dns" })
            ];
            let sing_box = serde_json::json!({
                "log": { "level": "info" },
                "inbounds": [{ "type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"], "auto_route": true, "strict_route": true }],
                "outbounds": [{ "type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": 10808, "version": "5" }, { "type": "direct", "tag": "direct" }, { "type": "block", "tag": "block" }],
                "dns": {
                    "servers": [{ "type": "udp", "tag": "bootstrap", "server": "1.1.1.1", "server_port": 53 }],
                    "final": "bootstrap",
                    "strategy": "prefer_ipv4"
                },
                "route": { "auto_detect_interface": true, "final": "proxy", "rules": route_rules }
            });
            return Ok(serde_json::json!({ "engine": "xray", "sing_box_config": sing_box, "xray_config": xray }).to_string());
        }
        let mut sing_box = serde_json::from_str::<serde_json::Value>(&self.to_generic_sing_box_json()?).map_err(|e| e.to_string())?;

        // Windows needs strict route handling to prevent DNS and other packets from
        // escaping the TUN route. The generic config also contains legacy TUN DNS
        // fields; Windows uses sing-box's DNS hijack rule instead, so remove those
        // legacy fields only from the Windows runtime config (Android stays unchanged).
        if let Some(inbounds) = sing_box.get_mut("inbounds").and_then(|value| value.as_array_mut()) {
            for inbound in inbounds {
                if inbound.get("type").and_then(|value| value.as_str()) == Some("tun") {
                    let object = inbound.as_object_mut().expect("TUN inbound must be an object");
                    object.insert("strict_route".into(), serde_json::Value::Bool(true));
                    object.remove("dns_mode");
                    object.remove("dns_address");
                }
            }
        }

        Ok(serde_json::json!({ "engine": "sing-box", "sing_box_config": sing_box }).to_string())
    }

    pub fn to_sing_box_json(&self) -> Result<String, String> {
        if self.protocol != "vless" {
            return Err("Пока реализована генерация sing-box только для VLESS".into());
        }

        let uuid = self.uuid.as_deref().ok_or("Для VLESS требуется UUID")?;
        let security = self.params.get("security")
            .map(|value| value.to_ascii_lowercase())
            .unwrap_or_else(|| "none".into());
        let transport = self.params.get("type").map(|v| v.to_ascii_lowercase()).unwrap_or_else(|| "tcp".into());
        let engine = self.engine();

        // REALITY requires the server's public key and the camouflage SNI.
        // Do not silently emit an unusable outbound when a subscription omitted
        // these values; report a safe, actionable error instead.
        if security == "reality" {
            let server_name = self.params.get("sni")
                .or_else(|| self.params.get("host"))
                .filter(|value| !value.trim().is_empty());
            if server_name.is_none() {
                return Err("VLESS REALITY: отсутствует SNI (параметр sni). Проверь ссылку или подписку.".into());
            }
            if self.params.get("pbk").is_none_or(|value| value.trim().is_empty()) {
                return Err("VLESS REALITY: отсутствует публичный ключ (pbk/publicKey). Проверь ссылку или подписку.".into());
            }
        }

        if engine == "xray" {
            return Ok(serde_json::json!({"engine": "xray", "protocol": "vless", "transport": transport, "params": self.params}).to_string());
        }

        let mut vless = serde_json::json!({
            "type": "vless",
            "tag": "proxy",
            "server": self.address,
            "server_port": self.port,
            "uuid": uuid
        });

        // VLESS Vision profiles require their flow to reach the selected engine.
        // Silently dropping this field makes otherwise valid REALITY profiles fail.
        if let Some(flow) = self.params.get("flow").filter(|value| !value.trim().is_empty()) {
            vless["flow"] = serde_json::json!(flow);
        }

        if security == "tls" {
            let mut tls = serde_json::json!({
                "enabled": true,
                "server_name": self.params.get("sni").or_else(|| self.params.get("host")).cloned()
            });
            if let Some(alpn) = self.params.get("alpn").filter(|value| !value.trim().is_empty()) {
                tls["alpn"] = serde_json::json!(alpn.split(',').map(str::trim).filter(|v| !v.is_empty()).collect::<Vec<_>>());
            }
            if self.params.get("allowInsecure").or_else(|| self.params.get("insecure"))
                .is_some_and(|value| matches!(value.as_str(), "1" | "true" | "yes")) {
                tls["insecure"] = serde_json::json!(true);
            }
            vless["tls"] = tls;
        } else if security == "reality" {
            // sing-box requires uTLS to be explicitly enabled for Reality outbounds.
            // The fingerprint comes from the share URI (?fp=); Chrome is the
            // interoperable default when providers omit it.
            let fingerprint = self.params.get("fp")
                .filter(|value| !value.trim().is_empty())
                .cloned()
                .unwrap_or_else(|| "chrome".to_string());
            vless["tls"] = serde_json::json!({
                "enabled": true,
                "server_name": self.params.get("sni").or_else(|| self.params.get("host")).cloned(),
                "utls": {
                    "enabled": true,
                    "fingerprint": fingerprint
                },
                "reality": {
                    "enabled": true,
                    "public_key": self.params.get("pbk").cloned().unwrap_or_default(),
                    "short_id": self.params.get("sid").cloned().unwrap_or_default()
                }
            });
        }

        match transport.as_str() {
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
            "http" => {
                vless["transport"] = serde_json::json!({
                    "type": "http",
                    "path": self.params.get("path").cloned().unwrap_or_else(|| "/".into()),
                    "host": self.params.get("host").map(|h| vec![h.clone()]).unwrap_or_default()
                });
            }
            "httpupgrade" => {
                vless["transport"] = serde_json::json!({
                    "type": "httpupgrade",
                    "path": self.params.get("path").cloned().unwrap_or_else(|| "/".into()),
                    "host": self.params.get("host").cloned().unwrap_or_default()
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
                "rules": [{ "protocol": "dns", "action": "hijack-dns" }],
                "auto_detect_interface": true,
                "final": "proxy"
            }
        });

        serde_json::to_string_pretty(&config).map_err(|e| e.to_string())
    }
}


/// Resolve every address for the proxy endpoint before the Windows TUN route is installed.
/// This prevents Xray's upstream socket from being routed back into its own local SOCKS chain.
fn resolve_server_cidrs(address: &str, port: u16) -> Result<Vec<String>, String> {
    let resolved = (address, port).to_socket_addrs()
        .map_err(|e| format!("Не удалось разрешить адрес сервера {} до запуска Windows TUN: {}", address, e))?;
    let mut cidrs = Vec::new();
    for socket in resolved {
        let ip = socket.ip();
        let cidr = format!("{}/{}", ip, if ip.is_ipv4() { 32 } else { 128 });
        if !cidrs.contains(&cidr) { cidrs.push(cidr); }
    }
    if cidrs.is_empty() {
        return Err(format!("DNS не вернул IP-адрес для сервера {}", address));
    }
    Ok(cidrs)
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
    let value = "NeoTUN Core 0.2.4 • Rust";
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

impl Profile {
    pub fn engine(&self) -> &'static str {
        let transport = self.params.get("type")
            .map(|value| value.to_ascii_lowercase())
            .unwrap_or_default();
        let security = self.params.get("security")
            .map(|value| value.to_ascii_lowercase())
            .unwrap_or_default();

        // Newer Xray REALITY servers require the X25519MLKEM768 key share.
        // sing-box's current Firefox uTLS fingerprint does not provide it,
        // producing "reality verification failed" even with correct URI keys.
        // Route VLESS REALITY through Xray, which supports this handshake.
        if self.protocol == "vless" &&
            (matches!(transport.as_str(), "xhttp" | "splithttp" | "mkcp" | "xdrive") || security == "reality") {
            "xray"
        } else {
            "sing-box"
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
        CoreInfo { name: "WireGuard", version: "next", protocol: "wireguard" },
        CoreInfo { name: "AmneziaWG", version: "next", protocol: "amneziawg" },
    ]
}

#[no_mangle]
pub unsafe extern "C" fn neotun_windows_runtime_json(uri: *const c_char) -> *mut c_char {
    if uri.is_null() { return std::ptr::null_mut(); }
    let result = std::panic::catch_unwind(|| {
        let value = CStr::from_ptr(uri).to_str().map_err(|e| e.to_string())?;
        let profile = Profile::from_share_uri(value)?;
        profile.to_windows_runtime_json()
    });
    let value = match result {
        Ok(Ok(json)) => json,
        Ok(Err(error)) => serde_json::json!({ "error": error }).to_string(),
        Err(_) => serde_json::json!({ "error": "Native core failed while generating runtime configuration" }).to_string(),
    };
    CString::new(value).map(CString::into_raw).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub unsafe extern "C" fn neotun_free_string(value: *mut c_char) {
    if !value.is_null() { drop(CString::from_raw(value)); }
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
        assert_eq!(profile.engine(), "sing-box");
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        assert_eq!(runtime["engine"], "sing-box");
        assert!(runtime.get("xray_config").is_none());
        assert_eq!(runtime["sing_box_config"]["inbounds"][0]["type"], "tun");
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], "vless");
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["server"], "example.com");
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["server_port"], 443);
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["transport"]["type"], "ws");
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], "vless");
    }

    #[test]
    fn windows_native_vless_uses_sing_box_without_socks_bridge() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=tcp"
        ).unwrap();
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        assert_eq!(runtime["engine"], "sing-box");
        assert!(runtime.get("xray_config").is_none());
        let config = &runtime["sing_box_config"];
        assert_eq!(config["outbounds"][0]["type"], "vless");
        assert_eq!(config["outbounds"][0]["server"], "example.com");
        assert_eq!(config["outbounds"][0]["server_port"], 443);
        assert_eq!(config["route"]["final"], "proxy");
    }

    #[test]
    fn windows_sing_box_tun_uses_strict_route_without_legacy_dns_fields() {
        let profile = Profile::from_share_uri(
            "hysteria2://secret@example.com:443?sni=example.com"
        ).unwrap();
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        let tun = &runtime["sing_box_config"]["inbounds"][0];
        assert_eq!(tun["type"], "tun");
        assert_eq!(tun["strict_route"], true);
        assert!(tun.get("dns_mode").is_none());
        assert!(tun.get("dns_address").is_none());
        assert_eq!(
            runtime["sing_box_config"]["route"]["rules"][0]["action"],
            "hijack-dns"
        );
        assert_eq!(
            runtime["sing_box_config"]["route"]["rules"][0]["protocol"],
            "dns"
        );
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
    fn xhttp_reality_preserves_spider_x_and_padding_parameters() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:9443?security=reality&type=xhttp&fp=firefox&pbk=public-key&sid=a1baa1&sni=poliigon.com&spx=%2F7e6a5d54c0a16b4&path=%2F&mode=auto&x_padding_bytes=100-1000"
        ).unwrap();
        assert_eq!(profile.engine(), "xray");
        let runtime: serde_json::Value = serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        let stream = &runtime["xray_config"]["outbounds"][0]["streamSettings"];
        assert_eq!(stream["network"], "xhttp");
        assert_eq!(stream["realitySettings"]["serverName"], "poliigon.com");
        assert_eq!(stream["realitySettings"]["fingerprint"], "firefox");
        assert_eq!(stream["realitySettings"]["spiderX"], "/7e6a5d54c0a16b4");
        assert_eq!(stream["xhttpSettings"]["path"], "/");
        assert_eq!(stream["xhttpSettings"]["mode"], "auto");
        assert_eq!(stream["xhttpSettings"]["xPaddingBytes"], "100-1000");
    }

    #[test]
    fn vless_mkcp_uses_xray_and_preserves_supported_transport_settings() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=none&type=mkcp&mtu=1200&tti=40&uplinkCapacity=10&downlinkCapacity=30&cwndMultiplier=2&maxSendingWindow=256"
        ).unwrap();
        assert_eq!(profile.engine(), "xray");
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        let stream = &runtime["xray_config"]["outbounds"][0]["streamSettings"];
        assert_eq!(stream["network"], "kcp");
        assert_eq!(stream["kcpSettings"]["mtu"], 1200);
        assert_eq!(stream["kcpSettings"]["tti"], 40);
        assert_eq!(stream["kcpSettings"]["uplinkCapacity"], 10);
        assert_eq!(stream["kcpSettings"]["downlinkCapacity"], 30);
        assert_eq!(stream["kcpSettings"]["cwndMultiplier"], 2);
        assert_eq!(stream["kcpSettings"]["maxSendingWindow"], 256);
    }

    #[test]
    fn vless_xdrive_uses_xray_and_requires_backend_configuration() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=xdrive&service=local&remoteFolder=%2Fneotun&segmentBytes=65536&concurrency=4"
        ).unwrap();
        assert_eq!(profile.engine(), "xray");
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        let stream = &runtime["xray_config"]["outbounds"][0]["streamSettings"];
        assert_eq!(stream["network"], "xdrive");
        assert_eq!(stream["xdriveSettings"]["service"], "local");
        assert_eq!(stream["xdriveSettings"]["remoteFolder"], "/neotun");
        assert_eq!(stream["xdriveSettings"]["segmentBytes"], 65536);
        assert_eq!(stream["xdriveSettings"]["concurrency"], 4);

        let missing = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=xdrive"
        ).unwrap();
        assert!(missing.to_xray_json().unwrap_err().contains("отсутствует параметр service"));
    }

    #[test]
    fn raw_transport_is_normalized_to_xray_tcp_network() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=raw"
        ).unwrap();
        assert_eq!(profile.engine(), "sing-box");
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        assert!(runtime["sing_box_config"]["outbounds"][0].get("transport").is_none(), "RAW is native TCP and needs no sing-box transport block");
    }

    #[test]
    fn sing_box_vless_preserves_flow_alpn_and_tls_insecure_option() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=tcp&flow=xtls-rprx-vision&alpn=h2%2Chttp%2F1.1&allowInsecure=1"
        ).unwrap();
        let config: serde_json::Value = serde_json::from_str(&profile.to_sing_box_json().unwrap()).unwrap();
        let outbound = &config["outbounds"][0];
        assert_eq!(outbound["flow"], "xtls-rprx-vision");
        assert_eq!(outbound["tls"]["alpn"][0], "h2");
        assert_eq!(outbound["tls"]["alpn"][1], "http/1.1");
        assert_eq!(outbound["tls"]["insecure"], true);
    }

    #[test]
    fn supported_vless_transports_use_native_sing_box_and_xhttp_uses_xray() {
        for transport in ["tcp", "ws", "grpc", "httpupgrade", "http"] {
            let uri = format!("vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type={transport}&path=%2Fneo");
            let profile = Profile::from_share_uri(&uri).unwrap();
            assert_eq!(profile.engine(), "sing-box", "transport={transport}");
            let runtime: serde_json::Value =
                serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
            assert_eq!(runtime["engine"], "sing-box", "transport={transport}");
            assert!(runtime.get("xray_config").is_none());
            assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], "vless");
        }

        // Xray REALITY supports only RAW/TCP, gRPC and XHTTP transports.
        // Reject incompatible combinations instead of treating every VLESS
        // transport as a valid Xray profile.
        for transport in ["tcp", "grpc", "xhttp", "splithttp"] {
            let uri = format!("vless://123e4567-e89b-12d3-a456-426614174000@example.com:9443?security=reality&type={transport}&fp=firefox&pbk=public-key&sid=a1baa1&sni=example.com");
            let profile = Profile::from_share_uri(&uri).unwrap();
            assert_eq!(profile.engine(), "xray", "REALITY transport={transport}");
            let runtime: serde_json::Value =
                serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
            assert_eq!(runtime["engine"], "xray", "REALITY transport={transport}");
            assert_eq!(runtime["xray_config"]["outbounds"][0]["protocol"], "vless");
        }

        for transport in ["ws", "httpupgrade", "mkcp"] {
            let uri = format!("vless://123e4567-e89b-12d3-a456-426614174000@example.com:9443?security=reality&type={transport}&fp=firefox&pbk=public-key&sid=a1baa1&sni=example.com");
            let profile = Profile::from_share_uri(&uri).unwrap();
            assert!(profile.to_windows_runtime_json().is_err(),
                "REALITY transport={transport} must be rejected");
        }

        for transport in ["xhttp", "splithttp"] {
            let uri = format!("vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type={transport}&path=%2Fneo");
            let profile = Profile::from_share_uri(&uri).unwrap();
            assert_eq!(profile.engine(), "xray", "transport={transport}");
            let runtime: serde_json::Value =
                serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
            assert_eq!(runtime["engine"], "xray", "transport={transport}");
            assert_eq!(runtime["xray_config"]["outbounds"][0]["protocol"], "vless");
            assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], "socks");
        }
        let hysteria = Profile::from_share_uri("hysteria2://password@example.com:443").unwrap();
        assert_eq!(hysteria.engine(), "sing-box");
    }

    #[test]
    fn alternating_profiles_select_their_own_engine_without_stale_runtime_config() {
        let profiles = [
            // Match the real-world Android switching sequence: Hysteria2/sing-box,
            // VLESS REALITY/Xray, then back to Hysteria2/sing-box. Every profile
            // must rebuild runtime configuration from its URI, not reuse the
            // previous engine's outbound or bridge settings.
            (
                "hysteria2://password@example.com:443?sni=example.com",
                "sing-box",
                "hysteria2",
            ),
            (
                "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=reality&type=tcp&fp=chrome&pbk=public-key&sid=abcd&sni=www.example.org",
                "xray",
                "socks",
            ),
            (
                "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=ws&path=%2Fneo",
                "sing-box",
                "vless",
            ),
            (
                "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type=xhttp&path=%2Fneo",
                "xray",
                "socks",
            ),
            (
                "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=none&type=tcp",
                "sing-box",
                "vless",
            ),
        ];

        for (uri, expected_engine, expected_sing_box_outbound) in profiles {
            let profile = Profile::from_share_uri(uri).unwrap();
            assert_eq!(profile.engine(), expected_engine, "profile={}", profile.address);

            let runtime: serde_json::Value =
                serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
            assert_eq!(runtime["engine"], expected_engine, "profile={}", profile.address);

            if expected_engine == "xray" {
                assert!(runtime.get("xray_config").is_some(), "Xray config missing for {}", profile.address);
                // Windows still uses sing-box as the TUN/routing bridge, while Xray
                // owns the selected profile's VLESS protocol connection.
                assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], expected_sing_box_outbound);
            } else {
                assert!(runtime.get("xray_config").is_none(), "stale Xray config for {}", profile.address);
                assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], expected_sing_box_outbound);
            }
        }
    }

    #[test]
    fn windows_xray_bridge_sing_box_config_omits_empty_direct_detour_on_bootstrap_dns() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:9443?security=reality&type=xhttp&fp=firefox&pbk=public-key&sid=a1baa1&sni=example.com"
        ).unwrap();
        assert_eq!(profile.engine(), "xray");
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        let sing_box = &runtime["sing_box_config"];
        let dns_servers = sing_box["dns"]["servers"].as_array().expect("dns servers must be an array");
        let bootstrap = dns_servers.iter().find(|s| s["tag"] == "bootstrap").expect("bootstrap dns server must exist");
        assert_eq!(bootstrap["type"], "udp");
        assert_eq!(bootstrap["server"], "1.1.1.1");
        assert_eq!(bootstrap["server_port"], 53);
        assert!(bootstrap.get("detour").is_none(), "bootstrap DNS must not define detour to direct");
        assert_eq!(sing_box["dns"]["final"], "bootstrap");
        assert_eq!(sing_box["outbounds"][0]["type"], "socks");
        assert_eq!(sing_box["outbounds"][0]["server_port"], 10808);
    }

    #[test]
    fn parses_percent_encoded_whole_uri() {
        let encoded = "%76%6c%65%73%73%3a%2f%2f123e4567-e89b-12d3-a456-426614174000%40example.com%3a443%3fsecurity%3dtls%26type%3dxhttp%26path%3d%252Fneo";
        let profile = Profile::from_vless_uri(encoded).unwrap();
        assert_eq!(profile.engine(), "xray");
        assert_eq!(profile.params.get("type").map(String::as_str), Some("xhttp"));
    }

    #[test]
    fn hysteria2_generates_safe_udp_config() {
        let profile = Profile::from_share_uri(
            "hysteria2://secret@example.com:443?sni=example.com&insecure=1&alpn=h3&obfs=salamander&obfs-password=mask#HY2"
        ).unwrap();

        assert_eq!(profile.protocol, "hysteria2");
        let config = profile.to_generic_sing_box_json().unwrap().replace(' ', "").replace('\n', "");
        assert!(config.contains("\"type\":\"hysteria2\""));
        assert!(!config.contains("\"network\":\"udp\""));
        assert!(config.contains("\"action\":\"hijack-dns\""));
        assert!(config.contains("\"server_name\":\"example.com\""));
        assert!(config.contains("\"type\":\"salamander\""));
        assert!(config.contains("\"alpn\":[\"h3\"]"));

        let hopping = Profile::from_share_uri(
            "hy2://secret@example.com:443,5000-6000/?sni=example.com&mportHopInt=30&up=50&down=100"
        ).unwrap();
        let hopping_config = hopping.to_generic_sing_box_json().unwrap().replace(' ', "").replace('\n', "");
        assert!(hopping_config.contains("\"server_ports\":[\"443\",\"5000:6000\"]"));
        assert!(!hopping_config.contains("\"server_port\":443"));
        assert!(hopping_config.contains("\"hop_interval\":\"30s\""));
        assert!(hopping_config.contains("\"up_mbps\":50"));
        assert!(hopping_config.contains("\"down_mbps\":100"));

        let pinned = Profile::from_share_uri(
            "hy2://secret@example.com:443?sni=example.com&insecure=1&pcs=abc123"
        ).unwrap();
        let pinned_config = pinned.to_generic_sing_box_json().unwrap().replace(' ', "").replace('\n', "");
        assert!(pinned_config.contains("\"certificate_public_key_sha256\":[\"abc123\"]"));
    }

    #[test]
    fn sing_box_vless_reality_preserves_server_name_key_short_id_and_fingerprint() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=reality&type=tcp&flow=xtls-rprx-vision&sni=www.example.org&fp=firefox&pbk=public-key-value&sid=ab12cd34"
        ).unwrap();

        assert_eq!(profile.engine(), "xray");
        let config: serde_json::Value =
            serde_json::from_str(&profile.to_sing_box_json().unwrap()).unwrap();
        assert_eq!(config["engine"], "xray");
        assert_eq!(config["protocol"], "vless");
        assert_eq!(config["transport"], "tcp");
        assert_eq!(config["params"]["flow"], "xtls-rprx-vision");
        assert_eq!(config["params"]["sni"], "www.example.org");
        assert_eq!(config["params"]["fp"], "firefox");
        assert_eq!(config["params"]["pbk"], "public-key-value");
        assert_eq!(config["params"]["sid"], "ab12cd34");
    }

    #[test]
    fn normalizes_common_reality_parameter_aliases() {
        let profile = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=reality&type=tcp&serverName=www.example.org&fingerprint=firefox&publicKey=YWJjZA&shortId=ab12cd34&spiderX=%2F"
        ).unwrap();

        assert_eq!(profile.params.get("sni").map(String::as_str), Some("www.example.org"));
        assert_eq!(profile.params.get("fp").map(String::as_str), Some("firefox"));
        assert_eq!(profile.params.get("pbk").map(String::as_str), Some("YWJjZA"));
        assert_eq!(profile.params.get("sid").map(String::as_str), Some("ab12cd34"));
        assert_eq!(profile.params.get("spx").map(String::as_str), Some("/"));

        let config: serde_json::Value =
            serde_json::from_str(&profile.to_sing_box_json().unwrap()).unwrap();
        assert_eq!(config["engine"], "xray");
        assert_eq!(config["params"]["sni"], "www.example.org");
        assert_eq!(config["params"]["pbk"], "YWJjZA");
        assert_eq!(config["params"]["sid"], "ab12cd34");
        assert_eq!(config["params"]["fp"], "firefox");
        assert_eq!(config["params"]["spx"], "/");
    }

    #[test]
    fn reality_profiles_fail_clearly_when_required_parameters_are_missing() {
        let missing_sni = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=reality&type=tcp&pbk=YWJjZA"
        ).unwrap();
        assert!(missing_sni.to_generic_sing_box_json().unwrap_err().contains("отсутствует SNI"));

        let missing_key = Profile::from_share_uri(
            "vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=reality&type=tcp&sni=www.example.org"
        ).unwrap();
        assert!(missing_key.to_generic_sing_box_json().unwrap_err().contains("отсутствует публичный ключ"));
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



