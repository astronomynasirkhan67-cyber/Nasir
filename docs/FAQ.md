# FAQ

**Does the app include channels, films or series?**
No. It is only a player. You add your own legal playlist (Xtream Codes or M3U).

**Where do descriptions and ratings come from without keys?**
With an Xtream Codes login the app reads them from your provider (description, cover, rating, genres, running time, trailer). A plain M3U address has no such data, so you only see what the list contains.

**Why are there no posters or ratings?**
Add your own free TMDB key (posters, descriptions, cast) and OMDb key (IMDb / Rotten Tomatoes) in *Settings > API keys*.

**Why is the app GPL-3.0?**
It includes the Jellyfin FFmpeg audio decoder (GPL-3.0), which is needed for AC3/E-AC3/DTS sound. Derived works must stay open source under the same license.

**Is there a phone version?**
The app is designed for TV and the remote. On phones and tablets it runs in landscape: tap the video to show the controls,
double-tap left/right to skip, double-tap in the middle to pause.

**My Xtream login works in other apps but not here (e.g. "error 884").**
Some providers block or limit `get.php`. Since 1.1 the app then loads the account through `player_api.php` automatically.

**Can I use a Stalker portal (MAC address)?**
Yes: *Add playlist > Stalker portal*, enter the portal address and the MAC address from your provider. Live channels and
movies are loaded; series are not supported yet.

**Will I lose my data when I update?**
No, as long as the new APK is signed with the same key as the installed one (see [RELEASE.md](RELEASE.md)). The app
offers updates itself once a day.

**Where are my logins stored?**
Only on your device, in app-private storage. They are never sent anywhere except to the playlist server you entered.

**Can I use the name and logo?**
For your own modified version, please choose another name and logo.

**How do I install the APK on Fire TV / Android TV?**
Enable "Install unknown apps" for a file manager or the Downloader app, download the APK from the Releases page and open it.
