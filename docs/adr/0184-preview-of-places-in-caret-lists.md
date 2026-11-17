# 0184. Preview of the places in caret lists: a shared file preview in `file-viewers`

- Status: accepted; amends [0177](0177-find-in-files.md) (its preview is the shared one) and
  [0146](0146-navigation.md) (the caret lists of Find Usages and Go to Definition get a preview) and
  [0145](0145-code-intelligence-module.md) (`CaretPopup` footer and `onSelected`); see
  [0084](0084-testability-logic-out-of-controls.md), [0179](0179-java-via-jdtls.md)
- Date: 2026-11-17

## Context
Find Usages and Go to Definition with several targets list their places in a caret list (`TargetChooser` →
`TargetPopup` → `CaretPopup`, [0146](0146-navigation.md)) as `file.py:LINE`, the path and the trimmed line text. The
user wants to see the code around the selected place while moving through the list with Up/Down. Find in Files already
shows such a preview under its result list ([0177](0177-find-in-files.md)): a read-only, highlighted editor of the
selected match's file, scrolled to the line with the matches marked. Its code sat in `find-in-files`, which
`code-intelligence` must not depend on (unrelated feature modules, [architecture](../architecture.md)).

## Decision
**Shared file preview** in `file-viewers` (package `dev.sluide.fileviewers`, `FilePreview.kt`; both modules already
depend on it), the generic part of Find in Files' `FindPreview`, moved as it was:
- `PreviewTexts` loads a file's text for the preview and answers on the FX thread (`null` for no text). Find in Files'
  `FilePreviewTexts` implements it (the unsaved buffer first, else the file read on its preview thread).
- `PreviewPlace` is where the preview marks and scrolls in the loaded text: `StyledRange` marks overlaid and a range
  scrolled into view, as offsets into the loaded text.
- `FilePreview(highlighter, texts, editorFont, onPress)` holds the editor in a `StackPane` at a fixed place in the
  owner's layout (the owner sets its height). `show(file, place)` loads the file and asks `place` against the loaded
  text, again for every show of the already shown file; `null` gives no marks and no scroll. Each file gets a fresh
  read-only editor (`CodeEditors.text`, nothing in it focus traversable, highlighted up to
  `CodeEditors.HIGHLIGHT_LIMIT`), reused for the other places in that file; a fresh editor is laid out before its
  first reveal. A generation counter drops an answer that arrives after another file was asked for; a show of the
  file being loaded does not load it again, but the latest place wins. `null` text empties the preview. `cancel()` drops loads on their way and keeps what
  shows, `clear()` also empties it. Every mouse press in the preview is consumed and goes to `onPress` (the focus stays
  where it is); the wheel still scrolls. The font follows `editorFont` (CSS).
- `revealAround(text, line, context = 5)` is the range of a 0-based line with up to `context` lines above and below,
  so the place lands roughly in the middle of a preview of about twelve lines.
- **Find in Files** uses `FilePreview` directly in its popup: the place of the selected row is the query's matches on
  that row's line (`previewTarget`, now on `revealAround`, styled with `HIT_CLASS`). Its behavior is unchanged.

### Caret lists (code intelligence)
The user's decisions:
- The preview sits below the list, in the same popup; the list stays 600 px wide and the popup flips above the caret
  as a whole when there is no room below.
- Both lists of the shared `TargetChooser` get it: Find Usages and Go to Definition with several targets (library
  documents are cache files on disk by then, [0179](0179-java-via-jdtls.md)).
- Fixed height as Find in Files: 232 px, about twelve lines, the target line revealed with up to five lines around it.
- Always shown while the list has items; hidden while it shows only a placeholder or note. A single usage still jumps
  directly. No setting, no key.
- Clicks in the preview do nothing (consumed, no caret, no focus, no selection change); the wheel scrolls it. The focus
  stays in the editor.

