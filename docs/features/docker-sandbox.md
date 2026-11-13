# Docker Sandbox

**Module:** `modules/docker-sandbox` (package `dev.sluide.dockersandbox`), depends on `modules/terminal`; menu in
`ide` (`WindowTools`); decision in [ADR 0010](../adr/0010-docker-sandbox.md)

- Menu **Docker Sandbox** with the session item **New Docker Sandbox Session (Environment)** when the project folder
  contains an `sbxenv.yaml` (a regular file, not in subfolders), otherwise **New Docker Sandbox Session (Claude
  Code)**. Accelerator Shortcut+Shift+B. The action is chosen by `SandboxAction.forFolder`, and again when the item
  fires (the accelerator works without the menu showing, so the label may be stale):
  - **Claude Code** runs `sbx run claude .` in the project folder.
  - **Environment** runs `sbx env run` in the project folder; it needs the `sbxenv.yaml`.
- The session opens a terminal tab ([terminal.md](terminal.md)) named "Claude Code 1", "Claude Code 2", … for both
  actions (one counter per window: Claude Code, Claude Code, Environment give 1, 2, 3), whose tooltip shows folder
  and command. The command runs in the user's interactive login shell (`$SHELL -l -i -c`, the startup files of a
  terminal tab), so `sbx` must be on its `PATH`. When `sbx` ends, the tab shows
  "(exited)".
- The second item, **Management Console** (no accelerator), runs bare `sbx` in the user's home folder in a terminal tab
  named "SBX" ([ADR 0170](../adr/0170-sbx-management-console.md)). Bare `sbx` mounts no folder, so the below-home
  rule below does not apply: the item is enabled whenever `sbx` was found, also with no project folder open. There is
  one SBX tab per window: firing the item while that tab's command runs selects it; once the command has ended the tab
  is closed and a fresh one opens; if it was closed, a new one opens. If it cannot be started, an error dialog says
  "The Management Console cannot be started. …" and no tab opens.
- Copying in Claude Code puts the text on the system clipboard (OSC 52,
  [ADR 0133](../adr/0133-terminal-osc-52-clipboard.md)). Claude Code takes the mouse, so to copy with Thoreau's own
  selection, Shift+drag and then Ctrl+Shift+C (Cmd+Shift+C on macOS).
- **Without `sbx` the whole menu is disabled.** Each window checks once, in the background when it opens, whether
  `command -v sbx` succeeds in the login shell; the menu stays disabled until that has found `sbx`. A shell that cannot
  be started or takes longer than 10 seconds counts as "not found". `sbx` installed while a window is open is picked
  up by windows opened afterwards (or after a restart).
- If the shell itself cannot be started, an error dialog says so ("Claude Code cannot be started. /bin/nosh could not
  be started in …") and no tab opens; the failed start uses up no number.
- Closing a tab while `sbx` still runs (×, middle click, context menu) asks first; Cancel keeps the sandbox running.
  After it has ended, the tab closes without asking, and Shortcut+W closes it too; while the sandbox runs, File →
  Close Tab is disabled. Closing the window asks once for all its running tabs, like for
  every terminal.
- Sandboxes run **only in folders below the user's home folder**: never in the home folder itself, a folder above it
  or outside it. Folders are compared by their real paths, so symbolic links and `..` do not get around this.
- Enforced twice:
  - The session item is disabled when `sbx` was not found, no folder is open, the folder is not below the home folder. This is re-evaluated each time the menu opens or the accelerator is used, and when the folder changes. Without `sbxenv.yaml` the item offers Claude Code, so it is never disabled for that.
  - Starting checks the same again (and, for Environment, that `sbxenv.yaml` exists) on the folder the terminal starts in; if it fails, an error dialog says why
    ("Claude Code cannot be started. Docker sandboxes run only in folders below your home folder …") and no tab
    opens. The reasons: "sbx is not installed (not found on the PATH of your login shell).", "No folder is open.", "The folder … does not exist.", "… is not a folder.", "Your home folder
    … does not exist." (e.g. a wrong `user.home`), not below the home folder, or (Environment) "There is no
    sbxenv.yaml in …".
- Structure: `DockerSandbox` (window-scoped Spring bean: `sbxInstalled`, `problem(action, folder)`,
  `open(action, folder)`, `consoleProblem()`, `openConsole()`; `problem` and `open` take the folder as `Path?`, `null`
  meaning no folder is open), `SandboxAction` (title, command, required file), `SandboxFolder` (the folder rule),
  `SbxCheck` (`command -v sbx` in the login shell, via the terminal module's `loginShellFinds`, which the Gradle check
  uses too).
