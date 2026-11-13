# T-019 Live TV channel row: focus change no longer rebuilds the row content

- **Goal:** less work per focus step in the Live TV channel list (held Up/Down on a Fire TV Stick). First item of the performance series after T-007.
- **Cause (hypothesis, not measured):** `ChannelLine` read its focus state in the same scope that builds the row content. Every focus move therefore ran the guide lookup (`Epg.current`), up to three time formats (`hhmm`) and the favorite, catch-up and dead-link checks again, for the row that lost and the row that gained the focus.
- **Change:** the row content moved unchanged into the new private composable `RowScope.ChannelLineContent(item, epg, variantCount)` in `LiveTab.kt`. It does not read the focus state, so Compose skips it on a focus change; `ChannelLine` keeps frame, scale, key handling and the guide loading. The dead-link icon now reads `DeadLinks.count` as a change signal, because the focus recomposition that used to refresh it by accident is gone.
- **Files:** `LiveTab.kt` (`ChannelLine`, new `ChannelLineContent`).
- **Acceptance:** same look and behaviour of the channel rows; fewer recompositions per focus step.
- **Risks:** the progress bar of the running show was recomputed on each focus move of that row; it now updates when the row is recomposed for another reason (guide data arrives, favorite or catch-up state changes, row scrolls back in). Skipping relies on strong skipping (default of the Compose compiler in Kotlin 2.2; **unverified** in a build).
- **Checks run:** bracket balance of the changed file only. **Not run:** compile, unit tests, device test, any measurement.
- **Device test (release build):** (1) Live TV > "Alle Sender": hold Down for 10 s, then Up: focus follows without stalls, rows show logo, title, show times and progress bar as before. (2) Menu key on a channel > "Zu Favoriten": the star appears at once; remove it again. (3) Switch category, rename a channel in an own list, set a reminder from the guide: unchanged. (4) Start a channel that does not play, go back: warning icon appears on that row.
- **Measurement (optional):** `adb shell dumpsys gfxinfo com.ipvio.stream reset`, hold Down for 10 s, `adb shell dumpsys gfxinfo com.ipvio.stream`: compare "Janky frames" and the 90th/95th percentile before and after.
- **Rollback:** `git revert` of the single commit of this task.
- **Status:** implemented, not built, not tested on a device.
