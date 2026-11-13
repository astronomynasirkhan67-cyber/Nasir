# Technical debt

Source: refactoring audit of 2026-11-11, section 6 (state at commit `b65e838`, tag `V1.3`), plus findings from sections 1, 3 and 5. Line counts are from the audit; they change as files are split (P5). File references are to `app/src/main/java/com/ipvio/stream/`.

## Large files

| File | Lines |
|---|---|
| `PlayerScreen.kt` | 1870 |
| `SettingsHub.kt` | 1356 |
| `PlaylistStore.kt` | 1185 |
| `HomeScreen.kt` | 972 |
| `HomeParts.kt` | 891 |
| `SportLeague.kt` | 857 |
| `DetailScreen.kt` | 813 |
| `Boomer.kt` | 679 |
| `Store.kt` | 676 |
| `LiveTab.kt` | 622 |
| `PlayerGuide.kt` | 617 |

Planned split order (P5, each with tests and approval): `SettingsHub`, `LiveTab`, `HomeScreen`/`HomeParts`, `PlaylistStore`, `PlayerScreen` last.

## Other items

| # | Item | Detail | Planned |
|---|---|---|---|
| 1 | Layer boundary | `PlayerLogic.kt` imports Media3 (`PlayerLogic.kt:3-8`); `PlayerLogicTest`, `PlayerBufferTest`, `CoreLogicTest` are not plain JVM tests | before P5 |
| 2 | Meaningless names | `Round17Logic.kt` contains `UpdateLogic`; tests `Round17Test` to `Round25Test` | P4 |
| 3 | Scattered storage access | 32 own `prefs(c)` helpers; no central list of store names | later (see [ADR 0003](adr/0003-shared-prefs-no-database.md)) |
| 4 | ~~Incomplete backup~~ | Fixed in T-006: `ipvio_dual`, `ipvio_live`, `ipvio_newep`, `ipvio_importedlists`, `ipvio_resumelog` are now in `NAMES`. Still not in the backup: caches and secrets, and `ipvio_plmode` (content mode per playlist address, added in PR #41, not profile-scoped); whether it belongs in the backup is undecided | done (T-006) |
| 5 | Outdated comment | "9 languages" (`PhoneImport.kt:40`); the app has 10 | P2 |
| 6 | R8 keep rules in a clean-up folder | `app/src/main/_to_delete_keepRules/` is committed; see [ADR 0001](adr/0001-no-r8-for-now.md) | later |
| 7 | German text outside UI strings | about 136 German comment lines in 44 files (many in tests); also `AndroidManifest.xml`, `app/build.gradle.kts:41, 60-61`, `gradle/libs.versions.toml:1`, `res/xml/file_paths.xml:2`; some test names (e.g. `handySeiteInDerSpracheDesHandys`). Heuristic count; full list comes in P4 | P2, P4 |
| 8 | Global state | 179 `object` singletons in 110 files; global Compose states; no ViewModel, no DI | later (no change planned) |
| 9 | Flat package | 162 files in one package | later (target structure in [ARCHITECTURE.md](ARCHITECTURE.md)) |
| 10 | Dependencies declared inline | versions in `app/build.gradle.kts`; version catalog holds only two plugins | later |
| 11 | Undeclared direct use | `androidx.core` (FileProvider) and lifecycle are used without own declaration (`AppUpdate.kt:10`, `PlayerScreen.kt:76-77`) | later |
| 12 | Media3 and FFmpeg decoder versions differ | 1.11.1 vs `1.9.0+1`, not tested on a device | later |
| 13 | Unused BuildConfig fields | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` (`app/build.gradle.kts:46-47`). Kept for 1.4 | none |
| 14 | Lint check disabled | `UnsafeOptInUsageError` is off globally (`app/build.gradle.kts:85-87`) | later |
| 15 | CI | actions pinned to `@v4`, not SHAs; Java 21 in CI vs toolchain 25; run status unknown (`build.yml:13-25`, `gradle-daemon-jvm.properties`) | later (approval needed) |
| 16 | Lint findings | 9 errors (3 ByteOrderMark in `ListImport.kt:19, 38` and `XtreamLogic.kt:183`), 203 warnings, 10 hints (baseline 2026-11-11) | after baseline |

## Resolved since the audit

- Documentation drift: `docs/REFACTORING-PROGRESS.md`, `docs/tasks/WORK_STATE.md` and `docs/SECURITY.md` did not exist; added in T-005.
- `PhoneImportTest` expectation for 10 languages (T-001).
- `Round25Test.parsesTraktJsonTolerantly` red because `org.json` is a stub in unit tests: test dependency added (T-001b, commit `75e1892`).
- `*.apk` not ignored in `.gitignore` (T-003).

Not debt (checked): `BuildStamp` in `Brand.kt` is already removed.
