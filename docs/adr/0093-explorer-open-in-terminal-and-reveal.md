# 0093. Explorer: Open in Terminal and Reveal in Finder

- Status: accepted
- Date: 2026-10-25

## Context
The explorer's context menu could open a folder in the system file manager but not show a file in it, and could not
start a terminal in a folder. The explorer module must not depend on the terminal module.

## Decision
- **Open in Terminal** is a plain item with a nullable hook `FileExplorerView.openInTerminal: ((Path) -> Unit)?`, like
  `showHistory`; the item is offered only while the window sets it (`tools.openTerminal(stage, folder)`, which gained an
  optional folder and keeps the error dialog of New Terminal). Target: one folder, one file (its folder), or the empty
  area with a project (project folder). It is not a `FolderAction`: those expand and refresh the folder, take folders
  only, and return a file to open.
- **Reveal**: one item serves both; for exactly one regular file it is "Reveal in Finder/Explorer/File Manager",
  otherwise "Open in ...". `SystemFileManager.reveal` lists the commands to try in order. macOS `open -R <file>`;
  Windows `explorer.exe /select,<file>` as one argument, so Java quotes it whole when the path has a space; this is
  checked by a unit test of the argument list only and is untested on Windows (like the comma limitation for
  folders). Linux calls `dbus-send ... org.freedesktop.FileManager1.ShowItems` with the percent-encoded file URI
  (`Path.toUri()`) and an empty string (no shell, so `string:` without quotes), falling back to `xdg-open <folder>` if
  it cannot start or exits non-zero. `dbus-send` splits an array value at every comma, so commas in the URI are
  encoded as `%2C`; it runs with `--reply-timeout=5000`, so a file manager that never answers does not block the
  thread. The last command of the chain (`xdg-open`) is only started, not waited for, so a file manager staying in the
  foreground does not hold the thread.
- Only the Linux chain needs an exit code (of all but its last command); it runs on the explorer's background thread
  (`runInBackground`) and shows a failure through `dispatch`. With a single command (macOS, Windows) the command is started without waiting, as
  before, since `explorer.exe` exits with 1 on success.
- `MenuState` gets `reveal` and `terminal`.

## Consequences
- Revealing works without a file manager supporting the D-Bus interface, though only the folder opens then.
- Windows reveal may need a different quoting if Explorer misreads the argument; only a Windows run can tell.
- No new key bindings.
