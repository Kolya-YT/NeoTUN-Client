# NeoTUN-Client — instructions for AI coding assistants

These rules apply to ChatGPT, Google AI Studio/Gemini, and any other assistant editing this repository.

## Read before editing
1. Read `AI_WORKFLOW.md`.
2. Read `README.md` for confirmed capabilities and known limitations.
3. Keep UI compact, dark and aligned with tokens in `android/app/src/main/java/com/neotun/app/NeoTunDesign.kt` and `windows/NeoTUN.Windows/App.xaml`.
4. Inspect current source and callers; do not rely on older chat snippets.
5. Check the latest commit on `main` and relevant GitHub Actions results.

## Subsystem Architecture & Module Bridges
- `core/` (Rust 2021, `neotun-core`):
  - Shared parsing, percent-decoding, URL normalization, and engine selection (`Profile::engine()`).
  - Configuration synthesis: `to_sing_box_json()`, `to_xray_json()`, `to_generic_sing_box_json()`, and `to_windows_runtime_json()`.
  - Android JNI Bridge (`Java_com_neotun_app_NeoTunCore_*`): `nativeVersion`, `nativeVlessConfig`, `nativeVlessEngine`, `nativeShareConfig`, `nativeShareEngine`.
  - Windows C ABI (`neotun_windows_runtime_json`, `neotun_free_string`): exports JSON payload containing engine choice, `sing_box_config`, and optional `xray_config`.
- `android/` (Kotlin, Android SDK/NDK, Gradle):
  - Storage: `ProfileStore.kt` (deduplication by canonical URI key without fragment), `SubscriptionStore.kt` (feed fetcher, Base64 parser, INCY/Happ `routing` and `autorouting` header/body decoder), `RoutingProfileStore.kt` (Proxy/Direct/Block rule groups, custom domains/IPs).
  - Capabilities: `NeoTunEngineCapabilities.kt` reflects client capabilities per protocol/engine without assuming unverified engine parity.
  - VPN Services: `NeoTunVpnService.kt` (sing-box runtime using `io.nekohasekai.libbox.CommandServer` + `NeoTunPlatform`), `NeoTunXrayVpnService.kt` (Xray runtime using `NeoTunXrayBridge`).
- `xraybridge/` (Go 1.26, cgo):
  - Integrates `github.com/xtls/libxray`.
  - Implements `androidController.ProtectFd` via CGO callback to protect socket file descriptors from Android VPN routing loops.
  - Exports `NeotunSetAssetPath`, `NeotunPrepare`, `NeotunInvoke`, `NeotunResetDNS`, `NeotunFree`, compiled into `libneotun_xray.so` for arm64-v8a, armeabi-v7a, x86_64.
