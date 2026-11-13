# Diagrams

C4 diagrams in Mermaid. Elements come from the refactoring audit of 2026-11-11 (commit `b65e838`, tag `V1.3`). Relations are indicative. TODO: verify call directions in the code. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Level 1: System context

```mermaid
C4Context
  title IPVIO Stream - system context

  Person(user, "User", "Watches live TV, movies and series; imports and searches from a phone browser")
  System_Ext(tv, "TV device", "Android TV, Google TV or Fire TV; runs the app, remote control input")
  System(app, "IPVIO Stream", "IPTV player app (package com.ipvio.stream)")
  System_Ext(provider, "Playlist provider", "Xtream, Stalker/Ministra portal or M3U source; chosen by the user")
  System_Ext(meta, "TMDB / OMDb", "Metadata; only with the user's own API keys")
  System_Ext(espn, "ESPN", "Data for the sports hub")
  System_Ext(github, "GitHub Releases", "App updates")
  System_Ext(lists, "Letterboxd / MAL / Trakt", "Source for list import")
  System_Ext(drive, "Google Drive", "Planned for 1.4 (LiveSync). Not in the code since 1.2")

  Rel(user, tv, "Uses with remote control")
  Rel(tv, app, "Runs")
  Rel(user, app, "Phone browser: import, transfer, search (HTTP, local network)")
  Rel(app, provider, "Loads playlists, EPG and streams")
  Rel(app, meta, "Looks up metadata")
  Rel(app, espn, "Loads sports data")
  Rel(app, github, "Checks for updates, downloads APK")
  Rel(app, lists, "Imports lists")
  Rel(app, drive, "Backup / sync (planned)")
```

## Level 2: Containers / modules

The app is one Gradle module (`:app`) with a flat package. The blocks below are logical groupings, not packages or deployable containers.

```mermaid
C4Container
  title IPVIO Stream - modules inside :app

  Person(user, "User", "Remote control on TV, browser on phone")
  System_Ext(provider, "Playlist provider", "Xtream, Stalker, M3U")
  System_Ext(meta, "TMDB / OMDb", "Metadata")
  System_Ext(espn, "ESPN", "Sports data")
  System_Ext(github, "GitHub Releases", "Updates")

  Container_Boundary(app, "IPVIO Stream (:app, com.ipvio.stream)") {
    Container(ui, "UI", "Kotlin, Jetpack Compose (no material3)", "MainActivity, HomeScreen, DetailScreen, SettingsHub, LiveTab, Boomer")
    Container(player, "Player", "Media3 ExoPlayer, FFmpeg decoder by reflection", "PlayerScreen, LiveTimeshift, Recorder, DualView, MultiView")
    Container(sources, "Playlist sources", "OkHttp", "XtreamApi, StalkerApi, PlaylistStore, M3U in Models, TlsFallback")
    Container(guide, "TV guide and metadata", "OkHttp", "Epg, Meta, Config")
    Container(sports, "Sports hub", "OkHttp", "SportHub, SportLeague, SportAlerts")
    Container(phone, "Phone server", "HTTP server, ports 8765-8775", "PhoneImport, RemoteHub, RemoteLogic, RemoteUi")
    Container(data, "Local data", "SharedPreferences and files", "Store, Profiles, Backup, Secrets, Parental")
    Container(update, "Updates", "OkHttp, WorkManager", "AppUpdate, UpdateLogic (Round17Logic.kt), Updater")
    Container(lang, "Languages", "Kotlin", "Lang, I18nCore (10 languages), Dict*")
  }

  Rel(user, ui, "Remote control")
  Rel(user, phone, "Phone browser (HTTP)")
  Rel(ui, player, "Starts playback")
  Rel(ui, sources, "Loads content")
  Rel(ui, guide, "Shows TV guide and details")
  Rel(ui, sports, "Shows sports hub")
  Rel(ui, data, "Reads and writes state")
  Rel(ui, lang, "Looks up UI text")
  Rel(phone, data, "Import, backup, restore")
  Rel(sources, provider, "Playlists")
  Rel(player, provider, "Streams")
  Rel(guide, meta, "Lookups")
  Rel(sports, espn, "Fetches")
  Rel(update, github, "Release check, APK download")
```
