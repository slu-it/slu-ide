# 0013. Git status from the git command line, shown as markers in the file explorer

- Status: accepted; partly superseded by [0060](0060-logging-conventions.md) (stderr of reads kept for the log) and
  [0062](0062-git-concurrency.md) (a change of `.git` refreshes only if it changed what the status reads; git writes of
  the window hold refreshes)
- Date: 2026-10-13

## Context
The file explorer should show the git status of files and folders: unversioned, added, modified and ignored. Ignored
files need the rules of every `.gitignore` in the tree, `.git/info/exclude` and the user's global excludes file
(`core.excludesFile`), with negations, anchors and `**`.

Options considered:
- **The `git` command line**: `git status --porcelain=v2 -z --ignored` is a stable, machine-readable format and
  applies all ignore rules exactly as the user's git does. Needs git installed.
- **Eclipse JGit**: pure Java, has a `StatusCommand` and `IgnoreNode` for `.gitignore` files. Works without git, but
  adds about 3 MB and can differ from real git in edge cases (newer index formats, config options, attributes).
- **Our own `.gitignore` parser** next to the CLI: two sources of truth that can disagree, for no gain.

## Decision
- A new module `modules/git` (package `dev.sluide.git`) runs the `git` command line; no library.
- `GitCli` checks once at startup (`git --version` when the bean is created, 3 s timeout) whether git is available.
  Without it, Thoreau shows no git information.
- `GitStatusService.open(folder)` follows a repository only if git is available and the opened folder itself has
  `.git` (a folder, or a file as in worktrees and submodules). It runs
  `git --no-optional-locks status --porcelain=v2 -z --ignored` on a background thread (`sluide-git`, 10 s timeout):
  - after opening;
  - when the explorer reports changes in a loaded folder or a manual refresh;
  - when an entry directly in the `.git` folder changes (`index`, `HEAD`: staging, commits, checkouts), watched with
    `FileWatcher` without its subfolders (changes only below `.git/refs/` or `.git/logs/` are not noticed). A `.git`
    file (worktrees, submodules) is not watched; such repositories refresh only on explorer changes or F5.
  Requests during a run are combined into one more run; results for a folder that is no longer open are dropped.
  Every `open` starts from an empty snapshot. A failing or timed-out run keeps the previous snapshot, so markers
  stay e.g. after `rm -rf .git` until the folder is opened again.
- No optional locks (also `GIT_OPTIONAL_LOCKS=0`): otherwise `git status` may rewrite `.git/index`, which would
  trigger the `.git` watch again and again.
- `GitStatusParser` turns the records into statuses: `?` unversioned, `!` ignored, `A` in the index or worktree
  (intent to add, `git add -N`) and renames/copies added, everything else (changes, deletions, conflicts) modified.
  Untracked and ignored folders come as `dir/` without their content, which keeps the call cheap for `build/` or
  `node_modules/`; `GitStatusSnapshot` lets everything inside inherit their status. A folder shows each status found
  below it, except ignored ones.
- Files and ignored folders show their status as the colour of their name; other folders as one bubble per status
  below them, so a folder's own name never claims a status its content has.
- The explorer does not depend on `git`: it has a generic `FileMarker`/`FileMarkers` (name colour, bubbles) and a
  `markers` callback, and `MainWindow` maps `GitStatus` to them (as with `canOpenFile` in ADR 0012). `git` depends
  on `file-watcher`.

## Consequences
- The status is exactly git's, including global excludes, with no ignore logic of our own.
- Opening a subfolder of a repository, or running `git init` after opening a folder, shows no markers until a
  repository root is opened (again).
- Edits in folders the explorer has not loaded are only noticed with the next `.git` change or refresh (F5).
- A GUI app on macOS started from the Finder has a minimal `PATH`: git is found as `/usr/bin/git`, which works only
  with the Xcode Command Line Tools installed (otherwise macOS may offer to install them). Not verified on macOS.
- Each refresh starts a process; for very large repositories `git status` may take noticeable time, which happens
  in the background and keeps the last markers meanwhile.

Note 2026-10-16: `--branch` was added to the status command
(`git --no-optional-locks status --porcelain=v2 --branch -z --ignored`) so that the window title can show the
checked-out branch or commit; the snapshot carries it, and a checkout without file changes still publishes a new
snapshot.

Note 2026-10-19: the standard error of reads is no longer dropped: the first 4 KB are kept for the log only
(ADR [0060](0060-logging-conventions.md), partly superseding this).

Note 2026-10-19: a change noticed directly in `.git` runs `git status` only if a stamp of what the status, the log and
the refs read (`HEAD`, index, refs, configuration, …; not objects or reflogs) changed, and a git write of the window
holds refreshes until its end, then runs one ([ADR 0062](0062-git-concurrency.md), partly superseding this). Changes
only below `.git/refs/` are counted with the next batch of `.git`.
