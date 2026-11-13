# T-009 Default playlist refresh: daily

- **Goal:** the app must not download the whole line on every start.
- **Cause:** `General.refreshMode` defaulted to 0 ("at every start"); every start re-downloaded the playlist in the background (Stalker: thousands of requests).
- **Change:** `General.kt`: `DEFAULT_REFRESH_MODE = 2` (daily, 04:00 by default) when `refresh_mode` was never stored. The first start still loads once (`last_refresh` = 0). Stored choices are unchanged; the options (every start, every X hours, daily, weekly, by hand) stay.
- **Approved by:** maintainer, 2026-11-11 ("standardmäßig auf täglich").
- **Risk:** users who never chose a mode now see yesterday's list until 04:00 or until "Neu laden". Background refresh runs through WorkManager (`Updater.apply`, mode 1-3).
- **Checks run:** none specific (one-line default). CI build.
- **Device test:** fresh install → list loads once; close and reopen → no loading, list from cache at once; Settings → "Automatisch aktualisieren" shows "Täglich".
- **Status:** implemented on branch `fix/round28-major`, device test open.
