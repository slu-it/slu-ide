# Git Status

**Module:** `modules/git` (package `dev.sluide.git`), decision in [ADR 0013](../adr/0013-git-status.md)

- At startup `GitCli` checks whether `git` is on the `PATH` (`git --version`); the log says which version is used or
  that git information is not shown.
- Opening a folder (`MainWindow` → `GitStatusService.open`) follows its git status if git is available and the folder
  has `.git`. Other folders, and no folder, show nothing.
- The file explorer shows coloured bubbles after the names ([file-explorer.md](file-explorer.md)):
  - red: unversioned (untracked, not ignored);
  - green: added (new in the index, or the new path of a rename or copy);
  - blue: modified (changed, deleted or in conflict, staged or not);
  - orange: ignored (`.gitignore`, `.git/info/exclude`, global excludes).
  A file has at most one bubble. A folder has one per status found below it, in that order; an ignored folder, or one
  inside an ignored folder, shows only orange, and an untracked folder only red. Ignored files do not mark their
  parent folders. Clean files and folders have none.
- Updates by themselves: after changes in a folder the explorer has loaded, after F5/Refresh, and after changes in
  `.git` (`git add`, commits, checkouts in a terminal or another tool). `git status` runs in the background; the
  markers change when it is done.
- `GitStatusService.snapshot` holds the latest `GitStatusSnapshot` (`fileStatus`, `folderStatuses`), replaced only
  when something changed.
- Not yet implemented: branch name, diff or blame, git commands from the IDE, repositories in subfolders or above the
  opened folder.
