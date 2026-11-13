# T-014 First-start update check card

Status: DONE, not device tested. Merged through PR #57 (`9eb22ee`), CI green. Marked as done by the maintainer on 12 Nov 2026 without a device test; the test steps below stay open. Option a2 (decided by the maintainer on 12 Nov 2026).

## Goal

On the first start, show a non-blocking card "Check for updates" that runs `AppUpdate.check()` and shows the result visibly:
checking / up to date / update available (button opens the existing `UpdatePromptHost` flow) / error with "Retry".
The card also has the toggle for the automatic check (`AppUpdate.setAuto`). It is shown only once.

## Affected files

- `UpdateCardLogic.kt` (new): pure rules (phases, when to show, error classification, primary button). No Android imports.
- `UpdateCard.kt` (new): `UpdateCardHost()`, the Compose card. Reuses `PillButton` and `ToggleRow`.
- `AppUpdate.kt`: two small functions for the new key `first_card_seen` in `ipvio_upd` (`firstCardSeen`, `markFirstCardSeen`). Existing keys and functions are untouched.
- `MainActivity.kt`: one line in `App()`, `if (picked) UpdateCardHost()` after `UpdatePromptHost()`.
- `DictK.kt`: six new rows in 10 columns under "First-start update check card". The existing keys "Neue Version {0} (installiert: {1})", "Erneut versuchen", "Schließen" and "Automatisch nach Updates suchen" are reused.
- `UpdateCardLogicTest.kt` (new): 12 JUnit tests.
- No new dependency, no manifest change.

## Behaviour (decisions in the code)

- **Shown once:** the key `first_card_seen` is written the first time the decision is made, whether the card is shown or skipped. It is shown only if the key is absent **and** no playlist is saved yet. Consequence: users who update an existing install (they have a playlist) never see the card, and the key is set silently for them. A fresh install or cleared data shows it.
- **Non-blocking, no focus steal:** no `requestFocus`, no `Dialog`. `AddPlaylistScreen` keeps its own start focus. The card sits at the bottom centre; the D-pad reaches it by moving down, OK activates the toggle and the buttons.
- **No automatic start dialog while the card is up:** `check()` sets `AppUpdate.available`, which would open the modal `UpdatePromptHost` and take the focus. The card therefore sets `AppUpdate.promptDismissed = true` before it checks. "Update anzeigen" sets it back to `false`, which opens the existing dialog ("Jetzt aktualisieren" / "Später"). Side effect: if the card is closed without opening the dialog, the start dialog stays hidden until the next app start (same as "Später"); the bell at the top right still shows the update.
- **Errors:** an `IOException` (no network, DNS, timeout, TLS) shows "Keine Verbindung. Prüfe das Netzwerk und versuche es erneut." with "Erneut versuchen". Other errors (for example "Fehler 403" or "Noch keine Veröffentlichung gefunden" from `AppUpdate`) show their short message, also with "Erneut versuchen". The check itself ends after at most about 25 s through the OkHttp timeouts (connect 10 s, read 15 s); the card adds no timer of its own.
- **Backup:** `ipvio_upd` is part of the backup (`Backup.NAMES`), so the new key travels with it. Restoring a backup on a fresh device therefore does not show the card. Intended.

- **Closes by itself (T-025):** the card goes away as soon as a playlist is being loaded or is saved, or the demo mode is entered. See `T-025-update-card-auto-hide.md`.

## Acceptance criteria

1. Fresh install or cleared data: the card appears on the add-playlist screen and shows "Suche nach Updates …", then one of the result states.
2. Up to date: shows the installed version.
3. Older APK installed and a newer release on GitHub: shows "Neue Version … (installiert: …)"; "Update anzeigen" opens the existing dialog; no dialog opens by itself.
4. Offline: error text and "Erneut versuchen"; after the network is back, Retry shows the result.
5. The toggle changes `AppUpdate.auto` (same value as in Settings > App-Aktualisierung).
6. Restart of the app: the card does not appear again.
7. The add-playlist screen still starts with the focus on its first button; the remote works on the card (down, left/right, OK).

## Risks

- The assistant has no Android SDK, so `UpdateCard.kt` was first compiled by the CI build of PR #57 (unit tests and debug APK: success). It has never run on a screen.
- Visual placement is unverified: the card sits at the bottom centre and may cover lower fields of the Xtream/Stalker form on small screens. Position or size may need a change after the device test.
- D-pad "down" from the lowest form element may now land on the card instead of leaving the screen. **Unverified** on a real remote.
- Two checks can run at the same time on the first start (the card, and `AppUpdate.startCheck` after 3 s when the automatic check is on). Both read the same GitHub answer; harmless, one extra request.

## Documentation needed

- `CHANGELOG.md` entry (done). `docs/SETTINGS.md` was not changed (the card adds no setting).

## Test results

Run by the assistant (JVM, Kotlin 2.0.21 compiler from the Gradle distribution, JUnit 4.13.2):
- `UpdateCardLogicTest`: 12 of 12 passed.
- `DictConsistencyTest`: 3 of 3 passed (placeholders in the new rows).

CI on PR #57 (GitHub Actions `Build APK`: `testDebugUnitTest` and `assembleDebug`): success. Not run: any device test.

Test steps for the maintainer: see the report of this task (fresh install / cleared data, offline, older APK installed).
