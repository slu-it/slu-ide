# 0163. Hide generated files

- Status: accepted; extends [0158](0158-hide-generated-folders.md)
- Date: 2026-11-11

## Context
The explorer hides generated folders ([ADR 0158](0158-hide-generated-folders.md)). macOS also litters every folder it
opens in Finder with a `.DS_Store` file, which is noise in the tree, while a committed one is worth noticing. The user
chose `.DS_Store` as the only generated file for now.

## Decision
- **File catalogue.** `generatedFileCatalogue` maps exact, case-sensitive file names to a reason (`.DS_Store` to "macOS
  folder metadata"); `generatedFileReason` looks the name up without I/O. Only regular files count (a symlink to one
  too). Scope as for folders: not top-level nodes, nothing classified below a generated folder.
  `GeneratedReason.file` tells files from folders, so re-checking after a git change needs no extra I/O.
- **Git rule for files: hidden unless tracked.** Outside a repository, before the status arrives and for uncovered
  paths the name decides (hidden). In a repository, ignored or unversioned files (also inside such folders) stay
  hidden; tracked files (clean, modified, added) are shown without a reason. `GeneratedFolderFilter.trackedByGit`
  (set from `GitStatusSnapshot.tracksFile`) answers it; folders keep `ignoredByGit` ("generated only if git ignores
  it").
  Rejected: the folder rule for files (`.DS_Store` would show in every repository without a gitignore entry) and
  always hiding (a committed `.DS_Store` would go unnoticed).
- **New files and nested repositories.** `GitStatusSnapshot` records when its status run started (`startedAt`, not
  part of `equals`: every run has a new time and an unchanged result is no change). A file absent from the snapshot
  that was modified less than 2 s before it or later (or has no readable modification time) is uncovered, so a
  `.DS_Store` that Finder just wrote stays hidden instead of showing as "tracked" until the next status. A file in a
  nested repository or submodule (a `.git` entry in a folder between it and the root) is uncovered too. Folders keep
  their behaviour there.
- **Compacting.** While the setting is on, Compact Middle Folders ignores entries that the file catalogue names (regular
  files, by name only, no git), because on a Mac nearly every folder Finder opened has one. Hidden generated folders
  still block compacting, as in 0158. Revealing such a file in a middle folder of a compacted row splits the row;
  hiding it again joins the row again.
- **Name.** The toggle and tooltips say "Hide Generated Files". The setting key `explorer.hideGeneratedFolders` and the
  Kotlin identifiers stay, so stored values keep working.
- **Look.** A shown generated file has Material `file-cog` in the generated folders' muted grey (same CSS class) and the
  tooltip "Generated file: <reason>. Hidden while Hide Generated Files is on".

## Consequences
- A Mac without a global gitignore gets a clean tree; a tracked `.DS_Store` appears when the status arrives.
- More names are one catalogue entry each; ideas are in [generated-folders-later](../ideas/generated-folders-later.md).
- A tracked `.DS_Store` in a middle folder of a compacted row is not visible while the row is compacted.
- A status run with an unchanged result keeps the old `startedAt`. A tracked `.DS_Store` that git itself wrote
  (checkout, pull, reset --hard) or that Finder touched without a content change has an mtime within the 2 s margin of
  the next run, and later runs return the same result, so it stays hidden until the status changes otherwise or its
  folder is listed again after that. A network share whose server clock runs ahead makes absent files count as new, so
  tracked ones stay hidden too, the safe direction. Kept on purpose: it errs on hiding a noise file, and only committed
  `.DS_Store` files are affected.
- The identifiers still say "folders", which is a naming wart accepted to avoid churn.
