# Shell scripts

Shell scripts open as source that can be run, with the output in a terminal beside it. Decisions:
[ADR 0089](../adr/0089-running-shell-scripts.md); the shared run machinery: [ADR 0088](../adr/0088-shared-command-runs.md).

## Which files
- `.sh`, `.bash`, `.zsh` and `.ksh` files, and text files without an extension whose shebang names `sh`, `bash`, `zsh`,
  `dash` or `ksh` (`#!/bin/bash`, `#!/usr/bin/env zsh`, `#!/bin/ksh93`), as long as no other viewer takes the name
  (module `shell-scripts`, `ShellScriptFileViewer`).
- Startup files (`.bashrc`, `.bash_profile`, `.zshrc`, `.zprofile`, `.profile`) stay plain highlighted source without
  Run ([file-viewers.md](file-viewers.md), `ShellFileViewer`).
- Diffs of scripts (also a commit's version) are highlighted as shell.

## The view
- Opens as **Split**: the source on the left, the run pane on the right (scripts usually get arguments). The toolbar
  has **Run** (play icon) on the left and the modes **Source | Split | Output** on the right. Output is the run pane:
  the arguments field (labelled "Arguments:"), the history of past arguments, the status line with **Stop** and
  **Rerun**, and the terminal of the last run.
- A run shows the output beside the source: Source switches to Split; Split and Output stay as they are.
- Shown again (tab selected), the view focuses the source, or the arguments while the output is shown alone. Going to a
  line while only the output shows switches to Split first.
- The toolbar and the run pane use the UI font, not the editor font; the terminal keeps its own.
- Outside an IDE window (no window session) Run is disabled, with the tooltip "Scripts run in an IDE window".

## Running
- **Run**, **Shortcut+Enter** in the source, **Enter** in the arguments, **Rerun** (again, with the current arguments),
  or **Run** in the explorer's context menu (see below).
- The script runs as a file, in its folder: `unset -f cd 2>/dev/null; \cd '<folder>' && '<script>' 'arg'…` in the
  user's interactive login shell, like a command typed into a terminal tab
  ([ADR 0087](../adr/0087-commands-in-an-interactive-login-shell.md)); a function or alias named `cd` from the
  startup files (e.g. zoxide's) does not apply. Its shebang decides what runs it; what the shell's startup files print
  shows above the script's output.
- **Arguments** (e.g. `--verbose input.txt`) are split into words like a shell does (spaces separate, quotes group,
  `\` escapes), but each word is passed as it is: no `$VAR`, `~` or glob expansion. An unclosed quote is reported in
  the status line and nothing runs. The field starts empty when the view opens; past arguments are in the history
  (below).
- Before the run starts, in this order:
  1. While the script still runs: "deploy.sh is still running. Stop it and run deploy.sh?" Asked before saving, since
     the shell reads a script while it runs it. Stopping closes the old terminal at once.
  2. With unsaved changes: "deploy.sh has unsaved changes." **Save and Run** saves as File → Save does (and runs
     nothing if that fails), **Run Saved Version** runs the file as it is on disk, **Cancel** runs nothing.
  3. When the file is not executable: "deploy.sh is not executable. Make it executable and run?" OK adds the owner's
     execute permission only (`chmod u+x`) and runs; if the file still cannot be executed (e.g. on a `noexec` mount or
     a file system without permissions) the status line says so.
- There is no project trust check: a run is always started by the user.

## History
Below the arguments, the **History** section lists the arguments of the script's past runs, newest first
([ADR 0091](../adr/0091-argument-history-of-runs.md)):
- A run's arguments are recorded when it starts, trimmed; not when the question before it is answered no or it cannot
  start (e.g. an unclosed quote), and not when they are blank. Arguments used again move to the top; at most 20 are
  kept per script, the oldest drops.
- Kept per file with the project, in `~/.sluide/projects/<id>.json`, or in `~/.sluide/outside-projects.json` for a
  file outside the project, such as a scratch ([ADR 0090](../adr/0090-per-project-metadata-store.md)). Moving or
  renaming the file in the explorer keeps its history; deleting it there drops it.
- A click on an entry puts it into the arguments field, without running. **✕** (on the row under the mouse or the
  selected one) removes that entry.
- **Down** in the arguments goes into the list, on the newest entry; **Up**/**Down** choose, **Enter** puts the
  entry into the arguments, **Escape** goes back to the arguments unchanged.
- **▾ History** / **▸ History** expands or collapses the list; up to 5 rows show, more scroll. Whether it is expanded
  is one setting for all run panes (the Gradle view's too), kept across restarts. The section is hidden while the
  history is empty.

## Status
As for Gradle runs ([gradle.md](gradle.md)):
- the **status line**: "▶ deploy.sh --dry-run — running 0:02", "✔ deploy.sh — finished in 0:05",
  "✖ deploy.sh — failed (exit 1) after 0:01", "■ deploy.sh — stopped";
- a **background task** row "Script: deploy.sh --dry-run" whose ✕ stops the run;
- a **marker on the editor tab**: running, or failed until the tab is next shown.

**Stop** sends Ctrl+C and closes the terminal if the script has not ended after 5 seconds. Closing the tab or the
window while the script runs asks first ("deploy.sh is still running.").

## In the explorer
The context menu of exactly one script has **Run** (play icon) after Open: it opens the script's tab (or selects it)
and runs it there, with the same questions, showing the output beside the source.

## Moving
A script renamed or moved in the explorer keeps its tab, arguments and run as long as its new name still opens as a
script (e.g. `deploy.sh` renamed to `deploy` with a bash shebang), or while it has unsaved changes. Otherwise its tab
is opened anew with the viewer of the new name, as for any file ([editor-tabs.md](editor-tabs.md)).

## Known limits
- Scripts with Windows line endings (CRLF) fail with "bad interpreter: …^M": the kernel reads `bash\r` from the shebang.
- Login shells that are not POSIX shells (nushell, xonsh) cannot run the quoted command line.
- Without a shebang the login shell decides how to run the file (bash and zsh run it as a script, fish refuses).
- Stop ends the script and what it started only if they exit on Ctrl+C or the terminal's hangup; children started with
  `nohup` (or that ignore SIGHUP) keep running.
- With dash as the login shell, Ctrl+C does not end the run; Stop then ends it after 5 seconds.
- Run Saved Version runs the file on disk; saving it while that run is going changes the running script.
- On macOS, scripts in protected folders (Documents, Desktop, Downloads) may make macOS ask whether Thoreau may access
  them the first time.
- A script renamed, moved or deleted outside the explorer (in a terminal, by git) keeps its argument history
  under the old path; the new path starts without one.
- A scratch deleted automatically because it was closed empty keeps its history entry in
  `~/.sluide/outside-projects.json`.
- Two views of the same file in different windows see the entries the other records or removes only after the view
  is opened again or the file is moved.
