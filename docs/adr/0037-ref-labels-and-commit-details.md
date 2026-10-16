# 0037. Ref labels and commit details in the git log

- Status: accepted
- Date: 2026-10-16

## Context
The git log ([ADR 0035](0035-git-log-area.md)) lists commits with their graph ([ADR 0036](0036-commit-graph-layout.md)),
but not which branches and tags point at them, nor more about a commit than its subject, author and date. IntelliJ
shows labels at the right end of the subject and a details pane right of the table: the full message, hash, author,
email, dates and the refs of the selected commit.

## Decision
- **Reading refs:** `git --no-optional-locks for-each-ref` over `refs/heads`, `refs/remotes` and `refs/tags` with
  `--format=%(objectname)%1f%(*objectname)%1f%(refname)%1f%(symref)`, one record per line (refnames have no control
  characters), 10 s timeout (`GitRefsParser`). Then `git rev-parse --verify -q HEAD^{commit}` for the commit of
  `HEAD` (none in a repository without commits) and `git symbolic-ref -q HEAD` for the current branch (none when
  detached). `GitLog.refs` gives `GitRefs`: `GitRef`s (full refname, kind `HEAD`/local/remote/tag, commit) with
  `at(commit)` ordered `HEAD`, local, remote, tag and by name, and the current branch.
  - `for-each-ref` rather than `git branch -a` or `log --decorate`: structured fields instead of text meant for people,
    `%(*objectname)` peels annotated tags to the commit they tag (a lightweight tag is its `objectname`), and symbolic
    refs such as `refs/remotes/origin/HEAD` are recognised by `%(symref)` and skipped. Only newer git peels a tag of a
    tag completely; older git gives the inner tag, so such a tag labels no row.
  - `HEAD` is a ref of its own, `HEAD`, at its commit, whether attached or detached.
- **Refs with every first page:** the background task of a reload reads the first page, then the refs; further pages
  reuse them, so every row (`GitLogRow`) gets its refs when it is built. If the refs cannot be read, the rows show
  without labels and nothing else changes.
- **Labels at the right end of the Subject cell** (IntelliJ's default): graph, subject (ellipsized, taking the rest),
  labels. The labels take at most a third of the cell at any width, so in a narrower column they shrink together with
  the subject (their text ellipsized), and never so far that the subject gets less than 60 px while there is room for
  it. The cell's `text` stays the subject; what is shown is its graphic.
  - Grouping (`refGroups`): a local branch and the remote branches of the same name are one group,
    `origin & upstream & main`; a remote branch without a local one of its name is `origin/main`, and so is one
    without a remote name (git-svn's `refs/remotes/trunk` is `trunk`, even next to a local `trunk`); every tag is a
    group;
    `HEAD` joins the group of the current branch if that branch is on the row, else (detached) it is the group `HEAD`.
    Ordered: the group with `HEAD`, local branches, remote branches, tags, by name within each. The label is the first
    group's and ` +N` for N more groups.
  - Icons: one `TAG_OUTLINE` per kind of ref on the row, in the order `HEAD`, local, remote, tag, overlapping like
    IntelliJ's stacked icons (the first on top). Their colours are looked-up colours on `.git-log`: `HEAD` `#d9a000`,
    local `#3f9a4a`, remote `#8d5bd0`, tag `#8a8a8a`, lighter in the dark theme (`#e8c04a`, `#6cc070`, `#b38cf0`,
    `#a8a8a8`). In selected rows the icons get a thin outline in the table's background colour
    (`-fx-control-inner-background`), like the graph's halo and, like it, not tied to the table's focus. The text is
    muted, in the selection text colour on selected rows.
  - A tooltip on the labels (not the whole cell) lists every ref of the row with its icon, `HEAD` first; one per cell,
    its content built when it shows. It is a window of its own, so it gets `git-log.css` itself and the lighter colours
    in both themes, as Modena's tooltips are dark.
- **Details pane** right of the table in the log's split pane, at first the right 30 %. A resize of the log keeps both
  panes' shares (`SharedSplit`): a plain `SplitPane` keeps the pixel width of a pane that does not resize with it,
  which squeezed the table in narrow windows, or splits the change equally between panes that do. Dragging the divider
  sets a new share, kept while the window is open.
  - Loaded lazily (`CommitDetails`, without controls): when the selection settles on exactly one commit (looked at once
    per event, so a reload clearing and restoring it counts once), `git --no-optional-locks show -s --no-color
    --no-show-signature --encoding=UTF-8 --format=%H%x1f%P%x1f%aN%x1f%aE%x1f%at%x1f%cN%x1f%cE%x1f%ct%x1f%B <hash> --`
    runs on the log's thread, 3 s timeout; `GitLog.details` accepts only full hashes (40 or 64 hexadecimal
    characters). Latest wins: a result for a commit that is no longer the single selection is dropped. A reload gives
    new rows, so the details of a commit that stays selected are read again; the refs shown are the row's.
  - Content in IntelliJ's order: the message (the subject, i.e. its first paragraph, bold, then the body); the hash's
    first 8 characters and the author, the author's email, `on <date> at <time>` (author date, localized short date
    and time, system zone); `committed on <date> at <time>`, with ` by <committer>` when name or email differ, only if
    the committer differs or the committer date and time as shown (to the minute), so an amend within the same minute
    adds no line saying the same; the refs, one per line with their icons. An empty message shows no subject line.
  - The texts are read-only `TextArea`s styled flat, wrapped and always as high as their text (their own scroll bars
    are off), so that they can be selected and copied (mouse, Shortcut+C) while only the pane scrolls, vertically.
    They are not focus traversable: the table keeps the focus when the selection changes; a click into a text takes
    it, so that Shortcut+C copies from there.
  - Placeholders: "No commit selected", "N commits selected", "The commit details could not be read."; while the
    details of another commit load, the previous ones stay for 300 ms before "Loading…" replaces them, so moving
    quickly through the rows does not flicker.

## Consequences
- Refs created without touching anything directly in `.git` (`git branch x`, `git tag x`) show only after Refresh, as
  their commits do ([ADR 0035](0035-git-log-area.md)).
- The refs are read slightly after the page, so a ref moved in between can label a commit that is not listed (yet);
  that is harmless, the next reload corrects it.
- Remote names that contain `/` are ambiguous: a remote branch is split at its first `/` when grouping, so the
  branches of such a remote may not join the local branch of the same name.
- The details of a commit are read again whenever it is selected again; there is no cache.
- No "In N branches" line yet (D2), and hashes in the details are not links (D3).
