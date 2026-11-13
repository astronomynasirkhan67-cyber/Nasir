# T-023 Sport tab: match games to channels off the main thread

- **Goal:** the sport tab appears at once; data follows in the background.
- **Cause (hypothesis, not measured):** `SportTab` compared every game with every running and upcoming sports show (`SportData.matches`) inside a `remember` block, so on the main thread during composition.
- **Change:** the same computation runs in `produceState` on `Dispatchers.Default` (`SportTab.kt`, value `matched`). The manual assignments (`SportFix.map`) are copied before the background work starts.
- **Risk:** the channels of a game appear a moment after the game rows; after a manual assignment the row updates one step later.
- **Checks run:** bracket balance of the changed file. **Not run:** compile, unit tests, device test, measurement.
- **Device test (release build):** (1) Open Sport: the page appears at once, games show their channels shortly after. (2) Start a game through one of its channels. (3) Assign a channel to a game by hand, then reset it: the row follows.
- **Rollback:** `git revert` of the single commit.
- **Status:** implemented, not built, not tested on a device.

## Reviewed in the same batch, no change

- `PlayerLive.kt` (`ChannelRow`): text and icon colors depend on the focus, so the row content has to recompose on a focus change; the guide lookup already runs off the main thread. Nothing to split as in T-019.
- `PlaylistStore.kt`: the rebuild and the re-read on a mode or language change are rare actions, not scrolling or start-up, and belong to the protected IPTV core. No blind change without a build and a test playlist.
- `PlayerScreen.kt`: candidate noted, not changed: the position ticker (every 500 ms during movie and series playback) writes `pos`, `dur` and `buffered`, which are read in the screen's main scope. Needs an approved plan and a device test (protected player core).
- Series pages and `HomeScreen.kt`: grouping and sorting already run in the background (`buildHome`) or inside `remember`; `SportHub.kt` builds its overview from the day's games only (small lists).
