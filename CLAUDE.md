# CLAUDE.md – IPVIO Stream

Working rules for AI-assisted changes to this repository. Read this file first in every session.

## 1. Project

- **IPVIO Stream**: free, open-source IPTV player (GPL-3.0) for Android TV, Google TV and Fire TV.
- Stack (from project notes, re-verify in the code): Kotlin, Jetpack Compose without material3, Media3/ExoPlayer, OkHttp, Coil, WorkManager. Package `com.ipvio.stream`.
- The app is in active use. Existing features and user data must be preserved.
- Persistence seen so far: SharedPreferences and app files. Verify whether a database exists before writing anything about migrations.
- Google Drive sync was removed in 1.2 and is planned again for 1.4 (LiveSync). Keep the BuildConfig fields and docs until then.

## 2. Principles

1. Refactor, do not rewrite.
2. Protect user data and user-visible behaviour first.
3. Every change needs a technical reason. Do not restyle working code.
4. Do not invent classes, dependencies or architecture. Mark anything unverified as **unverified**.
5. Prefer the smallest change that solves the problem.
6. One task, one problem. Do not mix unrelated issues.

## 3. Environment limits

- The assistant cannot run Gradle, has no Android SDK, and cannot test on a device.
- Checks that can run: syntax checks of changed files and pure-Kotlin logic tests (JUnit, no Android imports).
- The maintainer builds in Android Studio and tests on device or Fire TV. Every change ends with concrete test steps for the maintainer.
- Never report tests as passed unless they were actually run. State which checks ran and which did not.
- The maintainer commits and pushes. Commit messages are proposed in English.

## 4. Language

- Everything in the repository is in **English**: comments, KDoc, identifiers, file names, test names, commit messages, PR text, README, CHANGELOG, `docs/`.
- Conversation with the maintainer may be in German.
- Existing German identifiers and comments are translated in stages (see `docs/REFACTORING-PROGRESS.md`), not all at once.

## 5. Protected areas (no change without an approved plan)

- **SharedPreferences names and keys** (e.g. `ipvio_sportfavs`, `ipvio_sportleagues`, `ipvio_sporthub`). Renaming loses user data.
- **Stored file names, cache formats, backup JSON keys** (`Backup.kt`, list `NAMES`). Profile files use the suffix `_p<ID>`; profile 0 uses the original names.
- **Manifest components** and any class referenced by name (receivers, services, activities).
- **Phone-remote routes and parameters** (`/r`, `/b/CODE`).
- **UI strings**: the German text is the translation key (`I18nCore`, `DictJ`, `DictNl5`). Do not change without a decision by the maintainer.
- **Player and IPTV core**: `PlayerScreen.kt`, playlist sources (Xtream, Stalker, M3U), EPG.

## 6. Approval required before implementation

- Storage format changes with any risk to user data.
- Changes to signing, authentication, secrets handling, or phone-to-TV transfer.
- Removing any feature.
- Large changes to the player or IPTV core.
- Adding, removing or upgrading dependencies.
- Gradle restructuring or new modules.
- New CI workflows, or automatic formatting of the whole project.
- Anything with a visible effect on existing users.

## 7. Tasks and workflow

- Every task has an ID (`T-001`, `T-002`, …) and lives in `docs/tasks/`. Each task file states: goal, affected files, acceptance criteria, risks, documentation needed, test results, status.
- Split large work into subtasks. Work only on the assigned task. List dependencies between tasks.
- Large features and refactorings: plan first, implement only after approval. Simple bug fixes: no planning phase.

`ANALYZE → PLAN → APPROVAL → IMPLEMENT → VALIDATE → DOCUMENT → REPORT`

- **Analyze:** read the relevant code, find callers, note risks. Do not assume a technology is present. Do not analyse the whole codebase for a single task.
- **Plan:** list affected files and the reason for each change.
- **Implement:** keep unrelated code untouched.
- **Validate:** run what is possible (section 3); list the rest as test steps.
- **Document:** update the affected file under `docs/` and `CHANGELOG.md`.
- **Report:** what changed, which files, why, which checks ran and passed, what remains open, what the risks are.

## 8. Session handoff

