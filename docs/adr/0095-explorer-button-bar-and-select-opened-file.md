# 0095. Explorer button bar and Select Opened File

- Status: accepted
- Date: 2026-10-25

## Context
The explorer had no way to jump to the file of the current tab, to fold the tree back, or to refresh except F5 and the
context menu. Other IDEs have a small toolbar for this.

## Decision
- **Bar:** `ExplorerBar` (module file-explorer), a slim row of Ikonli Material Design icon buttons directly above the
  tree (`leftPane` in the ide module places it): Select Opened File, Collapse All, Refresh. A further Compact Middle
  Folders toggle is planned for the same row. Tooltips name the action and its key as the platform writes it. Buttons
  are not focus-traversable, so clicking them keeps the focus in the tree.
- **Select Opened File is manual only:** the tree never follows the selected tab (the user's choice). Three triggers do
  the same, `FileExplorerView.reveal(path, focus = true)`: the bar button, View → Select Opened File with Alt+F1
  (Option+F1 on macOS), and "Select in Explorer" in a file tab's context menu next to Show History (it acts on that
  tab's file, not necessarily the selected one). All **move the focus into the explorer**.
- **Only file tabs count** (`OpenFile`; `EditorArea.selectedFile`): not diff, terminal or other tabs. The button and the
  menu item are disabled while there is no such file or no top-level node holds it (`FileExplorerView.canReveal`, so
  files in Scratches work). The explorer module does not know the editor: the window passes the file and the action to
  the bar, and sets the tab menu's hooks.
- **Collapse All** (`tree/collapseAll`, a function over tree items): collapses every loaded folder of every top-level
  node; the project node stays expanded (its first level visible), extra roots collapse. A selected row that becomes
  hidden is replaced by its nearest ancestor still shown. It does not take or give the focus.
- **Refresh** is `refreshAll`, as F5 and the context menu.
- **Alt+F1 and terminals:** the terminal sends every key in `SPECIAL_KEYS`, F1 included, to the shell whatever the
  modifiers (macOS too), and consumes it, so the menu accelerator does not fire while a terminal has the focus. Left as
  it is (the terminal is not changed here); documented. The bar button and the tab menu work. On Linux, GNOME, KDE and
  Xfce often grab Alt+F1 by default (menu, launcher), so the shortcut may never reach the app there.
- **`canReveal` is a cheap check** (a root holds the path), not a promise: if the file is no longer shown (deleted
  outside the IDE, under `.git`), `reveal` finds nothing and Select Opened File does nothing; there is no feedback.

## Consequences
- The tab menu's window hooks (`showHistory`, `canShowHistory`, `selectInExplorer`, `canSelectInExplorer`) live in
  `TabMenuHooks`, owned by `TabStrip` and exposed as `EditorArea.tabMenu`.
- The file-explorer module now depends on `ikonli-materialdesign2`.
