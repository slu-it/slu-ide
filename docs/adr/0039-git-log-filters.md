# 0039. Git log filters: git's own commit limiting, revisions and paths on stdin

- Status: accepted
- Date: 2026-10-16

## Context
The git log ([ADR 0035](0035-git-log-area.md)) lists every commit of all branches, remote branches, tags and `HEAD`,
loaded in pages of 500 with its graph ([ADR 0036](0036-commit-graph-layout.md)), refs and details
([ADR 0037](0037-ref-labels-and-commit-details.md), [ADR 0038](0038-changed-files-and-commit-diffs.md)). IntelliJ has
a filter bar above its commits: a text or hash field with Regex and Match Case, Branch, User and Path filters and
graph options (sort order, first parent only, no merges). Paging must stay incremental, and the names of branches and
paths may have characters that the JVM cannot pass to a process in a POSIX locale (ADR 0038).

## Decision
- **Git limits the commits,** not Kotlin: the filters become options of the same `git log` (`GitLogFilter`,
  `GitLog.commits(root, filter, skip, limit)`), so a filtered log is still read page by page with `--skip`, and a
  filter that matches few commits in a large history needs no history loaded into the IDE. Filtering the loaded rows
  in Kotlin was rejected: it would have to load every page first (or show gaps), and git already knows `.mailmap`,
  history simplification for paths and regular expressions.
- **Arguments:**
  - Text: `--grep=<pattern>` (the whole message, any line), always with `--extended-regexp`; without Regex the text is
    escaped into a literal ERE by Thoreau (`a.b` does not match `axb`). `-F` is not used, as it would also turn the
    author patterns into literals. Without Match Case `--regexp-ignore-case`. An invalid regex makes git fail ("The git
    log could not be read.").
  - Users: per value `--author=^<value> <` and `--author=<<value>>`; git matches them against `Name <email> timestamp
    zone` after `.mailmap` (`--use-mailmap` is always given, so a `log.mailmap=false` of the user does not keep them
    from matching the mapped names the list shows), ORs several and ANDs them with `--grep`. They ignore case
    whatever Match Case says, as ASCII letters become bracket expressions (`[Aa]`); the rest is escaped.
  - Branches: the selected full refnames (or `HEAD`) instead of `--branches --remotes --tags HEAD`, with
    `--ignore-missing`, so a deleted branch is skipped (git 2.53 applies it to revisions on the standard input too).
  - Paths: `--literal-pathspecs` (no patterns; a folder includes everything below it), git's default history
    simplification (as `git log -- <path>`), and `--parents`, which makes `%P` give the rewritten parents.
    `-c log.follow=false`: a user's `log.follow=true` would make a single path follow renames, with parents that are
    not rewritten. Typed paths with a `..` segment are not taken: git fails for a path outside the repository.
  - Options: `--date-order` (default) or `--topo-order`, `--first-parent`, `--no-merges`.
- **Revisions and paths go on git's standard input** (`--stdin`: revision lines, then `--` and path lines), never as
  arguments, so they reach git as UTF-8 whatever the JVM's locale (`sun.jnu.encoding`). A revision or path with a line
  break, a revision starting with `-`, or more than 15 KB of input give `null` without running git. The input is
  written while git runs (its output is read in parallel), but that write blocks while git does not read and is not
  covered by the timeout (`GitCli.runBytes`); below macOS's default pipe buffer of 16 KiB it never blocks.
- **Patterns cannot go on stdin,** so they stay arguments: where the JVM's argument charset cannot encode a character,
  `encodableArgument` replaces it by one `.` per byte of its UTF-8 encoding. Git then runs in the same locale and
  matches byte by byte, so the pattern still matches (and slightly more: any bytes there). Inside a bracket expression
  of a user's regex (`[äö]`) this breaks.
- **Hash:** a text of 4–64 hexadecimal characters that names exactly one commit (`git rev-parse --verify -q
  <text>^{commit}`, whose result must start with the text, so a branch named like a hash does not count; ambiguous and
  unknown prefixes give none) is shown as the first row, ahead of the message matches, if it passes the users, paths
  and no merges (`git log --no-walk <hash>` with those options); the branch filter is not applied to it. It is not
  repeated when it also matches by message. Paging counts the commits git returned, not the rows.
- **Graph only where git rewrites the parents:** with either a path filter (`--parents`) or a branch filter, in either
  order, the parents are shown commits, so the graph works; with First Parent Only each row is laid out with its first
  parent only (`%P` still lists all, the changed files still compare with the first parent). Under a text or user
  filter, a hash match or No Merges the parents may be hidden and git does not rewrite them, so the rows get no graph at
  all (`GraphRow.NONE`: no dot, no edges, the subject at the left); ref labels stay. Drawing dotted edges through hidden
  commits, as IntelliJ does, was rejected: it needs the full graph of the unfiltered history.
- **UI** (`FilterBar.kt`): a bar above the commit table only: the text field ("Text or hash", applied 300 ms after the
  last keystroke, at once on Enter; Escape clears it) with `.*` and `Cc` toggles, Branch, User and Path buttons with a
  clear button each (only while set), and a cog menu "Graph Options". Branch, User and Path share one popup class: a
  `javafx.stage.Popup` with a search field and a list of check boxes, checked items first, toggled by click or Space
  and applied at once; Enter toggles the one item shown or the one named exactly, else adds a typed user or path. The
  popup keeps the values' list order and lower-case texts when it reads its items, so that typing and applying stay
  linear with 100 000 paths. The popup is a window of its own; it gets `git-log.css` and the
  `.git-log` colours, and `ThemedWindows` gives it the theme (ADR 0009). The branch list comes from the refs the log
  read last; the user list from the authors of all rows loaded since the folder was opened, plus `user.name` ("me");
  the path list from `git ls-tree -r -t -z HEAD` (folders flagged), read when the popup is first shown and again after
  Refresh or a change in `.git` (not after a filter change). Shortcut+F in the log body focuses the text field; all
  controls of the bar are reached with Tab. The cog is marked (`:on`, the accent colour) and its tooltip lists the
  options while one differs from the default.
- **Per window, not stored:** Refresh and changes in `.git` keep the filters, opening another folder resets them; a
  change while the log is collapsed only marks it stale. An empty filtered result says "No commits match the filters."

## Consequences
- The graph disappears under text and user filters, a hash match and No Merges; only paths, branches and First Parent
  Only keep it.
- Git's default history simplification for paths can hide changes on side branches that were merged TREESAME
  (IntelliJ's "full history" is not offered).
- The User popup only lists authors of rows loaded so far (and `user.name`); others can be typed.
- Non-ASCII characters in patterns match slightly more than asked where the JVM's locale cannot pass them, and break
  inside bracket expressions of user regexes.
- A hash match ignores the branch filter, so it can show a commit that the selected branches do not reach.
- No date filter.
