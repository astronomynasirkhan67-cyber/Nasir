# T-015 Harden the in-app update

Status: IMPLEMENTED and merged (PR #59, `2fbd874`), CI green; **NOT device tested.** The maintainer tests at the end (test steps below). Plan approved by the maintainer on 12 Nov 2026; the three open decisions were left to the assistant (recommended options taken).

## Goal

1. Primary source: `releases/latest/download/update.json` (release asset) with `versionCode`, `versionName`, `apk`, `sha256`, `minSupportedCode`, `mandatory`, `notes`. Fallback: the existing GitHub API address. A custom address (Settings > App update) keeps working, `sha256` optional there.
2. Before the installer opens: SHA-256 of the download, package name, versionCode and signer certificate of the APK against the installed app. Clear texts instead of Android's "App not installed".
3. No new dependency, no manifest change, no change to existing `ipvio_upd` keys, no CI change.
4. Pure logic in `UpdateLogic` with JUnit tests.
5. `docs/RELEASE.md` with the Windows steps for `update.json`.

## Affected files

- `Round17Logic.kt` (`UpdateLogic`): `parseManifest`, `normalizeSha`, `hex`, `sameHash`, `isRequired`, `versionProblem`, `sameSigners`. Pure.
- `AppUpdate.kt`: `PRIMARY_URL`; `check()` tries it first and falls back to `DEFAULT_URL` on any error; `UpdateInfo` gets `sha256`, `minSupportedCode`, `mandatory` (defaults keep old call sites valid); `download()` hashes while writing and rejects an incomplete or wrong-hash file; `downloadAndInstall()` runs `UpdateVerify.check` before `install()`; the `known` JSON keeps the new fields (`h`, `s`, `m`) so the bell still verifies after a restart.
- `UpdateVerify.kt` (new): reads the APK with `getPackageArchiveInfo` (`GET_SIGNING_CERTIFICATES` on API 28+, `GET_SIGNATURES` below) and checks package name, versionCode, signer.
- `UpdatePrompt.kt`: for a required update the text "Dieses Update ist erforderlich." is shown and "Später" is hidden. Back still closes the dialog.
- `DictK.kt`: 10 new rows (error texts, required hint) in 10 columns.
- `UpdateLogicTest.kt` (new): 15 tests. `docs/RELEASE.md`, `docs/SECURITY.md` (item 11), `CHANGELOG.md`, `WORK_STATE.md`.

## Behaviour (decisions in the code)

- **Source order:** only when the stored address is the built-in one. `update.json` answers "no update" (versionCode not higher): the check ends there, the API is not asked. `update.json` fails (404, no network, bad JSON, no valid `sha256`, non-HTTPS `apk`): the GitHub API is asked, as before T-015. With a custom address only that address is used.
- **`sha256` required** for the built-in `update.json` (a file without it counts as incomplete and triggers the fallback). Optional for a custom address and absent on the API path; if present it must be well-formed and is checked.
- **Checks on the downloaded file, for every source:** complete (size equals Content-Length when known), hash (if given), valid APK, package name = `com.ipvio.stream`, versionCode higher than installed and equal to `update.json`'s `versionCode` (if given), signer certificates equal to the installed app's, or the installed signer is in the new APK's signing history (key rotation, API 28+). The file is deleted when a check fails.
- **Required update:** `mandatory` is true, or installed versionCode < `minSupportedCode`. Effect: no "Später" button and a hint. Back still dismisses the dialog until the next start, so an offline user is never locked out. The bell and Settings are unchanged.
- **Protected items untouched:** `ipvio_upd` keys `url`, `auto`, `last`, `last_start`, `known`, `first_card_seen`. Only the value of `known` carries three extra fields; old entries without them are read as "no hash, not required".
- **What the hash does and does not do:** `update.json` and the APK come from the same release, so the hash catches damaged or partial downloads, not a manipulated release. The signer check is the real protection against a replaced APK.

## Acceptance criteria

1. Release with APK and `update.json`: the app finds the update through `update.json` (bell, Settings > App update > search now).
2. Release without `update.json`: the app still finds the update through the GitHub API.
3. A custom address with a JSON without `sha256` still works.
4. Wrong `sha256` in `update.json`: download ends with "Prüfsumme stimmt nicht …", no installer opens.
5. APK signed with another key than the installed app: "Die Signatur passt nicht zur installierten App …", no installer opens.
6. `versionCode` in `update.json` differs from the APK's: "Die Versionsnummer passt nicht zur Update-Datei."
7. Correct file: installer opens as before; playlists and settings survive.
8. `mandatory: true` or `minSupportedCode` above the installed code: no "Später" button; Back closes the dialog.

## Risks

- `UpdateVerify.kt` and the changes in `AppUpdate.kt` / `UpdatePrompt.kt` use Android APIs. The assistant has no Android SDK; they compile in CI (green) but have never run on a device.
- **Unverified:** `getPackageArchiveInfo(..., GET_SIGNATURES)` on API 24-27 returns the signatures of an APK file. Test on a Fire TV Stick with Fire OS 6 (API 25) or an emulator with API 24-27.
- A debug build (Android Studio, CI artifact) has another key than the release APK. Installing a release update over a debug build is rejected on purpose by the new check. Positive tests need two builds signed with the same key.
- The first release with `update.json` must be uploaded correctly: a wrong `versionCode` or `sha256` blocks every user's update with a clear message. RELEASE.md says to check the URL in a browser.
- Redirect: `releases/latest/download/update.json` answers with a redirect to GitHub's asset host; OkHttp follows it. **Unverified** until the first release carries the file.
- A backup contains `ipvio_upd` (and so `known`). A restored `known` without a hash skips the hash check; the signer check still applies.

## Documentation needed

`docs/RELEASE.md` (done), `docs/SECURITY.md` item 11 (done), `CHANGELOG.md` (done).

## Test results

**Device test: NOT done.** Nothing in this task has run on a phone or Fire TV yet. The maintainer will test at the end.

Run by the assistant (JVM, Kotlin 2.0.21 compiler and JUnit 4.13.2 from the Gradle distribution):
- `UpdateLogicTest`: 15 of 15 passed. **Caveat:** the real `org.json` jar was not available offline, so the run used a small stand-in `JSONObject` (flat objects only). The real library runs in CI.
- `DictConsistencyTest`: 3 of 3 passed.

CI on PR #59 (`testDebugUnitTest` and `assembleDebug`): success, so the Android files compile and all unit tests pass with the real `org.json`. Not run: any device test.

## Test steps for the maintainer

Phone and Fire TV (use a release-signed build with a lower `versionCode` as the installed app, for example 1.3 / code 4; publish a test release with a higher code):

1. **Normal path:** release `v1.4-test` as a normal release (not pre-release) with `IPVIO-Stream.apk` and a correct `update.json`. Open the app, Settings > App update > "Jetzt nach Update suchen". Expect "Neu: Version …". Tap "Herunterladen und installieren": progress, then Android's installer. Install; playlists and settings must still be there.
2. **Wrong hash:** change one character of `sha256` in `update.json` (replace the asset). Expect "Prüfsumme stimmt nicht …"; no installer.
3. **Wrong versionCode:** `versionCode` in `update.json` one higher than the APK's. Expect "Die Versionsnummer passt nicht zur Update-Datei."
4. **Foreign signature:** attach a debug-signed APK with a higher code (and its correct hash). Expect "Die Signatur passt nicht zur installierten App …".
5. **Fallback:** a release without `update.json`. Search finds the update through the API; install works (no hash).
6. **Custom address:** Settings > App update > own address pointing to an `update.json` without `sha256`. Search and install work. Clear the field afterwards.
7. **Required:** `"mandatory": true`. The start dialog shows "Dieses Update ist erforderlich." and only "Jetzt aktualisieren". Back closes it.
8. **Offline:** after step 7 switch the network off and restart the app; the app must stay usable (Back closes the dialog).
9. **Fire TV Stick with Fire OS 6 (API 25), if you have one:** repeat step 1 and 4 to cover the `GET_SIGNATURES` path.
10. Delete the test releases afterwards (the app ignores pre-releases and drafts, but a normal test release would be offered to every user).
