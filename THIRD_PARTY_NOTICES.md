# Third-party notices

IPVIO Stream itself is licensed under the GNU General Public License v3 (see `LICENSE`). It uses the following third-party components.

## Libraries

| Component | License |
|---|---|
| AndroidX (Activity, Compose UI/Foundation, Work) | Apache License 2.0 |
| AndroidX Media3 (ExoPlayer, HLS, DASH, UI) | Apache License 2.0 |
| Jellyfin Media3 FFmpeg decoder (`org.jellyfin.media3:media3-ffmpeg-decoder`) | GNU GPL v3 (contains FFmpeg) |
| OkHttp (Square) | Apache License 2.0 |
| Coil (`io.coil-kt:coil-compose`) | Apache License 2.0 |
| Kotlin standard library and coroutines (JetBrains) | Apache License 2.0 |
| JUnit 4 (tests only) | Eclipse Public License 1.0 |

The libraries are downloaded by Gradle at build time and are not stored in this repository. Their licence texts are available from the respective projects.

## Fonts

| Font | License | File |
|---|---|---|
| Outfit (The Outfit Project Authors) | SIL Open Font License 1.1 | `licenses/OFL_Outfit.txt` |
| Inter (The Inter Project Authors) | SIL Open Font License 1.1 | `licenses/OFL_Inter.txt` |

The font files in `app/src/main/res/font/` are subsets/copies of the original fonts and remain under the OFL.

## Online services (optional, user-supplied keys)

| Service | Purpose | Notes |
|---|---|---|
| The Movie Database (TMDB) | film and series information, images | *This product uses the TMDB API but is not endorsed or certified by TMDB.* Users must provide their own API key and follow the TMDB terms of use. |
| OMDb API | IMDb and Rotten Tomatoes ratings | Users must provide their own API key. |
| TheIntroDB | intro, recap and credits times | Read access without a key. |

IMDb, Rotten Tomatoes, TMDB, Android TV, Google TV and Fire TV are trademarks of their respective owners. IPVIO Stream is not affiliated with or endorsed by them.
