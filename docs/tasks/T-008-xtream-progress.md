# T-008 Xtream loading progress

Status: implemented as a separate commit on branch `perf/T-007`. CI debug build and unit tests run on the branch. Device test PENDING.

## Report

User, 2026-11-11: "the playlist is stuck at 256 KB while loading".

## Cause (from the code, not reproduced on a device)

`XtreamApi.write` fetches movies and series in parallel into temporary files while the channels are written to the list file. The progress (`PlaylistStore.bytesRead`) came only from `CountingOut` on the list file, which reports every 256 KB. After the channel part (often between 256 and 512 KB) nothing was counted until movies and series were complete, which can take minutes on large providers. The screen showed "256 KB" the whole time.

Not excluded: the provider really stalls a parallel request (for example a limit of one connection). Then the request runs into the read timeout (120 s, `PlaylistStore.kt:162`) and is fetched again one after the other. With this change the counter shows whether data still arrives.

## Change

| File | Change |
|---|---|
| `XtreamApi.kt` | new `onTempBytes`; `TempCounter` counts characters written to the temporary files; a repeated attempt and the final append take their part back |
| `PlaylistStore.kt` | `downloadApi`: shown bytes = list bytes + temporary bytes, never decreasing |

Not changed: download order, requests, timeouts, file names, list format.

## Test steps (maintainer)

1. Add or refresh an Xtream playlist that is loaded via the API (or one that showed "256 KB" before).
2. While "Filme werden geladen …" / "Serien werden geladen …" is shown, the number keeps growing.
3. The number never goes back; at the end the playlist is complete (number of movies and series as before).
4. If the number really stops for more than two minutes: note the text under the bar and the playlist type and report it.

## Risks

Low. Only the progress value changes. The temporary files are written through one more `FilterWriter`.
