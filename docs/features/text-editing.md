# Text Editing

**Modules:** `modules/text-editing` (logic without JavaFX), `modules/file-viewers` (`CodeView`), `modules/editor`
(saving and prompts), wired in `MainWindow`; decisions in [ADR 0020](../adr/0020-editing-and-saving-files.md) (editing
and saving), [ADR 0022](../adr/0022-editor-key-bindings.md) (keys) and
[ADR 0023](../adr/0023-indentation-from-editorconfig.md) (indentation)

- Code, plain text and the sources of previews (Markdown, HTML, SVG, CSV, notebooks) are edited in their tab. Images
  and diffs stay read-only.
- A tab with unsaved changes shows a dot before its name (`● main.py`). Undoing back to the saved text removes it.
- **File → Save** (Ctrl+S, Cmd+S on macOS) saves the selected file, **File → Save All** (Ctrl+Alt+S, Cmd+Option+S)
  every file with unsaved changes. In a terminal, Ctrl+S goes to the shell; Save All and Cmd+S work there too.
- Saving writes the file in place: permissions stay, a symbolic link stays a link and its target is written. The
  file's line endings are kept (LF, CRLF or CR; a file with mixed endings is written with the ending of most of its
  lines). Nothing else is changed (no trimmed whitespace, no added final newline). If saving fails, an error says why
  and the tab keeps its unsaved changes.
