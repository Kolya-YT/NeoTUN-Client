# NeoTUN Client — Design System

A compact dark interface for a fast, dependable connection client. Keep the violet-to-blue identity, use green for an active connection, and reserve red for errors.

## Visual tokens
- Background #080A12; navigation #0D101B; surface #131725; raised surface #1B2031.
- Input #0E1220; border #292F45; accent border #49416F.
- Brand violet #8068FF; brand blue #5B68F2; selected navigation #211D38.
- Primary text #F7F8FF; secondary #B1B8CD; muted #858EA8.
- Connected #65E7B0 on #12362F; error #FFB5C3 on #321C29.

## Layout and typography
- Use an 8dp spacing rhythm; common gaps are 8, 12, 16 and 24dp.
- Cards use 18dp corners and a subtle border; avoid heavy shadows and nested cards.
- Keep screen headings compact and the primary action visible without scrolling on common phone sizes.
- Server names and endpoint details remain single-line ellipsized.
- Settings rows use a clear title, short explanation and concise current value.
- Bottom navigation uses three equal targets and respects Android system insets.
- Narrow screens must not horizontally overflow or clip primary actions.

## Motion and feedback
- Screen changes use a short fade and vertical settle; buttons provide restrained press feedback.
- Never delay connection state changes with animation.
- Live statistics update in place rather than rebuilding the screen.

## Information architecture
1. Home: connection state, primary action, session traffic and selected server.
2. Servers: profiles and subscriptions, selection, ping and profile actions.
3. Settings: routing, DNS, MTU, IPv6, subscriptions, diagnostics and updates.

## Functional contract
- UI reflects the actual VPN service state; a button press alone is not proof of connection.
- Widget and Quick Settings tile delegate to the same connection lifecycle.
- Hide controls whose runtime behavior is not implemented.
- Routing controls persist to the active profile and are consumed by the engine adapter.
- DNS, MTU and IPv6 changes communicate when reconnecting is required.
- Keep Android tokens in `NeoTunDesign.kt` and Windows equivalents in `windows/NeoTUN.Windows/App.xaml` aligned.
