# NeoTUN Client

Cross-platform, native-first network client built around a shared **Rust core**.

> 🚧 **Current status:** Android-first development. The repository currently contains the Rust/JNI foundation and Android TUN/VpnService layer. It is **not yet a production-ready client** and protocol connections are not implemented yet.

## Goals

NeoTUN is being designed as one client with a shared core and platform-specific networking adapters.

### Platforms

| Platform | Status | Planned networking layer |
|---|---|---|
| Android | 🟡 In development | `VpnService` / TUN |
| Windows | ⚪ Planned | Wintun |
| iOS | ⚪ Planned | Network Extension |
| macOS | ⚪ Planned | Network Extension |
| Linux | ⚪ Planned | TUN |

Android is the first target so the core and profile model can be tested before adding desktop/mobile platforms.

## Architecture

```text
┌──────────────────────────────────────────────┐
│                  NeoTUN UI                   │
│        Android / Windows / iOS / ...         │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│               NeoTUN Core — Rust             │
│                                              │
│  Profiles • Parser • Routing • State • API  │
└───────────────┬──────────────┬───────────────┘
                │              │
        ┌───────▼──────┐ ┌────▼─────────────┐
        │ Engine layer │ │ Platform adapter │
        │              │ │                  │
        │ sing-box     │ │ Android VpnService│
        │ Xray         │ │ Windows/Wintun   │
        │ WireGuard    │ │ iOS NetworkExt.  │
        │ AmneziaWG    │ │ Linux TUN        │
        │ OpenVPN      │ │                  │
        └──────────────┘ └──────────────────┘
```

The important separation is that the **Rust core owns profiles, configuration, state and orchestration**, while mature protocol engines and platform networking APIs handle the low-level transport work.

## Protocol roadmap

The target is broad protocol compatibility, but protocols are **not claimed as working until their engine and end-to-end traffic path are implemented and tested**.

### Planned / target support

- VLESS
- VMess
- Trojan
- Hysteria2
- TUIC
- Shadowsocks
- WireGuard
- AmneziaWG
- OpenVPN
- OpenFlux
- Additional formats supported by the selected engines

### Engine strategy

The project is intentionally not trying to reimplement every protocol from scratch in Rust.

The planned approach is to integrate proven native engines where appropriate:

- **sing-box** — primary engine for a large set of modern protocols.
- **Xray-core** — compatibility path for Xray-based configurations.
- **WireGuard / AmneziaWG** — native/platform integration where appropriate.
- **OpenVPN** — dedicated native integration.
- **OpenFlux** — separate adapter/engine integration once its runtime requirements are finalized.

This keeps the application fast and maintainable while avoiding duplicated protocol implementations.

## Configuration formats

The profile layer will provide one normalized internal model and parsers for common inputs:

```text
vless://
vmess://
trojan://
ss://
hysteria2://
tuic://
WireGuard configuration
Amnezia configuration
Subscription URLs
QR codes
```

The intended flow is:

```text
Link / QR / File / Subscription
              ↓
        Profile parser
              ↓
     Normalized Rust profile
              ↓
       Selected engine
              ↓
          TUN adapter
```

## Android foundation

The current Android application already contains:

- Kotlin Android application shell
- Rust native library
- Rust ↔ Kotlin JNI bridge
- Android `VpnService`
- TUN interface creation
- ARM64 Android build target
- ARMv7 Android build target
- x86_64 Android build target
- GitHub Actions APK build

### Important

The current `VpnService` establishes a TUN interface, but it **does not yet forward traffic through VLESS, sing-box, Xray or another protocol engine**.

Therefore the current build should be treated as an architectural foundation, not as a finished working client.

## Repository structure

```text
NeoTUN-Client/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/neotun/app/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── NeoTunCore.kt
│   │   │   │   └── NeoTunVpnService.kt
│   │   │   └── res/
│   │   ├── build.gradle.kts
│   │   └── ...
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── core/
│   ├── src/lib.rs
│   └── Cargo.toml
├── .github/workflows/
│   └── android.yml
├── Cargo.toml
└── README.md
```

## Build locally

### Requirements

- JDK 17
- Android SDK
- Android SDK Platform 35
- Rust stable
- Android Rust targets
- `cargo-ndk`
- Gradle 8.10.2

### Rust core

Install the Android targets:

```bash
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add x86_64-linux-android
```

Install cargo-ndk:

```bash
cargo install cargo-ndk --locked
```

Build native libraries:

```bash
cd core

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release
```

Build the APK:

```bash
cd ../android
gradle assembleDebug --no-daemon
```

APK output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

Every push runs the Android build workflow.

The workflow:

1. Checks out the repository.
2. Installs JDK 17.
3. Installs Gradle 8.10.2.
4. Installs the Rust Android targets.
5. Installs `cargo-ndk`.
6. Builds the Rust library for ARM64, ARMv7 and x86_64.
7. Builds the Android debug APK.
8. Publishes `NeoTUN-debug` as a GitHub Actions artifact.

The workflow uses the Android SDK already provided by the GitHub runner and does not install the removed legacy `tools` SDK package.

## Development roadmap

### Phase 1 — Foundation
- [x] Rust workspace
- [x] JNI bridge
- [x] Android project
- [x] Android VpnService/TUN foundation
- [x] Multi-ABI Rust build
- [x] GitHub Actions APK build

### Phase 2 — Real connection
- [ ] Unified profile model
- [ ] `vless://` parser
- [ ] Subscription parser
- [ ] QR import
- [ ] First real engine integration
- [ ] TUN → engine traffic forwarding
- [ ] Connect/disconnect state
- [ ] Connection logs
- [ ] Traffic statistics

### Phase 3 — Protocol coverage
- [ ] sing-box integration
- [ ] Xray integration
- [ ] Hysteria2
- [ ] TUIC
- [ ] Shadowsocks
- [ ] VMess
- [ ] Trojan
- [ ] WireGuard
- [ ] AmneziaWG
- [ ] OpenVPN
- [ ] OpenFlux

### Phase 4 — Client features
- [ ] Server/profile list
- [ ] Subscription auto-update
- [ ] Ping/latency
- [ ] Upload/download statistics
- [ ] DNS configuration
- [ ] Routing rules
- [ ] Split tunneling
- [ ] Kill switch
- [ ] Background connection
- [ ] Notifications
- [ ] QR scanner
- [ ] Import/export

### Phase 5 — Cross-platform
- [ ] Windows + Wintun
- [ ] iOS + Network Extension
- [ ] macOS + Network Extension
- [ ] Linux + TUN

## Design principles

- **Rust-first:** shared logic and orchestration live in Rust.
- **Native performance:** avoid unnecessary managed-language overhead in the networking path.
- **Engine reuse:** use mature protocol implementations instead of rewriting complex cryptographic/networking stacks.
- **Platform abstraction:** Android-specific networking stays outside the shared core.
- **One profile model:** different protocol formats should map into a common internal representation.
- **Explicit support status:** a protocol is marked supported only after real end-to-end testing.
- **Security first:** credentials, keys and private configuration data must be handled carefully and never logged accidentally.

## Current version

```text
NeoTUN Core 0.1.0
Android app 0.1.0
Status: development foundation
```

## License

See [LICENSE](LICENSE).
