# T-009 Xtream backup servers with automatic failover

Status: merged into `main` through #37 (branch `feature/xtream-backup-servers`, based on `581099c`). CI unit tests and debug build passed on #37. Device test PENDING; not in a release yet.

## Origin

- Feature request in #2: up to two backup host addresses for an Xtream login, used when the main host goes down.
- First implementation in #3 (fork `mattlanglands/IPVIO-Stream`, branch `xtream-backup-servers`), commit `0db54e1`. Kept here as a cherry-pick with the original author.
- Review fixes in #35 (`a5ade1e`).
- #3 was based on an old `main` (`66f837d`), conflicted in five files and still contained the commit of #2 (`cecb52d`), which was closed without merging. This branch drops `cecb52d` and keeps the text-field handling of `main`.

## Behaviour

- Login screen (Add playlist > Xtream login) and Settings > Playlists > "Backup-Server": any number of backup addresses, same username and password. Empty fields are ignored; filled fields must be a valid address.
- On a connection error, timeout or 5xx the request is retried on the next server. 4xx counts as "server is alive".
- The last working server is remembered until the app restarts.
- Each server shows "Reachable" / "Not reachable since …"; the playlist row lists servers that are down.

## Changed files

| File | Change |
|---|---|
| `HostFailover.kt` (new) | `HostFailoverLogic` (pure, unit-tested): normalize, prefix matching, candidate order, `pickGroup`. `HostFailover`: groups, active server, health state, OkHttp `Interceptor`, Media3 `DataSourceWrapper` |
| `Net.kt` | `HostFailover.Interceptor` added to `Net.base` (every OkHttp client in the app derives from it) |
| `PlayerScreen.kt`, `DualView.kt` | data source factory wrapped in `HostFailover.DataSourceWrapper.Factory` (one line each) |
| `PlaylistStore.kt` | backups per playlist, health state, group sync, export/import of backups, cleanup on delete |
| `AddPlaylistScreen.kt`, `SettingsHub.kt` | backup fields, status lines, edit dialog |
| `DictA.kt`, `DictNl1.kt` | 11 new texts in 10 languages |
| `HostFailoverLogicTest.kt` (new) | 11 tests |

## Storage (new keys, prefs file `ipvio`)

- `xt_backup_<hash of playlist URL>`: JSON array `[main, backup1, …]`. Removed when the playlist is deleted or all backups are cleared.
- `xt_host_health`: JSON object `host -> [ok 0/1, since ms]`.
- Export/import (`Backup.kt` through `exportLists`/`importLists`): optional `backups` array per playlist. Older exports without it import as before; older app versions ignore it.

No migration needed: playlists without a `xt_backup_*` key behave exactly as before.

## Review findings fixed (#35)

1. Credentials could be sent to a server of another playlist (first group with the host in any position won). Now one group per Xtream playlist, including playlists without backups; `pickGroup` prefers the playlist whose main server matches; a host that is only a backup in groups with different main servers gets no failover.
2. Orphaned groups (failed add, changed password, deleted playlist) stayed active until restart. Groups are now built from the playlist list plus the playlist being added, and re-synced after remove, import, a finished add and a cancelled add.
3. A cancelled player load (loader thread interrupted on zapping, seeking, release) was treated as a dead server: all other servers were tried and healthy ones marked as down. Cancelled loads are now rethrown.
4. An unparsable backup address after a 5xx replaced the 5xx response with an exception. Invalid candidates are now dropped first.

Found while rebasing: the status texts contained the symbols ✓/✗, which `I18nCoreTest.noEmojisInDictionaries` rejects (CI failure on #35). The symbols are now drawn with `AppIcon` (`AIcon.Check`, `AIcon.Close`).

## Limits and open points

- Each dead server costs one connect timeout (10 s) before the next is tried. Worst case per request: n × (10 s connect + 15 s read). In the player, `IptvLoadErrorPolicy` retries a failed load several times, and each retry goes through all servers again.
- A backup entered without a scheme becomes `http://`. With an `https` main server the credentials then go over plain HTTP (see `docs/SECURITY.md`, item 16).
- `xt_host_health` is never pruned.
- The interceptor compares against `HttpUrl.toString()` (host lowercased, default port dropped). A main address written as `http://host:80` does not match, so it gets no failover.
- Stream URLs that a `get.php` playlist puts on a host that is not one of the entered addresses are not covered.
- Interrupt behaviour of the Media3 loader (finding 3) is taken from the Media3 source, not verified on a device.

## Checks run

- `HostFailoverLogicTest`: 11 tests compiled with Kotlin 2.0.21 and run with JUnit 4.13.2 by the assistant: passed.
- Dictionary symbol check (same ranges as `I18nCoreTest.noEmojisInDictionaries`) over `DictA`–`DictI`: no hits.
- New dictionary keys: no duplicates across the dictionaries.
- `git merge-tree` against `perf/T-007` and `refactor/U-1`: no code conflicts.
- Gradle build and full unit tests: by CI on the pull request.

## Test steps (maintainer, TV or Fire TV)

1. Add an Xtream playlist with a broken main address (e.g. an unused port) and a working backup. The playlist loads; Settings > Playlists > Backup-Server shows a cross under the main server and a check under the backup.
2. Play a live channel, a movie and a catch-up show from that playlist.
3. Remote: Up/Down move between the backup fields and the "+ Backup-Server" button; OK opens the keyboard.
4. Playlist B without backups whose server is also entered as a backup of playlist A: B never contacts A's main server (provider log or proxy).
5. Zap quickly between channels while A's main server is down: no server changes to "not reachable" because of zapping alone.
6. Add a playlist, enter backups, press Cancel: the backups have no effect.
7. Delete a playlist with backups: its servers get no more failover requests.
8. Export a backup with playlists and import it on another device: backups are restored.

## Risks

Medium. Touches the network stack of the whole app (`Net.base`) and the player's data source chain. For users without backup servers the interceptor and wrapper only pass requests through.
