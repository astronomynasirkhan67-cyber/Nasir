# Features

Feature status at version 1.3 (commit `b65e838`, tag `V1.3`). Source: refactoring audit of 2026-11-11, section 4. File references are to `app/src/main/java/com/ipvio/stream/`.

| Feature | Status | Notes and source |
|---|---|---|
| Xtream playlists | present | fallback to `player_api.php` when `get.php` fails (`XtreamApi.kt:16`) |
| Xtream backup servers with failover | present (T-009, device test pending) | `HostFailover.kt`; see `docs/tasks/T-009-xtream-backup-servers.md` |
| M3U playlists (URL and file) | present | `Models.kt:298`, `CatchupLogic.kt:68` |
| Stalker/Ministra portals | present | including series: placeholders, episodes load on open (`StalkerApi.kt:16-17, 28`) |
| Several playlists at once | present | `MultiLogic.kt:3-5`, `AreaSource.kt:7` |
| Live TV and TV guide (XMLTV) | present | `Epg.kt:50, 75` |
| TMDB / OMDb metadata | present | only with the user's own keys (`Meta.kt:318, 446`, `Config.kt:7-17`) |
| Lazy series loading | present | `LazySeries.kt:8-13` |
| Catch-up | present | works only if the provider supplies it (`Catchup.kt:19-24`) |
| Live rewind with buffer (256-2048 MB) | present | `LiveTimeshift.kt:21, 152` |
| Recording | present | `Recorder.kt:18-24` |
| Second stream (picture-in-picture or split) | present | `DualView.kt:43, 148` |
| Multi view | present | `MultiView.kt:32` |
| Sports hub (ESPN, leagues, tables, alerts) | present | `SportHub.kt:49`, `SportLeague.kt:72`, `SportAlerts.kt` |
| Profiles (max. 5) | present | `Profiles.kt:49, 113` |
| Parental control | present | 4-digit PIN, SHA-256 with salt, lock after 5 failed attempts (`ParentalLogic.kt:15-26`, `Parental.kt:10`) |
| Boomer mode (simplified interface) | present | `Boomer.kt:69-73` |
| Backup and restore (`ipvio_backup.json`) | present | `Backup.kt:13-28` |
| Phone import, transfer, crash report | present | ports 8765-8775 (`PhoneImport.kt:195, 276-373`) |
| Phone search (`/r`, `/api/...`) | present | `PhoneImport.kt:373-380`, `RemoteLogic.kt:67`, `RemoteHub.kt:21` |
| List import (Letterboxd, MAL, Trakt) | present | `ListImport.kt:6-10` |
| Favorites for channels, movies and series with manual order | present (order: T-018) | `Store.kt` (`Library`), `FavOrderLogic.kt`, `FavReorder.kt`, `HomeScreen.kt` (Favorites tab), `LiveTab.kt` |
| Hide categories, ignore groups, language filter | present | `HiddenCats.kt:6`, `IgnoredGroups.kt:8`, `LangFilter.kt:20` |
| Live quality bundles | present | `LiveBundle.kt:3` |
| Voice search, reminders, screen-edge calibration | present | `VoiceSearch.kt:8`, `Live.kt:71, 192`, `FitCalibration.kt:35` |
| Updates via GitHub Releases | present | verified by Android signature, no hash check (`AppUpdate.kt:20-34`, `Round17Logic.kt:14-45`) |
| 10 languages | present | `I18nCore.kt:9` |
| Google Drive backup | removed in 1.2, planned for 1.4 (LiveSync) | `DriveSync.kt` is empty. Old code: tag `V1.1` (`DriveSync.kt`, `TransferUi.kt`). See [GOOGLE_DRIVE.md](GOOGLE_DRIVE.md) |
| Licence system | not in the repository | removed on purpose when the project became open source (audit section 14) |
| IMDb dataset import | removed | only clean-up of old files remains (`General.kt:208-212`); IMDb values come through OMDb |

Names from older notes without a match in the code: "add-on system" (no hit). "Multiverse" has no hit either; "Multi view" exists.

## Planned

- 1.4: Google Drive as LiveSync (continuous sync instead of a single backup). Starts after the refactoring. A new OAuth client ID is needed first. TODO: status of resetting the old client secret is unknown.
- No new features during the refactoring.
