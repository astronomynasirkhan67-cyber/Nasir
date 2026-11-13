# ADR 0001: No R8 for now

## Status

Superseded by [ADR 0005](0005-r8-whole-app.md) once its device test has passed. Records the state of release 1.3.

## Context

Audit source: sections 1 (finding 7), 2 and 11.

- R8 is turned off in the release build: `optimization { enable = false }` (`app/build.gradle.kts:68-73`). The comment next to it says that an R8 release build of 12.7 MB did not start.
- The FFmpeg audio decoder is loaded by reflection (`Class.forName`, `SoftAudio.kt:24`). R8 would need a keep rule for it.
- The needed keep rule exists only in `app/src/main/_to_delete_keepRules/reflection.keep` (lines 1-3). `keepRules/rules.keep` is only a template. The folder `_to_delete_keepRules` is committed to the repository.
- Size of the current release APK without R8: TODO (not stated in the audit).

## Decision

Keep R8 disabled until it can be tested on a device.

## Consequences

- The release APK is larger than an R8 build would be. TODO: size difference.
- No code shrinking or obfuscation in the release build.
- Before R8 is enabled again:
  - take over the keep rules from `_to_delete_keepRules/reflection.keep` into an active rules file;
  - test software audio decoding (headphones, Bluetooth) on a real device;
  - find out why the earlier R8 build did not start: TODO (cause unknown).
- The folder name `_to_delete_keepRules` is misleading and is a tidy-up item, see [TECH-DEBT.md](../TECH-DEBT.md).
