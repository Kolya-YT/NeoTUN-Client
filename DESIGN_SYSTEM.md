# NeoTUN Shared Design System

This document is the visual contract for Android and Windows. Use it for new UI and when incrementally aligning existing screens. The goal is a compact, calm interface inspired by the clarity of Happ: obvious connection state, one primary action, readable server details, restrained cards and consistent navigation. Do not copy Happ branding or assets.

## Principles

1. **Connection comes first.** The current profile, real service state and primary connect/disconnect action dominate the home screen.
2. **One source of truth.** Connection labels and colors reflect actual engine/service state, not just a button press. Starting, stopping, connected and failed are distinct.
3. **Compact and responsive.** Avoid oversized empty hero areas, giant circular controls, redundant action rows and nested cards. Prefer clear hierarchy, modest spacing and useful density.
4. **Parity across platforms.** Android and Windows share colors, typography intent, radius/spacing scale, connection semantics and terminology. Layouts may respect each platform's conventions.
5. **Data-safe UI.** Do not change profile parsing, subscriptions, routing, engine selection, persistence, or connection lifecycle as part of visual work.
6. **Accessible by default.** Maintain readable contrast, scalable text, clear touch/click targets, keyboard focus, labels for icon-only actions and visible error text.
7. **Show, don't imply.** Do not label a tunnel connected until the service reports it active. A successful build is not proof of network behavior.

## Visual tokens

| Token | Value | Use |
|---|---|---|
| Background | `#0B0815` | Main window/screen |
| Navigation | `#120D20` | Top or bottom navigation |
| Surface | `#191226` | Primary cards and sheets |
| Raised surface | `#251A36` | Nested/selected rows, menus |
| Input surface | `#120C1E` | Text fields and selectors |
| Border | `#38284F` | Standard separators |
| Accent border | `#7851B8` | Focus/selected outline |
| Primary violet | `#8B5CF6` | Main actions and brand accent |
| Light violet | `#C4B5FD` | Accent text on dark surfaces |
| Blue | `#6D8DFF` | Gradient secondary color |
| Primary text | `#F7F8FF` | Headings and key values |
| Secondary text | `#B1B8CD` | Descriptions and metadata |
| Muted text | `#858EA8` | Hints and inactive labels |
| Connected | `#65E7B0` | Online state only |
| Connected surface | `#12362F` | Subtle connected backdrop |
| Error | `#FFB5C3` | Error text and indicators |
| Error surface | `#321C29` | Error panels |

Android already supports dark, light and OLED themes. Use the token provider for themed UI rather than hardcoding colors in screen layouts. Windows token resources should keep the dark defaults aligned with these values; any platform-specific overrides must preserve semantic meaning and adequate contrast.

## Spacing, sizing and shape

Use a 4 px/dp base rhythm: 4, 8, 12, 16, 20, 24 and 32. Typical outer screen padding is 16–20; spacing between related controls is 8–12; separate sections by 16–24. Prefer consistent radii: 10–12 for controls, 14–16 for list items, and 18–20 for main cards. Use 24 only for a dominant connection surface or modal. Borders are normally 1 px/dp and should be subtle.

Buttons should normally have a 44–48 dp touch target on Android and at least 36–40 px on Windows. On narrow Android displays, text wraps or truncates deliberately; no horizontal overflow or controls hidden beneath system bars. On Windows, allow the layout to contract and scroll instead of requiring users to resize the window just to access common controls.

## Typography

Use the platform system sans-serif. Keep the hierarchy restrained:
- Screen title: 22–26 sp/px, semibold or bold.
- Main connection state / server name: 18–24, semibold or bold.
- Body and action text: 13–15.
- Metadata and captions: 11–12, while keeping important states and values readable.
- Tiny uppercase section labels are optional and should not be used for explanatory copy.

Avoid mixing emoji as the main icon system. Prefer consistent vector/platform icons where available; use descriptive accessibility labels for icon-only buttons.

## Connection home screen

Recommended order:
1. Compact brand row and settings/import shortcuts.
2. One primary connection panel with a small status indicator, a clear current state, selected profile/endpoint and primary action.
3. Compact live statistics with separate upload/download values; label units and avoid repeating the same statistic in multiple places.
4. A concise selected-server row and an entry to all profiles.
5. Only high-value shortcuts; diagnostics and advanced options belong below or on their own screen.

Connected state uses the green semantic token. Starting uses violet/blue. Disconnecting uses a neutral/warning treatment and disables conflicting actions. Errors use the error surface and display a useful, clipped message with a path to diagnostics. Never show a hardcoded “driver ready” or online label unless it is actually verified.

## Servers and subscriptions

Make the selected profile obvious without duplicating the same selection in multiple large cards. Each row should prioritize profile name, protocol/engine and endpoint; latency and status are secondary. Keep import, add, refresh, QR and subscription actions discoverable. Destructive actions need confirmation. Common actions stay concise; raw configuration belongs in a clearly labelled advanced view.

## Settings, routing and diagnostics

Group settings by topic with short descriptions. Preserve platform-specific settings and all existing values. Routing rules should show order and action (Proxy/Direct/Block) clearly, while making the active profile visible. Diagnostics should be useful and bounded; never display UUIDs, subscription URLs/tokens, passwords or private keys.

## Navigation and interaction

Use one consistent navigation pattern per platform: Android bottom navigation and Windows top/side navigation are both acceptable. Highlight only the current destination. The navigation must not compete visually with the connection action. Maintain Android system insets, Windows keyboard focus, Escape/back behavior, and scroll position where practical. Animations should be subtle, brief, and nonessential; respect the existing animation preference.

## Implementation checklist

Before changing a screen:
- Read `AGENTS.md` and `AI_WORKFLOW.md`, then inspect current source and callers.
- Fetch the latest `main` and relevant file SHAs immediately before editing.
- Keep one logical visual change per commit and preserve all non-visual behavior.
- Check narrow Android widths, system insets, text scaling, light/dark/OLED themes and connected/error states where applicable.
- Keep Windows bindings, click handlers, data context and WPF resource keys intact.
- Run the corresponding GitHub Actions workflow and review any failure logs.
- Report what CI confirms separately from behavior not tested on a real device/PC.
