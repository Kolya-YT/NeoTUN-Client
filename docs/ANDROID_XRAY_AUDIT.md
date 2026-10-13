# Android Xray implementation audit

Audit date: 2026-10-13  
Scope: Android Xray service, libXray bridge, TUN handoff, VLESS transport conversion, routing, DNS, lifecycle and runtime diagnostics.

## Official references

- Xray client data flow: apps -> inbound -> routing -> outbound: https://xtls.github.io/en/document/level-0/ch08-xray-clients.html
- Xray TUN inbound / Android FD handoff: https://xtls.github.io/en/config/inbounds/tun.html
- Xray TUN Android README: https://github.com/XTLS/Xray-core/blob/main/proxy/tun/README.md
- libXray methods and Invoke envelope: https://github.com/XTLS/libXray/blob/main/README.md
- libXray implementation of runXray: https://github.com/XTLS/libXray/blob/main/invoke.go
- Xray transport and REALITY compatibility: https://github.com/XTLS/Xray-docs-next/blob/main/docs/en/config/transport.md
- REALITY configuration: https://github.com/XTLS/Xray-docs-next/blob/main/docs/en/config/transports/reality.md

## Expected Android data path

1. Android `VpnService.Builder` establishes the VPN interface and routes. The TUN FD must remain open for the entire Xray session.
2. The app calls `detachFd()` and passes that FD number in the Xray config root `env` as `xray.tun.fd` before `runXray`. This is the documented Android integration pattern.
3. Xray's `tun` inbound reads packets from that FD.
4. Xray routing matches inbound tag `tun`; rules route to `direct`, `block`, or `proxy`.
5. The VLESS outbound uses a supported transport/security combination and opens its socket outside the VPN (Android `VpnService.protect(fd)` callback).
6. The reply travels back through Xray and the same TUN FD to Android.
7. Disconnect must stop Xray first, reset libXray DNS hooks, close the detached FD, and clear the native service reference.

## Findings from source inspection

### Correct or directionally correct

- Android has a separate `:xray` process and dedicated `VpnService`.
- The service establishes Android TUN, detaches its FD, and sets `env.xray.tun.fd` in the full `runXray` config.
- The C/JNI bridge registers a libXray controller whose `ProtectFd` calls Android `VpnService.protect(fd)`.
- The selected outbound gets tag `proxy`; routing uses inbound tag `tun` and sends unmatched traffic to `proxy` by default.
- The service invokes `stopXray` before closing the TUN FD during expected teardown.
- `testXray` is only config validation; it is not a connectivity test. `runXray=true` and `getXrayState.running=true` prove lifecycle state, not end-to-end traffic.
- Official Xray docs only permit REALITY with RAW, XHTTP and gRPC. REALITY+WS, HTTPUpgrade and mKCP must not be silently accepted.

### Defects / risks that require runtime proof or follow-up

1. **False-positive connected state.** The app marks `KEY_RUNNING=true` after the core reports running, before any end-to-end connectivity probe. UI must distinguish `core running` from `traffic verified`.
2. **Insufficient Xray diagnostics.** The previous config used `loglevel=warning`, so dial, DNS, TLS/REALITY and transport errors were largely hidden. The service now writes Xray access/error logs under app-private files and copies recent lines into diagnostics after the metrics sample.
3. **TUN packet flow not yet independently proven.** The source correctly passes the FD, but only a device run with real counters/logs can prove Xray actually reads packets and returns replies.
4. **Metrics endpoint is diagnostic only.** The loopback endpoint/counters must not be treated as a substitute for a real connectivity test. If it is unavailable or its counter schema differs, use Xray's access/error logs and an explicit protected TCP connectivity probe.
5. **DNS path is easy to misdiagnose.** `libXray.SetDNS` is configured before `runXray`; this is separate from Android's VPN DNS server. Verify both the protected upstream resolver and application DNS queries; a configured DNS server does not prove resolution works.
6. **Configuration validation coverage.** `testXray` is currently passed an outbound-only JSON projection, while `runXray` receives the complete config including TUN, routing, metrics, policy and stats. It therefore does not validate the whole runtime config.
7. **Routing/geodata must not hide basic connectivity failures.** First prove default `tun -> proxy` works with routing rules disabled/minimal; only then test GeoIP/GeoSite and user routing rules.
8. **Transport support is version-dependent.** libXray's parser returns projected, buildable outbounds; parser success only means the URI became config. It does not prove the remote server supports the transport, its XHTTP mode, REALITY keys/SNI/short ID, or routing.
9. **Avoid treating `running` as success in widgets/status.** The service's internal state and actual network reachability are different states and need separate reporting.

## Next verification sequence

1. Install the latest successful Android CI artifact and connect one known-good VLESS RAW/TCP + REALITY profile, then one known-good XHTTP + REALITY profile.
2. Generate actual traffic (open a site and make a DNS lookup), then capture app diagnostics and Xray access/error logs.
3. Confirm the following separately: Android VPN active; Xray instance running; TUN inbound bytes increase; proxy outbound bytes increase; a VLESS outbound connection succeeds; response bytes return.
4. If inbound bytes stay at zero, inspect TUN FD ownership/inbound initialization.
5. If inbound grows but proxy does not, inspect Xray routing and tags.
6. If proxy attempts appear but fail, inspect Xray error log for dial, DNS, REALITY handshake, SNI/short ID/public key, and XHTTP settings.
7. If proxy bytes grow but the browser still fails, inspect return traffic, DNS, MTU and IPv4/IPv6 routing.
8. Repeat with a minimal config before restoring custom routing/geodata. Do not declare the issue fixed until the device test proves traffic.

## Latest code changes during this audit

- Commit `5aa078aae939dae4ce1b11f31d48374bd34c2db1`: added traffic diagnostics.
- Commit `1cff1ff9a34c7241079277e982b2606d9c9f6626`: changed metrics fetch to loopback socket.
- Commit `00323c5a59ebb518df14dbb19606ad4fc12eb346`: corrected HTTP request framing.
- Commit `4e68f7a6affc30012fde062d3244a9b4bb488db2`: enabled Xray info logging and captures recent access/error log lines in diagnostics.

These changes improve observability. They are not a claim that Xray traffic is fixed; device-level proof remains required.
