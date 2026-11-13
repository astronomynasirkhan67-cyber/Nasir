# T-018 Favorites: manual order

## Goal

Favorites for live channels, movies and series can be sorted by hand with the Fire TV remote (D-pad, menu key or long OK).
Favoriting itself already existed (`Library.favs`, SharedPreferences `ipvio_lib`, key `favs`); only the order was missing.

## Findings (analysis)

- One list for everything: `Library.favs` (`Store.kt`), newest first, newline separated, profile scoped through `Profiles.sp`. `ipvio_lib` is in `Backup.NAMES`.
- Favorites tab (`HomeScreen.kt`, `MenuLogic.FAVS`) and Live TV > Favorites (`FAV_CAT` in `LiveTab.kt`) are filtered views of that list.
- Movie and series cards had no menu key handling. Wide channel cards (`ChannelDetailCard`) toggled the favorite at once on menu key / long OK.
- A comparable pattern exists for categories (`CatOrder`, `CatOrderLogic`, menu with up/down/top).

## Change

- `FavOrderLogic.kt` (new, pure Kotlin): moves an ID inside the part of the list that is on screen; entries not on screen (other kind, other playlist not loaded) keep their exact position.
- `Store.kt`: `Library.moveFav`, `Library.favToTop` (write the same key `favs`, same format).
- `FavReorder.kt` (new): `FavReorderMenu` (popup), `favMoveActions`, `FavFocus` + `Modifier.followFav` (focus follows the moved entry for max. 2 s).
- `HomeParts.kt`: optional `reorder` parameter on `PosterTile`, `LandscapeCard`, `ChannelDetailCard`, `ChannelDetailRows`, `cardGridItems`; `null` = unchanged behaviour (My list, history, browse).
- `HomeScreen.kt`: pass the section lists at the three favorites call sites.
- `LiveTab.kt`: `ChannelLine` / `ChannelMenu` get optional `favShown`; set only in the favorites category.
- Labels reuse existing dictionary rows ("Nach oben verschieben", "Nach unten verschieben", "An den Anfang verschieben", "Aus Favoriten entfernen"); no dictionary change.

## Not changed (on purpose)

Storage format and keys, backup, player favorites list (`PlayerLive.kt`, follows the stored order automatically), Boomer mode, instant toggle on Live TV lists and Home.

## Acceptance criteria

1. Menu key or long OK on a favorite in the Favorites tab opens a menu with the possible moves and "Remove from favorites".
2. A move changes the order by one place (or to the top), the entry keeps the focus, the order survives restart, profile switch and backup restore.
3. Moving movies never changes the position of channels or series in the stored list.
4. Short OK still opens the title. Screens without `reorder` behave as before.

## Risks

- Behaviour change on wide channel cards in the Favorites tab (menu instead of instant removal).
- Focus-follow uses a delayed focus request; if the moved card is not on screen the request is ignored.

## Tests

- Unit: `FavOrderLogicTest` (9 cases). **Not run** (no Kotlin compiler in the assistant environment); expectations checked with a Python port of the algorithm.
- Device: see the T-018 steps in `WORK_STATE.md`.

## Status

Implemented in the working tree. Not committed, not built, not tested on a device.
