use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;

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
        if self.protocol == "vless" && self.engine() == "xray" {
            return Err("VLESS XHTTP uses the Xray runtime and cannot be converted to a sing-box outbound".into());
        }
        let outbound = match self.protocol.as_str() {
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
        let config=serde_json::json!({"log":{"level":"info"},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true,"dns_mode":"hijack","dns_address":["172.19.0.2"]}],"outbounds":[outbound,{"type":"direct","tag":"direct"},{"type":"block","tag":"block"}],"dns":{"servers":[{"type":"local","tag":"system"}],"final":"system","strategy":"prefer_ipv4"},"route":{"rules":[{"action":"hijack-dns"}],"auto_detect_interface":true,"final":"proxy"}});
        serde_json::to_string_pretty(&config).map_err(|e|e.to_string())
    }

    pub fn to_xray_json(&self) -> Result<String, String> {
        if self.protocol != "vless" { return Err("Xray adapter currently supports VLESS profiles only".into()); }
        let uuid = self.uuid.as_deref().ok_or("Для VLESS требуется UUID")?;
        let network = self.params.get("type").map(|v| v.to_ascii_lowercase()).unwrap_or_else(|| "tcp".into());
        let security = self.params.get("security").map(|v| v.to_ascii_lowercase()).unwrap_or_else(|| "none".into());
        let server_name = self.params.get("sni").or_else(|| self.params.get("host")).cloned().unwrap_or_else(|| self.address.clone());
        let mut stream = serde_json::json!({ "network": network, "security": security });
        if security == "tls" {
            stream["tlsSettings"] = serde_json::json!({ "serverName": server_name });
            if let Some(alpn) = self.params.get("alpn") { stream["tlsSettings"]["alpn"] = serde_json::json!(alpn.split(',').map(str::trim).filter(|s| !s.is_empty()).collect::<Vec<_>>()); }
            if self.params.get("allowInsecure").map(|v| v == "1" || v.eq_ignore_ascii_case("true")).unwrap_or(false) { stream["tlsSettings"]["allowInsecure"] = serde_json::json!(true); }
        } else if security == "reality" {
            stream["realitySettings"] = serde_json::json!({ "serverName": server_name, "publicKey": self.params.get("pbk").cloned().unwrap_or_default(), "shortId": self.params.get("sid").cloned().unwrap_or_default(), "fingerprint": self.params.get("fp").cloned().unwrap_or_else(|| "chrome".into()) });
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
        Ok(serde_json::json!({
            "log": { "loglevel": "warning" },
            "inbounds": [{ "tag": "socks-in", "listen": "127.0.0.1", "port": 10808, "protocol": "socks", "settings": { "auth": "noauth", "udp": true } }],
            "outbounds": [{ "tag": "proxy", "protocol": "vless", "settings": { "vnext": [{ "address": self.address, "port": self.port, "users": [{ "id": uuid, "encryption": "none", "flow": self.params.get("flow").cloned().unwrap_or_default() }] }] }, "streamSettings": stream }, { "tag": "direct", "protocol": "freedom" }]
        }).to_string())
    }

    pub fn to_windows_runtime_json(&self) -> Result<String, String> {
        if self.engine() == "xray" {
            let xray = serde_json::from_str::<serde_json::Value>(&self.to_xray_json()?).map_err(|e| e.to_string())?;
            let sing_box = serde_json::json!({
                "log": { "level": "info" },
                "inbounds": [{ "type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"], "auto_route": true, "strict_route": true }],
                "outbounds": [{ "type": "socks", "tag": "proxy", "server": "127.0.0.1", "server_port": 10808, "version": "5" }, { "type": "direct", "tag": "direct" }, { "type": "block", "tag": "block" }],
                "dns": {
                    "servers": [{ "type": "local", "tag": "system" }],
                    "final": "system",
                    "strategy": "prefer_ipv4"
                },
                "route": { "auto_detect_interface": true, "final": "proxy", "rules": [{ "action": "hijack-dns" }] }
            });
            return Ok(serde_json::json!({ "engine": "xray", "sing_box_config": sing_box, "xray_config": xray }).to_string());
        }
        let sing_box = serde_json::from_str::<serde_json::Value>(&self.to_generic_sing_box_json()?).map_err(|e| e.to_string())?;
        Ok(serde_json::json!({ "engine": "sing-box", "sing_box_config": sing_box }).to_string())
    }

    pub fn to_sing_box_json(&self) -> Result<String, String> {
        if self.protocol != "vless" {
            return Err("Пока реализована генерация sing-box только для VLESS".into());
        }

        let uuid = self.uuid.as_deref().ok_or("Для VLESS требуется UUID")?;
        let security = self.params.get("security").map(String::as_str).unwrap_or("none");
        let transport = self.params.get("type").map(|v| v.to_ascii_lowercase()).unwrap_or_else(|| "tcp".into());
        let engine = self.engine();

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

        if security == "tls" {
            vless["tls"] = serde_json::json!({
                "enabled": true,
                "server_name": self.params.get("sni").or_else(|| self.params.get("host")).cloned()
            });
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
                "rules": [{ "action": "hijack-dns" }],
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
    let value = "NeoTUN Core 0.2.4 • Rust";
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

impl Profile {
    pub fn engine(&self) -> &'static str {
        // Route every VLESS transport through Xray. sing-box remains the engine
        // for the other protocols (Hysteria2, TUIC, Shadowsocks, etc.).
        if self.protocol == "vless" { "xray" } else { "sing-box" }
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
        assert_eq!(profile.engine(), "xray");
        let runtime: serde_json::Value =
            serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
        assert_eq!(runtime["engine"], "xray");
        assert_eq!(runtime["xray_config"]["outbounds"][0]["protocol"], "vless");
        assert_eq!(runtime["xray_config"]["outbounds"][0]["settings"]["vnext"][0]["address"], "example.com");
        assert_eq!(runtime["sing_box_config"]["inbounds"][0]["type"], "tun");
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], "socks");
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["server"], "127.0.0.1");
        assert_eq!(runtime["sing_box_config"]["outbounds"][0]["server_port"], 10808);
        assert_eq!(runtime["sing_box_config"]["dns"]["servers"][0]["type"], "local");
        assert_eq!(runtime["sing_box_config"]["dns"]["final"], "system");
        assert_eq!(runtime["sing_box_config"]["dns"]["strategy"], "prefer_ipv4");
        assert_eq!(runtime["sing_box_config"]["route"]["rules"][0]["action"], "hijack-dns");
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
    fn all_vless_transports_select_xray() {
        for transport in ["tcp", "ws", "grpc", "httpupgrade", "xhttp"] {
            let uri = format!("vless://123e4567-e89b-12d3-a456-426614174000@example.com:443?security=tls&type={transport}&path=%2Fneo");
            let profile = Profile::from_share_uri(&uri).unwrap();
            assert_eq!(profile.engine(), "xray", "transport={transport}");
            let runtime: serde_json::Value =
                serde_json::from_str(&profile.to_windows_runtime_json().unwrap()).unwrap();
            assert_eq!(runtime["engine"], "xray", "transport={transport}");
            assert_eq!(runtime["xray_config"]["outbounds"][0]["streamSettings"]["network"],
                       if transport == "xhttp" { "xhttp" } else { transport });
            assert_eq!(runtime["sing_box_config"]["outbounds"][0]["type"], "socks");
        }
        let hysteria = Profile::from_share_uri(
            "hysteria2://password@example.com:443"
        ).unwrap();
        assert_eq!(hysteria.engine(), "sing-box");
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

