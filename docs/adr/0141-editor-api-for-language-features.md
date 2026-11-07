# 0141. Editor API for language features

- Status: accepted; amends [0108](0108-code-editor-extensions-and-context-menu.md), [0077](0077-graph-nodes-open-their-source.md); see [0128](0128-read-aloud-in-editors.md), [0022](0022-editor-key-bindings.md), [0020](0020-editing-and-saving-files.md), [0054](0054-keep-source-files-small.md), [0084](0084-testability-logic-out-of-controls.md)
- Date: 2026-11-07

## Context
Python code intelligence comes through a language server (ty), on a foundation meant for more languages. Its client
lives in a module of its own and reaches the editors as a `CodeEditorExtension` ([ADR 0108](0108-code-editor-extensions-and-context-menu.md)).
What the extension point offered so far (lines, gutter columns, menu items, and since
[ADR 0128](0128-read-aloud-in-editors.md) the text, caret, toolbar items, banners, locks, overlay layers) is not enough
for a language-server client:
- **Document sync** needs every change as a delta, in order and from every source, not just "something changed".
- **Completion, hover, signature help** need the caret as it moves, the character under the mouse and screen bounds
  for popups, and keys (Enter, Tab, Escape, Up, Down) taken only while a popup shows, before the editor's own.
- **Quick fixes, completion edits, rename** apply several edits at once, as one undo step.
- **Diagnostics and semantic highlights** arrive for an older text; cleared on every edit, as read aloud's layers are,
  they would flicker on each keystroke.
- **Go to definition** opens another file at a line and column; `FileViewHost.openFile(file, line)`
  ([ADR 0077](0077-graph-nodes-open-their-source.md)) only knows lines. LSP positions are 0-based lines and UTF-16
  columns, as the editor's offsets are.
- **Saves and the dirty state** drive `didSave` and when to pull diagnostics.

## Decision
`ExtensibleCodeEditor` (module `file-viewers`, root package) gets these members, all on the FX thread and each with an
interface default, so fakes compile. RichTextFX and wellbehavedfx types stay out of the API.
- **`host`**: the `FileViewHost` the editor was extended with, set before the extensions are called (a host that opens
  nothing before that).
- **`onTextChange((TextChange) -> Unit): AutoCloseable`**: `TextChange(start, removed, inserted)`, UTF-16 offsets, once
  per change, in order, synchronously, from every source: typing, the editing keys, undo and redo, a reload or
  `replaceText` (only the differing range), find and replace, `applyEdits` (one per edit that changes something;
  changes that change nothing are left out). Each change's offsets are in the text the change before left, so applying
  them in order to a copy rebuilds the text. Changes made together (a multi-change: Replace All, `applyEdits`, undoing
  them) arrive after all of them are applied: inside the callback `text`, `caretOffset`, `positionOf` and `offsetOf`
  already reflect the whole batch, so a language-server client computes its ranges on its own copy of the text,
  applying the deltas in order. Editing from inside a callback re-enters the telling, so it is done later
  (`Platform.runLater`). Built on RichTextFX's `multiPlainChanges`; a listener that throws is logged. The
  argument-less `onTextChanged` stays.
- **Caret and positions:** `caretOffsetProperty`; `moveCaret(offset)` (clamped, drops the selection, scrolls into view,
  keeps the focus where it is: a popup may own it); `offsetAt(MouseEvent)` (the character under the mouse, `null` over
  the gutter, past a line's end or below the text); `screenBounds(start, end = start)` (a range, or a caret at `start`;
  `null` while not shown or scrolled away); `positionOf(offset)` and `offsetOf(TextPosition)`, with
  `TextPosition.of(text, offset)` in `text-editing` as the inverse of `offsetIn`. `text-editing` becomes an `api`
  dependency of `file-viewers`, as `TextPosition` is part of its API.
