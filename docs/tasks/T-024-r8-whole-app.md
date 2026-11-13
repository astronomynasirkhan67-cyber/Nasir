# T-024 R8 over the whole app

## Goal

Turn R8 on again for the release build without the start-up crash seen with AGP 9.4.1 and the library-only `packageScope`.

## Change

- `app/build.gradle.kts`: `optimization { enable = true }`, `packageScope` removed (R8 runs over the whole app, classic full mode).
- `app/src/main/keepRules/reflection.keep`: `-keep class com.ipvio.** { *; }` (own code unchanged), `-dontobfuscate` (already there) and new `-dontrepackage`. The rule for `androidx.media3.decoder.ffmpeg.**` is unchanged.
- Decision and sources: [ADR 0005](../adr/0005-r8-whole-app.md).

## Affected files

`app/build.gradle.kts`, `app/src/main/keepRules/reflection.keep`, `docs/adr/0005-r8-whole-app.md`, `docs/adr/0001-no-r8-for-now.md` (status), `docs/ARCHITECTURE.md`, `docs/TESTING.md`, `CHANGELOG.md`, `docs/tasks/WORK_STATE.md`. No Kotlin source file.

## Acceptance criteria

1. `assembleRelease` builds without an R8 error.
2. The release APK starts on the Fire TV stick (cold start, no crash).
3. A movie with AC3 or E-AC3 audio has sound over Bluetooth headphones (software decoder, `SoftAudio.kt`).
4. Live TV list with a held arrow key, home page, cold start: no crash, not slower than the build without R8.

## Risks

- Start-up crash as before. Then: logcat from "FATAL EXCEPTION", add the missing keep rule.
- `-dontrepackage` comes from the documentation and was not checked against a build.
- Resource shrinking is on with `enable = true`.
- Library code that is reached only by reflection and has no rule of its own (not known for the current dependencies; Media3, OkHttp, Coil and WorkManager ship their own rules: **unverified** for the exact versions).

## Rollback

`enable = false` in `app/build.gradle.kts` (one line).

## Test results

- Ran: none. No Gradle, no Android SDK and no device in the assistant's environment. Only the edited files were read back.
- Not run: release build, start on a device, audio test, scroll test, APK size comparison.

## Test steps for the maintainer

1. Android Studio: Build > Generate Signed App Bundle or APK (or the Gradle task `assembleRelease`). Note the APK size.
2. Install on the Fire TV stick: `adb install -r app/build/outputs/apk/release/app-release.apk`.
3. Cold start. If it crashes: `adb logcat -d | findstr /C:"FATAL EXCEPTION" /C:"AndroidRuntime"`, send the block.
4. Play a movie with AC3 or E-AC3 over Bluetooth headphones: sound present?
5. Live TV: hold the arrow key in a long channel list. Home page: scroll through the rows.
6. Optional: `adb shell am start -W com.ipvio.stream/.MainActivity` and `adb shell dumpsys gfxinfo com.ipvio.stream` before and after.

## Status

Released with 1.4. Reported by the maintainer on 12 Nov 2026: built and tested on the device, works. The individual checks and any measurements were not recorded.
