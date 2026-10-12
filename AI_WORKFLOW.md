# Shared AI workflow: ChatGPT + Google AI Studio

Repository: `Kolya-YT/NeoTUN-Client`. This document and `AGENTS.md` are the shared collaboration contract.

## Important limitation
Google AI Studio and ChatGPT do not automatically share chat history, unsaved code or local edits just because these files exist in GitHub. Each session must be given the current repository context. Keep both documents in the prompt/context of both tools.

## Roles
- **ChatGPT & Google AI Studio:** both can inspect the repository, trace cross-platform behavior, prepare commits, or provide reviewed patches depending on the active task.
- **Strict Single Writer:** only one assistant applies changes to the target branch or subsystem at a time. The other assistant analyzes, reviews the diff, or validates regression safety.

## Standard workflow
1. Refresh the latest `main`, latest commit and relevant Actions run. Do not reuse old pasted source without comparing it with GitHub.
2. Define one task with symptom, expected behavior, reproduction and acceptance checks.
3. Trace the data path: import → parsing/normalization → persistence → engine config → runtime. For UI, trace state and callbacks.
4. Ask one AI to propose a small patch. Provide current source files plus `AGENTS.md`, `AI_WORKFLOW.md`, and design token files (`NeoTunDesign.kt` / `App.xaml`) for UI work.
5. Ask the other AI to review the patch against current source, architecture and tests. It reviews; it does not concurrently edit the same files.
6. The designated writer fetches latest target file and SHA, applies the reviewed patch and commits one logical change. Owner preference: direct commit to `main`, no PR.
7. Run relevant tests and inspect GitHub Actions. If a run fails, inspect the failing step/log and fix the cause; do not suppress tests.
8. Send the exact commit SHA and CI result to the other AI before related work begins.

## Prompt for Google AI Studio
> You are contributing to NeoTUN-Client (Kolya-YT/NeoTUN-Client). Follow AGENTS.md and AI_WORKFLOW.md. The latest GitHub main branch is the source of truth. First inspect the current code and identify the root cause with file/function references. Propose the smallest compatible patch and regression tests. Preserve subscription fields and routing data; never hardcode example endpoints, UUIDs, DNS, domains or route lists. Do not rewrite unrelated code or claim to have committed/published anything unless you actually did. Do not edit while another assistant is applying a patch. Return a unified diff, changed-file list, rationale, tests, risks and anything not verified. The owner prefers direct commits to main and no pull requests; if you cannot safely write to main, return the patch for review.

## Prompt for ChatGPT to review Gemini's patch
> Review this Google AI Studio patch for Kolya-YT/NeoTUN-Client against the latest main branch, not just the pasted context. Follow AGENTS.md and AI_WORKFLOW.md. Check whether the patch applies cleanly, preserves imported subscription and routing data, maintains Rust/Android/Windows contracts, includes regression tests and avoids unrelated UI changes. Identify concrete bugs before applying. Do not overwrite newer work. If approved, apply one logical change to main using the latest file SHA, then inspect relevant GitHub Actions results and report exact verification status.

## Preventing conflicts
- Never have both tools independently apply different versions of the same file at once.
- Exchange diffs, not entire stale copies of files such as `MainActivity.kt`, Rust modules or workflows.
- If two changes touch a shared interface, review them together before committing.
- If a new commit appears during patch preparation, refresh and rebase/recalculate the patch.
- Keep release/version changes separate from feature fixes. Publish only after intended CI checks pass.
- If a test cannot run locally, mark it unverified and use the matching Actions run. Green CI does not prove real traffic or subscription correctness.

## Handoff template
**Task:**  
**Latest main commit before work:**  
**Files inspected:**  
**Cause/evidence:**  
**Patch or commit:**  
**Tests and Actions results:**  
**Manual tests still needed:**  
**Next owner (ChatGPT / AI Studio):**
