# NeoTUN for Windows

This directory starts a native WPF desktop client and shares its design tokens with Android through `../DESIGN_SYSTEM.md`.

## Current status

Implemented in this first UI milestone:
- dark NeoTUN visual system and adaptive two-column desktop layout;
- navigation for Home, Servers, Routing, Settings and Logs;
- local profile persistence in `%LOCALAPPDATA%\NeoTUN\profiles.json`;
- paste/import of VLESS, VMess, Trojan, Hysteria2, TUIC and Shadowsocks share links, including Base64 lists;
- profile selection, search, editing, copy and delete;
- routing JSON syntax inspection and diagnostics view.

**Not yet implemented:** Windows tunnel/TUN driver integration, elevated service, sing-box/Xray runtime lifecycle, connection test and real live traffic stats. The Connect action clearly reports this limitation and does not pretend that a tunnel is active. Full Windows networking requires a separate, tested runtime integration milestone; do not distribute this UI as a working proxy client yet.

## Build

On Windows with .NET 8 SDK:

```powershell
dotnet build .\windows\NeoTUN.Windows\NeoTUN.Windows.csproj -c Release
dotnet publish .\windows\NeoTUN.Windows\NeoTUN.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o .\dist\windows
```

CI publishes the self-contained WPF UI as the `NeoTUN-Windows-x64` artifact. The Rust core is not included in this UI-only package because Windows tunnel/runtime integration is not wired yet.
