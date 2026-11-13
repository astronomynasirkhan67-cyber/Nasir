# Refactoring progress

Last update: 2026-11-11 (after P2 batch 8 and the restructuring plan U-0). Source: refactoring audit of 2026-11-11, sections 11 and 14. Baseline: commit `b65e838` = tag `V1.3` = tag `pre-refactor`.

Rules: one task at a time, approval before implementation (CLAUDE.md sections 6 and 7). No new features during the refactoring; 1.4 starts after it.

## Phases

| Phase | Content | Code change | Status |
|---|---|---|---|
| P0 | Audit | none | done |
| P1 | Test fix, baseline, repo hygiene, documentation skeleton | tests, `.gitignore`, `CLAUDE.md`, docs | in progress (T-001, T-001b, T-003, T-005 done; T-004 in progress) |
| P2 | Comments to English (about 44 files plus manifest, Gradle, XML); check that code without comments is identical | comments only | done (batches 1-8 and the last comment in `Backup.kt` with T-006) |
| P3 | Security fixes, one task each: S1 `/listimport` code and size limit; S2 6-digit code, atomic lock, constant-time compare; S3 HTTP warning (new texts); S4 TLS downgrade visible; S6 `SECURITY.md`; S7 backup completeness | small, with approval | in progress (S1 done as T-002; S6 done as part of T-005; S2, S3, S4 open; S7 is T-006) |
| P4 | Names (stage 2): test names, then `Round*` files, then remaining identifiers after an inventory | broad | not started (steps U-2, U-3) |
| P5 | Split large files: `SettingsHub`, `LiveTab`, `HomeScreen`/`HomeParts`, `PlaylistStore`, `PlayerScreen` last. Detach `PlayerLogic` from Media3 first | large, with tests | in progress: U-1 done (branch `refactor/U-1`), U-4 open |
| P6 | Repository documents to English and up to date | docs only | done (checked, already English; Google Drive mentions stay until 1.4) |
| later | R8 again, Media3/FFmpeg versions, CI hardening, backup gaps | open | not started |

S5 (Google fields) is dropped: the fields stay for 1.4.

## Tasks

Task IDs follow audit section 14. They replace the IDs in the table of section 11.

| ID | Task | Files | Status |
|---|---|---|---|
| T-001 | Fix the red test: expectation for 10 languages | `PhoneImportTest.kt` | done |
| T-001b | `org.json` as test dependency (`Round25Test.parsesTraktJsonTolerantly` was red) | `app/build.gradle.kts`, `Round25Test.kt` | done (commit `75e1892`) |
| T-002 | S1: `/listimport` needs the access code; upload limit 16 MiB | `PhoneImport.kt`, `RemoteLogic.kt`, tests | done (commit `a804180`, pushed); device test PENDING |
| T-003 | Repo hygiene: `*.apk` in `.gitignore`; correct CLAUDE.md | `.gitignore`, `CLAUDE.md` | done (commit hash PENDING) |
| T-004 | Quality baseline | `docs/quality/baseline-2026-11-11.md` | in progress (values PENDING) |
| T-005 | Documentation skeleton and CHANGELOG 1.2/1.3 | docs, `CHANGELOG.md`, `CLAUDE.md` section 15 | done |
| T-006 | Add five missing stores to the backup `NAMES` | `Backup.kt` | done (PR pending device check: export and restore) |
| T-007 ff. | P2, P3 S2/S3/S4, P4, P5 | | open, one approval each |
| 1.4 | Google Drive as LiveSync, rebuilt from the `V1.1` code | | after the refactoring |

## Restructuring plan (U-0, 2026-11-11)

Each step is one branch `refactor/U-n` with a tag `pre-U-n` before it. Code is moved verbatim; no change to SharedPreferences names or keys, stored file names and formats, persisted enum values (`Kind`: names and order), manifest classes, `UpdateWorker`, reflection names (FFmpeg decoder), UI strings, phone routes or build files. Check before every hand-over: the sorted list of all string literals in `app/src/main` must be identical before and after.

| Step | Content | Status |
|---|---|---|
| U-0b | P2 batch 8: final comment sweep, fix of `RemoteUi.kt:39` | done (commit pending) |
| U-1 | Move the Media3-dependent code out of `PlayerLogic.kt` (new `PlayerTracks.kt`; mirrored error codes with a Media3 contract test; `epgSane` and the retry rule into pure code) so `PlayerLogicTest`, `CoreLogicTest` and `PlayerBufferTest` run without Android | done 2026-11-11 (PR open, CI pending) |
| U-2 | Inventory of German identifiers (list only) | open |
| U-3 | Renames: a) test names, b) `Round*` files, c) remaining identifiers in groups | open |
| U-4 | Split: a) `SettingsHub`, b) `LiveTab`, c) `HomeScreen`/`HomeParts`, d) `PlaylistStore`, e) `PlayerScreen` | open |
| U-5 | Package `domain` (code without Android imports) and `i18n`; the other packages are not planned | open |

Decisions (2026-11-11):

1. U-1 mirrors the eight `PlaybackException` error codes in a local object; a contract test compares them with Media3.
2. Batch 8 is finished before U-1.
3. U-4 moves only functions that already stand alone. The large composables `PlayerScreen()`, `HomeScreen()` and `LiveTab()` are not cut up.
4. U-5 creates only `domain` and `i18n`, after U-4. Manifest classes and `UpdateWorker` stay in the root package.
5. English test names follow `subject_condition_expectation` in camelCase, e.g. `byteLimit_growsWithHeap`.
