# NeoTUN Client — Shared Design System

This file is the source of truth for Android, Windows, and future iOS clients. Native layout can adapt to the device; visual identity and semantic meaning must stay consistent.

## Palette
- Background: #070910
- Navigation: #0C0F19
- Surface: #121521
- Raised surface: #1C2033
- Input surface: #0D111D
- Border: #2A2E44
- Primary brand violet: #8769FF
- Brand blue: #5B5BF1
- Accent surface: #23203B
- Primary text: #FFFFFF
- Secondary text: #A5ABC2
- Muted text: #8F96AD
- Connected/success: #5FE6A6 on #12372E
- Error/destructive: #FFB1C0 on #311B27

## Typography
- Brand/title: 23–28sp/px, bold.
- Section headings: 17–18sp/px, semibold.
- Body and buttons: 13–15sp/px.
- Metadata: 9–11sp/px; uppercase sparingly.

## Geometry
- Base spacing: 8 units; common gaps 8, 12, 16, 18, 24.
- Card radius: 18–25 units; controls: 12–17 units.
- Prefer layered surfaces and subtle borders over heavy shadows.
- Disconnected primary action uses violet-to-blue; connected uses green.
- Phone layouts stack vertically; desktop layouts may use columns. No overflow or clipped primary actions.

## Shared information architecture
1. Home / connection status / selected server
2. Live traffic statistics: received, sent, speed
3. Server profiles and subscriptions
4. Routing profiles and settings
5. Diagnostics/logs and app updates

## Implementation
Android tokens: `android/app/src/main/java/com/neotun/app/NeoTunDesign.kt`.
Windows token map: `windows/NeoTUN.Windows/App.xaml`.
Update both token maps and this document when visual tokens change. Platform-specific controls are fine, but don't invent platform-specific colors, labels, or status meanings.


## Functional UI contract
- Routing editor uses the same `NeoTunDesign` palette and spacing scale as the main Android screens.
- Every visible routing control must persist to the active profile and be consumed by the relevant engine adapter.
- GeoSite/GeoIP refresh runs off the main thread, validates HTTPS sources, and preserves the last working files if a download fails.
- DNS split routing is opt-in: the domestic DNS is only selected for the profile's explicit `DomesticDNSDomains` list. Do not invent a domain list or silently rewrite imported profiles.
- Hide controls whose runtime behavior is not implemented; a decorative control is a bug, not a placeholder.


## Android quick access
- Provide a compact home-screen widget with the current connection state and a single tap action.
- Provide a Quick Settings tile for quick connect/disconnect; delegate to the main activity so VPN permission and profile validation are preserved.
- Keep widget and tile status derived from the same persisted connection state as the main screen. Do not create a separate tunnel lifecycle.

## Motion
- Screen changes use a short fade and vertical settle; buttons use a subtle press scale.
- Motion should communicate state and touch feedback, not delay connecting or hide errors.
- Avoid continuous decorative animation that wastes battery or competes with live traffic values.

## Android layout rules
- Keep the home brand header compact; do not use a large marketing slogan above the connection state.
- The primary connect/disconnect action is the visual focal point and must remain reachable on narrow screens.
- Use compact, equal-height navigation targets; show the selected label and keep inactive items visually quiet.
- Server names and endpoint summaries are single-line ellipsized; never let long names expand cards horizontally.
- Keep typography and spacing consistent with the shared palette; avoid oversized page titles and stacked decorative cards.
