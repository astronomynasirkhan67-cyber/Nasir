# ADR 0003: SharedPreferences and files, no database

## Status

Accepted. Documents the current state. The part "no Room during the refactoring" comes from the audit's target structure (section 9), not from a separate earlier decision.

## Context

Audit source: section 3 (storage), section 6 (technical debt) and section 9.

- Data is stored in SharedPreferences and files. There is no Room, SQLite or DataStore (audit search: no hits).
- 32 own `prefs(c)` helpers are spread over many files. There is no central list of store names.
- Profile-specific stores use the suffix `_p<ID>`; profile 0 keeps the original names (CLAUDE.md section 5).
- `Backup.kt` has a list `NAMES` of stores that go into the backup. Five profile-related stores were missing: `ipvio_dual`, `ipvio_live`, `ipvio_newep`, `ipvio_importedlists`, `ipvio_resumelog`. Task T-006 added them. Restore skips unknown names, so old backups stay readable.
- The app is in active use. Renaming a store or key loses user data.

## Decision

Keep SharedPreferences and files. Do not introduce a database or a storage framework as part of the refactoring.

## Consequences

- No schema migrations. Store names, keys and file names stay unchanged.
- Backup completeness depends on the manual list `NAMES`. New stores must be added there.
- A central registry of store names is possible without renaming anything. It would be a separate task.
- Any move to a database needs an approved plan with a migration path (CLAUDE.md section 6).
