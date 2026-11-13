# T-013 Search: Back from a selected result does not return to the sidebar

Status: DONE. Merged through PR #43 (`cfa223b`). Device test 12 Nov 2026: works.

## Goal

In the search screen, pressing Back while a title in the result list is selected must move focus back to the sidebar.

## Observed

- Reported by the maintainer on 12 Nov 2026: going back from a selected title in the result list does not return to the sidebar.
- The back logic is identical on `main`, so the bug predates PR #41 and PR #40.

## Affected files

- `app/src/main/java/com/ipvio/stream/SearchScreen.kt`: `BackHandler(enabled = !inSidebar)` (about line 125). When the search field is not focused, `target` is `null`, so only `sideFocus.requestFocus()` runs. If that call fails (the sidebar item is not composed or not focusable at that moment) nothing visible happens. **Unverified:** the real cause has not been reproduced. Start by logging the result of `sideFocus.requestFocus()`.
- Overlap risk: PR #40 and PR #41 both change `SearchScreen.kt`. Start this task only after PR #41 is merged.

## Cause (from reading the code, not reproduced on a device)

- The Search entry (menu id 4) is hidden by default (`MenuLogic.DEFAULT_HIDDEN = "4,6"`).
- `Sidebar` attached the `activeFocus` requester (`sideFocus` in `SearchScreen.kt`) only to the entry of the active tab. With Search hidden, no entry carried it.
- `sideFocus.requestFocus()` therefore failed and `runCatching` swallowed the error, so Back did nothing.

## Fix

- `Sidebar.kt`: if the active tab is not visible in the menu, the requester goes to the first visible entry (`anchorId`). Both sidebar layouts (top bar and side bar) use it. The behaviour for visible tabs and the `enterTo` rule are unchanged.

- `SearchScreen.kt`: maintainer decision (12 Nov 2026): Back from a hit goes to the input (app keyboard, or the text field with the device keyboard); Back from the input or keyboard opens the sidebar. The old logic went from the input to the hits and from everything else to the sidebar.

## Acceptance criteria

- Back on a selected result moves focus to the input (keyboard).
- Back in the input or keyboard moves focus to the sidebar on Android TV and Fire TV.
- Back in the sidebar on a tab other than 0 still goes to tab 0 (existing behaviour).

## Risks

- Focus handling in Compose depends on what is composed at that moment. A fix that works on the emulator may fail on a TV. Device test is required.

## Documentation needed

- `CHANGELOG.md` entry.

## Test results

Device test by the maintainer on 12 Nov 2026: Back from a hit goes to the keyboard, Back again opens the sidebar. Works. The assistant did not run Gradle or a device (CI build on GitHub: success).

Test steps for the maintainer (debug build):
1. Default menu (Search hidden): open Search, type a word, move to a result, press Back. Expected: focus goes to the keyboard. Press Back again: the sidebar opens.
2. Same with Search shown in the menu (Settings > menu): the second Back lands on the Search entry.
3. Back in the sidebar on tab 4 still goes to Home.
3b. With the device keyboard setting on: Back on a hit goes to the text field; Back closes the keyboard if open, Back again opens the sidebar.
4. Sidebar at the top and on the right: repeat step 1.
