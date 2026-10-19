# Git Status

**Module:** `modules/git` (package `dev.sluide.git` and its sub-packages, [ADR 0055](../adr/0055-sub-packages-within-modules.md)), decision in [ADR 0013](../adr/0013-git-status.md)

- At startup `GitCli` checks whether `git` is on the `PATH` (`git --version`); the log says which version is used or
  that git information is not shown.
- Opening a folder (`MainWindow` → `GitStatusService.open`) follows its git status if git is available and the folder
  has `.git`. Other folders, and no folder, show nothing.
- Git runs only once the folder's trust allows it ([project-trust.md](project-trust.md)): a repository whose own
  configuration or hooks would make git run commands shows nothing until the user trusts it.
- The file explorer shows the status in colour ([file-explorer.md](file-explorer.md)):
  - red: unversioned (untracked, not ignored);
  - green: added (new in the index, the new path of a rename or copy, or intent to add with `git add -N`);
  - blue: modified (changed, deleted or in conflict, staged or not);
  - orange: ignored (`.gitignore`, `.git/info/exclude`, global excludes).
  A file's name is drawn in the colour of its status. An ignored folder, or one inside an ignored folder, has an
  orange name. Any other folder keeps its normal name colour and gets a bubble after it per status found below it, in
  that order; an untracked folder gets only red. Ignored files do not mark their parent folders. Clean files and
  folders are not coloured.
- Updates by themselves: after changes in a folder the explorer has loaded, after F5/Refresh, and after changes of
  entries directly in `.git` such as `index` and `HEAD` (`git add`, commits, checkouts in a terminal or another tool;
  subfolders like `.git/refs/` are not watched). A `.git` file (worktrees, submodules) is not watched, so such
  repositories update only on explorer changes and F5. `git status` runs in the background; the markers change when
  it is done.
- A change in `.git` runs anything only if it changed what the status, the log and the refs read (`HEAD`, the index,
  refs, the configuration, …): new objects, reflogs and lock files alone run nothing, and git's trust check runs again
  only if what it reads changed ([ADR 0062](../adr/0062-git-concurrency.md)). While an operation of the git log runs,
  refreshes for changes of `.git` wait, and for a checkout, cherry-pick, revert, hard reset or commit also those of the
  explorer; at its end one refresh runs for all of them.
- `GitStatusService.snapshot` holds the latest `GitStatusSnapshot` (`fileStatus`, `folderStatuses`). Every `open`
  resets it to the empty snapshot; after that it is replaced only when something changed. If `git status` fails or
  takes longer than 10 s, the previous snapshot stays (e.g. markers remain after `rm -rf .git` until the folder is
  opened again).
- `GitStatusService.gitFolderChanged` counts the changes noticed directly in `.git` that changed what the status, the
  log and the refs read, and each operation of the git log once at its end (on the JavaFX thread), and
  `GitStatusService.root` is the followed repository root; the git log uses both ([git-log.md](git-log.md)). Changes
  only below `.git/refs/` (e.g. `git branch x`) are counted with the next change noticed directly in `.git`.
- Modified files get change markers and a diff against `HEAD` ([git-diff.md](git-diff.md));
  `GitStatusService.diffAsync(file, done)` reads it on a thread of its own (ADR 0062).
- The window title shows the checked-out ref: `slu-ide [master] – Thoreau` on a branch,
  `slu-ide [detached at 5f070b9] – Thoreau` on a detached `HEAD` (7-character hash), and the branch name of a
  repository without commits (`[main]`). Without a repository, git or a first status it stays `slu-ide – Thoreau`.
  The ref comes from the `# branch.head` and `# branch.oid` headers of `git status --branch` and is part of the
  status snapshot, so a checkout in Thoreau's terminal or any other tool updates it through the watch of `.git`
  (`HEAD` changes), which does not happen for a `.git` file (worktrees, submodules). Like the markers, the title keeps
  the last ref if `git status` fails.
- A downloaded repository cannot run code through a `core.fsmonitor` hook in its configuration (a command instead of
  `true` or `false`) by being opened: it is turned off for every read (`-c core.fsmonitor=false`; git's own daemon
  stays). Its filters still run (`git status` runs a clean filter for files whose index entry does not match them).
  And git always works on the opened folder: variables such as `GIT_DIR` or `GIT_INDEX_FILE` that Thoreau may have
  been started with are not passed on ([ADR 0052](../adr/0052-hardening-how-git-is-started.md)).
- Not yet implemented: blame, git commands from the IDE, repositories in subfolders or above the
  opened folder.
