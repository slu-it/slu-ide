# 0149. Rename, file structure and go to symbol; the search popup

- Status: accepted; amended by [0151](0151-workspace-folders-per-project.md) (Go to Symbol deduplicates); amends [0145](0145-code-intelligence-module.md) (`PopupCell.indent`, a second popup kind that
  takes the focus, `DocumentBinding.bindings`), [0146](0146-navigation.md) (the Navigate menu's last two items), [0142](0142-language-server-foundation.md)
  (`workspaceSymbol/resolve` in `ServerFeatures.supports`, `LanguageServerSpec.isIdentifier`); see [0148](0148-diagnostics-quick-fixes-semantic-highlighting.md),
  [0144](0144-python-via-ty.md), [0084](0084-testability-logic-out-of-controls.md)
- Date: 2026-11-08

## Context
With navigation ([ADR 0146](0146-navigation.md)) and the workspace-edit applier
([ADR 0148](0148-diagnostics-quick-fixes-semantic-highlighting.md)) in place, three server features remain of the
round: rename (`textDocument/prepareRename`, `textDocument/rename`), the structure of the file
(`textDocument/documentSymbol`) and symbols of the whole project (`workspace/symbol`). ty declares
`renameProvider: {prepareProvider: true}`, `documentSymbolProvider` and `workspaceSymbolProvider` (without resolve);
its rename answers `changes`, and its workspace symbols come fuzzy and unranked. Rename needs a name typed by the
user, and the two symbol lists need a query typed while the list shows: both need a field that takes the keys, which
the caret popups ([ADR 0145](0145-code-intelligence-module.md)) are built never to do. A rename that changes other
files changes files the user does not see, possibly closed ones on disk.

## Decision
- **Rename** (package `rename`, `RenameFeature` in `builtInFeatures` after the occurrences, so its menu item follows
  Find Usages): **Shift+F6** at the caret (an editor key binding, always consumed in a bound editor, free in the
  editors) and **Rename…** in the context menu at the right-click, disabled with the reason
  (`navigationBlocker(binding, "textDocument/rename")`: "server starting", "not supported"…).
  - **Where the name is:** with `prepareRename` the server is asked at the caret (at the word's start when the caret is
    right after it): a range is used with its text, a range with a placeholder offers the placeholder, the default
    behaviour takes the word at the caret, `null` or an error shows "Cannot rename this" (with the server's message)
    at the caret. Without `prepareRename` the word at the caret; none says "Cannot rename this".
  - **The rename field** (`RenameField`): a `TextField` with the name selected in a plain `javafx.stage.Popup` below the
    name, owned by `popupOwner`'s window, styled like the caret popups (`rename-popup`). It takes the focus: JavaFX
    redirects a popup owner's key events to the popup's scene first (`PopupEventRedirector`), so typing reaches the
    field while the editor's text area stays its own scene's focus owner; the popup's root consumes every key the
    field leaves, so Shift+F6 or Shortcut+B in the field never reach the editor, and neither do the window's
    accelerators (Shortcut+S, Shortcut+W) while it shows. Enter renames; Escape, a click outside, the field losing
    the focus and the window moving, minimizing or losing the focus cancel; the text area is asked for the focus when
    it hides. An empty name (after trimming) or the unchanged one does nothing.
  - **Valid names:** servers rename to anything (ty answers full edits for `foo bar`, `1abc` and `class`), so the name
    typed is checked first by `LanguageServerSpec.isIdentifier` (added to the spec API of ADR 0142; by default not
    empty and without whitespace or control characters). `TySpec` overrides it with Python's rule, as
    `str.isidentifier()`: a Unicode identifier start or `_`, then identifier parts, and no hard keyword (`class`,
    `None`, `async`…); soft keywords (`match`, `case`, `type`, `_`) are names. A refused name shows "Not a valid name:
    …" below the field, which stays open; typing clears it.
  - **The edit:** `textDocument/rename` at the same position. ty's edits are versionless `changes`, so every answer is
    checked against the text it was asked for: a `prepareRename` answer after the document changed, a typed name when
    the text changed while the field showed, and a rename answer when the document or any bound file of the edit
    changed since the request (the versions of the window's bound documents are noted when it is sent,
    `DocumentBinding.bindings`; a file bound since counts as changed) are dropped with "The text changed. Rename
    again." The same check runs again when the preview has looked at the files and after the user confirmed it
    (a reload from disk while the modal dialog showed). `null` or no
    files say "Nothing to rename". The edits are counted per file (`renameChanges`, through `planOf`, so the
    applier's refusals of resource operations and repeated files apply here too). An edit of the editor's file only
    is applied at once: one `applyEdits` call, one undo step. An edit of other files too shows a **preview** first: a
    `Dialog` owned by the window, title "Rename", header "Rename “old” to “new”?", "N changes in M files" and a list
    of the files by path (project-relative, absolute outside) with their counts, marking files open with unsaved
    changes ("unsaved changes": a bound editor, changed there and left unsaved; "unsaved changes the server does not
    know": an editor without code intelligence whose text differs from the disk), closed files in the project
    ("written to disk": no undo takes that back), closed files outside the project and files that do not exist (checked
    first, as the applier refuses them before it compares real paths). The states come from `RenameFileStates` (bound editors on the FX thread, the disk on a `NamedThreads`
    virtual thread). Rename is the default button, Cancel the other; a dismissed dialog cancels. Files the applier
    would refuse (outside the project, unknown unsaved changes, missing) are named in a line "The rename cannot be applied: …"
    and the dialog then offers Cancel only: applying could only be refused.
  - **Applying** goes through `WorkspaceEdits` (all its checks, ADR 0148). A refusal or a partial failure shows an
    `Alert` (warning, owned by the window) with "Rename not applied: …" or "Renamed in a.py only. …". The caret goes
    as far into the new name as it was into the old one (`caretAfterRename`) right when the editor's own edits apply
    (the applier's target is wrapped for that), before closed files are written, so typing meanwhile is not undone.
- **The search popup** (`ui/SearchPopup`, internal to the module, the reusable part): a `TextField` on top and a list
  below in a plain `Popup`, centred at the top of an anchor area (`searchPopupPosition`: the editor's text area, or the
  window's content) and 520 px wide. Unlike a caret popup it takes the focus, because the user types a query into it;
  the editor's own key bindings, which the caret popups use, would type into the text instead. Its field takes
  Up/Down (wrapping), PageUp/PageDown, Enter and Escape in an event filter before its own handling; a click selects,
  a double-click takes, and the list is not focus traversable, so a click keeps the field's focus. It hides on Escape,
  a click outside, the field losing the focus and the owner window moving, minimizing or losing the focus, and gives
  the anchor's node the focus back. Rows are `PopupCell`s (the caret popups' row, now with an `indent`) with the
  completion's kind icons. A **note** mode shows only a message, without the field, until the next key (Escape goes
  no further, other keys still act), for Go to Symbol without a server. The flows talk to it through `SearchView`.
- **File structure** (package `symbols`, `FileStructureFeature`): **Shortcut+F12** in a bound editor (an editor key
  binding, always consumed; free in the editors) and Navigate → File Structure. `textDocument/documentSymbol`;
  hierarchical `DocumentSymbol`s are flattened depth first with their nesting (at most 32 deep, iteratively), flat
  `SymbolInformation`s stay at depth 0 with their container as detail (`symbolRows`). Unfiltered the rows are indented
  by nesting and the innermost symbol whose range holds the caret is selected (`symbolAt`); typing filters by the
  completion's matcher (`matchKind`: case-insensitive, prefix matches, then part starts, then subsequences; document
  order within each group) without indentation, the best match selected (`shownSymbols`). "Searching…" until the
  answer, "No symbols" for none. Enter or a double-click jumps to the symbol's name through `Navigation.jump`, so
  Back returns. When the server cannot answer, a note at the caret says why ("File Structure is not available (not
  supported)", `CaretNote`).
- **Go to symbol** (`GoToSymbol`, one per window, owned by `CodeIntelligence`): **Shortcut+Alt+Shift+O** and Navigate →
  Go to Symbol…, a menu accelerator only (it acts window-wide). The server asked is the focused bound editor's if it
  answers `workspace/symbol`, else the first bound editor's whose server does (`symbolSource`). The search popup at the
  top centre of the window's content; each change of the query asks `workspace/symbol` 200 ms after the last key
  (`SymbolSearch`, delay and scheduler as constructor parameters), the latest question wins (a `RequestSlot`, and a
  generation check for answers already on their way); a blank query asks nothing ("Type a symbol name"); while asked,
  the last rows stay and "Searching…" shows only without any. Rows show the name, then the container and the path
  (project-relative) and the kind icon; the server's order is kept, but names equal to the query come first, then
  those starting with it, the same case before other cases, and types and functions before variables within each
  (`rankWorkspaceSymbols`: `Console` finds the class before ty's many `console` variables), at most 200. A symbol sent with its file only (`WorkspaceSymbol` with
  a `WorkspaceSymbolLocation`) is resolved first when the server declares `workspaceSymbol/resolve` (added to
  `ServerFeatures.supports`; a resolve still on its way is dropped when the popup opens again or the window closes),
  else opened at its first line. The jump starts from the caret of the editor focused when
  the popup opened (`Navigation.open`, recorded in Back and Forward); with the focus elsewhere it opens the file
  without a place to come back to. Without a server to ask the popup shows a note ("Go to Symbol is not available (no
  language server running)").
- **Navigate menu:** File Structure (Shortcut+F12) and Go to Symbol… (Shortcut+Alt+Shift+O) after Find Usages.
  File Structure is enabled, when the menu opens, as Go to Definition is (`fileStructureBlocker`). Go to Symbol is
  disabled while the menu shows without a server to ask (`goToSymbolBlocker`) and enabled again when it hides, so its
  accelerator always acts and shows the note. `navigateMenu` takes the window (Go to Symbol shows in it);
  `MainWindow` passes its stage on the same line.
- **Testability** ([ADR 0084](0084-testability-logic-out-of-controls.md)): the decisions are pure functions
  (`prepareAnswer`, `wantedName`, `renameChanges`, `renameFailure`, `renamePreview`, `caretAfterRename`, `symbolRows`,
  `shownSymbols`, `symbolAt`, `workspaceSymbolRows`, `rankWorkspaceSymbols`, `searchPopupPosition`, `symbolSource`)
  and plain classes over FX-free views (`Rename` over `RenameView` and `RenameFileStates`, `FileStructure` and
  `GoToSymbol` over `SearchView`, `SymbolSearch` with its scheduler), tested over fake servers; UI tests only for the
  focus, the key routing into the popups and the menu wiring.

Rejected:
- **The rename field as a `CaretPopup` or `EditorPopupWindow`:** they strip focus traversal and leave the keys to the
  editor's bindings, so typed characters would go into the text.
- **Inline rename in the text (IntelliJ's):** it needs the occurrences as linked edit ranges in the editor, which the
  editor API does not offer; a field at the name asks the same with less.
- **Offering Rename in a preview that will be refused:** the applier checks the same states moments later; a button
  that can only fail would teach users to ignore the warning.
- **Filtering workspace symbols again on the client:** the server already filtered by the query; re-filtering would
  hide its fuzzy matches. Only the order changes.
- **Asking `workspace/symbol` with an empty query:** servers answer every symbol of the project (or nothing); the
  popup asks for a name instead.

## Consequences
- Renames that touch closed files write them on disk (ADR 0148), after the preview named them; Shortcut+Z in the editor
  undoes only its own file's part.
- The search popup takes the focus while the caret popups do not: two popup kinds, chosen by whether the user types
  into them. The editor's text area stays its scene's focus owner while either shows.
- Go to Symbol works from any focus in a window with a running Python server, also from the explorer; Shortcut+F12
  and Shift+F6 only in bound editors (in a focused terminal on Linux they go to the shell).
- Listed in [../index.md](../index.md).
