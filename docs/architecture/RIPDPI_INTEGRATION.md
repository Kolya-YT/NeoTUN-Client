# RIPDPI integration plan

Status: **Phase 1 — NeoTUN-owned adapter contract added; upstream runtime not linked or enabled yet.**

Upstream: https://github.com/po4yka/RIPDPI  
Reviewed revision: `.github/ripdpi-upstream.lock`

## Goal and boundaries

Integrate RIPDPI's local packet strategies without replacing NeoTUN's Android VPN service, Windows tunnel lifecycle, Xray, sing-box, profile storage, routing configuration, or updater.

1. NeoTUN remains the sole owner of the platform VPN/TUN lifecycle.
2. RIPDPI is an optional adapter. If it is disabled, unsupported, or fails, existing Xray/sing-box connection paths remain available.
3. Pin upstream revisions. New upstream commits trigger review only; they must not silently change a shipped build.
4. Enable only platform capabilities that have passed real network tests.
5. Diagnostics must not log UUIDs, passwords, share URIs, or private keys, and probing must not rewrite profiles, DNS, or routing.

## Completed

### Phase 0 — source/update safety

- Reviewed upstream commit `5ea9c492c0ba1d27cf5223642c1aa36a044aa3d5`.
- Added `.github/ripdpi-upstream.lock` and a scheduled GitHub Actions review workflow.
- The workflow only compares revisions and opens/updates a review issue. It does not update dependencies or publish artifacts.

### Phase 1 — NeoTUN adapter contract

- Added `core/src/dpi_adapter.rs` and exported it from `core/src/lib.rs`.
- Contract includes target validation, backend state and capabilities, TCP/UDP transport declarations, per-target diagnosis, bounded strategy attempts, and explicit fallback to the existing engine.
- Unit tests cover invalid targets, transport capability gating, and fallback policy.
- **This is an interface and policy scaffold, not a working DPI implementation.** No RIPDPI packet strategy is linked or invoked by NeoTUN yet.

## Findings at the pinned upstream revision

- RIPDPI's `native/rust/Cargo.toml` is a large Rust 2024 workspace with many path-linked internal crates; NeoTUN's `core/Cargo.toml` is a small Rust 2021 JNI crate. Adding the entire workspace would be a high-risk first step.
- `ripdpi-proxy-runtime-desync-adapter` depends on internal config, desync, packet, session, platform, runtime-service, and strategy-registry crates. It is not a standalone dependency.
- `ripdpi-proxy-runtime-adapter` also depends on runtime/platform components and Unix-oriented libraries such as `nix` and `libc`. This does not establish Android or Windows portability.
- The current API surface needs to be examined together with the traffic model: a local SOCKS proxy cannot automatically apply raw-packet strategies to traffic already routed through NeoTUN's TUN. We must choose a supported integration topology before enabling the backend.
- The workspace declares MIT in its package metadata, while the upstream repository's root license information has also been reported as BSD-3-Clause. Audit the exact source files, license texts, and third-party notices before redistributing any selected code.

## Next phases

### Phase 2 — minimal upstream proof of concept

- Use a reproducible checkout of the pinned commit.
- Resolve and document the smallest dependency closure and required Rust toolchain.
- Compile and test the candidate adapter outside the production connection path.
- Verify whether its strategy API can be called over ordinary sockets, requires a local proxy, or requires raw packet/TUN access.
- Do not copy upstream binaries or start a second Android `VpnService`.

### Phase 3 — Android adapter

- Use NeoTUN's existing `VpnService` and protect outbound adapter sockets from looping back into the tunnel.
- Add explicit cancellation, timeouts, and generation checks.
- Test direct connection, local strategy, TCP, UDP, DNS, repeated connect/disconnect, and fallback to Xray/sing-box on a real device.
- Keep the feature off by default until these tests pass.

### Phase 4 — desktop adapters

- Reuse only proven portable logic.
- Implement platform-specific interception and permissions separately.
- Do not advertise Windows/Linux/macOS support until each backend has passed runtime network tests.

### Phase 5 — controlled upstream updates

- Compare new upstream commits against the pinned revision.
- Review API changes, license/dependency changes, build and regression results before updating the pin.
- Keep the last known-good revision for rollback.
- Release only through NeoTUN's existing signing and version workflow.

## Acceptance criteria

- Existing profiles and subscription data remain unchanged.
- Existing engines continue to work with the adapter absent, disabled, or failing.
- Exactly one component owns the platform VPN/TUN lifecycle.
- No upstream revision enters a release without a pinned SHA and passing checks.
- Support claims are based on real platform tests, not compilation alone.
