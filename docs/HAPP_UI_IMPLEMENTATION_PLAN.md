# Happ UI audit and NeoTUN implementation plan

**Status:** static APK analysis completed; Android home-screen restructuring is underway.  
**Reference:** user-provided Happ APK (65.7 MB) and the user's home-screen screenshot.  
**Scope:** use the interaction structure and visual hierarchy as inspiration. Do not copy Happ branding, proprietary artwork, or source code.

## Findings

### What was inspected

- Confirmed the APK is a valid ZIP-based Android package with DEX bytecode, Android resources, native libraries and app assets.
- Inspected DEX string tables and resource inventory for feature names and UI concepts.
- The APK was not installed or executed in an Android runtime, and its bytecode was not fully decompiled. Therefore, findings below distinguish observed UI evidence from static hints; they are not claims of complete reverse engineering.

### Home screen structure visible in the screenshot

1. **Top action row:** settings at the left and add/import at the right; no permanent bottom navigation.
2. **Connection focal point:** large circular power control, connection state and elapsed connection time.
3. **Subscription list in the same scrollable screen:** each subscription has a collapsible header, subscription name, update/ping/actions controls and server count/metadata.
4. **Subscription information row:** traffic/expiry/support shortcuts when those values are provided by the subscription/provider.
5. **Server rows:** country flag, readable server name, transport/protocol metadata, latency and selected-row highlight.
6. **Multiple subscriptions:** shown one after another vertically; the list itself is the primary way to navigate servers.
7. **Visual language:** deep blue/purple background, layered violet connection control, translucent-looking surfaces, subtle separators, compact metadata and strong contrast for server names.

### Static APK clues

The APK's string tables include names relating to subscription refresh and properties, ping-on-open, server sorting, expiry, support URL, traffic usage, subscription actions, settings, routing, DNS and profile/server management. These strings confirm that these are concepts present in the package, but do not prove their exact placement or behavior without runtime inspection.

## Target NeoTUN experience

- One Android home screen containing the connection control and all subscription/server groups.
- Settings opened by the gear; import/add opened by plus.
- No persistent bottom navigation bar.
- No mandatory separate “Servers” destination. Existing profile-management operations remain available through compact contextual actions and dedicated editors/dialogs where appropriate.
- A selected profile is visibly marked; tapping a server selects it, and when a tunnel is active the existing safe switch/teardown flow is used.
- Subscription headers can collapse/expand; refresh, ping and overflow actions remain accessible.
- Connection status and duration must be based on real service state and an actual session start timestamp, not a visual-only timer.
- Traffic, expiry and support fields are displayed only when the app has reliable data for them; never invent values.
- Preserve the existing profile/subscription store, imports, routing rules, engine choice, DNS, TUN lifecycle, diagnostics and updater.

## Implementation sequence

### Phase 1 — Navigation and home-screen composition
- [x] Remove the permanent bottom navigation from the Android shell (commit `57ed77e`; CI pending).
- [x] Keep settings reachable from the top gear and profile import reachable from the plus (commit `57ed77e`; CI pending).
- [x] Render subscription groups and server rows directly on Home, with collapsible groups, refresh/ping/actions and selected-profile switching (commit `57ed77e`; CI pending).
- [ ] Finish removing remaining navigation paths that can open the legacy separate profile screen; preserve profile-management operations through contextual actions and settings.
- [ ] Preserve system insets, scrolling, narrow-screen layout, large fonts and minimum touch targets.

### Phase 2 — Connection panel
- [x] Remove live download/upload speed metrics from the Android Home screen; live speed remains in the foreground notification (commit `9c15149`).
- [ ] Rework the remaining visual hierarchy around one prominent connection control.
- [ ] Add a reliable connected-duration display only after identifying/persisting the true session start time.
- [ ] Keep distinct connected, connecting, disconnecting and failed states; never infer connected state from a button tap.
- [ ] Preserve live traffic stats and diagnostic error access.

### Phase 3 — Subscription groups and server rows
- [ ] Compact subscription headers and provide clear expand/collapse state.
- [ ] Retain refresh, ping and subscription actions without overcrowding the header on narrow devices.
- [x] Refresh the visible Home server rows when a ping starts and completes (commit `ecb24d9`; Android/Windows CI pending).
- [ ] Improve server name/protocol/latency alignment and selected-row indication.
- [ ] Preserve sorting, rename/delete/pin/configuration actions and safe profile switching.
- [ ] Show subscription usage/expiry/support details only when backed by known metadata or provider responses.

### Phase 4 — Settings and secondary flows
- [ ] Audit common settings against Happ's interaction patterns: compact label/value rows, contextual bottom sheets, switches/selectors, and consistent confirmation feedback. Keep NeoTUN-specific settings and existing storage keys.
- [ ] Verify ping modes against the Happ APK beyond static strings before claiming exact behavioral parity. Current Android NeoTUN modes are TCP socket connect, ICMP reachability, and Xray `pingBatch` to `https://cp.cloudflare.com/` using GET/HEAD; Windows currently implements TCP handshake only. These are not yet a verified reproduction of Happ's implementation.
- [ ] Open settings from the gear without a bottom navigation item.
- [ ] Audit import, profile editor, routing, DNS, exclusions, diagnostics, update and reset flows for reachable entry points.
- [ ] Keep advanced/raw config separate from the common server action menu.

### Phase 5 — Windows alignment
- [ ] After Android structure is stable, align Windows visual tokens and hierarchy with the same model.
- [ ] Keep Windows platform conventions and keyboard accessibility; do not force a phone layout onto desktop.

