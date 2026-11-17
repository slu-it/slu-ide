# Find in Files

**Module:** `modules/find-in-files` (package `dev.sluide.findinfiles`: `FindInFiles` and `MatchOpener` are the API; the
search in `search`, the popup in `ui`); decisions in [ADR 0177](../adr/0177-find-in-files.md), what is left out in
[../ideas/find-in-files-later.md](../ideas/find-in-files-later.md)

Searches the text of every file the explorer tree shows, in the window's folders, and opens a match at its line. Open
editors count with their unsaved text. The window's `ide` wiring passes in the folders, the tree's listing, the unsaved
texts and the jump (Back returns to where the search started when it was opened from an editor with code intelligence).

## What is searched
- The files the tree would show: Hide Generated Files applies, `.git` is skipped, symlinked folders are not followed.
  Binary and unreadable files are skipped silently; files over 2 MiB are skipped and counted.
- Matching is the find bar's: **Aa** (match case), **W** (whole words) and **.\*** (regular expression) work as there.
  An invalid regex shows its message as the status and searches nothing.
- **File mask**: comma-separated globs, `!glob` excludes (`*.kt, !*Test.kt`); blank is all files.
- At least 2 characters (blanks count) are searched, 250 ms after the last keystroke; results stream in.

## The popup
- Shortcut+Shift+F opens it over the window: query field, the toggles, the file mask, the status line, the results
  grouped by file, and a preview of the selected match. Up/Down/PageUp/PageDown move over the matches, Enter or a
  double-click opens one, Escape hides it. The popup also hides on a click outside it.
- Opening a match closes the popup, opens the file at the line and puts the caret on the first match of that line; the
  jump is recorded for Back when the popup was opened from an editor with code intelligence (as Go to Symbol); from
  a plain or Markdown editor, the explorer, Find in Folder or a terminal nothing is recorded.
- The key is added to the Navigate menu (Find in Files…, disabled without a folder, with no reason in the label, as
  Go to Symbol… is) by the window wiring.

## Entry points
- **Navigate → Find in Files…** (Shortcut+Shift+F): the whole window; with the explorer tree focused and one folder
  selected, that folder.
- **Explorer → Find in Folder…** on a folder or a root: that folder. The popup shows a scope chip "In: <folder>": the
  folder's name, with its path relative to its root as the tooltip (`FindView.scopeLabel` is that relative path); its ×
  searches the whole window again.
- **Editor → Find Selection in Files** (context menu, with a selection): opens with the selection as the query. It is
  disabled as "Find Selection in Files (more than one line)" for a multi-line selection and as "… (no folder open)"
  without a folder, and "… (longer than 200 characters)" for a long one. With **.\*** on, the selection is escaped
  on this path too.
- Opening with the key while a code editor with a non-empty, single-line selection (up to 200 characters) has the
  focus: the selection is the query. With **.\*** on it is escaped, so it matches as typed.

## Remembered
Query, toggles and mask live until the window closes; every opening shows them again (and searches again, since the
results are a snapshot). The scope is never remembered: each opening sets it from its entry point.

## Limits and status
- 1000 matches in total, 100 per file, 200 000 visited entries.
- Status texts: "Searching…", "12 matches in 3 files…" while running, "No matches", "1 match in 1 file", "N matches in
  M files", "First 1000 matches in M files, type more to narrow", "N matches in the first 200 000 files, type more to
  narrow", each with " · K files skipped (over 2 MiB or too slow)" when files were skipped; "Search failed" if the walk
  threw (logged).

## Code
With several roots searched the file headers are prefixed with the root's folder name (`FindView.severalRoots`). The
public constructor takes `FileViewers` and `FontSettings` (the popup's editor font, `editorFont`).

`FindInFiles` keeps the remembered state and the open flow against a `FindView` (the popup, made on first use), builds
the `SearchRequest` (unsaved texts keyed by normalised paths, read on the FX thread when the query changes or a search runs at once) and maps
`SearchListener` calls to the view. The preview is the shared `FilePreview` of `file-viewers`
([ADR 0184](../adr/0184-preview-of-places-in-caret-lists.md)); the popup gives it the place of the selected row (the
query's matches on its line, `previewTarget`). `FilePreviewTexts` serves its texts (`PreviewTexts`): the unsaved text
first, else the file read on a `sluide-find-in-files-preview` thread. Tests: `FindInFilesTest` (flow, with a fake view), `QuerySelectionTest`,
`FilePreviewTextsTest`, and `FindInFilesUiTest` (focus lookup, anchor, the editor menu item).