How it is built (`code-intelligence`):
- `CaretPopup` takes an optional `footer` node: the root becomes a `VBox(HBox(list, detail), footer)` (without one the
  root is the `HBox` as before, so completion and the other popups are unchanged). The footer is visible and managed
  only while the model has items (`sync()`); the window's `noFocusTraversal` over its content covers it. `setItems`
  makes it managed and `relayout` sizes the window to the scene (which lays the content out) and re-places it, flipping
  above the caret through `popupPosition`, all before `onSelected` is told, so a preview told to show a place reveals
  against its real size, not 0 px (`CaretPopupUiTest` checks the footer's height at that moment).
- `CaretPopup.onSelected(item)` is told, while the popup shows, after the selected item changed by a key, a click or
  `setItems`, and once when it shows, also after it hid (not before: nothing is told while it does not show).
  `setItems(items, keepIndex)` keeps the selected position instead of the selected item.
- **Selection fix:** `TargetChooser.choose` shows the targets and `update` replaces them with copies carrying the line
  texts; `NavigationTarget` is a data class, so the by-equality selection of `PopupListModel` was lost and went back to
  the first item when the texts arrived after the user moved down. `TargetView.update` now keeps the index (the same
  places in the same order), and does not tell `onSelected`; this also makes ADR 0146's "the selection stays" true.
- `TargetView` gets `onSelected` and `preview(position)`. `TargetPopup` owns the `FilePreview` as the footer (232 px
  high, `onPress` does nothing), wires `CaretPopup.onSelected`, and hiding clears it (a waiting list or a note tells
  no item, which empties it, so a file changed meanwhile is read again).
  `TargetChooser` decides: on `onSelected` it previews the position unless it is the one last previewed (so an update or
  a reselect never reloads the preview or scrolls it back after the user scrolled it with the wheel); `choose` forgets
  it, as items come only from there (a note, a waiting list and hiding show or tell no item, so they need no reset).
- `targetPlace(text, position)` (`navigation/TargetPreview.kt`) is the pure place: `revealAround` of the line, and a
  mark `ci-preview-target` on the word at the column (`wordIn`, the caret also taking a word ending at it); no word, no
  mark; a line past the end, `null`. Columns are UTF-16 offsets, as the editor's.
- `TargetPreviewTexts(bound, executor, dispatch, maxBytes)` gives the unsaved text of a bound editor of the file (path
  absolute and normalised, read on the FX thread), else the file read on the `code-intelligence-lines` thread (shared
  with `DiskLines`): UTF-8 with malformed bytes replaced, `null` for a missing, unreadable, non-regular or over-10-MiB
  file; every answer goes through `dispatch`.
- `NavigationFeature`, `builtInFeatures` and the public `CodeIntelligence` constructor take the highlighter and the
  editor font (CSS, as a value: `code-intelligence` does not depend on `settings`); `CodeIntelligenceWiring` passes
  `fileViewers::highlighterFor` and the editor font of `FontSettings`. `.ci-preview-target` (a background like the
  occurrences', dark and light) is in `code-intelligence-popups.css`.

## Consequences
- One preview for every list of places in files: its races (stale answers, the same file asked twice) and its look
  are solved and tested once, in `file-viewers` (`FilePreviewUiTest`, `RevealAroundTest`).
- `file-viewers` gains a small public API that is not a viewer; it holds no Spring bean, so the previews' viewer scan
  is not affected.
- Nothing in the preview's editor is focus traversable (the whole node tree, not only the scroll pane that
  `FindPreview` set): an editor swapped into an already showing popup that does not take the focus (the caret lists'
  `EditorPopupWindow`, which strips traversal only on show and relayout) would otherwise become its scene's focus owner
  on the next pulse and get the editor window's keys.
- The caret lists' preview costs one more file read per selected file (off the FX thread, at most 10 MiB, once per
  shown list for each file); the unsaved text of editors of other windows is not seen (disk text is shown, as in the
  line texts).
- A fresh editor is laid out before its first reveal: unlaid, its area is 0 px high and `revealRange` scrolls the
  start of a range near the top of the file away.
- Listed in [../index.md](../index.md).
