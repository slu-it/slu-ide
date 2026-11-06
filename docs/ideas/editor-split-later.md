# Idea: Split editor area features left for later

**Status:** idea, collected 2026-11-06 while building the split editor area
([ADR 0134](../adr/0134-split-editor-area.md), [editor-tabs.md](../features/editor-tabs.md)); nothing decided or
built. Each needs a decision of its own.

- **Shortcut+\** to move the selected tab to the other side and **Shortcut+Alt+Left/Right** to focus a side; both
  were free on 2026-11-06 (check `keyboard-shortcuts.md` again).
- **The same file on both sides** (a shared document with two views); today a file is open on one side only.
- **Vertical split or more than two sides.**
- **Remembering the divider** instead of 50/50 each time the right side opens.
- **Auto-scroll of the target row** while a header is dragged over it; today the insertion uses its current scroll.
- **Dropping on the other side's content** (not only its tab row).
- **Dragging tabs into another window.**
- **Escape cancelling a drag.**
