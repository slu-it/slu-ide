# 0104. Collapsible file explorer

- Status: accepted
- Date: 2026-10-28

## Context
The TODO panel at the right of the window collapses to a narrow stripe ([ADR 0102](0102-todo-panel.md)). Users want to
give the editor the whole width on the left side too, now and then, without losing the explorer's width. The left
column holds the explorer's button bar ([ADR 0095](0095-explorer-button-bar-and-select-opened-file.md)), the tree and
the background tasks below it ([ADR 0061](0061-background-tasks.md)).

## Decision
- **The same approach as the TODO panel, mirrored.** `ExplorerPanel` (`ide.explorer`) is the first item of the main
  split: expanded, the bar, the tree and the background tasks; collapsed, a 26 px stripe at the left with a chevron
  and the vertical label "Explorer" (style class `explorer-stripe` in `main-window.css`, focus-traversable). A click,
  Enter or Space on the stripe expands it. The bar's **Collapse** button (chevron left, at its right end, next to the
  editor) and View → **Explorer** (Alt+1, Cmd+1 on macOS) collapse it; the menu item toggles.
- **Expanded by default**, unlike the TODO panel: every window starts with the explorer at its start width. The state
  is per window and not persisted.
- **The whole column collapses**, background tasks included; they show again on expand. A running task is still in
  the log file.
- **`WindowExplorer`** (`ide.explorer`, a window part of [ADR 0068](0068-window-parts-in-ide.md)) owns the divider:
  collapsed, the stripe's width with `maxWidth = USE_PREF_SIZE`, so dragging the divider cannot widen it; expanded,
  the width the column had when it collapsed (kept in memory), even if the window was resized. The window's width goes
  to the editor. It replaces `fitExplorer`: `fitStart` sets the start width once the split has a width and then sets
  the TODO panel's divider again. Collapsing and expanding do not touch the TODO panel's divider: the other positions
  are fractions of the split's width and stay, while setting the TODO divider again would undo a width the TODO panel
  was dragged to.
- **Focus:** collapsing with the focus in the column gives it to the editor (`EditorArea.focusSelected`); expanding
  focuses the tree. Select Opened File and "Select in Explorer" expand a collapsed column first.
- **A copy of the pattern in `ide`, not a shared module:** the stripe, `show()` and the divider arithmetic are about 30
  lines, and there are two users. A foundation module for them would make `todos` depend on it for little gain;
  `ExplorerPanel` and `WindowExplorer` mirror `TodoPanel` and `WindowTodos` instead.

Rejected:
- Collapsing only the tree and keeping the background tasks visible: a narrow column of progress rows would need its
  own layout, and the tasks are short-lived.
- Persisting the collapsed state: the TODO panel does not either; a window that opens without its explorer surprises.

## Consequences
- `MainWindow` builds the bar with `collapse = explorer::collapse` and lets `WindowExplorer.attach` put the column into
  the split; the bean lists of the tests are unchanged (plain `ide` classes, no new bean).
- Alt+1 goes to the shell while a terminal has the focus on Linux and Windows, like Alt+6 and Alt+9.
- Listed in [../index.md](../index.md).
