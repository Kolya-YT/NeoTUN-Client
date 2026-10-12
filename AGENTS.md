# NeoTUN-Client — instructions for AI coding assistants

These rules apply to ChatGPT, Google AI Studio/Gemini, and any other assistant editing this repository.

## Read before editing
1. Read `AI_WORKFLOW.md`.
2. Read `README.md` for confirmed capabilities and known limitations.
3. Read `DESIGN_SYSTEM.md` before changing UI.
4. Inspect current source and callers; do not rely on older chat snippets.
5. Check the latest commit on `main` and relevant GitHub Actions results.

## Architecture
- `core/`: shared Rust parsing, profile normalization, engine selection, configuration generation and native bridge.
- `android/`: Kotlin UI, profile/subscription persistence, routing settings, VPN services and lifecycle.
- `xraybridge/`: Go/cgo bridge and bundled Xray engine for Android.
- `windows/NeoTUN.Windows/`: Windows WPF client and platform integration.
- `installer/`: Windows installer assets and packaging.
- `.github/workflows/`: CI, platform builds and release publication.
- `DESIGN_SYSTEM.md`: shared visual contract.
Inspect actual code; this map is not a substitute for source review.

## Compatibility rules
- Treat imported profiles, subscriptions and routing rules as arbitrary user data. Never hardcode personal subscription URLs, UUIDs, domains, servers, DNS values, route lists or sample profiles into production behavior.
- Do not discard fields during parsing or refresh. Preserve unknown fields where possible and validate a complete update before replacing saved working data.
- A failed refresh must not erase existing profiles, routing data or the last working configuration.
- Engine behavior is specific: a feature implemented for Xray is not automatically implemented for sing-box or Hysteria2. Do not claim parity without tests.
- Keep Android and Windows behavior aligned where supported, without forcing platform-specific code into shared Rust core.
- Preserve connection lifecycle, TUN ownership, DNS, route cleanup, statistics, app exclusions, import/export, update flow and settings unless explicitly in scope.
- Do not change URI semantics or engine selection without regression tests.
- Never expose UUIDs, subscription URLs/tokens, private keys or credentials in diagnostics.

## UI rules
- Follow `DESIGN_SYSTEM.md`: compact, aligned, responsive UI; restrained spacing; violet/blue accents; green connected state; red errors.
- Do not move controls to another edge as a substitute for fixing layout. Respect system insets and test narrow screens.
- Avoid oversized navigation, nested cards, duplicate controls and huge action menus.
- Keep server actions concise; place advanced/raw configuration in a dedicated editor/view, not the common action menu.
- A profile editor must provide protocol-aware editable fields, validate before saving, preserve unedited parameters and explain invalid values. Raw URI editing belongs in a clearly labelled advanced mode.
- UI state must reflect actual service state, not merely a button press.

## Change protocol
1. State the symptom and evidence-backed cause (or label it a hypothesis).
2. Trace the affected data from import/storage through conversion to engine/runtime; for UI, trace state and callbacks.
3. Make the smallest complete change; avoid unrelated refactors.
4. Add regression tests, especially for parsing, subscription merge, routing conversion and engine selection.
5. Run relevant checks if available and inspect GitHub Actions. Never claim a test ran unless it did.
6. Review the diff for lost fields, hardcoded sample values, secrets and platform regressions.
7. Report commit, exact checks/results and remaining unverified behavior.

## Git and concurrent AI work
- Latest `main` is the source of truth, not an AI chat or pasted old file.
- The project owner prefers direct commits to `main` and no pull requests.
- **Only one writer at a time.** While one tool applies a change, the other may analyze/review but must not concurrently edit the same branch or files.
- Before every write, fetch the latest file and blob SHA. If it changed, re-read and merge; never overwrite newer content using stale source.
- One logical change per commit. Keep UI, parser, routing and version changes separate.
- Record commit SHA and wait for relevant CI before starting an overlapping edit.
- Do not bump app versions for docs-only/internal changes. Android `versionCode` must increase monotonically for a distributable update.

## Required completion report
- **Cause:** evidence or explicitly marked hypothesis.
- **Changed:** files and behavior.
- **Checks:** exact test/build commands or workflow links and actual outcomes.
- **Commit:** SHA/link, if committed.
- **Not verified:** device/server/OS behavior not tested.

A successful compile is not proof of a working tunnel or correct subscription import.
