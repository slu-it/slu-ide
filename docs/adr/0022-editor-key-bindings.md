# 0022. Editor key bindings

- Status: accepted
- Date: 2026-10-14

## Context
Editable code views ([ADR 0020](0020-editing-and-saving-files.md)) only had RichTextFX's own keys: Enter inserted a
bare line break, Tab a tab character. Editing code needs keys that keep the indentation, indent and outdent lines,
duplicate, delete and move lines, comment lines out, find and replace, and go to a line. These keys belong to the text
that has the focus: a terminal tab must keep getting Ctrl+D, Ctrl+R or Ctrl+G for its shell, and the file explorer
its own keys. JavaFX menu accelerators fire for whatever has the focus once a key press bubbles up unconsumed.

## Decision
- **One wellbehavedfx `InputMap` per view.** wellbehavedfx comes with RichTextFX, which binds its own keys through it
  as a fallback map. `CodeView` adds its maps with `Nodes.addInputMap` on its `CodeArea`, so they take precedence
  over RichTextFX's (Enter, Tab) and act only while that area has the focus. The maps live next to the view:
  `EditorKeys.kt` (editing), `FindBar` (find and replace), `GoToLine`. Within the find bar, a key filter on the bar
  takes Enter, Escape, F3 and Shortcut+F/R before its text fields do.
- **Editing keys need an editable view.** In a read-only view (a file that is not valid UTF-8) they are not consumed
  and RichTextFX's bindings, which do nothing there either, get them. Find and go to line work in read-only views;
  replacing is disabled.
- **Logic outside JavaFX.** Every editing command is a function in `text-editing` from the text and selection to one
  `TextEdit` (`Indentation`, `LineOperations`, `LineComments`, `TextSearch`), unit-tested without a toolkit. The view
  applies it with a single `replaceText` and then selects the edit's selection, so each command is one undo step.
  Commands are kept apart from the typing around them (`preventMerge` before and after), except Enter and a Tab that
  leaves just a caret: they merge with typing like the characters RichTextFX inserts. Replace All gets one `TextEdit`
  per match and applies them as one multi-change (`createMultiChange`): one undo step that leaves the text between
  the matches, and its styles, untouched.
- **Searching on the JavaFX thread, within a time budget.** Find and replace run where the text is, but give up after
  200 ms (`TextSearch.DEFAULT_BUDGET`): the text is handed to the regex engine wrapped in a `CharSequence` that throws
  once the deadline passed. A regex like `(a+)+\1b` or `.*foo.*bar` on a long line would otherwise freeze the window;
  the bar says "Search took too long" and does not repeat that search while the text is typed. At most 10,000 matches
  are marked; in texts above 1 MiB the query is searched once typing it pauses.
- **Shortcut modifier.** Keys use JavaFX's `SHORTCUT` modifier: Ctrl on Linux and Windows, Cmd on macOS.
- **The keys** follow IntelliJ IDEA and VS Code, picking IntelliJ's where they differ, unless the key is taken:

  | Key | Command | Convention |
  |-----|---------|------------|
  | Enter (also with Shift) | new line with the indentation of the current one, one more level after `{`, `[`, `(` | both |
  | Tab / Shift+Tab | indent / outdent the selected lines (Tab without a multi-line selection: spaces to the next stop or a tab) | both |
  | Shortcut+D | duplicate the selection or line | IntelliJ (VS Code: Shift+Alt+Down) |
  | Shortcut+Shift+K | delete the lines | VS Code; IntelliJ's Ctrl+Y is redo elsewhere, Cmd+Backspace deletes words |
  | Alt+Shift+Up / Down | move the lines | IntelliJ (VS Code: Alt+Up/Down) |
  | Shortcut+/ | comment lines out or back in | both |
  | Shortcut+F / Shortcut+R | find / replace | both / IntelliJ (VS Code: Ctrl+H) |
  | F3 / Shift+F3, Enter / Shift+Enter in the query | next / previous match (F3 in the text opens the bar but keeps the focus there) | both |
  | Escape | close the find bar | both |
  | Shortcut+G | go to a line | VS Code and IntelliJ on Linux and Windows (IntelliJ on macOS: Cmd+L) |
- **Shortcut+/ by character.** It is a `KeyCharacterCombination("/")` with Shift allowed: on many keyboard layouts
  (German, Swedish) `/` is Shift+7, so a `KeyCodeCombination(SLASH)` would never match there.
- **Window commands stay menu accelerators** in `MainWindow` (File → Save, Save All); they act on the selected tab,
  not on the focused text.
- **No command registry yet.** There is no Edit menu, no list of commands and no way to change keys. Commands are
  found through `docs/features/text-editing.md` and the tooltips of the find bar.

Rejected:
- **Menu accelerators for editor commands (an Edit menu):** they fire for any focus owner, so each command would have
  to find the focused view and leave terminals alone, and Ctrl+D or Ctrl+R would stop reaching shells unless every
  terminal consumed them first.
- **Plain event filters on the area:** they would work, but beside the mechanism RichTextFX binds its keys with;
  input maps state the precedence explicitly and express conditions such as "only while editable" (`consumeWhen`).
- **A command registry with configurable keymaps:** worth it once there are more commands and a menu or palette to
  show them; premature for a dozen keys.

## Consequences
- Keys are only discoverable from the documentation and tooltips until there is a menu or command palette.
- Window managers may take some keys before the application sees them: openbox, for example, binds Alt+Shift+Up/Down
  by default, so moving lines needs another window manager or its binding removed.
- With Shift allowed, Shortcut+Shift+/ also toggles comments on layouts where `/` needs no Shift.
- On macOS keyboards, F3 needs Fn (or the setting to use F keys as standard function keys); Enter and Shift+Enter in
  the query work without.
- Cmd+G is "find next" in most macOS applications, but goes to a line here, as Ctrl+G does on Linux and Windows: a
  deliberate choice for the same keys on all platforms, as IntelliJ has them on Linux and Windows.
- Searching blocks the window for at most the time budget; a regex that needs longer finds nothing.
- UI tests fire `KeyEvent`s at the area (`KeyPresses.kt`); the key for Shortcut+/ is looked up for the current layout.
- Add new editor keys to `EditorKeys.kt` (or the find bar) and to `docs/features/text-editing.md`.
