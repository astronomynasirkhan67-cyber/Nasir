# Settings overview

All settings are on the **Settings** tab of the sidebar. They are grouped into tiles.

| Tile | What it does |
|---|---|
| General | Start screen, exit behaviour, autostart, landscape on phones |
| Language & region | App language (10 languages), clock format, time zone |
| View | Card size and artwork with live preview, sidebar position (left, right, top) and labels, grid, default cinema view |
| Design | Colour theme, background, fonts (Outfit, Inter, system), text and display size, animations |
| Menu | Order and visibility of sidebar entries |
| Player | Seek steps, skip intro/credits, quality, 4K check, source fallback on broken links, frame-rate matching, aspect ratio |
| Audio & subtitles | Preferred audio and subtitle languages, subtitle style, remember per title |
| Genres & categories | Automatic genres or provider categories, hide categories |
| Live TV & guide | TV guide source, channel lists, reminders, rewind live TV (on-device buffer), recordings and their storage |
| Playlists | Add, edit, remove playlists, show several at the same time ("Also show"), language filter, refresh interval or fixed time |
| Profiles | Per-person history, favourites and parental controls |
| API keys | Your own TMDB and OMDb keys, with a connection test |
| Parental controls | PIN, hide adult content |
| Storage | Cache, history, resume points, backup, transfer to another device over Wi-Fi, optional Google Drive |
| Diagnostics | Status and troubleshooting information |
| About | Version, app updates from GitHub, sources, reset |

## API keys
Step-by-step instructions for the free TMDB and OMDb keys are in the [README](../README.md#your-own-api-keys-optional). Enter them under *Settings > API keys* and press **Save and check**. Without keys the app uses the information of your Xtream provider.

## Typing on a TV
Use the phone import: *Playlists > Enter from phone* shows an address; open it on a phone in the same Wi-Fi and type the login there.

## Several playlists at once
*Playlists > Also show* adds a playlist to the active one. Movies and series appear together: the same movie only once,
the same series as one series whose episodes have several sources. Filter by **Source** in Movies and Series; in Live TV
the **Source** entry at the top of the category list switches between all playlists and a single one.

## Rewind and record live TV
*Live TV & guide > Rewind live TV* keeps the running channel in a buffer on the device (256 MB – 2 GB). Pause without
missing anything, jump back with the rewind keys or "30 s back", and return with "Back to live". The red button in the
player records the current show until its end in the TV guide (from its beginning if it is still in the buffer).
Recordings are listed under *Live TV > Recordings* and in the settings, where you can also choose internal storage or a USB stick.

## Transfer to another device
*Storage > Start transfer over Wi-Fi* shows an address and a code. On the old device download the backup on your phone
(optionally with playlists), on the new device start the transfer as well and upload the file. Optional: Google Drive
(see [GOOGLE_DRIVE.md](GOOGLE_DRIVE.md)). The same page shows the last crash report so you can copy it on the phone.
