# NeoTUN for Windows

This directory starts a native WPF desktop client and shares its design tokens with Android through `../DESIGN_SYSTEM.md`.

## Current status

Implemented in the current Windows milestone:
- dark NeoTUN visual system and adaptive two-column desktop layout;
- navigation for Home, Servers, Routing, Settings and Logs;
- local profile persistence in `%LOCALAPPDATA%\NeoTUN\profiles.json`;
- paste/import of VLESS, VMess, Trojan, Hysteria2, TUIC and Shadowsocks share links, including Base64 lists;
- profile selection, search, editing, copy and delete;
- routing JSON syntax inspection and diagnostics view.

Implemented in code but still requiring device-level validation: Windows TUN via sing-box, Rust native profile parsing through a C ABI, sing-box/Xray process lifecycle, config validation, process logs, and Xray + local SOCKS chaining for VLESS XHTTP. The app requests administrator elevation because TUN routing changes system routes. This is an early runtime milestone, not yet a production-validated client: validate TCP, UDP, DNS, route cleanup on disconnect, sleep/resume, and every supported transport on a real Windows machine before recommending it for daily use.

## Build

On Windows with .NET 8 SDK:

```powershell
dotnet build .\windows\NeoTUN.Windows\NeoTUN.Windows.csproj -c Release
dotnet publish .\windows\NeoTUN.Windows\NeoTUN.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o .\dist\windows
```

CI publishes the self-contained WPF UI as the `NeoTUN-Windows-x64` artifact. The Rust core is not included in this UI-only package because Windows tunnel/runtime integration is not wired yet.
