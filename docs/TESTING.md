# Testing

Source: refactoring audit of 2026-11-11, section 7, the T-002 note and [quality/baseline-2026-11-11.md](quality/baseline-2026-11-11.md). Unit tests are in `app/src/test/java/com/ipvio/stream/`.

## Commands

Run in Android Studio, terminal in the project folder (Windows):

| Command | Purpose |
|---|---|
| `gradlew.bat testDebugUnitTest` | all unit tests |
| `gradlew.bat lintDebug` | Android Lint; report `app/build/reports/lint-results-debug.html` |

CI (`.github/workflows/build.yml`) runs unit tests and a debug build on every push. Status of the runs: PENDING (check the Actions tab on GitHub; the audit expected it to be red since 1.1 because of the `PhoneImportTest` expectation, which T-001 fixed, and the `org.json` stub, which T-001b fixed).

## Known state

- The audit counted 231 `@Test` functions in 31 test files.
- `PhoneImportTest`: 10 of 10 passed in the maintainer's run on 2026-11-11 (T-001 done).
- `Round25Test.parsesTraktJsonTolerantly` (`Round25Test.kt:70`) was red (expected `[Heat, Lost]`, was `[]`) because `org.json` is only a stub in local unit tests. T-001b added `testImplementation("org.json:json:20250517")` (commit `75e1892`). After that the maintainer reported a successful test run on 2026-11-11.
  - `org.json:json` is used for unit tests only; it is not part of the APK. Its "JSON License" is not OSI-approved (see [SECURITY.md](SECURITY.md), item 14).
- Exact Gradle totals ("X tests completed, Y failed") after T-001b: PENDING.
- Lint: 9 errors, 203 warnings, 10 hints (baseline 2026-11-11). Details in the baseline file.

## Tests that cannot run as plain JVM tests

`PlayerLogic.kt` imports Media3. Therefore `PlayerLogicTest`, `PlayerBufferTest` and `CoreLogicTest` depend on Media3 classes. The audit could not run them outside Gradle. Detaching `PlayerLogic` from Media3 is a prerequisite for splitting `PlayerScreen.kt` (P5). All other `*Logic.kt` files have no Android imports.

## Only meaningful on a device

Unit tests cannot cover these. Each needs a test step for the maintainer after a change:

| Area | Why | Source |
|---|---|---|
| Phone import, transfer and phone search over real Wi-Fi | HTTP server on the TV, access code, QR code. The emulator test used `adb forward` and a PC browser (T-002) | `PhoneImport.kt`, `RemoteHub.kt` |
| Software audio (headphones, Bluetooth) | FFmpeg decoder is loaded by reflection; Media3 1.11.1 with decoder 1.9.0+1 is untested | `SoftAudio.kt:24`, `app/build.gradle.kts:96-102` |
| R8 release build (T-024) | earlier R8 builds did not start; start, software audio and scrolling need a device | [ADR 0005](adr/0005-r8-whole-app.md), `docs/tasks/T-024-r8-whole-app.md` |
| Playback of HLS, DASH and provider streams | needs real providers | `PlayerScreen.kt` |
| Live rewind, recording, second stream, multi view | player and storage behaviour | `LiveTimeshift.kt`, `Recorder.kt`, `DualView.kt`, `MultiView.kt` |
| Remote-control focus and navigation | TV focus handling | `Nav.kt` |
| Alarms and reminders after reboot | `BootReceiver`, `ReminderReceiver` | `AndroidManifest.xml:39-71` |
| App update install | signature check and installer | `AppUpdate.kt` |
| Devices without Android Keystore | plaintext fallback in `Secrets` | `Secrets.kt:14-15` |
| TLS fallback with real providers | hostname mismatch case | `TlsFallback.kt:47-65` |
| Low-memory devices (1 GB) | memory use, `largeHeap` | `Mem.kt` |

## Rules

- Never report a test as passed unless it was run (CLAUDE.md section 3).
- Test names and some test classes are still German or named `Round17Test` to `Round25Test`; renaming is planned in P4.
