# RIPDPI integration plan

Status: **Phase 1 — NeoTUN-owned adapter contract added; upstream runtime not linked or enabled yet.**

## Goal

Add RIPDPI's local packet-strategy and diagnostics capabilities to NeoTUN without replacing the existing Android VPN service, Windows tunnel lifecycle, Xray, sing-box, profile storage, routing configuration, or updater.

Upstream: https://github.com/po4yka/RIPDPI
Reviewed upstream revision: see [.github/ripdpi-upstream.lock](../../.github/ripdpi-upstream.lock).

## Important architecture finding

RIPDPI is not a single reusable Rust crate. Its current Rust workspace lives in native/rust/, uses Rust edition 2024, and contains a large graph of internal crates plus Android-specific JNI artifact roots. The upstream project also documents that its main branch may contain breaking changes and partially broken functionality.

Therefore, do not add the whole workspace to NeoTUN's existing core/Cargo.toml, copy native libraries into the APK, or load RIPDPI's Android VPN/tunnel artifact beside NeoTUN's own VpnService before a focused compatibility audit.

## Integration boundaries

1. **NeoTUN remains the owner of the app and tunnel lifecycle.** No second VPN service and no automatic takeover of the TUN interface.
2. **RIPDPI is an optional local-strategy adapter.** A failed/disabled adapter must leave existing Xray and sing-box paths available.
3. **Keep upstream changes isolated.** Prefer a small, reviewed set of portable crates behind a stable NeoTUN-owned interface. Avoid patching upstream internals unless a patch is documented and tested.
4. **Pin revisions.** Never consume upstream main directly in a production build. A newer upstream commit triggers review; it does not silently update a shipped app.
5. **Gate by capability.** Do not claim Windows/Linux/macOS support until each platform's packet interception and lifecycle adapter is implemented and tested.
6. **Fail safely.** If a local strategy cannot be used or fails its health check, return control to NeoTUN's existing connection selection logic. Never change saved profiles, subscriptions, DNS, or routing rules as a side effect of probing.

## Phases

### Phase 0 — source and update safety (completed baseline)

- Reviewed upstream commit `5ea9c492c0ba1d27cf5223642c1aa36a044aa3d5`.
- Added `.github/ripdpi-upstream.lock` and a scheduled review workflow. The workflow only alerts for review; it does not update code or ship artifacts.

### Phase 1 — minimal Rust API proof (contract started)

- Added `core/src/dpi_adapter.rs` and exported it from `core/src/lib.rs`.
- The contract models target validation, backend state/capabilities, bounded diagnostics, TCP/UDP support, and fallback to existing engines.
- Unit tests cover invalid targets, transport capability checks, and safe fallback policy.
- **This is an integration boundary only, not a working DPI implementation.** No RIPDPI strategy or packet interception is currently linked into NeoTUN.

#### Upstream audit at the pinned revision

- RIPDPI's `native/rust/Cargo.toml` is a large Rust 2024 workspace with many path-linked internal crates; NeoTUN's current `core/Cargo.toml` is a small Rust 2021 JNI crate. Adding the upstream workspace wholesale would create a toolchain/dependency and maintenance risk.
- The candidate `ripdpi-proxy-runtime-desync-adapter` depends on numerous internal crates, including runtime services, platform, strategy registry, packet and session layers; it is not a standalone crate that can simply be added as one dependency.
- `ripdpi-proxy-runtime-adapter` includes Unix/Linux-oriented dependencies such as `nix` and `libc`, plus runtime/platform components. Its presence does not establish Android/Windows portability or mean the strategy can operate on NeoTUN's existing TUN traffic.
- Next gate: resolve the smallest dependency closure and its platform assumptions in a reproducible upstream checkout, then compile an isolated proof-of-concept before wiring the adapter into connection startup.

### Phase 2 — Android adapter (not started)

- Record the reviewed upstream commit.
- Monitor upstream for new commits and open a single review issue when the pinned revision becomes stale.
- Do not automatically change the pin, copy binaries, or publish a new app version.

### Phase 1 — minimal Rust API proof

- Audit candidate crates for dependency closure, feature flags, license notices, Android/desktop assumptions, unsafe boundaries, and required OS privileges.
- Define a NeoTUN-owned API for capability discovery, target diagnostics, strategy selection, cancellation, and redacted results.
- Build a small isolated proof of concept and tests without wiring it into the production connection path.

### Phase 2 — Android adapter

- Use the existing NeoTUN VpnService; do not start RIPDPI's own VPN service.
- Confirm whether the chosen upstream APIs operate on traffic NeoTUN can safely provide. Do not assume raw packet strategies work from a SOCKS proxy or an already-owned TUN.
- Test direct path, local strategy, timeout/cancellation, VPN lifecycle, DNS, TCP, UDP, and fallback to the existing engines.

### Phase 4 — desktop adapters

- Reuse only portable logic where possible.
- Implement separate platform-specific packet interception and permission handling.
- Keep the feature disabled on platforms without a tested backend.

### Phase 5 — controlled updates

- Update the pinned revision only after compatibility, license, regression, and platform checks pass.
- Keep the last known-good revision available for rollback.
- Release only through the existing NeoTUN version/signing workflow; never publish unreviewed upstream artifacts directly.

## Acceptance criteria

- Existing profiles and subscription data remain unchanged by enabling/disabling the adapter.
- Existing Xray and sing-box paths work when the RIPDPI adapter is absent, disabled, or fails.
- Exactly one component owns the platform VPN/TUN lifecycle.
- No upstream revision enters a release without a pinned SHA and passing checks.
- Claims of support are based on real platform tests, not just compilation.

## License gate

The upstream root currently presents a BSD-3-Clause license while its Rust workspace metadata declares MIT. Before redistributing selected source or binaries, inspect the exact crate manifests, source headers, vendored dependencies, and third-party notices at the pinned revision. Preserve all required notices and resolve the license scope before shipping.
