# NeoTUN

Native-first cross-platform client architecture.

## Current target

Android-first prototype using:

- Rust core
- Kotlin Android UI
- Android VpnService/TUN integration
- modular core adapters for Xray, sing-box, Amnezia and future engines

The first APK is a foundation build. It establishes the Rust ↔ Android bridge and TUN service; protocol engines will be integrated incrementally.

## Planned engines

Xray, sing-box, Amnezia, WireGuard, OpenVPN and OpenFlux adapters.

## Build

GitHub Actions builds the debug APK and publishes it as a workflow artifact.
