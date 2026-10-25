# 0091. Argument history of runs

- Status: accepted
- Date: 2026-10-25

## Context
Scripts and Gradle runs usually need arguments (`--dry-run`, `--info -x test`). The views that run commands keep them
in the arguments field of their tab only ([ADR 0089](0089-running-shell-scripts.md)): closing the tab or restarting
Thoreau loses them, and switching between a few typical sets of arguments means typing them again. Both views share
their run pane and runner ([ADR 0088](0088-shared-command-runs.md)), and per-file data now has a store
([ADR 0090](0090-per-project-metadata-store.md)).

## Decision
- **A visible list in the shared `RunPane`**, below the arguments field, for the shell script and the Gradle view
  alike (`ArgumentHistoryPane` in `command-runs`): the arguments of the file's past runs, newest first, up to 5 rows
  before it scrolls. A click on an entry, or Enter on the selected one, puts it into the field without running; ✕ (on
  the row under the mouse or the selected one) removes the entry. Down in the field goes into the list while it shows,
  Escape back. The header "▾ History" / "▸ History" collapses and expands it; the section is hidden while the history
  is empty.
- **What is recorded:** the arguments of a run that **started** (`StartResult.STARTED`), trimmed; not of one cancelled
  by the question before it, one that could not start (an unclosed quote, a terminal that failed), and not blank ones.
  Arguments used again move to the top; at most 20 are kept per file, the oldest drops. The Gradle load records
  nothing. `CommandRunner.start(..., arguments)` records through the runner's `ArgumentHistory`; the views pass the
  field's text as typed.
- **Where:** per file (the script, the Gradle build file) as the array `runArguments` in its project data
  (`ProjectDataStore.updateFileData`, ADR 0090): in the project's JSON file for a file inside the window's project, in
  `~/.sluide/outside-projects.json` for one outside, such as a scratch. `ArgumentHistories` (a singleton bean) reads
  and writes it; `CommandRunSession`, the window bean, hands it to the views, each of which keeps an `ArgumentHistory`
  of its file and the host's project folder (read live; `MainWindow` sets it before any tab opens). A view reads its
  history again when its file moves.
- **Expanded or not** is one global setting for all run panes, `runs.history.expanded` in `settings.properties`
  (default `true`, [ADR 0008](0008-settings.md)).
- **The field starts empty** when a view opens; the history offers the past arguments.
- **The script view opens in Split** (source beside the run pane, so the field and the history are in view), and the
  field has the label "Arguments:" (amends ADR 0088 and ADR 0089).
- **Explorer moves and deletes:** `MainWindow` re-keys the per-file project data on every move or rename in the
  explorer (`ProjectDataStore.moved`, also of files not open in a tab, folders with everything under them, into and out
  of the project) before the editor area lets the tabs follow, so a view that follows reads its history under the new
  path; a delete in the explorer drops the data at and below the path (`ProjectDataStore.deleted`).

Rejected:
- **A dropdown or combo box** on the arguments field: hides the history behind a click, and an editable combo box mixes
  editing and choosing; the list shows at a glance what was used.
- **Shell-like Up/Down in the field only**: invisible, and no way to remove an entry.
- **Per tab only** (as before): lost with the tab and at restart.
- **Prefilling the last arguments**: a run with forgotten arguments (`--dry-run` left out, or left in) is worse than
  an empty field and a click.
- **Multi-select delete** in the list: rarely needed for 20 entries, more UI to get right.

## Consequences
- Both views get the history from the shared pane and runner; a new view that runs commands passes an
  `ArgumentHistory` and the arguments of its starts.
- `CommandRunSession` takes `ArgumentHistories`; `MainWindow` takes the `ProjectDataStore`.
- Known limits:
  - Files renamed, moved or deleted outside the explorer (in a terminal, by git) keep their history under the old
    path: it is lost for the new path and stays in the project data, and comes back if a file of the old path appears.
  - A scratch deleted automatically because it was closed empty ([ADR 0066](0066-scratch-files.md)) keeps its entry in
    `outside-projects.json`.
  - Two views of the same file in different windows see each other's new entries only after the view is opened again
    or the file is moved; what each records or removes is stored at once.
- Listed in [../index.md](../index.md).
