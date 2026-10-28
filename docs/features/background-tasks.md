# Background tasks

**Module:** `background-tasks`: `BackgroundTasks` (per-window registry), `BackgroundTasksView` (the list under the
explorer). Decision in [ADR 0061](../adr/0061-background-tasks.md).

- **Where:** below the file explorer, in the left part of the window. While nothing runs in the background, the list is
  hidden and the explorer has the whole height. While the explorer is collapsed to its stripe, the list is hidden with
  it and shows again on expand ([ADR 0104](../adr/0104-collapsible-file-explorer.md)); the tasks run on.
- **When:** work appears once it has run for half a second; work that ends sooner never shows. A row leaves the list
  as soon as its work ends.
- **A row:** the work's name (shortened with "…" if the explorer is narrow), a moving progress bar and, for work that
  can be cancelled, ✕ ("Cancel"). Hovering over the name shows it in full with how long the work has been running.
- **Cancel:** ✕ asks the work to stop; the row stays until it has stopped. Work that cannot be cancelled has no ✕;
  work that goes on to a part that must not be stopped loses its ✕ then (a pull once it has fetched).
- **Many tasks:** at most four rows, the oldest first; the others are counted in a last row "+N more".
- **Log:** every task writes one line when it starts and one when it ends, with its duration, to the log file
  (`task#7 started: …`, `task#7 finished in 1.23 s: …`, `failed after …` or `cancelled after …`); also the tasks that
  are never shown.
- **Closing the window** while tasks run logs them as abandoned.
- **What appears here** (names and cancelling: the table in [ADR 0061](../adr/0061-background-tasks.md)):
  - git writes of the git log, the Commit tab and the Push dialog, named as the git log header says what they do
    ("Fetching origin…", "Committing…", "Pushing main to origin/main…"); fetch, commit and push have ✕, a pull while
    it fetches;
  - loading the git log ("Loading Git log", "Loading more commits", "Searching Git log for main") and a file's
    history ("Loading history of App.kt", with ✕: the rows loaded stay; a cancelled further page loads again when you
    scroll, a cancelled first load or refresh says "Loading cancelled. Refresh to load." until Refresh);
  - "Refreshing Git status" and "Checking project trust";
  - exports ("Exporting notes.md as HTML", "Exporting logo.svg as PNG 200%", "Exporting ci.yml as Mermaid");
  - HTTP requests, by their name in the `.http` file ("GET Get users") or else their host ("GET example.com"), never
    the path or query, with ✕; and OAuth token requests ("Acquiring token 'api'", "Refreshing token 'api'"), also
    while they wait for a certificate's passphrase.
