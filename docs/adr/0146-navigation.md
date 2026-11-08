# 0146. Navigation: go to definition, usages, occurrences, Back and Forward, the Navigate menu

- Status: accepted; amends [0145](0145-code-intelligence-module.md) (`popupOwner` also for focus checks); see [0141](0141-editor-api-for-language-features.md),
  [0142](0142-language-server-foundation.md), [0144](0144-python-via-ty.md), [0108](0108-code-editor-extensions-and-context-menu.md),
  [0084](0084-testability-logic-out-of-controls.md)
- Date: 2026-11-08

## Context
With Python files bound to ty ([ADR 0145](0145-code-intelligence-module.md)), the first features users expect of a
language server are to jump from a name to its declaration, to list where a name is used, to see its other
occurrences in the file, and to come back from where a jump led. ty answers definitions with several `Location`s for
overloads and the standard library (`posixpath.py`, `ntpath.py`), often in files outside the project (site-packages,
its typeshed cache). The keys are taken from IntelliJ (Shortcut+click, Shortcut+B, Alt+F7, Shortcut+Alt+Left/Right),
which the Navigate menu shows too; a key must not act twice when both the editor and the menu know it.

## Decision
- **Package `navigation`** of `code-intelligence`, two features in `builtInFeatures` after the server banner:
  `NavigationFeature` (definitions, usages, the link, the context menu items) and `OccurrencesFeature`. The restart
  item moves to the end of the list, so its context menu item comes last. The decisions are plain classes and
  functions ([ADR 0084](0084-testability-logic-out-of-controls.md)): `wordIn`/`wordAt` (identifier characters
  `[\p{L}\p{N}_]` by code point, a caret also taking the word that ends at it), `NavigationHistory`, `LinkModel`,
  `OccurrenceModel`, `sortedUsages`, `targetCell`, `readLineTexts`, `navigationBlocker`; the flows (`Definitions`,
  `Usages`, `TargetChooser`) talk to the popup through `TargetView` and to the disk through `LineReader`, so they run
  in tests over a fake server.
- **Go to definition** (`textDocument/definition`) at the caret (Shortcut+B, Navigate → Go to Definition, the context
  menu at the right-click) or at a Shortcut+click: none shows "No declaration found" at the name (a `CaretPopup` with
  only its placeholder, hidden by the next key press that no key binding took, or a click); one is jumped to; several
  are offered in a `CaretPopup` chooser in the server's order. A request is asked at the caret, or at the start of the
  name when the caret is right after it. One request per editor at a time (`RequestSlot`): a new one, hiding the list,
  an edit of the text and the end of the binding drop the one on its way, so an answer never shows at old offsets.
- **The list** (`TargetChooser`, one per editor, shared by definitions and usages): `file.py:LINE` (1-based), then the
  path relative to the project folder, absolute outside it, and the trimmed text of the line: from the editor for its
  own file, for other files read on a logged background thread (`NamedThreads`), files over 10 MiB skipped, and set
  when they arrive (the selection stays). Enter or a double-click jumps; Tab does not take an item here. An edit
  hides the list, as its places are of the text before.
- **Shortcut+hover and Shortcut+click** (`DefinitionLink`): while Shortcut is held (a key press or release says, and
  so does each mouse move) and the mouse is over a name, the name gets the layer `ci-link` (an underline, not
  following edits) and the hand cursor (`setTextCursor`); releasing Shortcut, moving off the name, leaving the text,
  typing or the window losing the focus (Cmd+Tab with Shortcut held sends no release) ends it. Only while the server
  runs and declares definitions. A primary Shortcut+click on a name (the same rule as the underline) goes to its
  definition; the press is consumed and so are its drag, release and click, so the caret and the selection stay. A
  Shortcut+click elsewhere (on `(`, a space) stays the editor's. On macOS Shortcut is Cmd, so Ctrl+click stays the context menu trigger; a popup trigger is never taken.
- **Jumps** (`Navigation`, one per window, owned by `CodeIntelligence`): to a place in the file of the editor that
  acts and has the focus, the caret moves there (`moveCaret`) and the text area keeps the focus
  (`popupOwner.requestFocus()`); everything else goes through `host.openFile(file, line, column)`, which selects the
  file's tab or opens it (also outside the project) and puts the caret there. So Back with the focus in the explorer
  or in an unbound file (a site-packages module) selects the tab instead of moving the caret of a background editor.
  `popupOwner` is also the node of the focus check; its KDoc says so. Every jump of a flow (definition, chooser, usages; later symbols) records the caret before it and the
  arrival in the history.
