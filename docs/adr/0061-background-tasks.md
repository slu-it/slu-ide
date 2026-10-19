# 0061. Background tasks: a per-window registry and an inline list under the explorer

- Status: accepted
- Date: 2026-10-19

## Context
Much of Thoreau's work runs off the JavaFX thread: git writes, loading and searching the git log, the git status and
the trust checks, exports, HTTP requests. Each tool shows its own state, if any (a disabled button, a status text in
the git log header), and the rest is invisible: a fetch that hangs, a slow status refresh or an export still running
look like nothing happens. The log ([ADR 0060](0060-logging-conventions.md)) had no common start and end lines for
this work either.

IntelliJ shows running background tasks in its status bar, with a popup that lists them with progress bars and cancel
buttons. Thoreau has no status bar.

## Decision
- **A registry per window** (`BackgroundTasks` in the foundation module `background-tasks`, window-scoped like the other
  beans of a window, [ADR 0014](0014-project-windows.md)). Work registers with `start(name, cancel)` and ends with
  `close()`, `failed(message)` or `failed(error)`; `track(name) { … }` wraps work that runs on the calling thread. Any
  thread may start, rename and end a task; the list of shown tasks changes on the JavaFX thread only. The registry
  reaches file views through `FileViewHost.windowService` (`MainWindow` adds it to the editor area's window services).
- **An inline list under the file explorer** (`BackgroundTasksView`): one row per task with its name, an indeterminate
  progress bar and ✕ if the task can be cancelled. IntelliJ is the model for what is shown. The user chose a list that
  is always visible while something runs over IntelliJ's popup: there is no status bar to open it from, and a popup
  hides what it lists until it is opened.
- **Only after 0.5 s:** a task becomes visible once it has run for 0.5 s, and never if it ends before. Most work ends
  sooner, so the list neither flickers nor fills with every short git read.
- **At most 4 rows plus "+N more"**, so the list never pushes the explorer away. The rows are the oldest tasks.
- **Logging per [ADR 0060](0060-logging-conventions.md):** every task gets a process-wide number and exactly one start
  and one end line at INFO with its duration (`task#7 started: Fetching origin…`, `task#7 finished in 1.23 s:
  Fetching origin…`). A failure with a message for the user ends it with a WARNING, an unexpected exception with an
  ERROR and its stack trace. A rename is logged when the name changes, a cancel when it is asked for; the end line is
  always the task's last line.
- **Cancel only where the work supports it:** ✕ appears only for tasks registered with a cancel function. It asks the
  work to stop; the task ends when the work does. A cancelled task is not a failure: a `CancellationException` or an
  `InterruptedException` (also through `track`), or any failure after ✕ was used, ends it with one INFO line
  `task#7 cancelled after 1.23 s: <name>` instead of a WARNING or an ERROR.
- **Window close:** tasks still running are logged as abandoned (INFO) and their later end is not logged again. Work
  started after the window closed is still logged, but never shown.
- **What will be registered** (a later change wires it):

| Group | Name shown | ✕ |
|---|---|---|
| Git writes | the operation's progress text ("Checking out main…", "Fetching origin…", "Committing…", "Pushing to origin…") | fetch, commit, push |
| Git log | "Loading Git log", "Loading more commits", "Searching Git log for <rev>", "Loading history of <name>" | history |
| Status & trust | "Refreshing Git status", "Checking project trust" | — |
| Exports | "Exporting <file> as HTML", "Exporting <file> as PNG 200 %" | — |
| HTTP | "<METHOD> <host><path>" | yes |

  Not shown, only logged: commit details, branch containment, file diffs, filter sources, Commit-tab and Push-dialog
  reads, working-tree compare, editor change markers, highlighting.

Rejected:
- **IntelliJ's popup from a status-bar indicator:** Thoreau has no status bar, and the work stays hidden until the
  popup is opened.
- **Showing every task at once:** short reads would flicker in and out many times a second.

## Consequences
- Long-running work is visible in one place, with a way to cancel it where that is possible, and every task leaves a
  start and an end line in the log.
- Work must end its task on every path (finished, failed, cancelled); a task that is never ended stays in the list
  until the window closes. `track` does that for work on the calling thread.
- While tasks are shown, the explorer is shorter by their rows (at most five).
- Added to [../index.md](../index.md).
