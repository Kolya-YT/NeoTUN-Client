# NeoTUN Shared UI

React + TypeScript frontend intended to become the common UI for Android and Windows. This remains a migration scaffold, not the production interface.

## Rules for the migration

- Preserve the current Android and Windows UI as the functional fallback until platform parity is proven.
- Do not replace Android VPN services, profile/subscription/routing stores, or Windows `EngineRuntime` and `MainViewModel`.
- Connection state and traffic must come from the native host. No simulated traffic, fake ping, or pretend connection success.
- Controls are enabled only when the native adapter advertises the corresponding capability.
- Features from the design that do not exist in the current app stay in the future backlog.

## Existing functionality that must not disappear

The bridge contract explicitly tracks native-only features while the UI catches up: profile ping and QR actions, config viewing, subscription CRUD and scheduling, routing-profile import/export/validation, process exclusions, appearance and ping-method settings, runtime logs, widgets, quick-settings tile, profile grouping, clipboard and deep-link import.

The presence of a feature in `NativeFeature` or `NativeCommand` is a contract declaration only. It does **not** mean the feature is already implemented in the WebView or platform adapter. An adapter must implement and test each command before enabling the UI action.

## Development

Run `npm install`, then `npm run lint` and `npm run build`.

Platform hosts are not wired yet. Read `../UI_MIGRATION.md` and `../UI_FEATURE_PARITY.md` before changing the launch UI or adding platform integration.
