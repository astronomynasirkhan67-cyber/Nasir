# ADR 0005: R8 over the whole app, own code kept

## Status

Accepted (T-024), released with 1.4. Reported by the maintainer on 12 Nov 2026: built and tested on the device, works. The individual checks and any measurements were not recorded. The original condition was: accepted when the release build starts on a Fire TV stick and the checks in `docs/tasks/T-024-r8-whole-app.md` pass. Supersedes [ADR 0001](0001-no-r8-for-now.md) then.

Number 0004 ("R8 for the libraries again") exists only on the old branch `perf/T-007` and was never merged into `main`. Its approach is the one that failed; this record replaces it.

## Context

- Releases 1.0 and 1.1 used `optimization { enable = true; packageScope = androidx.**, kotlin.**, kotlinx.** }` (Git history, `app/build.gradle.kts`).
- During 1.2 the scope was extended to `com.ipvio.**`. That release build (12.7 MB) did not start. Cause unknown ([ADR 0001](0001-no-r8-for-now.md)).
- 12 Nov 2026, AGP 9.4.1: the library-only scope crashed the release at start. `IllegalAccessError`, a renamed `kotlin.text` class, in `androidx.startup.InitializationProvider`. Adding `-dontobfuscate` did not help (maintainer's device test, note in `keepRules/reflection.keep`).
- Without R8, Compose runs unoptimized in the release build. This is a known cost for Compose apps.
- What the Android documentation says (read on 12 Nov 2026):
  - AGP 9.3 and higher: `optimization { enable = true }` in the release build type enables code and resource optimization. Keep rules are files with the suffix `.keep` in `src/<variant>/keepRules`. The default Android rules (equal to `proguard-android-optimize.txt`) are included.
  - `packageScope` is experimental, needs `android.r8.gradual.support=true` in `gradle.properties`, and the page says to remove it to optimize the entire app in full mode. That property is not in this repository's `gradle.properties`; whether this played a part in the crash is **unverified**.
  - Since AGP 9.1 repackaging of classes into one package is the default for apps. The documented opt-out is `-dontrepackage`. `-dontobfuscate` is described as preventing the shortening of names; the page does not say that it also stops repackaging.
  - `keep` without modifiers prevents every optimization on the matched classes.
- Reflection in the app's own code: only `SoftAudio.kt` (`Class.forName` for the FFmpeg audio renderer). No `getIdentifier` for resources.

## Decision

- R8 runs over the whole app in the classic way: `optimization { enable = true }`, no `packageScope`.
- `app/src/main/keepRules/reflection.keep`:
  - `-keep class com.ipvio.** { *; }`: the app's own code is not removed, renamed or optimized.
  - `-dontobfuscate` and `-dontrepackage`: no class is renamed or moved into another package.
  - `-keep class androidx.media3.decoder.ffmpeg.** { *; }` stays as it was.
- No new dependency, no change in `gradle.properties`.

## Consequences

- Expected, not measured: faster UI in the release build (optimized Compose, Media3, OkHttp, Kotlin) and a smaller APK.
- The app's own code is not optimized. Calls from it into the libraries are not inlined. This is the price for a low start-up risk; it can be relaxed later, package by package, with device tests.
- The documentation advises against `-dontobfuscate` in production builds. It is accepted here: the app is open source (nothing to hide), stack traces stay readable without a mapping file, and the cost is only some APK size.
- `enable = true` also turns on resource shrinking. Resources referenced from code or the manifest stay. **Unverified** on a device.
- Risk: the release build does not start or a library path that uses reflection breaks. Then the logcat part from "FATAL EXCEPTION" shows the class that needs a keep rule.
- Risk: the R8 version in AGP 9.4.1 might reject `-dontrepackage` at build time (taken from the documentation, not from a build). The build error would name the option.
- Rollback: set `enable = false` in `app/build.gradle.kts` (one line). The keep rules have no effect then.
- Debug builds are unchanged.
