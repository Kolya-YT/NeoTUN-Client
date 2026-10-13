# Happ → NeoTUN feature parity audit

**Baseline:** `main` at `e92c6bc96887d872de5761cf1153867877370fda` (2026-10-13).
**Purpose:** track functional parity for Android and Windows before the planned NeoTUN redesign.
**Important:** the uploaded Happ APK has been inspected at the archive/resource level, but its complete JADX decompilation and the Windows installer payload have not been verified. This is a baseline audit, not a claim of full reverse engineering.

## Status meanings

- **Present in source** — corresponding implementation is visible in the current repository.
- **Partial / verify** — code exists, but parity or runtime behavior is not proven.
- **Not yet audited** — inspect Happ behavior and trace NeoTUN source before deciding.
- **Not supported** — deliberately outside the current product scope.

## Feature matrix

| Area | Android | Windows | Next verification |
|---|---|---|---|
| Profile links: VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC | Present in app/core; protocol parameters need per-engine checks | Importer recognizes these URI schemes | Compare accepted URI variants and preserved parameters; add parser regression tests |
| Subscription URL refresh | Present; inspect merge and failure paths | Present in `SubscriptionStore.RefreshAsync` | Test Base64/plain feeds, duplicate links, empty/invalid feeds, failed refresh without data loss |
| Manual profile management | Present in `ProfileStore.kt` and main UI | Present in profile store and server UI | Compare rename, copy, delete, pin/sort/group, import/export and QR workflows |
| Routing profiles / Happ-INCY deep links | Present in `RoutingProfileStore.kt` and routing screen | Present in `RoutingProfileStore.cs` and routing UI | Compare JSON/Base64/deep-link formats, header directives, route ordering and unknown fields |
| Proxy / Direct / Block rules | Adapter code present; engine parity not guaranteed | Domain/IP and supported GeoSite/GeoIP conversion now present in source; CI and runtime validation pending | Verify remote rule-set URLs/downloads, rule order, unknown-token reporting and real traffic; test Xray and Hysteria2 separately |
| DNS, MTU, IPv6 | Settings and service integration present | Settings/runtime path present; full device validation outstanding | Confirm each setting reaches the active engine and resets correctly after disconnect |
| Engine lifecycle and tunnel | Android Xray and sing-box services present | Runtime manager launches sing-box/Xray; README says Windows route/TUN lifecycle remains unverified | Validate connect/disconnect/reconnect, route cleanup, DNS, TCP/UDP, sleep/resume and failure rollback |
| Ping / latency | Present in UI/runtime areas; parity to be tested | Ping service and selected-profile status present | Confirm checks do not disconnect or alter the active tunnel |
| Traffic statistics | UI/service pieces present | Traffic monitor and view-model fields present | Compare actual received/sent/rate semantics while connected |
| QR and clipboard | QR import and profile QR are documented | Clipboard import/paste exists; QR parity not established | Decide platform-appropriate behavior and test round trips |
| App exclusions | Android exclusions are documented for Xray and sing-box | Platform equivalent requires audit | Keep Android-only feature explicit unless a valid Windows equivalent exists |
| Widgets / Quick Settings tile | Widget and tile sources present | No Android-equivalent widget requirement on desktop | Test widget/tile against the same real connection lifecycle |
| Theme / text size / UI preferences | Shared design tokens and settings areas exist | WPF theme manager/settings exist | Inventory actual preferences and make supported options consistent |
| GeoIP / GeoSite data | Android sing-box adapter maps supported tokens to remote binary `.srs` rule-sets; Xray path uses `.dat` data | Added matching remote binary `.srs` rule-set definitions, direct download detour and separate GeoSite/GeoIP matchers; pending CI/runtime checks | Verify upstream files exist, cold-start download, offline/failure behavior, Xray `.dat` path and actual traffic routing |
| App update | Android updater present | Windows release/installer workflow present | Test upgrade-over-existing-install and preserve user data |
| Diagnostics / logs | Diagnostics source present | Logs page and runtime logs present | Ensure logs redact credentials, UUIDs and subscription tokens |
| Protocol-specific advanced settings | Some Xray/sing-box configuration support present; complete Happ parity not yet established | Importer recognizes schemes but recognition alone does not prove support | Compare Happ controls and generated configs for TLS/Reality, transport, XHTTP, flow, SNI, fingerprint, ALPN and protocol-specific fields |

## Guardrails

1. Never copy Happ's branding, proprietary assets, private credentials, embedded endpoints or bundled server lists into NeoTUN.
2. Reimplement behavior against observed inputs/outputs and compatible protocol specifications; do not treat decompiled implementation as a requirement to copy source code.
3. Preserve unknown profile/subscription fields where possible. A failed or empty refresh must not replace working data.
4. A URI being accepted by the importer is not proof that the engine supports all of its options.
5. Do not claim feature parity from compilation alone. Mark device/runtime checks separately.
6. Keep the temporary UI aligned across Android and Windows; redesign only after functional behavior is mapped.

## Work order

1. **Baseline and gaps:** inspect the complete Happ APK resources/manifest and safely extract/inspect the Windows installer payload; finish the feature inventory without executing unknown binaries.
2. **Profile and subscription parity:** compare accepted links, Base64/plain feeds, metadata, deduplication, refresh rollback and routing directives.
3. **Routing parity:** compare imported rule formats and verify actual rule consumption separately in Xray and sing-box.
4. **Runtime parity:** validate lifecycle, DNS, TCP/UDP, stats, ping, error handling and cleanup on each OS.
5. **Settings and auxiliary features:** compare QR, clipboard, exclusions, widgets, updater, themes, diagnostics and advanced protocol settings.
6. **Temporary UI parity:** align supported screens and state transitions with the current Happ interaction model, without a broad visual redesign.
7. **Regression and release:** run CI, review logs and verify real-device/real-PC behavior before claiming completion.

## Work log (2026-10-13)

- [x] Created this parity matrix and work order (commit `8860cae`).
- [x] Windows subscription refresh validates that supported profiles exist before replacing stored profiles or importing routing directives (commit `f93e7ac`).
- [x] Windows routing import accepts typed JSON booleans/numbers, array or string route order, DNS aliases, IPv6, MTU and process exclusions (commit `ccf4728`).
- [x] Windows routing deep-link decoder accepts path payloads as well as query payloads (commit `6d912c9`; Windows CI passed including app-start smoke test and installer packaging).
- [x] Windows sing-box runtime now maps supported Happ/INCY GeoSite/GeoIP tokens to remote binary rule-sets, separates domain matchers from GeoSite matchers and IP matchers from GeoIP matchers, and uses the direct outbound for downloads (implementation commits `832bae2`, `697ba5a`, `b02e7d6`; source-boundary cleanup `f4e2778`).
- [x] Fixed the Windows build error identified by run `38419511815`: `StopAfterUnexpectedExitAsync` was truncated after `try`, causing CS1513. Added awaited runtime cleanup and error reporting (commit `57ce03a`); Windows CI for this fix is queued.
- [ ] Confirm latest Windows CI for GeoSite/GeoIP support succeeds.
- [ ] Verify remote `.srs` endpoints and sing-box config validation with the pinned sing-box version.
- [ ] Validate on Windows with a real profile: DIRECT for selected domestic rules, PROXY for selected proxy rules, BLOCK for selected block rules; include cold start with empty geodata cache.
- [ ] Continue the audit: Xray `.dat` lifecycle, subscription edge cases, Android/Windows profile management parity, protocol engine coverage, real TCP/UDP and cleanup.