- Before ending a session or switching chats, update `docs/tasks/WORK_STATE.md`: current task, completed steps, changed files, test results, open problems, next step.
- Start a new session by reading `CLAUDE.md` and `docs/tasks/WORK_STATE.md`. Do not repeat analyses already recorded in `docs/`.
- Use separate sessions for independent tasks.

## 9. Subagents

- Use subagents only for large, clearly separated read-only questions, e.g. "list every place that reads `ipvio_sportfavs`" or "review this file for security issues".
- Each subagent gets a precise brief and only the relevant files.
- No parallel edits to the same file.
- Results are checked by the main agent before use. The main agent owns architecture and integration.
- Simple tasks: no subagents.

## 10. Security rules

- No API keys, passwords, tokens or payment links in source code or logs.
- Do not print secrets in reports. Never commit `secrets.properties`, `keystore.properties` or key files.
- Validate input from playlists, phone-remote requests and backups.
- Do not weaken security settings to make a build pass.
- Analyse the impact before any change to authentication, storage or network code.
- Known open items (from project notes, verify before acting):
  - Phone-remote pages use plain HTTP on the local network.
  - `Secrets` falls back to plaintext on devices without an Android keystore.
  - `PhoneImport` failure counter is not atomic; the access code has 4 digits.

## 11. Quality tools

ktlint, detekt, Android Lint, mobsfscan, OSV-Scanner. Rules:

- Baseline first. Existing findings are recorded as **existing**; new findings must not slip in unnoticed.
- No blanket suppressions. Suppress a single finding only with a written reason.
- No project-wide auto-formatting without approval.
- Reports go to `docs/quality/` (one baseline file per date).
- Tool versions are checked for compatibility before they are added.

## 12. CI

- Existing workflow: .github/workflows/build.yml (unit tests and debug build on push). Do not change without approval.
- Pin actions to reviewed versions or commit SHAs.
- Do not overwrite existing workflows without approval.
- README badges only show results of workflows that exist and succeed.

## 13. Documentation

Maintained in `docs/` (English), kept in line with the code:

- `ARCHITECTURE.md` – compact arc42-style overview; unverified parts marked TODO
- `DIAGRAMS.md` – C4 level 1 and 2 in Mermaid
- `adr/NNNN-title.md` – one decision record per architectural decision (not for every small change)
- `FEATURES.md`, `IPTV.md`, `PLAYER.md`, `SECURITY.md`, `TESTING.md`, `TECH-DEBT.md`, `REFACTORING-PROGRESS.md`
- `RELEASE.md` (exists); `CHANGELOG.md` at repo root, updated for every task

Documentation must describe the actual code. Mark unknowns as unknown.

Task state and progress live in `docs/tasks/WORK_STATE.md` and `docs/REFACTORING-PROGRESS.md` (the restructuring steps U-1 to U-5 are listed there).

## 14. Uncertainty

If you do not know how something works: read the code first. Do not guess. Ask before any risky decision. When several options exist, compare maintainability, stability, complexity, performance, security impact and migration risk, and recommend the option with the best balance.

## 15. Current state

- Current phase: **P2 (comments to English) done** with batch 8 (final sweep). Next: restructuring steps U-1 to U-5 (P4 names, P5 file splits, package structure). P1 still has PENDING values (T-004). S1 from P3 was done early as T-002.
- Completed tasks: T-001 (test expectation for 10 languages), T-001b (`org.json` test dependency), T-002 (`/listimport` needs the access code, upload limit 16 MiB), T-003 (repo hygiene: `*.apk` ignored, CLAUDE.md corrected), T-005 (documentation skeleton and CHANGELOG for 1.2 and 1.3), P2 batches 1-8 (comments to English), P6 (repository documents checked, already English).
- Open tasks: T-004 (quality baseline, values pending), U-2 to U-5. T-006 (backup `NAMES`) is done.
- U-1 (Media3 code out of `PlayerLogic.kt`) is done (PR #30). Next step: see `docs/tasks/WORK_STATE.md`.
- Details: `docs/REFACTORING-PROGRESS.md` and `docs/tasks/WORK_STATE.md`.
