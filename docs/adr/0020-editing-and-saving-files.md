# 0020. Editing and saving files

- Status: accepted
- Date: 2026-10-14

## Context
Every view was read-only ([ADR 0004](0004-file-viewer-selection.md), [ADR 0005](0005-syntax-highlighting.md)). Files
are to be edited and saved: code, plain text and the sources of previews. Work must not get lost: not to a change on
disk (a `git checkout`, a formatter, another editor), not when a tab or the window closes. Saving must keep what the
file was (line endings, permissions, symbolic links). RichTextFX turns `\r\n` and `\r` into `\n` when text is put
into an area, and its default undo manager also records style changes, i.e. highlighting.

## Decision
- **One editor:** the RichTextFX `CodeArea` of the code views becomes editable (`CodeView`). It shows code, the
  sources of previews and plain text: `DefaultTextFileViewer` uses a `CodeView` without highlighter instead of a
  `TextArea`, so all text gets line numbers and the same behaviour.
- **Plain-text undo:** `UndoUtils.plainTextUndoManager`, which records only text changes; with the default manager,
  every highlighting pass would be an undo step.
- **`EditableFileView : FileView`** adds `dirty` (observable) and `save()`. Read-only views (images, diffs) stay
  `FileView`s. The editor area only knows these interfaces.
- **Dirty from the undo mark:** the undo position is marked when the file is read, saved or reloaded;
  `dirty = !atMarkedPosition`. Undoing back to the saved text makes the view clean again. After a mark,
  `preventMerge()` makes the next typing an undo step of its own, so the mark stays reachable.
- **Explicit save, in place:** File → Save (Shortcut+S) and Save All (Shortcut+Alt+S) write the text with
  `Files.writeString` (UTF-8, create, truncate, write) to the file's path. Writing in place keeps permissions, owner
  and links: a symbolic link stays a link and its target is written. The file is created again if it was deleted, but
  not its folder. Nothing is changed besides the text: no trimmed whitespace, no added final newline.
- **UTF-8 only, decoded strictly:** a file is decoded with a decoder that reports malformed input, when it is read
  and on every reload. Whether a file is text is decided from its first 8 KiB only (ADR 0004), and a lenient decoder
  would turn invalid bytes later in the file into U+FFFD, which a save would write back. A file that is not valid
  UTF-8 is therefore shown read-only, with its invalid bytes as U+FFFD and a banner saying so; it is never saved. It
  becomes editable again when it changes into valid UTF-8 on disk.
- **Line endings preserved:** the file's ending is detected when it is read (`LineEnding.of`, the ending of most lines;
  LF on ties and without line breaks, so a file with mixed endings is written with its majority ending). The area holds
  `\n`; saving restores the ending. Texts are compared normalised, so a change of line endings alone is no change.
- **Saved text as the baseline for changes on disk:** the view keeps the text last read or saved.
  `DiskChange.decide(disk, saved, buffer, dirty)` says what a change on disk means:
  - IGNORE: the file holds the saved text, e.g. the file watcher reporting our own save, also after more typing.
  - RELOAD: the view has no unsaved changes; only the smallest differing range is replaced (`TextReplacement`, common
    prefix and suffix), so caret, scroll position and undo history stay, and the reload can be undone. Also when the
    view holds exactly the file's new text: it becomes clean, without asking.
  - CONFLICT: the view has unsaved changes; a banner offers **Reload** and **Keep mine**. Keep mine takes the file's
    text as the saved text, so the next save writes over it and the same content does not ask again. The view stays
    dirty until it is saved (a flag besides the undo mark), even when undone back to the text saved last. Saving while
    the banner shows writes over the file too.

  A file deleted (or no longer viewable) while its view has unsaved changes keeps its tab, with a banner; saving
  recreates it. Without unsaved changes, the tab closes as before.
- **Closing asks:** closing tabs with unsaved changes from the UI asks once, listing all of them: Save, Don't Save or
  Cancel (`askToSave`), before the confirmation for running tabs. Closing the window asks the same for all its files.
  Quitting on macOS (Cmd+Q) asks every window to close (JavaFX `requestClosingAllWindows`), so it asks too. A file that
  cannot be saved shows why and stops the close.
- **Accelerators and terminals:** JavaFX handles accelerators when a key press bubbles up unconsumed. The terminal
  consumes Ctrl+S (it is XOFF for the shell), so Save does not take it there; Cmd+S on macOS and Ctrl+Alt+S send
  nothing to the shell and reach the menu.
- **Previews render on save:** a preview shows the saved text, rendered again when the file is saved or changes on
  disk, not while typing. HTML pages are served from disk ([ADR 0018](0018-html-preview-server.md)) and could not show
  unsaved text anyway; all formats behave the same.
- **Highlighting in the background after a pause:** opening and reloading highlight on the JavaFX thread as before.
  While typing, the text is highlighted again once typing pauses for 150 ms (600 ms for texts above about 1 MiB when the file is opened), on
  one daemon thread shared by all views (`sluide-highlight`). A revision counter skips requests that a newer one
  superseded before the thread got to them, and drops results for an older text; only
  the range between the first and the last changed style is restyled. `RSyntaxHighlighter` creates a fresh lexer per
  call and is otherwise immutable, so it is safe on that thread; `SyntaxHighlighter`s must be thread-safe.
- **Interim, until diffs are shown on demand:** modified files still open as the read-only diff
  ([ADR 0015](0015-git-diff.md)), but an open editor is never swapped for its diff: it may have unsaved changes, and
  saving a tracked file would otherwise take the editor away. A diff still turns back into the editor when the file is
  clean again.

Rejected:
- **Plain `TextArea` for plain text:** no line numbers, different keys and behaviour than code.
- **The default (rich-text) undo manager:** highlighting would be undone step by step.
- **Dirty by comparing the text with the saved text:** a full comparison on every change, where the undo mark is free.
- **Atomic save through a temporary file and a move:** survives a crash during the write, but replaces symbolic links
  by files and loses permissions, owner and other attributes unless they are copied by hand.
- **Autosave:** surprising next to the git diff, which follows every save; explicit saves match the close prompts.
- **Replacing the whole text on a change on disk:** loses caret, scroll position and undo history.
- **Highlighting on every change on the JavaFX thread:** typing lags in large files.
- **Rendering previews while typing:** reloads the `WebView` on every key, and HTML pages come from disk.

## Consequences
- New module `modules/text-editing` (`dev.sluide.textediting`) holds the logic without JavaFX: `LineEnding`,
  `TextReplacement`, `DiskChange`. `file-viewers` depends on it (`implementation`).
- Partly supersedes ADR 0004 (views are read-only) and ADR 0005 (the code area is read-only; highlighting only on the
  JavaFX thread).
- Only UTF-8 files can be edited; files that are valid UTF-8 only in their first 8 KiB open read-only.
- Saving is not atomic: a crash or a full disk during the write can leave the file truncated.
- The pause before highlighting is chosen when the file is opened; a file growing past 1 MiB keeps the short one.
- Until diffs are shown on demand, a file that becomes modified while open shows no diff until it is opened again.