- `windows/NeoTUN.Windows/` (.NET 8 WPF, C#):
  - `NativeCore.cs`: P/Invoke bridge to `neotun_core.dll`.
  - `EngineRuntime.cs`: orchestrates external child processes (`sing-box.exe`, `xray.exe`), `wintun.dll`, and internal Clash API for bandwidth monitoring.
- `installer/` (Inno Setup 6):
  - `NeoTUN-Windows.iss`: packages `NeoTUN.exe`, `neotun_core.dll`, `sing-box.exe`, `xray.exe`, `wintun.dll` into `NeoTUN-Setup-x64.exe`.
- `.github/workflows/`:
  - `android.yml`: builds Rust core (3 ABIs), Go xraybridge, builds signed release APKs, publishes GitHub releases.
  - `desktop.yml`: builds Rust core for Windows MSVC, builds .NET 8 WPF, bundles pinned sing-box (v1.14.3), Xray (v26.3.27), Wintun (0.14.1), runs smoke test, compiles installer.

## Protocol & Engine Compatibility Matrix

| Protocol | Transport / Security | Selected Engine | Core Conversion & Requirements |
| :--- | :--- | :--- | :--- |
| **VLESS** | `security=reality` (any transport) | `xray` | Requires `sni` (or `host`) and `pbk` (`publicKey`). Normalizes aliases (`serverName`, `fp`, `sid`, `spx`). Selected for Xray due to X25519MLKEM768 handshake compatibility. |
| **VLESS** | `type=xhttp` or `splithttp` | `xray` | Extracts `path`, `host`, `mode`, and preserves `xPaddingBytes` / `x_padding_bytes`. |
| **VLESS** | `type=tcp` (or `raw`), `ws`, `grpc`, `http`, `httpupgrade` (TLS / none) | `sing-box` | Generates native sing-box VLESS outbound. Preserves `flow` (`xtls-rprx-vision`), `alpn`, `allowInsecure`. |
| **Hysteria2** (`hysteria2://`, `hy2://`) | UDP / QUIC | `sing-box` | Supports `server_ports` (from `mport`, `ports` or range), `hop_interval`, `up_mbps`, `down_mbps`, `alpn` (defaults to `h3`), `pcs` (SHA-256 cert pin), `obfs` (`salamander` / `gecko`). |
| **Trojan** | TCP / WebSocket | `sing-box` | Standard sing-box Trojan outbound with TLS parameters. |
| **TUIC** | UDP / QUIC | `sing-box` | Supports `congestion_control` (default `cubic`), `udp_relay_mode` (default `native`), TLS and ALPN. |
| **VMess** | TCP, WS, gRPC, HTTP, HTTPUpgrade | `sing-box` | JSON-decoded VMess links; preserves `security`, `encryption`, `net`, `path`, `host`, `sni`. |
| **Shadowsocks** | AEAD | `sing-box` | Extracts method and password from standard share links; passes plugins to sing-box outbound. |

## Compatibility & Data Preservation Rules
- Treat imported profiles, subscriptions and routing rules as arbitrary user data. Never hardcode personal subscription URLs, UUIDs, domains, servers, DNS values, route lists or sample profiles into production behavior.
- Do not discard fields during parsing or refresh. Preserve unknown fields where possible and validate a complete update before replacing saved working data.
- A failed refresh must not erase existing profiles, routing data or the last working configuration.
- Engine behavior is specific: a feature implemented for Xray is not automatically implemented for sing-box or Hysteria2. Do not claim parity without tests.
- Keep Android and Windows behavior aligned where supported, without forcing platform-specific code into shared Rust core.
- Preserve connection lifecycle, TUN ownership, DNS, route cleanup, statistics, app exclusions, import/export, update flow and settings unless explicitly in scope.
- In Windows runtime, resolve remote endpoint IPs before activating TUN interface to prevent routing loops when chaining sing-box TUN with Xray SOCKS upstream.
- In Android runtime, ensure all network I/O and remote rule-set downloads occur on background threads; promote VPN service to foreground immediately to avoid ANR and OS kill in Android 14+.
- Do not change URI semantics or engine selection without regression tests.
- Never expose UUIDs, subscription URLs/tokens, private keys or credentials in diagnostics.

## UI Rules
- Follow the established design tokens: dark background `#080A12`, navigation `#0D101B`, surface `#131725`, raised surface `#1B2031`, brand violet `#8068FF`, brand blue `#5B68F2`, connected green `#65E7B0` on `#12362F`, error `#FFB5C3` on `#321C29`.
- Compact, aligned, responsive UI; restrained spacing (8, 12, 16, 24dp); 18dp card radius.
- Do not move controls to another edge as a substitute for fixing layout. Respect system insets and test narrow screens.
- Avoid oversized navigation, nested cards, duplicate controls and huge action menus.
- Keep server actions concise; place advanced/raw configuration in a dedicated editor/view, not the common action menu.
- A profile editor must provide protocol-aware editable fields, validate before saving, preserve unedited parameters and explain invalid values. Raw URI editing belongs in a clearly labelled advanced mode.
- UI state must reflect actual service state, not merely a button press.

## Change Protocol
1. State the symptom and evidence-backed cause (or label it a hypothesis).
2. Trace the affected data from import/storage through conversion to engine/runtime; for UI, trace state and callbacks.
3. Make the smallest complete change; avoid unrelated refactors.
4. Add regression tests, especially for parsing, subscription merge, routing conversion and engine selection.
5. Run relevant checks if available and inspect GitHub Actions. Never claim a test ran unless it did.
6. Review the diff for lost fields, hardcoded sample values, secrets and platform regressions.
7. Report commit, exact checks/results and remaining unverified behavior.

## Git and Concurrent Dual-AI Collaboration (AI Studio + ChatGPT)
- Latest `main` is the source of truth, not an AI chat or pasted old file.
- The project owner prefers direct commits to `main` and no pull requests.
- **Both ChatGPT and Google AI Studio may prepare commits or patches depending on the active task.**
- **Strict Single-Writer Rule:** Only one AI assistant applies/commits changes to a subsystem at any given time. While one tool applies a change, the other may analyze/review but must not concurrently edit the same branch or files.
- Before every write, fetch the latest file and blob SHA. If it changed, re-read and merge; never overwrite newer content using stale source.
- One logical change per commit. Keep UI, parser, routing and version changes separate.
- Record commit SHA and wait for relevant CI before starting an overlapping edit.
- Do not bump app versions for docs-only/internal changes. Android `versionCode` in `android/app/build.gradle.kts` must increase monotonically for a distributable update (which also drives the Windows installer version in `desktop.yml`).

## Handoff Template between Assistants
When handing off a task or patch between Google AI Studio and ChatGPT, use this structure:
- **Task:** Clear objective and scope.
- **Latest main commit before work:** Commit SHA.
- **Files inspected / modified:** File paths and touched components.
- **Cause / Evidence:** Root cause and rationale.
- **Patch or Commit:** Unified diff or commit SHA/link.
- **Tests & CI results:** Exact commands, workflow links, and verification status.
- **Remaining unverified behavior:** Network, device, or OS characteristics not verified locally.
- **Next owner:** Designated assistant (ChatGPT or AI Studio).

## Required Completion Report
- **Cause:** evidence or explicitly marked hypothesis.
- **Changed:** files and behavior.
- **Checks:** exact test/build commands or workflow links and actual outcomes.
- **Commit:** SHA/link, if committed.
- **Not verified:** device/server/OS behavior not tested.

A successful compile is not proof of a working tunnel or correct subscription import.
