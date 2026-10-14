# Text Editing

**Modules:** `modules/text-editing` (logic without JavaFX), `modules/file-viewers` (`CodeView`), `modules/editor`
(saving and prompts), wired in `MainWindow`; decision in [ADR 0020](../adr/0020-editing-and-saving-files.md)

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
    texts, mapping of positions), `DiskChange` (what a change on disk means for an edited file).
  - `file-viewers`: `EditableFileView` (`dirty`, `save()`, `showChanges(diff)`), `CodeView` (editable RichTextFX
    area with plain-text undo, banners styled by `code-view.css`, highlighting in the background with
    `changedSpanRange` from `StyleRanges.kt`, line numbers and change markers in its `Gutter`).
  - `editor`: `EditorArea.save()`, `saveAll()`, `confirmCloseAll()`, `confirmSave` and `showError` (replaceable in
    tests), `OpenFile.dirty` for the title; `askToSave` / `SaveChoice` in `CloseConfirmation.kt`.
- Not yet implemented: other encodings than UTF-8, Save As, autosave, find and replace, indentation keys.
