# 0110. Tools menu: window tools open as one tab per window

- Status: accepted; amends [0068](0068-window-parts-in-ide.md) (`WindowTools` gets the Tools menu)
- Date: 2026-10-29

## Context
Thoreau gets helpers that belong to no file, starting with the Coloring tool ([ADR 0109](0109-coloring-tool.md)).
Terminals and sandboxes are non-file tabs too, but each menu choice opens another one. A helper keeps state the user
comes back to, so a second tab of it would only split that state.

## Decision
- A **Tools** menu after Docker Sandbox, one item per tool, no shortcuts so far.
- A **window tool** is an `ide` concept, not module API: `WindowTool(title, createTab)`, listed in `ToolsMenu` (package
  `ide.tools`, used by `WindowTools`). Choosing a tool selects its tab while it is open in the window, else creates
  and opens a new one; a tool's module keeps whatever state should outlive its tab.
- Tool tabs close with Shortcut+W, ask nothing on closing and are not restored after a restart, like every non-file
  tab.

## Consequences
- Another tool is one `WindowTool` in `ide` and its tab in its module.
- Listed in [../index.md](../index.md).
