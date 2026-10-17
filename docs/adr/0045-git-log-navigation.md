# 0045. Git log navigation: branches containing a commit, hash links and Go to

- Status: accepted
- Date: 2026-10-17

## Context
The git log's details ([ADR 0037](0037-ref-labels-and-commit-details.md)) show the refs pointing at a commit, but not
which branches contain it, and hashes in commit messages ("This reverts commit 1a2b3c4d.") are plain text. IntelliJ
lists the containing branches below the refs ("In 3 branches: …"), makes such hashes links, and has Go to
Hash/Branch/Tag (Ctrl+G) that jumps to a commit by anything git can resolve. The branches pane already navigates to a
branch's commit, loading pages until it is there ([ADR 0040](0040-git-log-branches-pane.md)).

## Decision
- **One resolver for revisions: `git cat-file --batch-check` with the revisions on its standard input**
  (`GitLog.resolveCommits`, `GitRevisionParser`): two lines per revision, `<revision>^{commit}` and `<revision>`, one
  process for any number of them. `^{commit}` peels annotated tags and accepts everything git's revision syntax has
  (`HEAD~3`, `main^2`, `v1.0^{}`, `@{upstream}`), and disambiguates a short hash by commits only. Git reports a short
  hash of several commits as `missing` for the first line, so the plain second line tells `ambiguous` from unknown.
  The result per revision is `GitRevisionResolution`: `Commit(hash)`, `Missing` (also a tree or blob) or `Ambiguous`;
  `null` if git fails, which a revision git cannot even look up does for the whole batch (`@{upstream}` without one).
  - Standard input rather than `rev-parse` arguments: the JVM mangles non-ASCII arguments in a POSIX locale (as for
    the log's revisions and paths, [ADR 0039](0039-git-log-filters.md)), an argument starting with `-` would be an
    option, and `rev-parse` stops at the first failure, so many candidates would need many processes. Input is capped
    at git's safe pipe size (about 15 KB); a revision with a line break is refused.
- **Hash links in the message:** words of 7–40 hexadecimal characters, not part of a longer word (`hashCandidates`),
  at most 100 per message, are resolved together right after the details are read, in the same background task and
  before the details are shown, so a message never flickers from text to links. Only a `Commit` whose hash starts
  with the word links (a branch or tag named like a hash does not). A click goes to the commit through
  `GitLogPages.loadUntil`, with the branches pane's messages in the header and the 8-character hash as the name, and
  gives the table the focus (the clicked text may disappear with the next commit's details).
- **The texts with links are RichTextFX areas** (`LinkText`, a read-only `StyleClassedTextArea`, already used by the
  code views, [ADR 0005](0005-syntax-highlighting.md)): style classes colour and underline the links, `hit(x, y)`
  finds the character under the mouse, auto height makes the area as high as its wrapped paragraphs so that only the
  details pane scrolls, and selection, Shortcut+C and a Copy/Select All menu work as in the text areas. A link is
  followed on a single primary click that selects nothing; the hand cursor is a pseudo-class, as the area's own style
  sheet sets its cursor. Its scroll events go to the details pane. The subject, body and branches line use it; the
  author and date lines stay text areas. Links take the accent colour, a lighter blue (`#589df6`) in the dark theme,
  where the accent is too dark on the background. RichTextFX keeps the text colours inside a selection (it has no
  selected-text fill for its text nodes), so the selection is a pale blue of its own (`#cfe3f9`, dark `#214283`), not
  the accent, and a selected link stays readable.
  - Rejected: keeping `TextArea` and hit-testing its skin: a text area draws its text as one `Text` node, so a part of
    it cannot be coloured or underlined. A `TextFlow` of our own would need selection and copying built by hand.
- **Branches containing a commit, read lazily:** `git for-each-ref --contains=<hash> --format=%(refname)%1f%(symref)
  refs/heads refs/remotes` (`GitLog.branchesContaining`, symbolic refs such as `origin/HEAD` skipped), 10 s timeout,
  on a thread of its own (`sluide-git-branches`) so that it neither waits behind nor delays the pages, only for the one
  commit whose details are shown and only after they are (`CommitBranches`). A request superseded before its turn on
  that thread reads nothing, so stepping through the rows does not queue reads; a result for a commit no longer shown
  is dropped but kept: results are cached per commit until the refs read with a first page differ from the last ones
  (then the shown commit's are read again, the old line staying meanwhile) or another folder opens. "Looking for
  branches…" only after 300 ms, as the details' "Loading…". Ordered current branch, local, remote, by name ignoring
  case first; at most 6 names before a "Show all (N)" link, which lists all for that commit.
- **Go to Hash/Branch/Tag** (`GoToPopup`): Shortcut+G in the log body or the table's context menu; a popup at the top of
  the table completing the branches and tags already read (no git call while typing), resolving the choice (an item
  by its full refname, else the typed text) with the resolver on the log's thread, then `loadUntil`. Its outcome stays
  in the popup, not the header, so the user can correct the text. "Hidden by the filters" offers Clear Filters
  (`FilterBar.clearFilters`: everything back to the defaults but the Regex and Match Case toggles), then the search
  goes on in the reloaded log.
- **One navigation path:** the branches pane, hash links and Go to all end in `GitLogPages.loadUntil` with the same
  `missingMessage`, which also tells a log that cannot be read (a failed page ends the search with
  `CommitMissing.UNREADABLE` instead of silently); a newer request replaces an older one, whichever kind; the branches
  pane still acts only while its branch is selected and follows a moved branch as before.

## Consequences
- A link resolution adds one short `cat-file` to reading the details of a message with candidates; none without.
- `for-each-ref --contains` walks history and can take seconds in a large repository; the details never wait for it,
  and a slow read delays only the branches line of the commit shown next (reads superseded meanwhile are skipped).
- Unknown revisions and revisions git cannot look up (no upstream) both say "Unknown revision"; git's own message is
  not shown.
- The completion lists only what the last first page read, like the Branch filter; a new branch shows after Refresh.
- RichTextFX is now a dependency of `git-log` too.
