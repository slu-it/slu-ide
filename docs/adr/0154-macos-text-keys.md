# 0154. macOS text keys for RichTextFX areas

- Status: accepted
- Date: 2026-11-10

Amends [0022](0022-editor-key-bindings.md).

## Context
RichTextFX 0.11.7 binds word skipping and word deletion to Shortcut, which is Cmd on macOS, and has no macOS variant:
Cmd+Left skips a word where a Cocoa text view goes to the line start, and Option+arrows do nothing. Plain JavaFX
`TextField` and `TextArea` already follow the platform, so only the RichTextFX areas feel wrong: the code editor, the
editor of dialogs (`CodeEditors.text`), the side-by-side diff and the read-only code display.

## Decision
`macTextKeys` (`MacTextKeys.kt`) is an `InputMap` overlaid on RichTextFX's with `Nodes.addInputMap`, which takes
precedence; `installMacTextKeys` adds it at every area's creation, only where `os.name` starts with "Mac". Other
platforms are unchanged. Keys: Cmd+Left/Right line start/end, Option+Left/Right to the start / end of the word
(`macWordLeft`, `macWordRight`; letters, digits and `_` are word characters), Cmd+Up/Down text start/end,
Option+Up/Down paragraph start/end (all but Option+Up/Down with Shift to extend the selection),
Option+Backspace/Delete a word, Cmd+Backspace/Delete to the line start/end (one character when already there, like
NSTextView). Deleting acts only while the area is editable, each as one undo step through `applyEdit`.

The patterns use explicit `META_DOWN` and `ALT_DOWN`, not `SHORTCUT_DOWN`, so tests on Linux can fire them, and match
no other modifier: Cmd+Option+Left/Right stay back/forward navigation of code intelligence, and Option+Shift+Up/Down
stay "move lines" ([editorKeys](../../modules/file-viewers/src/main/kotlin/dev/sluide/fileviewers/code/EditorKeys.kt)),
so Option+Up/Down have no Shift variant. Git log's `LinkText` and plain JavaFX controls are out of scope.

## Consequences
- Code areas on macOS behave like a Cocoa text view; the decisions are plain functions (`paragraphTarget`,
  `macDeleteEdit`) with unit tests.
- Only tests that fire Meta and Alt on Linux verify this; behaviour on a real Mac keyboard is not checked by CI.
- Cmd+Left/Right no longer skip words on macOS; Option does.
- On a wrapped line Cmd+Left/Right and Cmd+Shift+Left/Right behave like Home / End: `lineEnd` of RichTextFX stops one
  character before the wrap point, at the trailing space, on a visual line that is not the last of its paragraph. So
  Cmd+Delete there may need a second press to remove that space (known limit, no special code).
