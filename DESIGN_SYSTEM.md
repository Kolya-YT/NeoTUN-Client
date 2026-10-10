# NeoTUN Client — Shared Design System

This is the source of truth for visual decisions across Android, Windows, and future iOS clients. Native controls may differ where the platform requires it, but colors, typography hierarchy, spacing, shapes, interaction states, and product terminology must remain consistent.

## Brand

- Product name: NeoTUN
- Tone: calm, technical, minimal, friendly; never imitate Happ/INCY layouts directly.
- Default appearance: dark.
- Primary brand accent: violet-to-blue gradient.
- Positive/connected state: green. Green is a status color, not the primary brand color.

## Color tokens

| Token | Value | Usage |
|---|---|---|
| background | #070910 | Main app canvas |
| navigation | #0C0F19 | Bottom/side navigation |
| surface | #121521 | Cards and grouped content |
| surface-raised | #1C2033 | Selected rows, raised surfaces |
| surface-input | #0D111D | Inputs and editable areas |
| border | #2A2E44 | Subtle outlines and dividers |
| brand-violet | #8769FF | Primary accent and focus |
| brand-blue | #5B5BF1 | Gradient endpoint / emphasis |
| brand-soft | #23203B | Tinted accent surface |
| text-primary | #FFFFFF | Primary content |
| text-secondary | #A5ABC2 | Supporting content |
| text-muted | #8F96AD | Metadata and hints |
| success | #5FE6A6 | Connected / healthy |
| success-surface | #12372E | Positive state backgrounds |
| danger | #FFB1C0 | Errors and destructive feedback |
| danger-surface | #311B27 | Error backgrounds |

## Typography

- Brand/title: 23–28sp/px, semibold or bold.
- Main section headings: 17–18sp/px, semibold.
- Body and buttons: 13–15sp/px.
- Labels, protocol and status metadata: 9–11sp/px; use uppercase sparingly.
- Keep text readable and avoid truncating important state/error messages.

## Layout and shape

- Use 8dp/px base spacing; common gaps 8, 12, 16, 18, 24.
- Card radius: 18–25dp/px; controls: 12–17dp/px.
- Prefer layered dark surfaces and thin borders over heavy shadows.
- Primary connect action uses the violet → blue gradient while disconnected; connected state uses green.
- Responsive layouts must reflow instead of overflowing: phone layouts stack, desktop layouts use wider columns.
- Keep safe-area/system insets on mobile and minimum window sizing on desktop.

## Shared screens and behavior

All platforms should use the same information architecture and labels:
1. Home / connection status and selected server
2. Live traffic statistics (received, sent, speed)
3. Server profiles and subscriptions
4. Routing profiles and settings
5. Diagnostics/logs and app update

The connection state, selected profile, traffic metrics, import/refresh status, and errors must have equivalent meaning on every platform. A platform-specific feature may be arranged natively, but must not invent a different visual language.

## Implementation rule

Keep these tokens centralized per platform and reference them rather than scattering literal colors through view code. When changing the design, update this file and all platform token maps in the same change. A shared design system does not mean forcing one UI toolkit on every OS; it means a consistent design contract with native rendering.
