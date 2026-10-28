# 0101. Shared data file `~/.sluide/shared.json`

- Status: accepted
- Date: 2026-10-28

## Context
[ADR 0090](0090-per-project-metadata-store.md) keeps the per-file data of files outside any project in
`~/.sluide/outside-projects.json`. The TODO feature needs data that belongs to no project and no file (the todos of
the user), and other features will follow. A project's file already lets modules keep their own top-level fields; the
global file had no such access and a name that only describes one of its uses.

## Decision
- The global file becomes the shared data file `~/.sluide/shared.json`. `ProjectDataStore` calls its second constructor
  parameter `sharedFile`.
- Ownership as in the project files: the store owns `version` and `files` (the per-file data of files outside any
  project, keyed by absolute path, unchanged); every other top-level field belongs to the module that writes it and
  round-trips unknown content. A file with a greater `version`, or an unreadable one, is never written.
- `readShared()` returns a deep copy of the whole tree (`null` if missing or unreadable); `updateShared(change)`
  applies `change` to the whole tree under the store's lock and, like `editProject`, puts `version` back and restores
  `files`, so a change cannot break what the store owns. Same threading contract as `update`.
- Rename migration, once per store before the first access (`SharedFileMigration.kt`): `outside-projects.json` next to
  the shared file is moved to it (atomic, plain move as fallback) if the shared file is missing. If both exist, the
  shared file wins, the old one stays untouched and a warning is logged once per start. A failed move is logged, the old file
  stays and the move is retried at the next start.

Rejected: a separate second global file (two files for the same kind of data, each with its own version and
migration); keeping the name `outside-projects.json` (it says nothing about the new role).

## Consequences
- Modules store global data with `updateShared`/`readShared` without new files or store classes.
- All modules share one file and one lock; a large field in it is rewritten with every change.
- Users who run an old version after the move do not see their old per-file data again (the old file is gone). The
  downgraded Thoreau recreates `outside-projects.json`, so both files exist then and the newer Thoreau keeps
  `shared.json` and warns.
- Amends [ADR 0090](0090-per-project-metadata-store.md): the outside file is the shared data file.
- Listed in [../index.md](../index.md).
