# 0038. Changed files and commit diffs in the git log

- Status: accepted
- Date: 2026-10-16

## Context
The git log ([ADR 0035](0035-git-log-area.md)) lists commits with their graph, refs and details
([ADR 0036](0036-commit-graph-layout.md), [ADR 0037](0037-ref-labels-and-commit-details.md)), but not what a commit
changed. IntelliJ shows the changed files of the selected commit as a tree next to its details, opens a file's diff
(the parent against the commit) in an editor tab and opens the local file with F4. Thoreau already has a
side-by-side diff view ([ADR 0015](0015-git-diff.md), [ADR 0021](0021-change-markers-and-diffs-on-demand.md)), but it
reads its right side from the file on disk, and the git log module depends on neither the editor nor the viewers.

## Decision
- **Changed files:** `GitLog.changedFiles(root, commit, firstParent)` runs
  `git --no-optional-locks diff-tree -r -z --no-commit-id --name-status -M --no-ext-diff <firstParent> <commit> --`,
  for a root commit `… --root <commit> --`, 10 s timeout, full hashes only.
  - The two-tree form against the first parent compares a merge with its first parent, as IntelliJ does by default;
    `diff-tree <commit>` alone would give a combined diff of a merge, or nothing.
  - `-M` detects renames (`R100\0old\0new\0`); `-z` gives paths unquoted, whatever characters they have;
    `GitNameStatusParser` reads the records into `GitChangedFile(change, path, oldPath)`. `U` and `X` count as
    modified. `-M` alone never reports copies (that needs `-C`), but the parser reads them.
- **File diffs:** `GitLog.fileDiff(root, commit, firstParent, file)` gives `GitFileDiff.Text(original, current,
  hunks)`, `Binary`, `TooLarge` or `Submodule(old, new)`, `null` if git fails; 10 s per call.
  - `git cat-file --batch-check` resolves `<firstParent>:<oldPath ?: path>` and `<commit>:<path>` (an added file has
    no original, a deleted one no current side) to their object names and sizes; the names go to git's standard
    input as UTF-8, so paths with non-ASCII characters work even where the JVM's locale is plain ASCII (where process
    arguments and file names lose them). Git reads them line by line, so a path with a line break gives `null`
    before git is asked: it would make git read another object, whose content must never show. Above 5 MB on either
    side gives `TooLarge`. A gitlink (`<name> submodule`, its commit not in the repository) gives `Submodule` with
    the commits before and after.
  - The contents come from `git cat-file blob <object name>`: raw blobs, without filters (line endings, smudge), as
    stored in the commit. A NUL byte in the first 8000 bytes of either side is git's own test for binary files and
    gives `Binary`. The texts are read as UTF-8.
  - The hunks come from git, not a diff library (ADR 0015): `git diff -U0 --no-color --no-ext-diff --no-textconv
    <original object> <current object>` between the two blobs, read by `GitDiffParser`; an added file is one hunk
    of all lines, a deleted one one hunk removing them, the same blob on both sides (a pure rename, a change of the
    mode only) none. Diffing the two blobs rather
    than `<parent> <commit> -- <paths>` needs no paths on the command line (see above) and always compares exactly
    the two texts shown, also for a copy and its source.
- **Layout:** the right side of the log's split pane is a vertical split: the changed files on top, the details below,
  half and half at first; the divider can be dragged, its share is kept while the window is open (`SharedSplit`, now
  for either orientation). The left/right share stays as in ADR 0037.
- **Loading:** the changed files are read right after the details, in the same task on `sluide-git-log`, with the
  same latest-wins rule (`CommitDetails`) and the same 300 ms keep-then-"Loading…" (`ChangedFilesPane`). The details
  are handed on as soon as they are read, so they do not wait for the files. A refresh that keeps the commit selected
  reads them again and keeps the selected file and the collapsed folders (by path).
