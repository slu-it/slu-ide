# 0180. Shell and Docker sandbox tabs close themselves when their process exits with 0

- Status: accepted; amends [0007](0007-terminal.md), [0010](0010-docker-sandbox.md) and
  [0170](0170-sbx-management-console.md)
- Date: 2026-11-16

## Context
A terminal tab stayed open as "Name (exited)" when its shell or command ended, so every `exit` or finished Claude Code
session left a dead tab to close by hand. A tab that ended with an error is worth keeping: its output tells why.

## Decision
- `EditorTab.closeRequested` (an `ObservableValue<Boolean>`, false by default) becomes true, on the FX thread, when a
  tab wants to close itself. `EditorTabs` watches every tab it holds (`CloseRequests`: subscribed after the tab was
  added, also when it is already true then; dropped when the tab is removed or replaced; moves keep it) and closes the
  tab like any close, without asking (a finished process has no `closeWarning`) and selecting the neighbour. The default
  is a `ConstantValue` (file-viewers), which keeps no listeners: a shared property would keep every window's tabs
  alive through the watchers' listeners. The other shared defaults of `EditorTab`, `FileView`, `OpenFile` and
  `EditorArea` use it too.
- `TerminalTab` takes `closeOnSuccess`: its `closeRequested` is true once the process has exited with code 0.
  `Terminals.open(folder)` (shell tabs) and the Docker sandbox tabs ("Claude Code N", "SBX") set it;
  `Terminals.open(folder, name, command, environment, closeOnSuccess = false)` keeps the default, so the Docker tabs and
  Run in Terminal commands stay open.
- A tab that stays open shows "Name (exited N)" for a non-zero code N and "Name (exited)" for code 0 or an unknown one
  (`terminalTitle`).
- Accepted: `exit` after a failed command returns that command's code and keeps the tab.
- ADR 0170's SBX tab logic is unchanged: a closed tab is reopened anew, a tab that ended with another code is "ended"
  (`Terminals.hasEnded`) and replaced.

## Consequences
Finished sessions and shells leave no tab behind; failures stay visible with their code. Other tab kinds can use
`closeRequested` later. There is no setting to turn this off.
