# T-020 to T-022 Speed series (home cards, image decoding, start-up)

Follow-up to T-019. Three small changes, one commit each, each revertible on its own. **All causes are hypotheses: nothing was built or measured.**

| ID | Change | Files | Hypothesis | Risk |
|---|---|---|---|---|
| T-020 | `RowPosterCard` keeps its layout coordinates and computes its position in the window once, when the card expands, instead of on every scroll frame | `HomeParts.kt` | less work per frame while home rows scroll | expand direction (middle to both sides, edges inwards) is now decided from the position at the moment of opening |
| T-021 | Coil decodes two images at a time on devices up to 3 GB RAM (`Mem.imageDecoders`), four as before on larger ones | `Mem.kt`, `MainActivity.kt` | decoding no longer occupies all cores while scrolling | images may appear slightly later during fast scrolling |
| T-022 | `Profiles.prewarm` opens the settings files of the start-up path right after the profile is known, so they load in parallel in the background | `Profiles.kt`, `MainActivity.kt` | shorter time to the first frame | none known; no value is read or written, no name or key is changed |

- **Decisions without a change:** the held-key limit in `LiveTab.kt` (about 9 steps per second) stays until a measurement shows the list keeps up without it. `PlayerGuide.kt` already limits a row to the shows around the visible window (PR #41); the earlier note "every show of a row is built" was wrong. R8 stays off (start crash with AGP 9.4.1, see `app/build.gradle.kts`).
- **Checks run:** bracket balance of the changed files. **Not run:** compile, unit tests, device test, measurement.
- **Device test (release build):** (1) Home: scroll quickly through all rows; rest on a card in the middle, at the left and at the right edge: it expands to both sides / inwards as before. Continue watching works. (2) Movies and series grids: covers load while scrolling, none stay empty. (3) Cold start with a saved playlist and first start after clearing the data: no crash, colors and text size are right in the first frame. (4) Switch the profile and restart: the profile's settings and favorites are there.
- **Measurement:** start time with `adb shell am start -W com.ipvio.stream/.MainActivity` (value `TotalTime`, app force-stopped before each run, 5 runs). Frames with `adb shell dumpsys gfxinfo com.ipvio.stream reset`, scroll for 10 s, then `adb shell dumpsys gfxinfo com.ipvio.stream`. A freshly installed APK is not precompiled yet; for a fair comparison run `adb shell cmd package compile -m speed-profile -f com.ipvio.stream` once after installing.
- **Rollback:** `git revert` of the single commit of the item.
- **Status:** implemented, not built, not tested on a device.
