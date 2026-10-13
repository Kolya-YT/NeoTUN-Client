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
| Subscription URL refresh | Present; supports Base64 feeds and deduplicates parsed links | Windows `SubscriptionStore.RefreshAsync` now accepts standard/URL-safe Base64 (including missing padding), multiple links per line and per-line encoded links; deduplicates links while preserving order | Verify Base64/plain feeds, duplicate links, empty/invalid feeds and failed refresh without data loss |
| Manual profile management | Present in `ProfileStore.kt` and main UI | Present in profile store and server UI | Compare rename, copy, delete, pin/sort/group, import/export and QR workflows |
| Routing profiles / Happ-INCY deep links | Present in `RoutingProfileStore.kt` and routing screen | Present in `RoutingProfileStore.cs` and routing UI | Windows accepts JSON, standard/URL-safe Base64 (including missing padding) and path/query deep links. Imported string/array `RouteOrder` is now normalized and retained as `RouteOrderRaw`, then consumed by the Windows runtime in the same order as Android; verify directives and unknown-field preservation |
| Proxy / Direct / Block rules | Adapter code present; engine parity not guaranteed | Domain/IP and supported GeoSite/GeoIP conversion present; Windows UI now exposes a persisted enable/disable toggle; disabling suppresses custom rules without deleting the profile; real traffic still unverified | Verify remote rule-set URLs/downloads, rule order, unknown-token reporting and real traffic; test Xray and Hysteria2 separately |
| DNS, MTU, IPv6 | Settings and service integration present | Settings/runtime path present; full device validation outstanding | Confirm each setting reaches the active engine and resets correctly after disconnect |
| Engine lifecycle and tunnel | Android Xray and sing-box services present | Runtime manager launches sing-box/Xray; README says Windows route/TUN lifecycle remains unverified | Validate connect/disconnect/reconnect, route cleanup, DNS, TCP/UDP, sleep/resume and failure rollback |
| Ping / latency | Present in UI/runtime areas; parity to be tested | Ping service and selected-profile status present | Confirm checks do not disconnect or alter the active tunnel |
| Traffic statistics | UI/service pieces present | Traffic monitor and view-model fields present | Compare actual received/sent/rate semantics while connected |
| QR and clipboard | QR import and profile QR are documented | Clipboard import/paste exists; QR parity not established | Decide platform-appropriate behavior and test round trips |
| App exclusions | Android exclusions are documented for Xray and sing-box | Platform equivalent requires audit | Keep Android-only feature explicit unless a valid Windows equivalent exists |
| Widgets / Quick Settings tile | Widget and tile sources present | No Android-equivalent widget requirement on desktop | Test widget/tile against the same real connection lifecycle |
| Theme / text size / UI preferences | Shared design tokens and settings areas exist | WPF theme manager/settings exist | Inventory actual preferences and make supported options consistent |
| GeoIP / GeoSite data | Android sing-box adapter maps supported tokens to remote binary `.srs` rule-sets; Xray path uses `.dat` data | Added matching remote binary `.srs` rule-set definitions, persistent cache and separate GeoSite/GeoIP matchers; removed an invalid `http_client.detour=direct` setting after user logs showed sing-box 1.14 rejects the empty direct outbound, and added `route.default_domain_resolver=system`; Windows runtime fix is present and the user confirmed that the startup error is gone. CI run `38433920892` exposed a test-fixture defect (`default domain resolver not found: system`), fixed by adding a local `system` DNS server to the fixture (commit `6ae317f`). Android received the same removal of the invalid rule-set download detour (commit `45dc087`). Windows run `38434465420` passed GeoSite/GeoIP validation, app-start smoke test, installer creation and release publishing; Android run `38434143357` passed. Real routing behavior is not yet independently verified | Verify upstream files exist, cold-start download, offline/failure behavior, Xray `.dat` path and actual traffic routing |
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
- [x] Recovered the truncated tail of `EngineRuntime.cs` against the last intact version of the same file, preserving the newer GeoSite/GeoIP code. Restored runtime stop helpers, log/error utilities, safe process state helpers and `Dispose()` (commit `32dd76c`). Windows run `38421790242` completed successfully: Rust tests, Windows core build, WPF compile, self-contained publish, engine staging, app startup smoke-test, installer EXE verification, and release-asset publishing all passed.
- [x] Enabled persistent sing-box remote rule-set cache at `%LOCALAPPDATA%/NeoTUN/sing-box-cache.db` while preserving existing experimental options (commit `9d9078a`).
- [x] Migrated remote rule-set downloads from deprecated `download_detour` to sing-box 1.14+ `http_client` with `detour: direct` (commit `ad2b42c`). Windows CI passed for the cache and HTTP-client changes (runs `38422376686` and `38422657682`).
- [x] Improved Windows subscription feed parsing to match Android: URL-safe Base64 and missing padding, multiple links on a line, per-line encoded entries, and case-insensitive deduplication (commit `82363e5`). Windows CI run `38423971144` passed.
- [x] Confirm Windows CI succeeds for persistent remote-rule-set cache and the sing-box 1.14+ `http_client` migration (runs `38422376686` and `38422657682`).
- [x] Verify remote `.srs` endpoints and sing-box config validation with pinned sing-box 1.14.3. CI fetched every supported GeoSite/GeoIP `.srs` endpoint and ran `sing-box check` on a representative config containing the full set; Windows build, app startup smoke test, installer verification and release upload all passed in run `38423832056`.
- [x] Updated Android's sing-box adapter from deprecated `download_detour` to `http_client: { detour: direct }`, matching its pinned libbox 1.14.1 (commit `1b26c2c`). Android/Windows CI runs `38429894018` and `38429893925` are in progress.
- [x] Extended Windows routing-profile decoding to accept URL-safe Base64 and missing padding, matching Android's accepted export formats (commit `211fee9`). Windows CI run `38429977993` is queued.
- [x] Windows subscription import now resolves remote `autorouting`/`routing/onadd` HTTP(S) profile URLs, normalizes GitHub `/blob/` links to raw content, decodes URL-safe Base64 routing directives and enforces the same routing-header precedence as Android (commit `75185c3`). CI run `38430778405` was cancelled by subsequent pushes before completion; the newer Windows workflow should validate this change together with the routing-enabled state.
- [x] Added a persisted Windows routing-enabled state, applied it to runtime routing rules, wired explicit subscription `off` directives to disable custom routing rules without deleting the saved profile, and added a UI toggle with a reconnect notice (commits `2238fa2`, `d7f181b`, `6683aca`, `a71d71c`, `f82e980`). Windows CI is validating the combined changes; runtime behavior still needs a real-PC check.
- [ ] Validate on Windows with a real profile: DIRECT for selected domestic rules, PROXY for selected proxy rules, BLOCK for selected block rules; include cold start with empty geodata cache.
- [x] Corrected the Windows CI sing-box 1.14 validation fixture: removed the same invalid `http_client.detour=direct` that had been left in the test fixture and added `route.default_domain_resolver=system` (commit `540f224`). The earlier runtime fix commit `8bae5ce` passed Windows CI run `38432427244`, but that run's rule-set fixture still contained the invalid option, so it did not independently prove this specific configuration is valid. The corrected fixture must pass in the current Windows CI run `38434465420`.
- [x] Windows now preserves imported Happ/INCY `RouteOrder` string/array data and applies arbitrary Block/Proxy/Direct ordering at runtime while keeping endpoint-bypass and DNS rules ahead of user rules. Changing the legacy UI preset clears the imported override (commits `2ad6d62`, `5d0f729`, `8f4f43b`, `2741c0b`). CI for this change is pending; real traffic remains unverified.\n- [ ] Continue the audit: Xray `.dat` lifecycle, subscription edge cases, Android/Windows profile management parity, protocol engine coverage, real TCP/UDP and cleanup.