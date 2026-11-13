# Architecture

Compact arc42-style overview. Facts come from the refactoring audit of 2026-11-11 (commit `b65e838`, tag `V1.3`), sections 3, 5 and 9. Anything not verified in the audit is marked **TODO**. See [DIAGRAMS.md](DIAGRAMS.md) for the C4 diagrams.

All Kotlin files are in `app/src/main/java/com/ipvio/stream/`, so file names below carry no path.

## 1. Introduction and goals

- IPVIO Stream is a free, open-source IPTV player (GPL-3.0) for Android TV, Google TV and Fire TV.
- It plays content from the user's own playlist providers (Xtream, Stalker/Ministra, M3U). It does not supply content.
- TODO: quality goals in priority order (not stated in the audit).

## 2. Constraints

| Constraint | Value | Source |
|---|---|---|
| Gradle wrapper | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties:5` |
| Android Gradle Plugin / Kotlin Compose plugin | 9.4.1 / 2.2.10 | `gradle/libs.versions.toml:3-8` |
| compileSdk / targetSdk / minSdk | 37 / 37 / 24 | `app/build.gradle.kts:30-37` |
| Java source/target | 11 | `app/build.gradle.kts:77-78` |
| Gradle daemon toolchain | 25 | `gradle/gradle-daemon-jvm.properties` |
| CI Java version | 21 | `.github/workflows/build.yml:17` |
| Modules | one (`:app`) | `settings.gradle.kts:26` |
| Debug build | own application ID suffix `.test`, installable next to the release app | `app/build.gradle.kts:62-65` |

## 3. Context and scope

External actors and systems (details in [DIAGRAMS.md](DIAGRAMS.md)):

- **User**: controls the app with the TV remote; uses a phone browser for playlist import, transfer and search.
- **TV device**: Android TV, Google TV or Fire TV; runs the app.
- **Playlist providers**: Xtream, Stalker/Ministra portals, M3U URLs or files. Provided by the user.
- **TMDB and OMDb**: metadata, only with the user's own API keys.
- **ESPN**: data for the sports hub.
- **GitHub Releases**: source of app updates.
- **List import sources**: Letterboxd, MAL, Trakt (`ListImport.kt`).
- **Google Drive**: not in the code since 1.2. Planned again for 1.4 (LiveSync).

TODO: EPG (XMLTV) source: whether it always comes from the playlist provider is not verified.

## 4. Solution strategy

- Single-module Kotlin app, UI in Jetpack Compose (`ui` and `foundation` only, **no material3**; own `Text` in `Lang.kt`).
- Playback with Media3 ExoPlayer (HLS, DASH); the FFmpeg audio decoder is loaded by reflection (`Class.forName`, `SoftAudio.kt:24`).
- State lives in global singletons (`object`) with Compose `mutableStateOf`. No dependency injection, no ViewModel.
- Persistence in SharedPreferences and files. No database. See [ADR 0003](adr/0003-shared-prefs-no-database.md).
- UI text: the German string is the translation key. See [ADR 0002](adr/0002-german-text-as-i18n-key.md).
- R8 runs over the whole app in the release build; the app's own code is kept unchanged (not device tested yet). See [ADR 0005](adr/0005-r8-whole-app.md).

## 5. Building block view

Technology stack (audit section 3):

| Area | Current state | Source |
|---|---|---|
| Navigation | no navigation library; `tab` state in `MainActivity` (`remember`) plus focus objects | `MainActivity.kt:196`, `Nav.kt:15-26` |
| Dependency injection | none; 179 `object` singletons in 110 files (e.g. `PlaylistStore`, `AppSettings`, `Config`, `Profiles`) | `PlaylistStore.kt:54`, `Store.kt:43` |
| State | global Compose states in singletons (`Store.kt` 18 `mutableStateOf`, `PlaylistStore.kt` 16) | audit count |
| ViewModel | none | audit count |
| Player | Media3 ExoPlayer, HLS, DASH, UI 1.11.1 | `app/build.gradle.kts:96-102` |
| Network | OkHttp 4.12.0, shared client `Net.base` | `Net.kt:11` |
| Images | Coil 2.7.0, memory share depends on RAM class | `MainActivity.kt:58`, `Mem.kt:23` |
| Background work | WorkManager 2.10.0, only playlist refresh | `Updater.kt:14, 48` |
| Storage | SharedPreferences and files | see ADR 0003 |
| JSON | `org.json`, used in 19 files | audit count |
| Package layout | flat: 162 files, about 36,800 lines in `com.ipvio.stream` | audit count |

Android components declared in `AndroidManifest.xml:39-71`: `MainActivity`, `RestartActivity` (process `:restart`), `FileProvider`, `ReminderReceiver`, `RecordService` (type dataSync), `BootReceiver` (exported).

Logical building blocks, derived from the audit's feature table. The package is flat, so these are groupings, not packages:

| Block | Main files |
|---|---|
| UI | `MainActivity.kt`, `HomeScreen.kt`, `HomeParts.kt`, `DetailScreen.kt`, `SettingsHub.kt`, `LiveTab.kt`, `Boomer.kt`, `Nav.kt` |
| Player | `PlayerScreen.kt`, `PlayerLogic.kt`, `PlayerGuide.kt`, `LiveTimeshift.kt`, `Recorder.kt`, `DualView.kt`, `MultiView.kt`, `SoftAudio.kt` |
| Playlist sources | `XtreamApi.kt`, `StalkerApi.kt`, `PlaylistStore.kt`, `Models.kt` (M3U), `MultiLogic.kt`, `LazySeries.kt`, `Catchup.kt` |
| TV guide and metadata | `Epg.kt`, `Meta.kt`, `Config.kt` |
| Sports hub | `SportHub.kt`, `SportLeague.kt`, `SportAlerts.kt` |
| Phone server | `PhoneImport.kt`, `RemoteHub.kt`, `RemoteLogic.kt`, `RemoteUi.kt` |
| Local data | `Store.kt`, `Profiles.kt`, `Backup.kt`, `Secrets.kt`, `Parental.kt`, `ParentalLogic.kt` |
| Network and security | `Net.kt`, `TlsFallback.kt` |
| Updates | `AppUpdate.kt`, `Round17Logic.kt` (contains `UpdateLogic`), `Updater.kt` |
| Languages | `Lang.kt`, `I18nCore.kt` (10 languages), dictionaries `Dict*` |

TODO: call directions between blocks are not verified. The audit gives no call graph.

Libraries (all declared with versions in `app/build.gradle.kts`; the version catalog holds only two plugins):

| Library | Use | Note |
|---|---|---|
| compose-bom 2026.09.00, `ui`, `foundation` | everywhere | |
| activity-compose 1.13.0 | 15 files | |
| media3-exoplayer 1.11.1 | player | |
| media3-exoplayer-hls, -dash | no imports; found by Media3 at runtime | do not remove (`app/build.gradle.kts:98`) |
| media3-ui | `PlayerScreen.kt`, `DualView.kt` | |
| Jellyfin media3-ffmpeg-decoder 1.9.0+1 | loaded by reflection | version gap to Media3 1.11.1, not tested on a device |
| okhttp 4.12.0 | 18 files | |
| coil-compose 2.7.0 | 23 files | |
| work-runtime-ktx 2.10.0 | `Updater.kt` | |
| junit 4.13.2 | tests | |
| androidx.core (FileProvider), lifecycle | transitive, no own declaration | `AppUpdate.kt:10`, `PlayerScreen.kt:76-77` |

Not checked: library freshness and known vulnerabilities (OSV-Scanner has not been run).

## 6. Runtime view

TODO: not covered by the audit. Candidates: app start, playback start, playlist refresh, phone import.

## 7. Deployment view

- Distribution: APK in GitHub Releases. In-app update checks GitHub Releases once a day (`AppUpdate.kt:20-34`). Signing: see [RELEASE.md](RELEASE.md).
- CI: `.github/workflows/build.yml` runs unit tests and a debug build on every push. Actions are pinned to `@v4`, not to commit SHAs.
- Network ports: the phone server uses ports 8765-8775 (`PhoneImport.kt:195`).

## 8. Cross-cutting concepts

- **Security configuration**: `allowBackup=false`, `usesCleartextTraffic=true`, `largeHeap=true`, no `networkSecurityConfig` (`AndroidManifest.xml:35-37`). Details: [SECURITY.md](SECURITY.md).
- **Memory**: the Coil image memory share depends on the RAM class (`Mem.kt:23`); the manifest sets `largeHeap`. TODO: other memory handling is not verified.
- **Profiles**: up to 5; profile files use the suffix `_p<ID>`, profile 0 keeps the original names (`Profiles.kt:49, 113`, CLAUDE.md section 5).
- **Lint**: `UnsafeOptInUsageError` is disabled globally (`app/build.gradle.kts:85-87`).

## 9. Architecture decisions

- [ADR 0001](adr/0001-no-r8-for-now.md): R8 stays off for now.
- [ADR 0002](adr/0002-german-text-as-i18n-key.md): German text is the translation key.
- [ADR 0003](adr/0003-shared-prefs-no-database.md): SharedPreferences and files, no database.
- [ADR 0005](adr/0005-r8-whole-app.md): R8 over the whole app, own code kept (0004 exists only on the old branch `perf/T-007`).

## 10. Quality requirements

TODO: not stated in the audit. Current test and lint state: [TESTING.md](TESTING.md), [quality/baseline-2026-11-11.md](quality/baseline-2026-11-11.md).

## 11. Risks and technical debt

See [TECH-DEBT.md](TECH-DEBT.md) and [SECURITY.md](SECURITY.md).

## 12. Glossary

TODO.

## Proposed target structure (not implemented)

The audit (section 9) proposes package groups, no Gradle modules, no Hilt, no Room: `ui/`, `domain/` (logic without Android), `data/`, `core/`, `remote/`, `i18n/`. Moving manifest-referenced classes (`BootReceiver`, `ReminderReceiver`, others) can break set alarms; they stay in the root package or the manifest is changed with them. This needs an approved plan before any move (CLAUDE.md section 6).
