# 0090. Per-project metadata store

- Status: accepted; amended by [0101](0101-shared-data-file.md) (the outside file is the shared data file
  `shared.json`)
- Date: 2026-10-25

## Context
What Thoreau remembers per project lives in two properties files keyed by the absolute folder path:
`~/.sluide/projects.properties` (when a project was last opened, [ADR 0034](0034-project-picker.md)) and
`~/.sluide/project-trust.properties` (the trust decision and the fingerprint it was made for,
[ADR 0053](0053-project-trust.md)). New features need more per project and also per file in a project, such as the
argument histories of runnable files ([ADR 0089](0089-running-shell-scripts.md)). Flat key/value files do not hold
nested data, every new kind of data would add another file keyed by the same paths, and forgetting a project would mean
cleaning up each of them.

Files outside any project have per-file data too: scratch scripts in `~/.sluide/scratches`
([ADR 0066](0066-scratch-files.md)) can be run with arguments.

## Decision
- **Module `project-data`** (foundation, package `dev.sluide.projectdata`, flat) with the Spring bean
  `ProjectDataStore`. Its data is a Jackson 3 tree (`ObjectNode`), so `jackson-databind` is part of its API.
- **One JSON file per project** in `~/.sluide/projects/<random UUID>.json`, holding the project's `path` and all its
  data: `{ "version": 1, "path": "/abs/folder", "lastOpened": …, "trust": {…}, "files": { "src/run.sh": {…} } }`.
  Looking a project up by path scans the folder; folders compare as `toAbsolutePath().normalize()`. If two files name
  the same folder, the most recently modified wins (logged); removing the project deletes all of them.
- **Files outside any project** keep their per-file data in `~/.sluide/outside-projects.json`
  (`{ "version": 1, "files": { "/abs/file": {…} } }`). A file's data lives in its project's file under its path
  relative to the project (with `/`) when it lies under the project folder, else in the outside file under its absolute
  path. Moves and deletes (`moved`, `deleted`) carry or drop the entries of a path and everything under it (by path
  components), in both files; a move into or out of the project moves the entries between the files, writing the
  target first so that a failure duplicates an entry rather than losing it. A move first drops the entries already
  stored at the target and under it, in both files and also when the source has none, so a moved file never inherits
  stale data. A move of the project folder itself takes its entries to the outside file (the explorer cannot move the
  project root). An empty per-file object is dropped.
- **Paths** compare as `toAbsolutePath().normalize()`, without resolving symbolic links or case: a project opened
  through a link or in other case is another project, and per-file keys are exact strings.
- **Ownership:** the store owns `version`, `path` and `files`; every other top-level field belongs to the module that
  writes it, and the store never interprets it. Every write is a round trip of the whole tree, so fields the running
  code does not know are kept.
- **Version rule:** a file whose `version` is greater than 1 is read but never written (logged; the update returns
  `false`), so an older Thoreau never damages data of a newer one.
- **Robustness** as for the properties files: no I/O in the constructor, every method synchronized, problems logged
  and never thrown, writes through a temporary `.tmp` file in the same folder and an atomic move (a `.tmp` file left
  behind by a crash is ignored, not cleaned up, as before). A project file that
  cannot be read, or has no `path`, is skipped in scans (logged once) and, since it matches no folder, never replaced or
  deleted. It blocks no other project: an update of a folder without a readable file creates a new file as usual. If
  the damaged file becomes readable again and names the same folder, the duplicate rule applies (newest wins). An
  unreadable outside file is never replaced; its updates return `false`.
- **Migration:** each owning module implements `LegacyProjectData` (its old properties file and how to write its
  entries into the store, never overwriting a value already there). Before the first read or write of a store, once,
  the store loads each existing old file and hands its entries to the migration; the old file is deleted only when
  everything was written, otherwise (or if it cannot be read) it is kept and the migration runs again at the next
  start. Running before the first access keeps a later migration from re-creating a project just removed.
  Implementations are beans of their own that do not inject `ProjectDataStore` (the store collects them, so that would
  be a circular reference); the store is handed to `migrate`.
- **Locking:** every method holds the store's lock; the `change` functions of `update`/`updateFileData` and
  `migrate` run under it, on the calling thread (any thread). They must not take other locks; a `change` must not call
  back into the store (a write nested in it would be overwritten), `migrate` writes through the store's methods.
- **Removing a project** deletes its whole file.

## Consequences
- New per-project or per-file data needs no new file and no new storage code: a module reads and updates its own
  field. Forgetting a project forgets everything about it.
- File names say nothing about the project; a lookup reads every project file. With the tens of projects a user keeps
  this costs little, and nothing is cached that could go stale between windows.
- Two writers in one process are serialized by the store; two Thoreau processes can still overwrite each other's
  change of the same file (as with the properties files).
- `projects` (`lastOpened`) and `project-trust` (`trust`) keep their data in the store, each with its
  `LegacyProjectData`; removing a project from the picker forgets its trust decision too.
- A damaged project file that becomes readable later may bring back data that was changed in the new file meanwhile
  (whichever file is newer wins); this is preferred to blocking every new project while one file is broken.

### Rejected alternatives
- **One JSON file for all projects:** every change rewrites everything, and one broken file loses all projects.
- **SQLite:** a native dependency and a schema to migrate, for a handful of small records.
- **Keep the properties files:** no nested data, one more file per kind of data, removal spread over all of them.
- **File names from a hash of the path:** lookup without a scan, but moving or renaming a project folder, and
  normalization differences between platforms, would orphan the file; the path inside the file stays the one source.
