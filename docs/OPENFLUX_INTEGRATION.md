# OpenFlux integration plan for NeoTUN

Status: design/audit stage. No OpenFlux runtime is enabled by this document.

## Goal

Add OpenFlux as an independent connection engine in NeoTUN while preserving existing VLESS/Xray and sing-box behavior on Android and Windows.

## Findings from the current NeoTUN repository

- Shared Rust Core: `core/`.
- Android app and VPN services: `android/`; Android already has separate sing-box and Xray VPN service lifecycles.
- Xray bridge: `xraybridge/`.
- Windows WPF client: `windows/NeoTUN.Windows/`.
- CI workflows: `.github/workflows/android.yml` and `.github/workflows/desktop.yml`.
- Android profiles are currently persisted as a URI plus an engine string in `ProfileStore.kt`. Adding a URI alone does not make it connectable.
- Current README documents Xray and sing-box; it does not claim OpenFlux support.

## Protocol boundary

OpenFlux is not a VLESS transport and an `openflux://` profile is not a VLESS URI. The integration must expose separate engine families:

- VLESS and other currently supported profiles -> existing Xray or sing-box adapter.
- OpenFlux profiles -> OpenFlux client runtime.

Do not rewrite VLESS URIs or silently convert one profile type into the other.

## Upstream mobile API findings

The upstream `OpenFlux/mobile` package exposes a packet transport API suitable for an Android bridge:

- `ReadShareLink(link)` / `ParseShareLink(link)` validate and decode OpenFlux share links.
- `Start(...)` and `StartSession(...)` start carrier/session transports.
- `Send(packet)` sends a complete IP packet to the remote transport.
- `Read()` / `ReadTimeout(...)` return received packets.
- `Stop()` stops the transport; `IsConnected()` reports transport connectivity.

This API does not itself own Android's TUN descriptor. NeoTUN would need a dedicated adapter/service that reads packets from its established VPN interface, sends them through OpenFlux, writes received packets back, handles errors and closes both sides reliably. Use the upstream API rather than reimplementing the OpenFlux link decoder.

Reference: https://github.com/p1neappleXpress/OpenFlux/blob/main/mobile/mobile.go and https://github.com/p1neappleXpress/OpenFlux/blob/main/mobile/share.go.

## Proposed architecture

1. Introduce an explicit profile/engine discriminator for OpenFlux only after tracing import, persistence, UI selection and connection lifecycle.
2. Preserve the original `openflux://` link as user data. Validate before saving and never log credentials or session secrets.
3. Wrap the OpenFlux client behind a narrow lifecycle contract: validate, start, stop, state, diagnostics and counters.
4. Android: use the upstream Go mobile bridge behind a dedicated adapter. There must be one owner of Android's VPN/TUN at a time. Coordinate engine switches with the existing stop acknowledgements; do not race a new VPN service against native teardown.
5. Windows: investigate the upstream supported TUN/Wintun and SOCKS5 modes. Do not start a second full-tunnel adapter while NeoTUN's existing TUN is active.
6. Keep engine selection explicit. Never infer OpenFlux from a VLESS URI or from a failed VLESS connection.
7. Add regression tests for link validation, profile round-trip persistence, unknown-field preservation and exclusive tunnel ownership before enabling runtime integration.
8. Benchmark connection startup, throughput, latency, loss, memory use and recovery on each platform before calling the engine stable.

## Protocol limitations to validate

OpenFlux capabilities vary by client mode and core version. Do not advertise generic UDP or IPv6 support just because an OpenFlux link imports. Verify the exact selected upstream runtime's packet path, DNS handling, TCP/UDP behavior and IPv6 behavior with device-level tests. Some documented Android OpenFlux implementations currently support IPv4/TCP only and drop other traffic.

## Licensing and dependency gate

The upstream OpenFlux repository declares GPL-3.0-or-later, while NeoTUN currently declares MIT. Before bundling or linking OpenFlux code into distributable NeoTUN builds, review licensing consequences and required source/license notices. Do not copy upstream source into the Rust Core as part of the initial patch. A separate executable/component is not automatically a solution to all licensing questions; distribution model should be reviewed before packaging.

Pin a specific upstream commit and verify the Go toolchain, gomobile build, Android NDK and generated AAR before changing CI. Do not rely on a moving `main` branch or assume the locally available Go version is compatible.

## Acceptance criteria

- Existing VLESS/Xray and sing-box profile import, connection and disconnect behavior remains unchanged.
- OpenFlux links/configurations are imported and stored distinctly, with invalid inputs rejected without deleting existing profiles.
- Android and Windows never attempt to own the same full-tunnel path concurrently.
- Runtime state reflects actual process/service state, not just a button press.
- Relevant CI builds and tests pass; device-level TCP/UDP, DNS, disconnect/reconnect and sleep/wake tests are recorded separately.
- README claims OpenFlux support only after these checks have passed.

## Current limitation

This plan is not evidence of working OpenFlux integration. No runtime code or profile import support is enabled yet. Runtime API compatibility, license/distribution model, CI toolchain compatibility and real-device tunnel behavior remain to be verified.