- **History** (`NavigationHistory`, root package): one per window, in `code-intelligence`, not in `editor-area`: only
  bound editors know their caret and the jumps, and Back acts across their files. Places are file, line and column. A
  jump pushes the place it left (not twice in a row, not for a jump to the same place) and forgets the way forward;
  Back pushes where the user is now (the caret of the focused bound editor, else where the last step arrived) to the
  way forward and goes to the previous place, Forward the reverse. At most 50 places each way, the oldest dropped;
  places in files that no longer exist (`Files.exists` when stepping onto them) and the current place are skipped.
  Places do not follow edits or renames.
- **Navigate menu** (`ide`, `NavigateMenu.kt`, right after View): Back (Shortcut+Alt+Left), Forward
  (Shortcut+Alt+Right) | Go to Definition (Shortcut+B), Find Usages (Alt+F7); File Structure and Go to Symbol follow.
  Back and Forward are bound to the history's `canGoBack`/`canGoForward`; the other two act on the focused bound
  editor (`CodeIntelligence.focusedBinding`: the editor whose text area is its scene's focus owner) and are disabled,
  set when the menu opens, without one or while its server cannot answer (`definitionBlocker`, `usagesBlocker`).
  `CodeIntelligenceWiring.intelligence` hands the `CodeIntelligence` to the menu.
- **Keys act once:** Shortcut+B and Alt+F7 are editor key bindings (`addKeyBinding`) that always consume the press in
  a bound editor, also when they cannot act (a note says why, e.g. "Go to Definition is not available (server
  starting)"). The menu has the same accelerators, but JavaFX runs a scene's accelerators only in the bubbling phase
  and only for presses nobody consumed (`KeyboardShortcutsHandler`), so they run only where no bound editor has the
  focus, and act on nothing there. The editor bindings make the keys work in any window with the editors and keep the
  editor's own behaviour first. Back and Forward are menu accelerators only: they act window-wide, and RichTextFX binds
  Ctrl/Alt+arrows for words, not Shortcut+Alt+arrows, so the press reaches the menu from an editor too.
- **Find usages** (`textDocument/references`, `includeDeclaration = false`) at the caret (Alt+F7, the menu, the
  context menu): the list shows "Finding usages…" at the name while asked; the usages in the editor's file come first,
  then by path, line and column, without repeats; one is jumped to, none shows "No usages found".
- **Occurrences** (`textDocument/documentHighlight`): the caret resting on a name for 300 ms (a constructor parameter)
  asks; the answer is the layer `ci-occurrence` with `followEdits`, classes `ci-occurrence-read`, `-write` and
  `-text` (none counts as text), subtle backgrounds with a dark variant (`occurrences.css`, added with
  `addStylesheet`). Moving within the name keeps them, leaving it clears them and drops the question; an edit keeps
  what shows (the layer follows) and asks again for the name at the caret. When the server could not be asked as the
  delay ended (still starting), the next caret move asks again, also within the same name.
- **Context menu:** Go to Definition and Find Usages after the editor's own items, showing their keys, disabled with
  the reason in the label ("Go to Definition (server starting)", "(not supported)", "(server not installed)",
  "(project not trusted)", "(server stopped)"); Restart Python Language Server last.

Rejected:
- **Accelerators only, no editor bindings:** the menu items' states are read when the menu opens, so a disabled state
  left from the last opening would swallow the key in the editor; and the keys would need a menu bar.
- **The history in `editor-area`** (as the plan had it): the editor area knows tabs, not carets or jumps; a window
  service there would need the code intelligence to report every jump anyway.
- **Following places through edits and renames:** the places would need the editor's change stream for closed tabs
  too; IntelliJ's history drifts the same way.

## Consequences
- Shortcut+B, Alt+F7 and Shortcut+click do nothing special in editors without code intelligence, and in a focused
  terminal on Linux they go to the shell; so do Shortcut+Alt+Left/Right.
- The focus check reads the scene's focus owner through `popupOwner`, the node ADR 0145 names for popups.
- The chooser's line texts of other files are read from disk, so a file open with unsaved changes shows its saved line.
- Listed in [../index.md](../index.md).
