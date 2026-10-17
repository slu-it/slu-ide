# 0044. Git log compare: a range as a log mode, the working tree in a window of its own

- Status: accepted
- Date: 2026-10-17

## Context
IntelliJ compares a branch with the current one ("Compare with Current": the commits on each side that the other
lacks) and a branch or commit with the working tree ("Show Diff with Working Tree", "Compare with Local": the files
that differ, each openable as a diff). The git log already filters with git's own commit limiting, revisions on
standard input ([ADR 0039](0039-git-log-filters.md)), builds its branches pane from the refs it reads
([ADR 0040](0040-git-log-branches-pane.md)), shows changed files as a tree and opens diffs of given texts in editor
tabs ([ADR 0038](0038-changed-files-and-commit-diffs.md)); the editor shows a file against `HEAD` with the file on
disk on the right ([ADR 0015](0015-git-diff.md)). Paths and refnames may have characters the JVM cannot pass to a
process in a POSIX locale (ADR 0038). All of this only reads.

## Decision
- **Compare with Current is a mode of the log,** not a tab or window of its own: the filter gets
  `GitLogFilter.excluded`, revisions whose commits are left out, written as `^<rev>` lines on `git log --stdin`
  (checked: git takes them there, also with `--ignore-missing`). The log then lists `<ref> ^HEAD` or `HEAD ^<ref>`
  (`LogComparison.limit`), with the text, user, path filters and graph options of the bar on top; it keeps paging,
  the details, changed files and every action of the table. A separate view would have repeated all of that.
  - The bar's Branch filter is set aside while comparing (disabled, its selection kept); stopping (×, "Stop
    Comparing", Escape in the table) restores the filter as it was, as the comparison never changes the bar's state.
    Filtering by branches from the branches pane stops comparing first.
  - The graph is laid out as for other revision filters: parents outside the range never come, so their lanes run to
    the end of the list. No hash match while comparing (it would show a commit outside the range).
  - Counts: how many commits of each side pass the bar's filter (its text, users, paths, First Parent Only, No
    Merges), so that the toggle's number matches the list: `git log --left-right --format=%m` over `<ref>...HEAD`, the
    range and the paths on standard input, built by the same code as the log's arguments (`limitingOptions`), so the
    two cannot drift; the marks `<` and `>` are counted. `git rev-list --left-right --count` would be cheaper, but it
    does not take `--use-mailmap`, so the user filter would match other names than the list. Read with every first
    page while comparing (`GitLogPages.loadCounts`), so a refresh after a checkout, commit or fetch, and every filter
    change, counts again. `HEAD` stays the symbolic `HEAD`, so both sides follow it; the bar names its branch (or
    `HEAD` when detached) as read with the refs.
  - A refresh whose refs no longer have the compared ref, or have it checked out, ends the mode before its rows are
    shown, loads the log again without it and says why in the header ("feature no longer exists.", "feature is the
    current branch now."), shown once the log loaded again (replacing the rows changes the selection, which clears the
    header). With `--ignore-missing`, a vanished `^ref` would otherwise list all of `HEAD`.
  - Navigating from the branches pane to a commit outside the range says "<name> is not in this comparison."
  - Offered for one local branch, remote branch (also the current branch's upstream) or tag that is not `HEAD` or the
    current branch, while `HEAD` has a commit.
- **The working tree is compared in a top-level window of its own** (`WorkingTreeWindow`), not modal and not owned by
  the IDE window: an owned window stays above its owner, so the list covered the diff tabs it opened. One per ref or
  commit (reopening brings it to the front); the git log closes them with the IDE window and when it opens another
  folder. It gets the IDE window's icons and the theme (`ThemedWindows`), opens first at the IDE window's right edge
  (vertically centred on it, within its screen), and opening a diff from it brings the IDE window to the front with the
  diff tab focused. A window keeps the list next to the editor while diffs open in tabs, and the log stays as it was.
  - Files: `git diff -z --name-status -M <commit> --`, read by `GitNameStatusParser`, shown with the changed-files tree
    (`ChangeTreeView`, now shared with the commit's changed files). The revision (a full refname, `HEAD` or a hash) is
    resolved to its commit on every refresh through `git cat-file --batch-check` with `<rev>^{commit}` on standard
    input, so a window of a branch follows the branch and says when it is gone. Only tracked files: `git diff` does not
    list untracked ones; the window says so.
  - The window refreshes with its Refresh button and whenever it gains the focus: edits of the working tree are not
    seen by the watch of `.git`. Its reads run on a thread of their own (`sluide-git-compare`), not behind the log's
    pages; latest wins.
- **Working-tree diffs:** both sides in their checkout form, compared as they are: left the file at the commit as a
  checkout would write it (`git cat-file --batch --filters` with `<blob> <path>` on standard input, so line endings,
  `ident` and smudge filters of its path apply, as for the editor's diff against `HEAD`, ADR 0015, which reads its
  left side with `cat-file --filters`; the old path for a renamed file), right the working-tree file's bytes, hunks
  between exactly these two texts.
  - A smudge filter can make the commit's side much larger than the stored blob, whose size the 5 MB check sees (a
    git-lfs pointer): its output is read only up to the limit, then git and what it started are killed and the file is
    too large. Git holds a filter's whole output before writing any of it, so a filter that fetches over the network
    (git-lfs without the object locally) or never ends is only stopped by the 10 s timeout ("The diff could not be
    read."); telling such filters apart beforehand from the `filter` attribute alone was rejected, as the same filter
    may find the object locally.
  - The bytes are read by the JVM where it can name the file (`canPassArgument`); a symbolic link gives the path it
    points to, as git stores a link. Where it cannot (non-ASCII in a plain ASCII locale), git reads the file from a
    path on its standard input (`git hash-object -w --no-filters --stdin-paths`) and the blob is read back, as Continue
    does (ADR 0042); this writes an unreachable loose object, which `git gc` prunes later, and follows a symbolic link
    to the file it points to. The file is stored whole before its size is known; the size of its index entry is taken as
    a hint first (a file staged above 5 MB is not stored), but a file that grew since it was staged is stored and only
    then found too large. Meanwhile the window's single thread waits.
  - The hunks come from git's own diff, not a JVM diff (ADR 0015): both texts go to two temporary files with plain
    names (`original`, `current`) in a temporary folder, and `git diff --no-index --text -U0` compares them there, with
    the folders above it not searched for a repository (`GIT_CEILING_DIRECTORIES`), no attributes (an empty
    `core.attributesFile`, `GIT_ATTR_NOSYSTEM`) and no line-ending conversion (`core.autocrlf=false`, `core.eol=lf`),
    with the repository's `diff.algorithm`. Run in the repository, its attributes and the user's settings converted the
    temporary files by their names (`* text eol=crlf` did, `*.txt text eol=crlf` did not) and `* -diff` made them
    binary, without hunks. Git exits with 1 both for different files and for one it cannot read: an exit value of 1
    counts only with a hunk in the output. The folder is deleted afterwards.
  - `git diff <commit> -- <path>`, as the editor's diff against `HEAD` uses, needs the path as an argument, and git has
    no option to read a diff's pathspec from standard input (`--pathspec-from-file` is not taken by `diff`); diffing
    two stored blobs would mean writing every working-tree file into the object store. There is no line diff in the
    codebase to reuse (the word diff of ADR 0019 works within lines).
  - Binary (NUL in the first 8000 bytes), larger than 5 MB, a submodule (the commit's or the index's entry is a gitlink)
    and a file whose content did not change (a pure rename) are told in the window's bar instead.
  - `GitLogView.onShowWorkingTreeDiff` gets a `WorkingTreeFileDiff`; `MainWindow` opens a `WorkingTreeDiffTab` with
    `FileViewers.openDiff(file, diff)`, the live file on the right as in ADR 0015, or, for a deleted file or one the JVM
    cannot name, `openDiff(name, current, diff)` with the text read (empty for a deleted file). With unsaved changes of
    the file in an editor the right side is titled "Working Tree (saved)", as in ADR 0021. Showing the same comparison
    again (same repository, commit and path) shows it read again in the same tab, whatever changed meanwhile (a
    deleted file that is back gets the live file). The callback returns `false` where no text viewer shows the file,
    and the window says so.

## Consequences
- The comparison is always with `HEAD`: no branch-vs-branch comparison of two other refs, no compare of two arbitrary
  commits, no file diff between two branches or since their merge base (three dots); only commits are compared in the
  log, and only a commit with the working tree in the window.
- Lanes of parents outside a range run to the end of the list instead of ending at the range's boundary.
- Untracked files are not listed in the working-tree window; ignored files neither.
- A text the working-tree file has in a form a checkout would not write (e.g. LF where the attributes ask for CRLF)
  shows as changed, as it is.
- The right side of a tab with the live file and the hunks come from two reads of the file: a save in between
  misaligns the colouring until Show Diff again. A diff tab does not follow later edits by itself; Show Diff for it
  again reads the file and the hunks again.
- Where the JVM cannot name a working-tree file, diffing it stores a loose object in the repository, follows a
  symbolic link, and may store a file larger than 5 MB before refusing it.
- Show Diff of a file with a smudge filter that fetches over the network may wait up to 10 s, and git itself may hold
  a large smudged file in memory meanwhile.
- Counting each side walks the commits as the log does (`git log`), slower than `rev-list --count` in large ranges.