- Undo (Ctrl+Z) and redo cover the text only; typing without a pause of half a second is one step.
- Highlighting follows typing once it pauses (150 ms, 600 ms for very large files), computed in the background.
- Editing keys act while the text has the focus; Shortcut is Ctrl, Cmd on macOS. Each command is one undo step (Enter
  and a Tab within a line merge with the typing around them, like typed characters). Keys that change the text do nothing in a
  read-only view.

  | Key | What it does |
  |-----|--------------|
  | Enter, Shift+Enter | Replaces the selection by a line break and the indentation of the line up to the caret. After an opening `{`, `[` or `(` (spaces before the caret ignored) the new line gets one more level; if the closing bracket follows the caret, it moves to a line of its own with the old indentation, the caret on the line between. |
  | Tab | Within one line: replaces the selection by a tab or by spaces up to the next indentation stop. Over several lines: indents every non-empty line it touches by one level and keeps them selected. |
  | Shift+Tab | Removes one level (a tab, or up to one level of spaces) from every line the selection or caret touches. |
  | Shortcut+D | Duplicates the selection after it and selects the copy; without a selection, copies the line below and moves the caret along. |
  | Shortcut+Shift+K | Deletes the lines the selection or caret touches; the caret keeps its column on the line that takes their place. |
  | Alt+Shift+Up / Down | Moves those lines above the previous / below the next line, with the selection. |
  | Shortcut+/, Shortcut+Shift+7 | Comments the touched non-blank lines out, or back in if all of them are commented (see below). Shortcut+/ is matched by the character, so it also works where `/` is Shift+7; Shortcut+Shift+7 works on every layout. |
  | Shortcut+F | Opens the find bar (below). |
  | Shortcut+R | Opens the find bar with the row for replacing. |
  | F3 / Shift+F3 | Selects the next / previous match; opens the find bar if needed, with the focus staying in the text. On macOS keyboards F3 needs Fn. |
  | Shortcut+G | Asks for `line` or `line:column` (1-based; shows the caret's position) and moves the caret there, scrolling it into view. Numbers beyond the text go to its last line or the line's end; other input does nothing. |

  A selection that ends at the start of a line does not touch that line.
- Indentation: tabs or spaces and the size come from `.editorconfig` (`indent_style`, `indent_size`, `tab_width`),
  each value not set there from the file's own indentation, else 4 spaces. They are read when the file is opened,
  and again when it is renamed in the explorer.
  Saving does not apply other `.editorconfig` properties (no trimmed whitespace, no added final newline).
- Comments per language: `#` in Python and YAML; `//` in Kotlin, Java, JavaScript, TypeScript, JSX, TSX, SCSS, LESS and
  Sass; `/* */` in CSS; `<!-- -->` in XML, HTML, Markdown, MDX, Vue and Svelte files and SVG; none in JSON, CSV,
  notebooks and plain text. Line comments go after the indentation the lines share, followed by a space, and
  uncommenting removes that space too. Languages with only block comments wrap each line's content instead
  (`/* a: b; */`, `<!-- text -->`) after the line's own indentation; a line counts as commented if it is one block
  comment.
- The find bar (top of the view, also in read-only views): the query, toggles for **Aa** (match case), **W** (whole
  words) and **.*** (regular expression), a counter ("3 of 12", "12 results", "No results", "Search took too long",
  or why a regular expression is invalid), previous / next and close. Shortcut+F takes a selection within one line as the query and
  focuses the query; typing it selects the first match at or after the selection. Enter and F3 go to the next match,
  Shift+Enter and Shift+F3 to the previous one, wrapping around; the match is selected and scrolled to. All matches
  are marked, the selected one stronger, over the syntax colours; the marks follow edits once typing pauses. Escape
  (in the bar or the text) closes it and the focus goes back to the text.
  - Limits: a search gives up after 200 ms ("Search took too long", e.g. for `(a+)+\1b`) and is then not repeated
    while the text is typed, only when the query or an option changes. At most 10,000 matches are marked and counted
    ("1 of 10000+"); next and previous wrap around within them. In texts above 1 MiB the query is searched once
    typing it pauses (300 ms), or right away on Enter or F3.
  - Whole words: a match that starts with a letter, digit or `_` must not follow one, and one that ends with such a
    character must not be followed by one. Regular expressions are Java's, with `^` and `$` at line breaks; empty
    matches are skipped when finding.
  - Replacing (Shortcut+R; disabled in read-only views): **Replace** (or Enter in the replacement) replaces the selected
    match and selects the next; without a selected match it only selects the next. **Replace All** replaces every match
    (also beyond the marked ones), each on its own so that the text between them keeps its colours, in one undo
    step, and the counter says how many; with regular expressions it also replaces empty matches, so `^`
    prefixes every line. With regular expressions, `$1` or `${name}` insert groups and `\n`, `\t`, `\\` and `\$` a
    line break, a tab, a backslash and a dollar sign; otherwise the replacement is taken literally. An invalid
    replacement (e.g. `$2` without a second group) is shown in the counter and changes nothing.
  - Shortcut+F and Shortcut+R in the bar switch the replacing row off and on and keep the query.
- When the file changes on disk:
  - without unsaved changes, the tab shows the new text, changing only what differs: caret, scroll position and undo
    history stay, and the reload can be undone;
  - with unsaved changes, they stay and a banner says "*file* changed on disk." with **Reload** (show the file's
    text) and **Keep mine** (keep editing; the tab stays dirty, also when undone back to the last save, and the next
    save writes over the file). Saving while the banner shows writes over the file as well;
  - the file's content after our own save is no change, also after typing on; a file changed to exactly the text
    the tab holds makes the tab clean, without a banner.
- When the file is deleted (or can no longer be shown) while its tab has unsaved changes, the tab stays with the banner
  "*file* was deleted on disk; saving recreates it." Saving creates the file again, not its folder: if the folder is
  gone too, saving shows an error. Without unsaved changes, the tab closes.
- Closing tabs with unsaved changes (×, middle click, any context menu entry) asks once for all of them: **Save**,
  **Don't Save** or **Cancel**. The files are listed by their path in the project (in full outside of it), so two
  `build.gradle.kts` tabs can be told apart. Closing the window asks the same for all its files, and so does quitting on macOS.
  This comes before the question about tabs that still run something ([editor-tabs.md](editor-tabs.md)).
- Previews show the saved text: they render again when the file is saved or changes on disk, or when Keep mine
  takes the file's new text as the saved one, not while typing ([previews.md](previews.md)).
- Only UTF-8 files are edited. A file that turns out not to be valid UTF-8 (only its first 8 KiB decide whether it
  opens) is shown read-only with the banner "*file* is not valid UTF-8; it is shown read-only."; invalid bytes show
  as replacement characters, and it is never saved. It becomes editable once it changes into valid UTF-8 on disk.
- Files always open in the editor, also when their git status is modified: the changed lines are marked in the
  gutter, following unsaved edits, and the read-only diff is shown on demand over the editor, which keeps its unsaved
  changes, caret and undo history meanwhile ([git-diff.md](git-diff.md)).
- Structure:
  - `text-editing`: `LineEnding` (detect, normalise, restore), `TextReplacement` (smallest replacement between two
    texts, mapping of positions), `DiskChange` (what a change on disk means for an edited file); the editing
    commands as `TextEdit`s of a text and `Selection`: `Indentation` (Enter, Tab, Shift+Tab, detection),
    `IndentStyle.forFile` with `EditorConfigIndent` (ec4j), `LineOperations`, `LineComments` with `CommentSyntax`,
    `TextSearch` (`SearchOptions`, find, replace, replace all), `parseGoTo` / `TextPosition`.
  - `file-viewers`: `EditableFileView` (`dirty`, `save()`, `showChanges(diff)`), `CodeView` (editable RichTextFX
    area with plain-text undo, banners styled by `code-view.css`, highlighting in the background with
    `changedSpanRange` from `StyleRanges.kt`, line numbers and change markers in its `Gutter`); `EditorKeys.kt` (the
    editing keys as a wellbehavedfx `InputMap`, `applyEdit`, `applyEdits`), `FindBar` (matches overlaid on the token
    styles), `GoToLine` (dialog replaceable in tests); the viewers pass their `CommentSyntax` to the view.
  - `editor`: `EditorArea.save()`, `saveAll()`, `confirmCloseAll()`, `confirmSave` and `showError` (replaceable in
    tests), `OpenFile.dirty` for the title; `askToSave` / `SaveChoice` in `CloseConfirmation.kt`.
- Not yet implemented: other encodings than UTF-8, Save As, autosave, an Edit menu or other ways to discover and
  rebind the keys, find in several files, smart indentation beyond brackets (e.g. after `:` in Python).
