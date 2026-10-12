# NeoTUN engine-native connection resilience

Status: **Selected direction — remove the external RIPDPI dependency; audit and use capabilities already provided by the selected engine.**

## Why this direction

NeoTUN already ships Xray and sing-box. A second local proxy/VPN service adds another traffic path, lifecycle, permissions, and failure modes. Rather than embedding a large third-party workspace or launching another VPN, first use and test the selected engine's own connection-resilience features.

This is not a claim that engine-native fragmentation defeats every network filter. TLS fragmentation helps only against some plaintext handshake matching. It cannot repair a dead server, blocked destination IP/port, invalid credentials, or every form of active probing.

## Supported building blocks to audit

### Xray

Xray has a `fragment` option on the `freedom` outbound, including TLS ClientHello fragmentation. This is not a universal switch for every VLESS outbound: routing/dialer composition must be verified against the exact Xray version and profile type before enabling it. Do not rewrite user profiles blindly.

Reference: https://xtls.github.io/en/config/outbounds/freedom.html

### sing-box

sing-box 1.12+ documents `tls.record_fragment` and `tls.fragment` for outbound TLS handshakes. The documentation recommends trying record fragmentation first and applying it only to known affected destinations because of performance costs. Feature availability depends on the bundled core version and platform behavior.

Reference: https://sing-box.sagernet.org/configuration/shared/tls/

## NeoTUN implementation rules

1. No second `VpnService`, TUN owner, SOCKS relay, or third-party DPI daemon.
2. Keep engine-specific configuration inside the Xray and sing-box adapters; keep the UI and policy shared.
3. Feature-detect capabilities using the actual bundled engine version; do not emit unsupported JSON fields.
4. Make the feature opt-in or scoped to a target known to fail, not a global default.
5. Start with a diagnostic/preview mode and validate generated config using the actual core before starting it.
6. Preserve the original profile URI and subscription payload. Store NeoTUN-only overrides separately.
7. If a strategy fails, retry the original configuration and then allow the user to select another profile/engine. Do not report connected until the native engine is running and traffic health has been checked.
8. Test Android and Windows separately; do not infer runtime support from a successful compile.
9. If engine-native options are insufficient for a demonstrated failure case, evaluate a platform-specific packet backend as a separate project, with explicit platform limitations. Do not pretend one implementation covers Android, Windows, Linux, and macOS.

## Phases

### Phase 1 — inventory actual bundled versions

- Read Android and Windows build manifests and locate the exact Xray and sing-box binaries/artifacts and versions.
- Check which options those exact versions support.
- Add config-generation tests for VLESS TCP/TLS, REALITY, XHTTP, and Hysteria2 so unsupported options are never injected.

### Phase 2 — Xray proof of concept

- Add a NeoTUN-owned, reversible override for Xray fragmentation only where the current core/config path supports it.
- Validate with Xray's own config test and a real device.
- Verify ordinary direct traffic and existing REALITY/XHTTP profiles remain unchanged when disabled.

### Phase 3 — sing-box proof of concept

- Gate `record_fragment` / `fragment` on the actual bundled sing-box version and transport compatibility.
- Apply only to explicitly selected targets or a manual diagnostic test.
- Test TCP, UDP, DNS, repeated start/stop, and profile switching.

### Phase 4 — evidence-based automatic mode

- Only after per-network tests, add a bounded strategy sequence with timeouts, cancellation, and clear diagnostics.
- Never silently modify subscription content or claim success based on process state alone.

## Acceptance criteria

- RIPDPI source, lock files, and workflows are absent from NeoTUN.
- Existing connection behavior is unchanged when resilience mode is disabled.
- No unsupported engine config is generated.
- Config validation and Android/Windows runtime tests pass before enabling by default.
