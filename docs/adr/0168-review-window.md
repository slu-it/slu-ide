# 0168. Review window: a local, GitHub-style review of a branch, commits or uncommitted changes

- Status: accepted
- Date: 2026-11-12

## Context
Reviewing a finished feature meant opening one diff tab per file and commit (git log, Push dialog), or pushing the
branch and reading the pull request on GitHub or Azure DevOps. A local review should show a whole scope (the branch
since it left the default branch, one commit, a range of commits, the uncommitted changes) the way a pull request
does: all changed files in one long scroll, a filterable file tree beside it, context that starts small and expands,
unified or split, whitespace changes hidden on demand.

Options considered for the diff:
- **A WebView** with our own HTML or diff2html: the table layout, expanders and wrapping come almost free, but the
  syntax colours would differ from the editor's (highlight.js) unless Kotlin tokenizes, theme, font, keys and context
  menus would be rebuilt in web CSS and a JS bridge, and very large scopes get slow in WebKit.
- **The existing `SideBySideDiffView`**: two RichTextFX areas over a whole file, with no collapsed context, no file
  headers and no room for several files in one scroll.
- **Native rows**: a pure-Kotlin row model rendered by a virtualized list of our own cells.

## Decision
- **Native rows.** `ReviewDocument` (module `code-review`) turns each file's two texts and git's `-U0` line changes
  into `ReviewRow`s: file header, gap (hunk header with expanders), unified or split line rows (split rows aligned with
  fillers as in `alignDiff`), notice and spacer rows. A Flowless `VirtualFlow` (`ReviewDiffList`) renders them; line
  cells are a `TextFlow` of `Text` nodes with the `syntax.css` token classes, wrapping to the column, with changed
  words (and find matches) drawn as `TextFlow.rangeShape` paths behind the text. Highlighting is the editor's
  (`FileViewers.highlighterFor`), the word diff the one of ADR 0019 (`wordChanges`, with `ignoreWhitespace`).
- **Module and family.** `code-review` belongs to the editor family (it renders file diffs with `file-viewers`) and
  does not depend on `git`: it reads through its `ReviewSource` interface, which `ide` implements over `git`'s
  `GitReviews` (the pattern of `WindowWorkflowSources`). `git-log` only offers the entry points and hands a
  `ReviewRequest` to `ide`.
- **Own window.** Every review opens a new top-level `Stage` without owner (like the working-tree window, ADR 0044),
  80 % of the IDE window's screen, centred, offset by 30 px per further window; bounds are not remembered. A project
  window's reviews close with it.
- **Scopes.** A branch is reviewed from `merge-base(base, head)` to `head`, GitHub's three-dot diff, so commits merged
  in from the base do not show. The default base is the remote default branch (the `HEAD` of the remote the reviewed
  head's upstream is on, or of a remote branch's own remote, then `origin`'s, then other remotes', else their
  `main`/`master`), else the local `main`/`master`; a base picker changes it. Commits are reviewed from the oldest
  one's first parent to the newest one; one commit (a merge too) against its
  first parent. When the reviewed head is the checked-out `HEAD`, the uncommitted changes (untracked files included)
  are a pseudo-commit of the commit picker, and "All" reaches the working tree. Commit diffs read raw blobs, diffs to
  the working tree filter the original side like a checkout (as `GitLog.workingTreeFileDiff` does).
- **Context** is computed from the two texts, not by git: 3 lines, expanders reveal 20 more or all, with no further
  git call. **Hide whitespace** runs git again with `-w`.
- **Loading.** The file list comes first; the diffs follow in tree order in chunks of 25 on the window's own
  `sluide-review` thread, highlighted there; each reload, scope, base or whitespace change cancels the running load.
- **Staleness.** A fingerprint (base, head and merge-base hashes; for scopes reaching the working tree also the status
  and the changed files' size and modification time) is compared when `.git` changes, when the status snapshot
  changes and when the window gains focus; a difference shows a notice bar with Reload, never a reload by itself.
- **Anchors for comments later.** A line row is identified by path, side and 0-based line (`ReviewAnchor`); the line
  selection (click and shift-click on line numbers) is the anchor review comments will hang on
  ([ideas](../ideas/review-later.md)).

## Consequences
- The diff looks and behaves like the editor (colours, font, keys, menus) and stays fast for large scopes, at the cost
  of a renderer of our own: text selection is by whole lines, and find is our own.
- `file-viewers` gained public `FileViewers.highlighterFor` and `wordChanges`; `git` gained `GitReviews` and an
  `ignoreWhitespace` flag on its blob and file diffs.
- Viewed is remembered per file path and content (a hash of both texts) as long as the window lives: a file is viewed
  exactly when its current content was marked, so a changed file or another commit range shows it unchecked and
  coming back to the marked content checks it again. Picking commits or a base drops all contents first, so no file
  shows the lines of the previous range. Viewed flags are not persisted; persisting them, comments, image diffs and free text selection are ideas.
