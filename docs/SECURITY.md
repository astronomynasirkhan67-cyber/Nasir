# Security

Status of known security items and the current security state of IPVIO Stream.
Source: refactoring audit of 2026-11-11, section 8 (state at commit `b65e838`, tag `V1.3`), updated for T-002.
File references are to `app/src/main/java/com/ipvio/stream/` unless stated otherwise.

Status values: **open**, **partly fixed**, **fixed**.

## 1. Scope

IPVIO Stream is a free, open-source (GPL-3.0) IPTV player for Android TV, Google TV and Fire TV.
It has no accounts and no backend of its own. It does open network connections in these places:

- **Local Wi-Fi:** the phone-remote pages and the Wi-Fi transfer. The TV app runs a small HTTP server on ports 8765–8775 (`PhoneImport.kt:195`).
- **Playlist providers entered by the user:** Xtream Codes, M3U (URL or file) and Stalker/Ministra portals.
- **Optional metadata services with user-supplied keys:** TMDB and OMDb. TheIntroDB needs no key.
- **GitHub Releases:** in-app update check and download.

## 2. Reporting

The repository root has no `SECURITY.md` yet, so there is no documented reporting channel in this repository. This is open item 15 below.

## 3. Known items

| # | Item | Status | Detail | Planned task | Source |
|---|---|---|---|---|---|
| 1 | Phone pages over plain HTTP; server listens on all interfaces; no warning on phone or TV | open | Use only on your own, trusted Wi-Fi. No warning text found in the code | P3 / S3 (warning, new texts in 10 languages) | `PhoneImport.kt:195`, `RemoteUi.kt:64, 73` |
| 2 | Access code has 4 digits | open | | P3 / S2 (6 digits) | `PhoneImport.kt:38` and the `maxlength="4"` lines |
| 3 | Failed-attempt counter and lock | partly fixed | Fixed: counter is `AtomicInteger`, random value from `SecureRandom`. Open: the "locked?" check and the increment are not atomic together; comparison is not constant-time | P3 / S2 | `PhoneImport.kt:170, 172, 254-263` |
| 4 | `/listimport` accepted uploads without a code while phone search was active; up to 16 MB reserved per request | fixed | T-002: code required, limit 16 MiB (`MAX_LIST_UPLOAD`), body read in 64 KiB chunks, answer 413 when too large. Test on a TV or Fire TV: pending | done (T-002) | was `PhoneImport.kt:307-312`; audit finding 4 |
| 5 | Backup via `GET /backup?code=...` (optionally with playlists and credentials) over HTTP | open | Follows from item 1 | with S2/S3 | `PhoneImport.kt:180, 337-346` |
| 6 | `Secrets` falls back to plaintext without Android Keystore | partly fixed | A hint exists in the settings, but it shows only when encryption was attempted in the current session. The fallback itself remains | none planned (decision pending) | `Secrets.kt:14-15, 41`, `SettingsHub.kt:1126` |
| 7 | Silent HTTPS-to-HTTP downgrade after a certificate hostname mismatch; used for Stalker login | open | Credentials can be sent unencrypted. Reported only through `Diag.report`. Behaviour decision open (audit section 14, point 6) | P3 / S4 | `TlsFallback.kt:47-65`, `StalkerApi.kt:20` |
| 8 | API keys in `Config.kt` | fixed | No keys in code. Values come from `secrets.properties` or user input | – | `Config.kt:18-58` |
| 9 | Release APK contains no keys | fixed | Verified for the APK in the repository root at the audit date (version 1.3) | – | audit section 2 |
| 10 | Google client secret could enter the APK through `secrets.properties` | open | `BuildConfig.GOOGLE_CLIENT_SECRET` is defined but unused; kept for the planned LiveSync (1.4). Status of resetting the old secret: unknown | before 1.4 | `app/build.gradle.kts:46-47` |
| 11 | App update | partly fixed (T-015) | HTTPS required for GitHub assets; a custom update URL may use HTTP. Before the installer opens, the app checks SHA-256 (when `update.json` gives one), package name, versionCode and signer certificate against the installed app. The hash only detects damaged downloads (it comes from the same release); the signer check is the real protection. A custom address or the GitHub API fallback has no hash. Not device tested yet (signer check and API below 28 unverified) | device test (T-015) | `AppUpdate.kt`, `UpdateVerify.kt`, `Round17Logic.kt` (`UpdateLogic`) |
| 12 | Parental-controls PIN has 4 digits | open | Lock after 5 failed attempts exists. Decision on a change pending | none planned | `ParentalLogic.kt:20-26` |
| 13 | Cleartext traffic allowed globally | open | Required for IPTV providers. No `networkSecurityConfig` | none planned | `AndroidManifest.xml:36` |
| 14 | Licence terms of TMDB and IMDb data | open | Unverified; to be documented only | PENDING | audit section 8 |
| 15 | No documented vulnerability reporting channel | open | No `SECURITY.md` in the repository root (audit section 6) | maintainer decision | repository root |
| 16 | Xtream backup servers receive the playlist's username and password | open (by design) | Credentials go only to addresses the user entered for that playlist (`HostFailoverLogic.pickGroup`). A backup entered without a scheme becomes `http://`, so with an `https` main server the credentials go over plain HTTP | T-009 follow-up (UI hint, decision pending) | `HostFailover.kt`, `PlaylistStore.kt` |

## 4. Follow-ups found in T-002 (not in the audit)

Source: project note `t002_stand.md`.

| Item | Status | Detail |
|---|---|---|
| `/restore` reserves 4 MB up front (after the code check) | open | |
| ZIP parsing in `ListImport.kt` has no size limit | open | |
| Error message „Eingabe unvollständig oder ungültig“ (incomplete or invalid input) is shown for several different cases | open | Better messages need new texts in 10 languages (maintainer decision) |

## 5. Android configuration (facts, not status items)

- `allowBackup=false`, `usesCleartextTraffic=true`, `largeHeap=true` (`AndroidManifest.xml:35-37`).
- `BootReceiver` is exported (`AndroidManifest.xml:39-71`).
- Permissions include `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, `SCHEDULE_EXACT_ALARM`, `FOREGROUND_SERVICE_DATA_SYNC` (`AndroidManifest.xml:11-18`).

## 6. Verified baseline

- The release APK for version 1.3 was built without API keys (audit section 2).
- `keystore.properties` and `local.properties` are ignored by Git (`.gitignore:15-19`).

## 7. Not checked

- Known vulnerabilities in dependencies (OSV-Scanner not run).
- mobsfscan not run.
- Android Lint, ktlint and detekt not run.
- No device test of the security items.

## 8. Planned work

Task status is tracked in `docs/REFACTORING-PROGRESS.md` and `docs/tasks/WORK_STATE.md`.
