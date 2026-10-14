# File Watching

**Module:** `modules/file-watcher` (package `dev.sluide.filewatcher`), decision in
[ADR 0006](../adr/0006-file-watching.md)

- `FileWatcher` (Spring bean `sluideFileWatcher`) watches single directories with the JDK `WatchService`:
  - `watch(directory) { changed: List<Path> -> }` returns an `AutoCloseable`, and closing it stops the
    notifications;
  - created, modified and deleted entries are reported. If the OS lost events, the directory itself is reported,
    meaning "anything may have changed".
- Events of a directory are combined until it has been quiet for 200 ms, or for at most 1 s if it keeps changing (e.g.
  a growing log file). Then each listener gets one call on the JavaFX thread. Tests pass their own dispatcher and a
  shorter quiet period to the constructor.
- A deleted directory is reported one last time as the directory itself, and then its watch ends. The editor watches
  the folder of an open file again after every change; the explorer watches a loaded folder again when it or its
  parent reports it. So a folder that is deleted and recreated (e.g. `build/`) keeps being followed, except the opened
  folder itself when recreated later ([file-explorer.md](file-explorer.md)). Two paths to the same directory (e.g. via
  a symlink) are both watched.
- A failing round (e.g. the dispatcher throws) is logged and its changes are lost; watching goes on.
- Directories that cannot be watched (missing, no permission) give a handle that does nothing; `watch` never
  throws.
- Users:
  - the explorer watches every loaded folder ([file-explorer.md](file-explorer.md));
  - the editor watches the folder of every open file ([editor-tabs.md](editor-tabs.md));
  - git watches the `.git` folder of the followed repository, without subfolders ([git-status.md](git-status.md)).
- Not yet implemented: native macOS events (JDK 25 polls there every 2 s, so changes show up to about 2 s late; not
  measured), following renamed files.
