# Shared React UI migration

## Goal

Use React + TypeScript as the shared presentation layer for Android and Windows, based on the design system in [NeoTUN-style](https://github.com/Kolya-YT/NeoTUN-style). Keep platform-specific networking, permissions, storage and lifecycle in the existing native layers.

## Migration rules

- Do not replace the production UI until the native adapter covers every visible action.
- Never use prototype/mock connection status, traffic, ping, GeoIP or logs in the shipped client.
- The UI must render connection state and counters reported by the native service, not infer success from a button press.
- Keep profiles, subscriptions, routing rules and settings in the existing native stores during migration; do not silently move the source of truth to browser localStorage.
- If an operation is unsupported on a platform or engine, show that state explicitly and do not pretend the setting was applied.
- Keep all production UI work on `main`, as requested, and run Android and Windows CI after each integration stage.

## Shared UI/native bridge contract

Use versioned JSON messages with a request id. The UI sends commands and the host responds with a result or error. The host also publishes state snapshots/events.

### Initial state and events

- `app.getState` — platform, app version, connection status, active profile id, profiles, settings and capabilities.
- `connection.stateChanged` — authoritative `disconnected | connecting | connected | error` state and a user-safe error message.
- `traffic.updated` — received/sent bytes and current rates from real platform counters.
- `diagnostics.updated` — real engine/service logs, with credentials and private profile data redacted.

### Commands

- `profiles.list`, `profiles.import`, `profiles.select`, `profiles.update`, `profiles.delete`.
- `connection.connect`, `connection.disconnect`.
- `subscriptions.refresh`.
- `routing.get`, `routing.save`, `routing.validate`.
- `settings.get`, `settings.save`.
- `diagnostics.run`, `diagnostics.clear`.
- `app.checkUpdate`, `app.installUpdate`.

Every command must return a typed success/error response. Destructive operations require confirmation in the UI. Changes that only take effect after reconnect must say so before saving.

## Platform adapters

### Android

Use a local bundled WebView UI with a narrowly scoped JavaScript bridge. Delegate profile and routing operations to `ProfileStore` and `RoutingProfileStore`; delegate connection lifecycle to the existing VPN services. Do not expose arbitrary intents, filesystem access or generic command execution to JavaScript.

### Windows

Host the same bundled UI in WebView2. Use an explicit allowlist of message types and validate payloads before dispatching to existing .NET/native components. Keep the WPF window lifecycle, updater and networking integration native.

## Rollout stages

1. Align and validate the React screens, particularly routing and settings; remove unsupported or misleading controls.
2. Add shared message types and native-adapter interfaces, while leaving the native UI as the production fallback.
3. Implement and test Android bridge coverage.
4. Implement and test Windows bridge coverage, including connection lifecycle and TUN/Wintun status.
5. Build both platforms, verify all visible controls, and only then enable the shared UI by default.

## Acceptance criteria

- React/TypeScript typecheck and production build pass.
- Android APK and Windows EXE/installer builds pass.
- Import/edit/delete profile, connect/disconnect, subscriptions, routing, DNS, settings, diagnostics and updates are verified against real native state.
- No horizontal overflow on narrow Android screens; Windows window opens at a sensible non-maximized size.
- No mocked network data or no-op buttons in production builds.
