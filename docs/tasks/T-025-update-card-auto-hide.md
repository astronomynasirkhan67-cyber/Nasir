# T-025 First-start update card closes by itself

## Goal

Maintainer feedback (12 Nov 2026): the first-start card with the update check and the "Automatisch nach Updates suchen" toggle stays on screen and gets in the way. After a playlist was added it must disappear by itself.

## Findings

- `UpdateCardHost` (`UpdateCard.kt`, T-014) was closed only by its "Schließen" button. It is drawn above `AppContent`, so it stayed on top of the loading screen and the home screen.
- A new playlist enters `PlaylistStore.lists` only after it has loaded (`finishSave`); while it loads, `PlaylistStore.loading` is true.

## Change

- `UpdateCardLogic.kt`: new pure rule `leftFirstStart(hasPlaylist, loading, demo)`.
- `UpdateCard.kt`: the card is not drawn when the rule is true, and it is marked closed so it does not come back in this session (for example after a failed load or after deleting the playlist).
- `UpdateCardLogicTest.kt`: one new test.
- Decision by the assistant, not asked for explicitly: the demo mode ("Weiter ohne Playlist") closes the card as well, because it would cover the home screen in the same way.

## Not changed

Storage keys (`first_card_seen`), the texts, the update check itself, `AppUpdate`, `MainActivity.kt`. The setting stays reachable under Settings > App-Aktualisierung.

## Acceptance criteria

1. Fresh install: the card shows on the add-playlist screen as before.
2. Confirming a playlist: the card disappears when loading starts and does not return, also when the load fails.
3. "Weiter ohne Playlist": the card disappears.
4. Next app start: no card (unchanged, T-014).

## Risks

- As with "Schließen": if an update was found and the card closes, the start dialog stays hidden until the next app start; the bell at the top right still shows the update.
- A running check is cancelled when the card closes (handled by `UpdateCardLogic.ignorable`).
- `UpdateCard.kt` was not compiled.

## Test results

- Ran: `UpdateCardLogicTest` 13 of 13 on the JVM (Kotlin 2.2.10 compiler, with a stand-in for JUnit; the real jar was not reachable).
- Not run: Gradle build, device test.

## Test steps for the maintainer

1. Clear the app data (or fresh install), start: the card is at the bottom of the add-playlist screen.
2. Enter a playlist and confirm: the card is gone during loading and on the home screen.
3. Clear the data again, start, choose "Weiter ohne Playlist": the card is gone.
4. Clear the data again, enter a wrong address: after the error the card stays gone.

## Rollback

Revert the commit (three source files).

## Status

Released with 1.4. Reported by the maintainer on 12 Nov 2026: built and tested on the device, works. The individual checks and any measurements were not recorded.