- **`addKeyBinding(KeyCombination, action: () -> Boolean): AutoCloseable`**: an event filter on the text area, so it
  comes before the view's input maps ([ADR 0022](0022-editor-key-bindings.md)) and RichTextFX's, which are handlers;
  `action` returns whether it handled the press, which is consumed only then. Key presses reach the area only while it
  has the focus. A binding skips a press an earlier filter consumed. A handled press also consumes the key typed that
  follows it: RichTextFX types characters with Alt held outside Windows (Alt+J, Option chords on macOS). An action
  that throws is logged and counts as not handled. `CONTROL_DOWN` expresses a literal Ctrl (Ctrl+Space),
  `SHORTCUT_DOWN` Ctrl/Cmd.
- **`applyEdits(List<OffsetEdit>): Boolean`**: `OffsetEdit(start, end, text)` in the current text, any order, not
  overlapping (inserts at the same offset keep their order); applied from the last as one multi-change, one undo step
  of its own, as Replace All does (the same `CodeArea.applyEdits`, which keeps the caret in view). Line breaks in the
  texts become `\n`, as `replaceText` does. Caret and selection move with the text (an edit ending at or before them
  shifts them, one containing them puts them after its new text). Refused (`false`, nothing changed) while the view
  cannot replace its text (read-only for its encoding, locked); edits outside the text or overlapping throw. Named
  `OffsetEdit`, not `TextEdit`, apart from `text-editing`'s `TextEdit` (seen through the `api` dependency) and lsp4j's.
- **`dirty`** (the view's) and **`onSaved(listener)`**, after each successful `save()`.
- **`setTextCursor(Cursor?)`**: the area's cursor property is bound once an extension sets one, since the area's own
  stylesheet (`-fx-cursor: text`) would otherwise reset a value set in code; `null` is the text cursor.
- **Following overlay layers:** `overlay(layer, ranges, followEdits)`. With `followEdits`, a change keeps the ranges
  entirely before the changed range, moves those entirely after it by the change in length, and drops those that
  overlap or touch it: the owner sets them again with fresh results. Layers without the flag are cleared by any change,
  as before (read aloud relies on it). A change restyles only where dropped ranges went and the inserted text, no
  longer the whole text. The layers take each change before the extensions' listeners (`TextChanges`), so a listener
  setting a layer sets it on the changed text; background highlighting adds the moved ranges, so highlighting and
  overlays agree.
- **Open at a position:** `FileViewHost.openFile(file, line, column)` (default: `openFile(file, line)`) and
  `FileView.showPosition(line, column)` (default: `showLine(line)`), 0-based, UTF-16 columns. The editor area's host
  lets the opened view go there; code views put the caret there with Go to Line's movement (clamped, scrolled into
  view, focused); views that embed a code editor and forwarded `showLine` (previews, the `.gitignore` view, the
  Gradle, shell script, Dockerfile and Compose views) forward `showPosition`, their `showLine` being `showPosition(line,
  0)`.

`CodeView` was split first ([ADR 0054](0054-keep-source-files-small.md)): its styles moved to `CodeViewStyles`, the
generated-file banner to `GeneratedFileBanner`. The offset math (the layers after a batch of changes, sorting edits,
caret after edits) is in pure functions (`EditOffsets.kt`), unit-tested
([ADR 0084](0084-testability-logic-out-of-controls.md)).

Rejected:
- **An `InputMap` for extension bindings** (`Nodes.addInputMap`): it takes precedence over the maps added before it
  only, so a map an embedding view adds later would come first; a filter comes before every handler.
- **Shifting overlay ranges that touch an edit:** whether typing at a diagnostic's end extends it is the server's
  call; dropping the range and waiting for fresh results is never wrong.
- **Passing RichTextFX's change objects or a wellbehavedfx `InputMap`:** would tie the extension modules to the editor
  library, which ADR 0108 keeps out of the API.

## Consequences
- A language-server client syncs documents, places popups, takes keys, applies edits and keeps diagnostics in place
  without touching RichTextFX.
- Interface defaults keep fakes compiling but hide a missing override in the real view; the real view's members are
  tested in `ExtensibleCodeEditorApiUiTest`.
- Views that embed a code editor without forwarding `showLine` (HTTP, `.env`) get neither line nor column from a host.
- Listed in [../index.md](../index.md).
