# 0010. Docker sandboxes run in terminal tabs, only below the home folder

- Status: accepted
- Date: 2026-10-13

## Context
Users want to start Docker sandboxes (`sbx`) for the open project from the IDE: Claude Code (`sbx run claude .`) and
the project's environment (`sbx env run`, defined by `sbxenv.yaml`). A sandbox gets access to the folder it is started
in. Started in the home folder or above, it would see far more than one project (SSH keys, credentials, all other
projects), so that must never happen, also not through a bug in the menu logic.

## Decision
- A new module `modules/docker-sandbox` (`DockerSandbox`, `SandboxAction`, `SandboxFolder`) depends on `terminal`;
  `ide` adds the **Docker Sandbox** menu.
- The command is the terminal tab's process: `$SHELL -l -c '<command>'` via `Terminals.open(folder, name, command)`,
  so the login shell's `PATH` finds `sbx` and nothing is typed into an interactive prompt (which would depend on
  timing and the user's shell configuration). When `sbx` ends, the tab shows "(exited)".
- Folder rule (`SandboxFolder.check`): only folders strictly below the user's home folder, comparing **real paths**
  (`toRealPath()`), so symbolic links and `..` cannot lead to the home folder, above it or outside it; `Path.startsWith`
  compares path elements, so `/home/bob2` is not below `/home/bob`. Missing folders and files (not folders) are
  rejected.
- Enforced twice: the menu disables actions whose `DockerSandbox.problem` is not `null` (recomputed when the menu is
  shown and when the folder changes), and `DockerSandbox.open` checks again on the very folder it passes to the
  terminal and throws `SandboxNotAllowedException`, which the main window shows as an error. Both take the window's
  folder as it is (`null` without a folder), so the messages, including "No folder is open.", come from
  `DockerSandbox`. A shell that cannot be started is reported the same way (`TerminalStartException` from
  `Terminals`).

## Consequences
- The sandbox tab has no shell left after `sbx` ends; users open a normal terminal for that.
- `sbx` must be on the `PATH` of the login shell; other installations are not looked for. Each window checks this
  once when it opens (`command -v sbx` in that shell) and disables the Docker Sandbox menu without it, so `sbx`
  installed later shows up in new windows only.
- Projects outside the home folder (e.g. `/opt/work`) cannot use sandboxes from the IDE.
