# 0134. Split editor area: two sides in one tab model

- Status: accepted
- Date: 2026-11-06

## Context
Users want to see two tabs side by side, e.g. a file and its test, or a file and a terminal. The editor area has one
row of tabs over one `EditorTabs` model. `EditorArea` listens to that model's tab list: a tab that leaves it loses its
file watch and its editor-font binding and, for a file, reports `onFileClosed` (the window deletes empty scratch files
with it); closing it calls `EditorTab.dispose()`. A tab moved to the other side must keep all of that: its view,
caret, undo history, diff, watch and font.

## Decision
- The editor area has two sides, `Side.LEFT` and `Side.RIGHT`, in one `EditorTabs` model. Every tab is on exactly one
  side; `tabs` lists the left side's tabs, then the right side's. `tabsOf(side)` and `selectedOf(side)` give each
  side's tabs and selected tab, `sideOf(tab)` a tab's side, `split` whether the right side has tabs.
- A move between the sides (`moveTo(tab, side, index)`) is a permutation of `tabs`, never a removal and an addition,
  so the listeners in `EditorArea` do not take the tab for closed: no `dispose`, no `onFileClosed`, the watch and the
  font binding stay. The moved tab is selected on its new side, which becomes current; its old side selects as after
  a close (right neighbour, else left). The moved tab gets the focus as a chosen tab does
  ([ADR 0050](0050-focus-when-a-file-tab-is-shown.md)). A move within a side (`move`) stays a permutation too. The
  only tab, without a split, stays where it is.
- New tabs always open on the left side (`open(path)`, `open(tab)`, tabs opened in the background), also while the
  right side is current. Opening a selected tab makes the left side current; a background open changes neither the
  current side nor a selection.
- The current side (`current`) is the side that last had the focus or a mouse press, left by default.
  `selected` is the current side's selected tab, so Save, Close Tab and Show Changes act on it. Selecting a tab makes
  its side current, and so do the close actions of a tab's context menu. Every operation updates the lists first,
  then `split`, the selections and `current`, and `selected` last, once, with its final value: the area swaps the
  content and moves the focus on each change (ADR 0050: a tab shown in place of another takes the focus only if its
  side had it). A side that takes the focus while an operation notifies its listeners does not become current
  (`makeCurrent` is ignored meanwhile), so it cannot undo the side the operation chose.
- The right side exists only while it has tabs; when it loses its last one (closed, moved left), the left side becomes
  current. When the left side loses its last tab while the right side has tabs, the right side's tabs become the left
  side's, in the same order and with their selection, so `tabs` does not change.
- Close Others, Close Tabs to the Left/Right and Close All of a tab's context menu act on its side only
  (`closeAll(side)`); `closeAll()` still closes every tab, e.g. with the window.
- Moving a tab by mouse to the other side is a plain mouse drag between the tab rows, like the drag within a row
  (no JavaFX drag and drop with a `Dragboard`, which is meant for transfers between nodes or applications).
- Nothing about the split is stored: a window opens with one side, and the divider is not remembered.

Rejected: two `EditorTabs` instances, one per side. A move would be a removal from one list and an addition to the
other, which the watch, font and `onFileClosed` listener in `EditorArea` takes for a close and a new open; telling the
two apart would need a "moving" flag shared between the models, and `tabs`, `files` and `find` would have to merge
two lists everywhere.

## Consequences
- The `tabs` listener in `EditorArea` stays as it is; only the rules for the sides are new, in `EditorTabs`, and are
  unit-tested without a toolkit (`EditorTabsTest`).
- `TabStrip` shows one side's tabs (`TabStrip(editorTabs, side, ...)`); the layout with a second strip, the context
  menu entries to move a tab and the drag between the rows build on this model.
- A file is open on one side only; the same file on both sides would need a second view of it and is not possible.
