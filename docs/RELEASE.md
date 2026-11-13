# Releasing a new version

IPVIO Stream checks the latest GitHub release once a day and offers the update in the bell at the top right.
It reads `update.json`, a small file you attach to every release (section 3). Without that file it falls back to the GitHub API.
Android installs an update **over** the old app (and keeps playlists, favorites, history and settings) only if
both APKs are signed with **the same key**. Lose the key and every user has to uninstall and start from scratch.

## 1. One-time: the signing key

### If version 1.0 was built with "Build APK" in Android Studio (debug key)

That APK is signed with the debug key of your computer:
`C:\Users\<you>\.android\debug.keystore` (Windows) or `~/.android/debug.keystore` (macOS/Linux).
To let 1.0 users update without losing anything, keep signing with exactly this key:

1. Copy `debug.keystore` to a safe place **outside** the project (and make a backup, e.g. on a USB stick).
2. Create `keystore.properties` in the project root (it is ignored by Git):

```properties
storeFile=C:/keys/ipvio-debug.keystore
storePassword=android
keyAlias=androiddebugkey
keyPassword=android
```

### Starting fresh with your own key (recommended for the future)

Android Studio: **Build > Generate Signed App Bundle / APK > APK > Create new…**, or:

```
keytool -genkeypair -v -keystore ipvio-release.jks -alias ipvio -keyalg RSA -keysize 4096 -validity 36500
```

Then the same `keystore.properties` with your file, alias and passwords.
Users of an APK signed with a different key have to uninstall once (Settings > Storage > Create backup first, then restore).

**Never commit the key or `keystore.properties`.** Both are already in `.gitignore`.

## 2. Every release

1. In `app/build.gradle.kts` raise `versionCode` (by 1) and `versionName` (e.g. `"1.2"`).
2. Build the signed APK: `gradlew assembleRelease` (or Build > Generate Signed App Bundle / APK).
   The file is `app/build/outputs/apk/release/app-release.apk`.
3. Rename it to **`IPVIO-Stream.apk`**.
4. Create `update.json` for exactly this APK (section 3).
5. On GitHub: **Releases > Draft a new release**
   - Tag: `v` + versionName, e.g. `v1.2` (the app compares this tag with its own version)
   - Title and notes: what is new (the first lines appear in the app)
   - Attach **both** `IPVIO-Stream.apk` and `update.json`
   - **Publish release** (not "pre-release", the app ignores those)
6. Check: open `https://github.com/Emrodev/IPVIO-Stream/releases/latest/download/update.json` in a browser. It must show your JSON.

The download link in the README and the Downloader code always point to the newest release:
`https://github.com/Emrodev/IPVIO-Stream/releases/latest/download/IPVIO-Stream.apk`

## 3. update.json (Windows)

The app downloads `https://github.com/Emrodev/IPVIO-Stream/releases/latest/download/update.json` first. It contains the
SHA-256 of the APK, so the app can reject a damaged download before the installer opens.

1. Put the final `IPVIO-Stream.apk` in a folder, open **Command Prompt** there (type `cmd` in the folder's address bar).
2. Run `certutil -hashfile IPVIO-Stream.apk SHA256`.
   The second line is the hash: 64 characters `0-9 a-f`. Older Windows versions print it with spaces; the app ignores spaces and upper/lower case.
3. Create `update.json` with Notepad (save as **UTF-8**, file type "All files", name exactly `update.json`):

```json
{
  "versionCode": 5,
  "versionName": "1.4",
  "apk": "IPVIO-Stream.apk",
  "sha256": "PASTE-THE-64-CHARACTER-HASH-HERE",
  "minSupportedCode": 0,
  "mandatory": false,
  "notes": "What is new, one short line"
}
```

| Field | Meaning |
|---|---|
| `versionCode` | Must equal `versionCode` in `app/build.gradle.kts` for this APK. The app refuses an APK with another number. |
| `versionName` | Shown to the user; use the same as the tag without `v`. |
| `apk` | Name of the APK asset in the same release (a full `https://` address also works). |
| `sha256` | Hash from step 2. **Required** for the built-in source. |
| `minSupportedCode` | Installed apps with a lower `versionCode` get a required update (no "Später" button). `0` = off. |
| `mandatory` | `true` = this update is required for everybody. Back still closes the dialog, so nobody is locked out. |
| `notes` | Optional text under the version in the update dialog (max 300 characters). |

4. Attach `update.json` to the release together with the APK (step 5 of section 2).

Rules:
- **Every time you rebuild the APK, calculate the hash again** and update `versionCode` and `sha256`. A wrong hash makes every user see "Prüfsumme stimmt nicht".
- Use `"mandatory": true` or `minSupportedCode` only for real emergencies (a broken release). Both are visible to all users.
- If `update.json` is missing or broken, the app falls back to the GitHub API. That path has **no hash**; the signature check still applies.
- A custom update address (Settings > App update) may point to an `update.json` without `sha256`; if present it is checked.

## 4. What users see

- Within a day the bell shows a red dot and "Update available: version 1.2". One click downloads and opens the installer.
- Settings > About > App update: check now, install, or turn off the automatic check.
- The first time, Android asks to allow "Install unknown apps" for IPVIO Stream.

Version 1.0 has no update check yet, so 1.0 users install 1.1 once by hand (Downloader code or link). From 1.1 on it works from inside the app.

Before the installer opens, the app checks the download and shows a clear text instead of Android's "App not installed":
wrong checksum, incomplete download, not an app file, another app, version not newer, version number differs from `update.json`,
or a signature that does not match the installed app (for example a debug build over a release build).
