# File Watching

**Module:** `modules/file-watcher` (package `dev.sluide.filewatcher`), decision in
[ADR 0006](../adr/0006-file-watching.md)

- `FileWatcher` (Spring bean `sluideFileWatcher`) watches single directories with the JDK `WatchService`:
  - `watch(directory) { changed: List<Path> -> }` returns an `AutoCloseable`, and closing it stops the
    notifications;
  - created, modified and deleted entries are reported. If the OS lost events, the directory itself is reported,
    meaning "anything may have changed".
- Events of a directory are combined until it has been quiet for 200 ms, then each listener gets one call on the
  JavaFX thread. Tests pass their own dispatcher and a shorter quiet period to the constructor.
- Directories that cannot be watched (missing, no permission) give a handle that does nothing; `watch` never
  throws.
- Users:
  - the explorer watches every loaded folder ([file-explorer.md](file-explorer.md));
  - the editor watches the folder of every open file ([editor-tabs.md](editor-tabs.md)).
- Not yet implemented: native macOS events (the JDK may poll there, with a delay of seconds), following renamed
  files.
