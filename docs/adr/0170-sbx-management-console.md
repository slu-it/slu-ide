# 0170. Management Console: bare `sbx` in the home folder, and one "Claude Code" tab title

- Status: accepted; amends [0010](0010-docker-sandbox.md) and [0156](0156-menu-overhaul.md)
- Date: 2026-11-13

## Context
Bare `sbx` opens sbx's own management console (sandboxes, their state). Users want it inside the IDE, next to the
sessions. ADR 0010 allows sandboxes only in folders below the home folder, because a sandbox mounts the folder it is
started in. Bare `sbx` mounts no folder, so that reason does not apply, and it should work without an open project.

Session tabs were named per action ("Claude Code 1", "Environment 1"), though both actions run Claude Code's
sandbox from the user's point of view.

## Decision
- The Docker Sandbox menu gets a second item, **Management Console** (no accelerator). It runs bare `sbx` in the
  home folder (`DockerSandbox.openConsole`) in a terminal tab named "SBX". It is the one exception to the below-home
  rule: it needs only `sbx` (`DockerSandbox.consoleProblem`), so it is enabled without a project folder. Its error
  dialog reads "The Management Console cannot be started. …".
- One SBX tab per window. Firing the item while that tab is open and its command runs selects it; once the command has
  ended, the tab is closed (an ended command tab has no close warning) and a fresh one opens; a closed tab is opened
  anew. "Ended" is the exact state `Terminals.hasEnded(tab)`: the tab is one of that instance's open terminal tabs and
  its view has exited, never read from the "(exited)" title.
- Every session tab, for `sbx run claude .` and `sbx env run`, is named "Claude Code N" with one counter per window;
  a failed start uses up no number. The menu label and error messages keep the action's title.

## Consequences
- The below-home rule still guards every sandbox that mounts a folder; the console is reachable from any window.
- Tab titles no longer tell the actions apart; the tooltip still shows the command.
- `EditorArea.close(tab)` is public API to close one tab without asking.
