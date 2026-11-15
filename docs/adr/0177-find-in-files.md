# 0177. Find in Files: a background content search in a popup, with the explorer's listing rule

- Status: accepted; see [0176](0176-explorer-search.md), [0146](0146-navigation.md), [0149](0149-rename-file-structure-go-to-symbol.md), [0108](0108-code-editor-extensions-and-context-menu.md)
- Date: 2026-11-15

## Context
Thoreau has a find bar per editor and a name search in the explorer ([0176](0176-explorer-search.md)), but no way to
search the text of every file of a window, as IntelliJ's Shortcut+Shift+F does. Such a search reads many files, so it
must run off the FX thread, stay bounded on large trees, and search what the user sees: the files the tree shows and
the unsaved text of open editors.

## Decision
- **Module `find-in-files`** (package `dev.sluide.findinfiles`, editor family: it shows files and works inside the
  editors). It depends on `file-viewers` (`CodeEditorExtension`, `CodeEditors`, `FileViewers`), `file-icons`,
  `text-editing` (`TextSearch`, the encodings) and `background-tasks` (`NamedThreads`), not on `file-explorer` or
  `code-intelligence`: those are unrelated feature modules, so `ide` wires them through callbacks (the listing, the
  roots, the focused folder, the jump with Back/Forward history), as the module rules ask.
- **Files searched: exactly what the tree would show.** The walk lists folders with a function `ide` passes in (the
  explorer's `shownEntries`: Hide Generated Files, revealed folders, sorting), depth-first in tree order, roots (project
  and scratches) in tree order. `.git` entries are skipped, symlinked folders are not followed (a symlinked file is
  searched), only regular files are read; dotfiles are searched. A folder that cannot be listed is skipped.
- **Reading like the editor**: a dirty open editor's buffer is searched instead of the file (a snapshot taken on the FX
  thread when the search starts, keyed by the `toAbsolutePath().normalize()`d path); otherwise the file is read with
  `readNBytes(2 MiB + 1)`, `isTextSample` on its first 8 KiB, then `decodeText` (UTF-8, else Windows-1252). Binary or
  undecodable files are skipped silently, unreadable ones too. Line breaks are normalised to `\n`, so lines and
  columns (0-based, UTF-16 units) are the editor's everywhere, from the result row to the jump.
- **Matching is the find bar's**: `TextSearch.find` with `SearchOptions` (match case, whole words, regex) over the
  whole text. A regex match spanning lines shows at its start line, highlighted to that line's end. An invalid regex
  is reported once (`TextSearch`'s message as the status) and nothing is searched. A file whose search runs out of
  `TextSearch`'s time budget counts as skipped.
- **File mask**: comma-separated globs, `!glob` excludes, blank = all; a glob without `/` matches the file name, with
  `/` the path relative to its root, so a leading `/` anchors it at the root (`!/README.md`). `*` and `?` stay in one
  path part, `**` crosses parts, every other character is literal (an odd mask never fails). Case-insensitive. Only
  excludes: everything else. The globs are matched by an own matcher, not a regex: dynamic programming over glob and
  name, which cannot backtrack (`*a*a*a*b` on a long name would take seconds per file in a regex).
- **Limits** (`SearchLimits`): 1000 matches in total (the walk stops), 100 per file (the file shows "100+"), files over
  2 MiB (or buffers over 2 Mi characters) skipped and counted, 200 000 visited entries (the walk stops). The status
  line says which limit ended the walk ("First 1000 matches in M files, type more to narrow", "N matches in the first
  200 000 files, type more to narrow") and adds " · K files skipped (over 2 MiB or too slow)": one count for files
  too large and files whose search ran out of time.
- **Results per line**: a match row per line with matches: the line with leading whitespace trimmed, cut to about 200
  characters around its first match (40 before it) with "…" at a cut end, every match on it highlighted.
- **Controller** (`FindInFilesSearch`, without JavaFX, modelled on `ExplorerSearch`): at least 2 characters (blanks
  count and are searched as typed, the query is never trimmed), debounced 250 ms (a constructor parameter); new input
  cancels the wait and the running walk, whose results never reach the view. Runs on one `NamedThreads` daemon
  thread, sequentially. Results stream in: files with matches are collected on the walk thread and posted to the FX
  thread as one batch at most every 100 ms (the first at once), the rest before the end. The search is a snapshot: no
  file watching while the popup is open.
- **Popup**: a focus-taking `javafx.stage.Popup` owned by the window (the rules of the popups you type into,
  [0149](0149-rename-file-structure-go-to-symbol.md)), centred at the top of the window: query field, toggles Aa / W /
  .*, file mask, scope chip; status line; the results grouped by file (header rows are not selectable); a read-only
  preview of the selected match. Enter or double-click opens the match at its line and column, recorded in the
  Back/Forward history ([0146](0146-navigation.md)); Escape hides. Query, toggles and mask are remembered per window
  for its lifetime; the scope never is.
- **Entry points**: Shortcut+Shift+F (Navigate → Find in Files…); with the explorer focused and one folder selected it
  searches that folder; a single-line editor selection becomes the query. The explorer's folder menu has Find in
  Folder…, the code editor's context menu Find Selection in Files ([0108](0108-code-editor-extensions-and-context-menu.md)).

## Consequences
- The whole window can be searched without opening files; the cost is disk reads on one thread, bounded by the limits.
- What is searched always matches what the tree shows, including Hide Generated Files, at the price of the listing
  rule running off the FX thread (accepted in [0176](0176-explorer-search.md) already).
- Accepted limits: results are a snapshot (a file changed after its row appeared shows the new text in the preview,
  with the matches found again on that line); reads are sequential (parallel reads only if a warm search of this
  repository takes over a second); left-out features are listed in
  [../ideas/find-in-files-later.md](../ideas/find-in-files-later.md).
- Listed in [../index.md](../index.md).
