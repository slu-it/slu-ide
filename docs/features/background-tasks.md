# Background tasks

**Module:** `background-tasks`: `BackgroundTasks` (per-window registry), `BackgroundTasksView` (the list under the
explorer). Decision in [ADR 0061](../adr/0061-background-tasks.md).

- **Where:** below the file explorer, in the left part of the window. While nothing runs in the background, the list is
  hidden and the explorer has the whole height.
- **When:** work appears once it has run for half a second; work that ends sooner never shows. A row leaves the list
  as soon as its work ends.
- **A row:** the work's name (shortened with "…" if the explorer is narrow), a moving progress bar and, for work that
  can be cancelled, ✕ ("Cancel"). Hovering over the name shows it in full with how long the work has been running.
- **Cancel:** ✕ asks the work to stop; the row stays until it has stopped. Work that cannot be cancelled has no ✕.
- **Many tasks:** at most four rows, the oldest first; the others are counted in a last row "+N more".
- **Log:** every task writes one line when it starts and one when it ends, with its duration, to the log file
  (`task#7 started: …`, `task#7 finished in 1.23 s: …`, `failed after …` or `cancelled after …`); also the tasks that
  are never shown.
- **Closing the window** while tasks run logs them as abandoned.
- **What appears here:** git writes, loading and searching the git log, the git status and trust checks, exports and
  HTTP requests, once they are registered (see the table in [ADR 0061](../adr/0061-background-tasks.md)).
