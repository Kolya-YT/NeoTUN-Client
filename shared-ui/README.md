# NeoTUN Shared UI

React + TypeScript frontend workspace intended to become the common UI for Android and Windows. This is a migration scaffold, not yet the production UI.

- Design tokens follow `NeoTUN-style` (dark surfaces, violet/blue brand, green connected state).
- The UI reads real state only through `src/nativeBridge.ts`; no simulated traffic or fake connection success is used.
- Without a native adapter, connection and editing controls remain unavailable and the UI explains why.
- Routing and settings are structured as separate sections and expose capability-aware controls.

Run `npm install`, then `npm run lint` and `npm run build`. Platform hosts are not wired yet; see `../UI_MIGRATION.md`.
