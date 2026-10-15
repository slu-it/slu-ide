# 0006. Watch the file system with the JDK WatchService behind our own FileWatcher

- Status: accepted
- Date: 2026-10-12

## Context
Files change outside Thoreau: other tools, `git checkout`, build output. The explorer and open tabs must follow
without the user reopening the project. Projects can be large, and we load folders lazily.

## Decision
- A separate module `modules/file-watcher` offers `FileWatcher.watch(directory) { changedPaths -> }`, which returns
  a handle to stop watching. It is implemented with the JDK `WatchService`, so there is no new dependency.
- Only what is in use gets watched:
  - every folder whose children the explorer has loaded;
  - the parent folder of every open file;
  - the `.git` folder of the repository whose status is shown (added with [ADR 0013](0013-git-status.md)).
  Directories are watched one by one, not recursively. Watches share one `WatchKey` per directory.
- Events of a directory are collected until it has been quiet for 200 ms, but at most 1 s, so constant changes still
  show. Each listener then gets one call with every changed path once, on the JavaFX thread. A `git checkout`
  therefore causes one refresh per folder, not hundreds.
- Reactions:
  - the explorer re-reads the folder and keeps unchanged items, so expansion and selection survive;
  - a tab reloads its file and keeps the scroll position;
  - a tab whose file was deleted, renamed away or became unviewable (e.g. binary) closes.
- A deleted directory ends its watch, so users watch again when it is reported and exists: the editor after every
  change of an open file's folder, the explorer when a loaded folder or its parent reports it. Folders that are
  deleted and recreated (e.g. `build/`) keep being followed; the opened folder itself only if it is back by the time
  its deletion is reported, otherwise after a manual refresh.
- "Refresh" in the explorer's context menu (and F5) re-reads all loaded folders by hand and renews their watches.

Rejected for now: `io.methvin:directory-watcher`, which uses native FSEvents on macOS and watches whole trees. It
can replace the implementation behind `FileWatcher` if the JDK's macOS behaviour turns out too slow.

## Consequences
- On Linux (inotify) changes show up almost immediately. On macOS the JDK still polls: JDK 25's macOS file system
  returns a `PollingWatchService` that scans every 2 s (read in the jdk25u sources, not measured here), so changes
  show up within about 2 s there; the manual refresh covers misses.
- Renaming an open file closes its tab instead of following the new name.
- `FileExplorerView`, `EditorArea` and `GitStatusService` depend on the `file-watcher` module (documented in
  `architecture.md`).
- The Spring bean is named `sluideFileWatcher`, because Spring Boot's SSL auto-configuration already defines a bean
  called `fileWatcher`.
