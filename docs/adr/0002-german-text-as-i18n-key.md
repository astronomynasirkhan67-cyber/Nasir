# ADR 0002: German text is the translation key

## Status

Accepted. Records the current design. The original reason for choosing German as the key is PENDING (not documented in the audit or in CLAUDE.md).

## Context

Audit source: section 3 (Compose without material3, own `Text` in `Lang.kt`), section 4 (10 languages, `I18nCore.kt:9`) and section 9 (`i18n/` group). The rule itself is in CLAUDE.md section 5: "UI strings: the German text is the translation key (`I18nCore`, `DictJ`, `DictNl5`). Do not change without a decision by the maintainer."

- The app supports 10 languages (`I18nCore.kt:9`). Dutch is the tenth.
- The app has its own `Text` wrapper in `Lang.kt:59` instead of material3.
- The rest of the repository is English (CLAUDE.md section 4). UI strings are the exception: they stay German as keys.

## Decision

The German UI string in the code is the lookup key for all translations. No separate key identifiers.

## Consequences

- Changing a German string, even a typo fix, changes the key and breaks the translation in every dictionary. Strings are therefore protected (CLAUDE.md section 5).
- The English-only rule does not apply to these strings. Comment and identifier translation (P2, P4) must skip them.
- New UI text needs entries in all 10 languages. TODO: behaviour for a missing entry (fallback) is not verified; read `Lang.kt` and `I18nCore.kt` first.
- A change to key identifiers would be a large, separate task with its own plan and approval.
