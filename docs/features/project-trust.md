# Project Trust

**Modules:** `modules/project-trust` (package `dev.sluide.projecttrust`), git's check in `modules/git`
(`GitTrustCheck`), the dialog and bar in `ide` (`WindowTrust`); decision in [ADR 0053](../adr/0053-project-trust.md)

- Thoreau runs some tools by itself in an opened folder: git's status for the explorer's markers and the editor's
  changes, and the git log. A folder can make such a tool run commands of its own. For git that is its configuration
  (`.git/config` and the files it includes), its hooks, and the same in its submodules. A cloned repository carries
  none of this; a folder unpacked from an archive or copied from elsewhere can.
- **When a folder is opened,** each tool's check looks at what the folder would make it run, without running
  anything. Nothing found: nothing is asked, and the tools run as usual. Most clones are like this.
- **Something found:** a dialog "Trust Folder?" lists it per tool, each with where it is and when it runs:
  ```
  Git
    core.fsmonitor = ./watch.sh (.git/config)
        runs on every git status
    hook pre-commit (.git/hooks/pre-commit)
        runs on commit
    something.new = x (.git/config)
        not known to Thoreau
  ```
  - **Trust:** the tools run as usual, with what the folder defines.
  - **Keep Off** (the default; also Esc): the tools with findings stay off for the folder. For git: no markers, no
    changes in editors, an empty git log. A bar under the menu bar says so, with Review… to decide again.
- **Remembered** per folder for exactly what was shown, in the project's JSON file (field `trust`, [ADR 0090](../adr/0090-per-project-metadata-store.md);
  migrated from `project-trust.properties`): opened again with the same findings, nothing is asked. Other findings
  ask again. Removing the project from the picker forgets the decision.
- **While working:** whenever `.git` changes in what git's check reads (its configuration and included files, hooks,
  the index, `HEAD`, a submodule's `.git`: a commit, a checkout, a setting written by a tool or in a terminal) and after
  every operation of the git log, git's check runs again before git; other changes of `.git` (new objects, reflogs)
  do not run it ([ADR 0062](../adr/0062-git-concurrency.md)). If it finds something new, git goes off and the bar says
  "Git is off: what this folder makes it run changed." without a dialog; Review… asks.
- **What git's check finds:** every setting of the repository's own configuration that is not known to be harmless
  (what `git clone`, `git init`, `git lfs install` and everyday commands write is). Settings known to run commands
  (filters, fsmonitor, hooks path, ssh command, credential helpers, …) say when. Settings of the user's own
  configuration (`~/.gitconfig`) are never reported.
- **Off while working:** the git log closes the repository as for another folder (the Commit tab's draft is lost, a
  fetch is stopped); its header says "Git is off until the folder is trusted.", and Git → Commit…, Push…, Pull…, Stash
  Changes… and Pop Latest Stash are disabled. The http client's warning about private environment files git would commit
  is silent too.
- **Not noticed until the next change of `.git`:** a hook edited in `.git/hooks`. Worktrees and submodules opened as
  folders (with a `.git` file) are checked only when opened.