- **Tree:** folders first, then files, by name (case-insensitive); a chain of folders that each hold only the next
  folder is one node (`src/main/kotlin`), as in IntelliJ; everything expanded (`changeTree`, without controls). Above it
  "N files", for a merge "N files (compared with the first parent <short hash>)". Placeholders as in the details pane,
  and "No changed files", "The changed files could not be read.". A generic folder and file icon; the file name coloured
  by its change with looked-up colours on `.git-log` (added `#2e7d32`, modified, renamed, copied and type changed
  `#1e63c8`, deleted `#8a8a8a`; dark `#5cbf62`, `#5a9ae6`, `#9a9a9a`; added and modified are the explorer's marker
  colours, the grey for deleted files is new), the selection text colour in selected rows; renamed and copied files with
  a muted ` ← old/path` and a tooltip.
- **Actions,** only on files: Show Diff (double-click, Enter, Shortcut+D, context menu), Open Local File (F4, context
  menu; disabled when the file is not in the working tree now, e.g. deleted), Copy Path (the path relative to the
  root). Enter on a folder opens or closes it. No accelerators on the context menu items: those of a control's context
  menu act in the whole scene, F4 would then fire in the editor too.
- **Callbacks wired by `ide`:** modules do not depend on each other for this. `GitLogView.onShowDiff` gets a
  `CommitFileDiff` (root, path, old path, change, commit, parent, both texts, hunks), read in the background (latest
  wins); `Binary`, `TooLarge`, `Submodule`, the same text on both sides (of a file neither added nor deleted) and
  failures are told in the log's header instead ("<name> is a binary file.", "<name> is too large to compare.",
  "<name> is a submodule: <old> → <new>." or ", added at <new>." / ", removed.", "<name> has no content changes.",
  "The diff could not be read."), until the next selection or load; a result that comes after the selected commit or
  file changed tells nothing (a late `Text` still opens its tab). `onOpenFile` gets the
  file in the working tree. `MainWindow` opens the file with `EditorArea.open`, and the diff with
  `FileViewers.openDiff(name, current, diff)` in a `CommitDiffTab`: title "<file name> (<commit short>)", tooltip
  "<path> at <commit short>", sides titled with the parent's short hash (and the old path of a renamed or copied
  file; "(none)" for an added file and a root commit) and the commit's. Short hashes in the git log feature have 8
  characters, as in the details pane and IntelliJ (the window title keeps 7).
  Read-only, without the Edit button. `GitLogView.showStatus` tells "<name> cannot be shown as text." for names whose
  viewer shows no text (images).
- **Diff of given texts:** `FileViewers.openDiff(name, current, diff)` shows `current` instead of reading a file
  (`SideBySideDiffView` takes its right side from a `CurrentText`: a file, re-read on `show`, or a fixed text). The
  highlighter is picked by the name alone, without touching the disk: code viewers by name, preview viewers and
  images by extension, a name without an extension by the shebang of `current`, else plain text. Source viewers of
  other modules (`.http`) cannot tell their names without the file, so their files show as plain text there.
- **Tab reuse:** Show Diff for a commit and path already open selects that tab (`EditorArea.select`) instead of
  opening a second one.

## Consequences
- Merges are compared with their first parent only; their changes against other parents are not shown.
- The file icons were generic at first; they are replaced by the explorer's file-type icons, shared through the
  `file-icons` module ([ADR 0047](0047-module-dependencies-and-shared-modules.md)).
- Renames show only as far as git's `-M` detects them (50 % similarity by default); copies show as added files.
- The texts are the blobs as stored, without filters: a repository that stores CRLF shows the lines as they are,
  without a conversion to the working tree's line endings; `.gitattributes` diff drivers are not applied.
- Files that are binary or larger than 5 MB, and submodules, are not compared; the header says so.
- Paths with a line break cannot be compared ("The diff could not be read.").
- Commit diff tabs do not follow anything: they show the commit as it was, also after the working tree changed.
