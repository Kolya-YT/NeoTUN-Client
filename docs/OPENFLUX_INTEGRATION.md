# OpenFlux integration plan for NeoTUN

Status: design/audit stage. No OpenFlux runtime is enabled by this document.

## Goal

Add OpenFlux as an independent connection engine in NeoTUN while preserving existing VLESS/Xray and sing-box behavior on Android and Windows.

## Findings from the current NeoTUN repository

- Default branch: `main`.
- Audited head: `54d96bfa5c7f47d59a5501939f7e77c3606fef9a` (2026-10-18).
- Shared Rust Core: `core/`.
- Android app and VPN services: `android/`.
- Xray bridge: `xraybridge/`.
- Windows WPF client: `windows/NeoTUN.Windows/`.
- CI workflows: `.github/workflows/android.yml` and `.github/workflows/desktop.yml`.
- Current README documents Xray and sing-box; it does not claim OpenFlux support.

## Protocol boundary

OpenFlux is not a VLESS transport and an `openflux://` profile is not a VLESS URI. The first integration should expose two independent engine families:

- VLESS profiles -> existing Xray or sing-box adapter.
- OpenFlux profiles -> OpenFlux client runtime.

Do not rewrite VLESS URIs or silently convert one profile type into the other.

## Proposed architecture

1. Introduce an explicit profile/engine discriminator for OpenFlux only after tracing current profile serialization, import, persistence and engine-selection code.
2. Preserve the original `openflux://` link/configuration as user data. Validate it before saving and never log credentials or session secrets.
3. Wrap the OpenFlux client behind a narrow platform-neutral lifecycle contract: validate, start, stop, state, diagnostics and counters.
4. Android: investigate the upstream Go mobile bridge and how its packet/session interface can be hosted by NeoTUN's existing VPN service. There must be one owner of Android's VPN/TUN at a time.
5. Windows: investigate the supported upstream TUN/Wintun and SOCKS5 modes. Do not start a second full-tunnel adapter while NeoTUN's existing TUN is active.
6. Keep profile engine selection explicit. Never infer OpenFlux from a VLESS URI or from a failed VLESS connection.
7. Add regression tests for URI/config detection, round-trip persistence, unknown-field preservation, and exclusive tunnel ownership before enabling runtime integration.
8. Benchmark connection startup, throughput, latency, loss, memory use and recovery on each platform before calling the engine stable.

## Licensing and dependency gate

The upstream OpenFlux repository declares GPL-3.0-or-later, while NeoTUN currently declares MIT. Before bundling or linking OpenFlux code into distributable NeoTUN builds, review the licensing consequences and required source/license notices. Prefer a separately built/packaged OpenFlux component until compatibility and distribution obligations are resolved. Do not copy upstream source into the Rust Core as part of the initial patch.

The upstream `go.mod` currently declares Go 1.26.6. Confirm CI toolchain availability and upstream mobile bridge APIs before selecting a pinned revision or adding a build dependency.

## Acceptance criteria

- Existing VLESS/Xray and sing-box profile import, connection and disconnect behavior remains unchanged.
- OpenFlux links/configurations are imported and stored distinctly, with invalid inputs rejected without deleting existing profiles.
- Android and Windows never attempt to own the same full-tunnel path concurrently.
- Runtime state reflects actual process/service state, not just a button press.
- Relevant CI builds and tests pass; device-level TCP/UDP, DNS, disconnect/reconnect and sleep/wake tests are recorded separately.
- README claims OpenFlux support only after these checks have passed.

## Current limitation

This plan is not evidence of working OpenFlux integration. Runtime API compatibility, license/distribution model, CI toolchain compatibility and real-device tunnel behavior remain unverified.
