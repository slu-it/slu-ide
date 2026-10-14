# Docker Sandbox

**Module:** `modules/docker-sandbox` (package `dev.sluide.dockersandbox`), depends on `modules/terminal`; menu in
`ide` (`MainWindow`); decision in [ADR 0010](../adr/0010-docker-sandbox.md)

- Menu **Docker Sandbox**:
  - **Claude Code** runs `sbx run claude .` in the project folder.
  - **Environment** runs `sbx env run` in the project folder; it needs an `sbxenv.yaml` in the folder (not in
    subfolders).
- Each opens a terminal tab ([terminal.md](terminal.md)) named "Claude Code 1", "Environment 1", … (numbered per
  action, so the second Claude Code tab is "Claude Code 2"), whose tooltip shows folder and command. The command runs
  in the user's login shell (`$SHELL -l -c`), so `sbx` must be on its `PATH`. When `sbx` ends, the tab shows
  "(exited)". If `sbx` is not found, there is no dialog: the shell prints its "command not found" message in the tab,
  which then shows "(exited)".
- If the shell itself cannot be started, an error dialog says so ("Claude Code cannot be started. /bin/nosh could not
  be started in …") and no tab opens; the failed start uses up no number.
- Closing a tab while `sbx` still runs (×, middle click, context menu) asks first; Cancel keeps the sandbox running.
  After it has ended, the tab closes without asking. Closing the window asks once for all its running tabs, like for
  every terminal.
- Sandboxes run **only in folders below the user's home folder**: never in the home folder itself, a folder above it
  or outside it. Folders are compared by their real paths, so symbolic links and `..` do not get around this.
- Enforced twice:
  - The menu items are disabled when no folder is open, the folder is not below the home folder, or (Environment)
    `sbxenv.yaml` is missing. This is re-evaluated each time the menu opens and when the folder changes.
  - Starting checks the same again on the folder the terminal starts in; if it fails, an error dialog says why
    ("Claude Code cannot be started. Docker sandboxes run only in folders below your home folder …") and no tab
    opens. The reasons: "No folder is open.", "The folder … does not exist.", "… is not a folder.", "Your home folder
    … does not exist." (e.g. a wrong `user.home`), not below the home folder, or (Environment) "There is no
    sbxenv.yaml in …".
- Structure: `DockerSandbox` (window-scoped Spring bean: `problem(action, folder)`, `open(action, folder)`; both take
  the folder as `Path?`, `null` meaning no folder is open), `SandboxAction` (title, command, required file),
  `SandboxFolder` (the folder rule).