### Phase 6 — Verification and documentation
- [ ] Run Android APK and Windows Desktop CI after each logical change.
- [x] Confirm CI for speed-metric removal commit `9c15149`: Android run `38788784526` and Windows run `38788784521` succeeded.
- [ ] Test narrow Android screens, large font settings, collapsed/expanded subscriptions, empty state, multiple subscriptions, long names, and selected profile.
- [ ] On a real Android device, verify connect/disconnect/reconnect, Xray↔sing-box switching, traffic, DNS and routing; CI alone does not establish these.
- [ ] Update this plan and the parity audit with exact commits and actual CI outcomes.

## Safety and collaboration constraints

- Work on the latest `main`; fetch the current target file and SHA immediately before every write.
- One logical change per commit; do not edit overlapping files concurrently.
- UI work must not alter engine selection, URI semantics, subscription parsing, routing conversion, TUN ownership or service lifecycle.
- VLESS TCP must continue to use Xray where the current engine-selection logic requires it.
- Do not reintroduce the removed DPI packet-shaping code.
- Do not claim that the APK was run or fully decompiled; current analysis was static.

## Deeper static APK review — component and screen inventory

The APK contains three DEX files. Static strings from the app-specific DEX expose a richer set of UI components and state than the screenshot alone. This is a static inventory, not proof that every item is visible in every version or screen.

### Reusable visual components identified

- `HappBottomSheetDialog`, `HappBottomSheetShape`, and a drag-handle style: bottom sheets are a core interaction pattern for contextual tasks.
- `HappInfoField`, `HappInputField`, and `HappForwardField`: settings/profile forms appear to use consistent label/value/help rows rather than unrelated one-off controls.
- `HappToggleField`, `HappSwitch`, `HappSpinnerField`, `HappSliderField`, and `HappCheckbox`: switches, selectors, sliders and checkboxes are part of a shared component language.
- `HappSettingsDivider`: settings use dedicated separators instead of placing every option in a separate large card.
- `HappSnackbarAction`: short confirmation/action feedback is part of the component set.
- Custom button/text button and debounced image button types suggest repeated-action protection and consistent action affordances.

### Screens and interaction states indicated by app-specific strings

- **Main/server selection:** selected server/profile state, profile dragging/reordering, profile naming, selected-server timestamp and least-ping sorting.
- **Profile actions:** create profile, create profile from subscription, edit/name profile, delete, export selected profile, import from clipboard, custom config import result and profile copy.
- **Subscription management:** subscription edit sheet, subscription sync dialog, refresh/update actions, import into a subscription, and hiding/closing the subscription-selection bottom sheet.
- **Connection:** explicit connect/disconnect commands, including variants that operate without showing UI; connection/error states and ping lifecycle.
- **Routing:** routing-enabled state, routing JSON and invalid-routing feedback.
- **Settings:** automatic update enablement and interval, update notifications, DNS-related options, exclusions, light/dark themes and other preference state.
- **Diagnostics/operations:** separate access/error/subscription logs, update state and service-related messages.

These names are evidence that the APK contains code paths or state for those concepts. They do not reveal every layout dimension, exact color value or exact sequence of interactions; those require runtime screenshots or a complete decompilation.

### Design implications for NeoTUN

1. **Use sheets for contextual operations.** Server actions, subscription actions, import choices and profile naming should open compact bottom sheets/dialogs instead of sending the user to a new top-level destination for every action.
2. **Use one settings grammar.** Build on the existing settings rows but normalize spacing, dividers, labels, helper text, switches, selectors and slider controls so settings feel like one coherent system.
3. **Keep the home list as the primary navigation.** Profiles should be selectable/reorderable in the home list. Advanced editing can remain a dedicated screen or sheet; it must still be reachable without a bottom navigation bar.
4. **Show actions progressively.** Main rows should stay compact. Less frequent actions belong in an overflow sheet; destructive actions require confirmation.
5. **Keep states explicit.** Selected, connecting, connected, disconnecting, pinging, refresh-in-progress and error states must be visually distinct.
6. **Respect data provenance.** Happ's traffic/expiry/support information is not a reason to fabricate those fields in NeoTUN. Display only values available from the provider or app's own reliable state.
7. **Accessibility and responsive behavior are part of the design.** Preserve 44–48dp touch targets, readable text at larger font scales, long-name truncation, clear labels and narrow-screen layouts.

### Full implementation checklist

- [ ] Home header and connection focal point.
- [ ] Home-embedded subscriptions and server selection.
- [ ] Subscription collapse/expand, refresh feedback and action sheet.
- [ ] Server row selected/ping/loading states, reordering and sorting affordances.
- [ ] Server action sheet: edit, rename, QR, export/copy, pin, delete.
- [ ] Subscription action sheet: rename/edit URL where supported, refresh, copy/share URL where safe, delete with confirmation.
- [ ] Import sheet for clipboard, URI, QR and subscription paths.
- [ ] Profile editor: protocol-aware fields, validation, advanced/raw mode kept separate.
- [ ] Settings component consistency: switches, selectors, sliders, helper copy and separators.
- [ ] Routing editor and DNS/MTU/IPv6/exclusion settings remain reachable and behavior-preserving.
- [ ] Diagnostics, app update, about/version and reset confirmation.
- [ ] Empty, loading, error, no-ping and offline states.
- [ ] Android large-font/narrow-screen/accessibility checks.
- [ ] Windows desktop parity after Android flows stabilize.
