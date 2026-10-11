# NeoTUN Client UI feature parity

## Non-negotiable migration rule

The shared UI is a presentation layer, not a replacement for the network implementation. Keep the current Android Activity/services/stores and Windows WPF/ViewModel/EngineRuntime as the source of truth until each platform adapter implements and verifies the relevant command. Do not remove or bypass working native screens before parity checks pass.

Design-only features that do not exist in the current app belong in the future backlog. They must not delay parity work, and must not be represented by mock data or controls that appear to work without a native implementation.

## Existing functionality to preserve

### Android

- Real connect/disconnect flow through the existing VPN services and selected engine.
- Live session traffic and connection state on the home screen.
- Profile and subscription management: manual profiles, subscription-owned profiles, subscription refresh, periodic auto-refresh and update interval.
- Profile actions, including connection selection and import flows; preserve the existing URI parsing and subscription deduplication behavior in `ProfileStore` and `SubscriptionStore`.
- QR scanning/import and clipboard/deep-link import flows where currently supported.
- Routing profile management: import compatible JSON/INCY/Happ routing data, select/enable profiles, preserve routing data and use it when building the engine config.
- DNS, MTU, IPv6 and existing routing options applied by the native connection/configuration code.
- Ping checks and the configured ping method.
- Diagnostics, update checks, app updater, widgets/intents, and routing deep links.
- Existing preferences, migrations and stored profiles must remain intact.

### Windows

- Real connection lifecycle through the existing `EngineRuntime`, including engine startup/shutdown, TUN/Wintun setup, failure handling and cleanup.
- Live traffic counters and runtime status; diagnostics/log view and runtime log stream.
- Profile search, import from clipboard/text/subscription, select/edit/copy/delete, and subscription refresh with configured interval.
- Routing configuration: remote/domestic DNS, DNS domain list, MTU, blocked/proxied/direct domain and IP/CIDR lists, process exclusions, JSON load/validation/export, save/reset flows.
- Existing appearance preferences (theme, font scale and animations), startup/subscription settings, updater and release checks.
- Keep the current resizable, non-maximized WPF window behavior and keyboard/mouse interaction.

## Current implementation status (2026-10-11)

### Android shared UI preview

The shared React UI is bundled into Android APK builds and can be opened manually from the native Settings screen. It is not the default launch screen; Android Back returns to the native UI.

Implemented in the preview adapter:
- Read real profile/subscription state from native stores; no browser persistence.
- Select, import, rename and delete profiles, with a guard against deleting the profile actually used by the running tunnel.
- Start/stop through the existing Android VPN services.
- Read native connection status and traffic counters through the existing traffic sampler.
- Create, rename, refresh and delete subscriptions; deleting a subscription from the preview leaves imported profiles intact.
- Change the subscription refresh interval and toggle native auto-refresh.
- Serve the bundled UI through AndroidX WebViewAssetLoader on a local HTTPS origin. External navigation and file access are disabled; prompts/confirmations use native dialogs.

Still native-only and intentionally unavailable in the shared preview: routing profile editor and JSON/INCY import/export, DNS/MTU/IPv6 editing, ping, QR scan/share, per-profile config viewer, process exclusions, diagnostics/log viewer, updater, appearance/font settings, widgets, quick-settings tile and deep-link entry points. Continue using the native screens for these functions.

The adapter is an incremental preview, not proof of full parity. Device-level testing is still required for WebView behavior, subscription refresh results, real TCP/UDP traffic and connect/disconnect failure paths before the shared UI can become the default.

## Shared UI rollout order

1. **Contract and visual shell:** React/TypeScript type-check and production build pass. No mock networking state or fake counters.
2. **Android parity adapter:** map only to existing Android stores, services and updater; preserve QR/deep links, ping, routing profiles, widgets and subscription scheduling. Unsupported actions must be explicitly disabled.
3. **Windows parity adapter:** use the existing WPF-owned `EngineRuntime`, `MainViewModel`, stores and routing/update logic. Do not create a second engine runtime inside WebView2.
4. **Functional acceptance on each platform:** import/select/rename/delete profile; refresh subscription; connect and disconnect; confirm real traffic; edit/save routing and DNS/MTU settings; check errors and logs; check updater; verify no regressions to platform-specific actions.
5. **Only after parity:** make shared UI the default, while retaining a safe native fallback during rollout.

## Design-only backlog (after parity)

- Any dashboard widgets, latency/health visualizations or connection summaries not currently backed by actual platform data.
- Extra profile-management and routing conveniences that are not supported by the current stores or engine configuration.
- Cross-platform settings that do not map to a real setting on one of the platforms.
- Any new diagnostics or update UX beyond the existing updater and real runtime logs.

Every backlog item needs an explicit native capability and tested implementation before it becomes an enabled control. Platform-specific features must remain available even if they do not fit the first shared-screen layout.

## Acceptance gate

- `shared-ui` type-check and production build pass.
- Existing Android and Windows build workflows pass.
- Both native connection lifecycles remain the only source of actual connection state and traffic.
- No data loss or migration of profile/subscription/routing stores to browser storage.
- All controls are enabled only when the platform adapter advertises support.
- Existing platform-specific features listed above have a clear UI entry point before the old UI is retired.
