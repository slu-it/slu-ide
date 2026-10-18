# 0053. Project trust: tools check what a folder would make them run, the user decides once

- Status: accepted
- Date: 2026-10-18

## Context
Thoreau runs tools on its own in an opened folder. Git is one: `git status` runs on open and on every change, and the
editor's diff runs `cat-file --filters`. Through its own configuration, a repository can make git run commands:
- a `core.fsmonitor` hook, which [ADR 0052](0052-hardening-how-git-is-started.md) keeps from running on reads;
- clean and smudge filters on status and diff;
- hooks and `core.sshCommand` on writes.

`git clone` copies neither `.git/config` nor hooks, so a cloned repository carries none of this. A folder unpacked from
an archive or copied from elsewhere does. Git's `safe.directory` refuses only repositories owned by another user. More
tools that read a folder's contents may come, and they will have the same problem.

VS Code asks about every folder it opens for the first time ("workspace trust"). Its tasks, extensions and debuggers
take commands from tracked files, so a clone can carry them there. Asking about every clone would teach users to trust
without reading.

## Decision
- **Check first, ask only about what was found.** A new foundation module, `project-trust`, holds the extension point.
  - Every tool that runs commands on its own implements `ProjectTrustCheck` as a Spring bean. It has an `id` (`git`), a
    `name` (`Git`), and `check(folder)`, which returns `TrustFinding`s or `null` if it could not check. A finding has:
    - a text: what it is;
    - a note: when it runs;
    - a fingerprint: what identifies it, changing when what would run changes.
  - The window-scoped `ProjectTrust` gets every such bean as a `List` (Spring collection injection). `open(folder)` runs
    all checks on a thread of its own (`sluide-trust`) and combines them into one state and one fingerprint. Opening
    another folder cancels the checks still running. The fingerprint is SHA-256 over the sorted SHA-256s of the
    findings, each with its check's id, hashed one by one so that no finding's text can pass for two:
    - a check that could not check counts as a finding of its own;
    - a check that found nothing adds nothing, so adding a tool that finds nothing keeps earlier decisions.
  - A new tool adds its check as a bean and asks `allows(id)` before it runs anything; nothing else changes.
- **States:**
  - Nothing found: `Trusted`, without asking or storing anything.
  - A decision about exactly this fingerprint, stored or made while the folder is open: that decision, `Trusted` or
    `Untrusted`.
  - Otherwise: `Ask`, until the user decides. `Ask` is marked as a change when a check run again (`recheck`) found
    something new once the folder was settled (trusted, also because nothing was found, or not). An `Ask` from opening
    stays one.
  - The user decides about the state that was shown (`decide(shown, trusted)`). An answer about findings that a recheck
    has replaced since is ignored, so nothing the user did not see is trusted.
  - `Checking` while the checks run, `Closed` without a folder.
- **Off per tool:** once the checks have run, a tool may run if the folder is trusted or its own check found nothing.
  - `allows(id)` answers at once and says no while the checks run.
  - `awaitAllows(id)` first waits for them, for tools asking from their own thread, at most 30 s (no then). A check must
    end in a few seconds, or when its thread is interrupted.
  - A tool's findings never turn off another tool, and a tool without a check runs once the checks have run.
- **Decisions are stored per folder with their fingerprint** in `~/.sluide/project-trust.properties` (`TrustedProjects`,
  in the same pattern as `RecentProjects`, [ADR 0034](0034-project-picker.md)): `trusted:<fingerprint>` or
  `untrusted:<fingerprint>`.
  - Other findings than those decided about ask again.
  - A decision about a check that could not check is never stored; it lasts while the folder is open.
    (`decide(…, remember = false)` keeps any decision only that long.)
- **Git's check** (`GitTrustCheck`, id `git`, in `modules/git`) reads what the repository's own configuration says.
  - **No repository:** a folder without `.git`, or no git installed, finds nothing. A repository git refuses (owned
    by another user, `safe.directory`; a `.git` file pointing nowhere) could not be checked: `git rev-parse
    --git-path hooks` fails for it, while `git config --list` would list only the user's configuration.
  - **How it reads:** `git config --list --includes --show-scope --show-origin -z` (`GitCli.configEntries`), which runs
    nothing (checked with git 2.53, with a `core.fsmonitor` command that `git status` runs). `--includes` follows
    `include` and `includeIf` files, which `--local` alone would not; their entries keep the scope of the file that
    includes them.
  - **Which entries count:** only the `local` and `worktree` scopes. `system`, `global` and `command` (the
    environment) are the user's own. `--show-scope` needs git 2.26 or later; with an older git every repository could
    not be checked and asks each time it is opened.
  - **Allow-list, not deny-list:** every entry is reported, with where it is, unless it is known to be harmless.
    - Harmless keys are what `clone`, `init`, `git lfs install`, `git sparse-checkout` and everyday commands write
      (`core.filemode`, `remote.*.url`/`fetch`, `branch.*.merge`, `user.*`, `pull.rebase`, …).
    - So are keys whose command Thoreau's git never runs: aliases, pagers (its output goes to a pipe) and
      `core.editor` (it sets `GIT_EDITOR`).
    - `core.fsmonitor` is harmless as a boolean (git's own daemon; compared as git compares booleans, ignoring case,
      not spaces), and `filter.lfs.*` with exactly the values `git lfs install` writes.
    - `core.worktree` is harmless only where git's work tree is the checked folder itself (`git rev-parse
      --show-toplevel`; submodules have one pointing at their folder). Elsewhere it is reported: git would read and
      write the files there, so a folder with `core.worktree = ../..` would have a hard reset or checkout overwrite
      the user's `~/.bashrc` (checked with git 2.53).
    - Not harmless though written by git: the keys of a partial clone (`remote.*.promisor`,
      `extensions.partialclone`). With them, reads fetch missing objects from the remote's URL by themselves (checked
      with git 2.53: `cat-file` started ssh to the URL), so a crafted repository could make Thoreau connect wherever
      it wants with the user's keys. A partial clone asks once.
    - Git keeps adding keys that run commands (`core.fsmonitor` became one in 2017), so a list of known dangerous keys
      would miss the next one. Known ones get a note on when they run: fsmonitor, hooks path, ssh command, proxy,
      askpass, credential helpers, filters, textconv and diff commands, merge drivers, gpg programs, upload/receive
      pack, remote helpers, protocol permissions, includes.
    - Keys are compared with their subsection as a wildcard (`remote.*.url`); the subsection's case is kept by git.
  - **Hooks:** files in the hooks folder (`git rev-parse --git-path hooks`, which follows `core.hooksPath`) that git
    could run: executable, not `.sample`, not hidden. Each is fingerprinted by its content, so a changed hook asks
    again. A `core.hooksPath` from the user's own configuration is the user's, and its hooks are not reported, unless
    it resolves inside the repository (a relative path), whose own files they are.
  - **Submodules:** `git status` looks into every submodule with a work tree (a gitlink in the index whose folder has
    `.git`, also without `.gitmodules`) and runs it with the submodule's own configuration, its filters and
    `core.fsmonitor` too (checked with git 2.53; ADR 0052's guard covers only the opened repository). Their
    configuration and hooks are checked the same way (`git ls-files --stage -z`, mode 160000), shown as
    `submodule lib: …`, down to 5 levels and 50 repositories in all; with more, the folder could not be checked.
    Paths of the index outside the repository are skipped.
  - **Fingerprint of a setting:** origin, key and value (an entry without `=` differs from one with any value). Not the
    content of a script a setting names (`filter.x.clean = ./clean.sh`): changing it does not ask again. An
    `includeIf` file that does not apply now (another branch, `onbranch:`) is not listed until it does; then the
    check run again on the change in `.git` finds it.
- **Git's tools run only while `allows("git")`** (`GitStatusService`):
  - `git status` waits for the checks (`awaitAllows`) and does not run without; the snapshot stays empty, so the
    explorer shows no markers, editors no changes, and the http client no warning that git would commit a private
    environment file. The editor's diff (`diff`) asks `allows`.
  - On every change noticed directly in `.git` (a config written, a checkout, a commit, `FETCH_HEAD`), and after every
    operation of the git log (`GitLogView.afterWrite`, which `MainWindow` sets to `GitStatusService.gitChanged`), git's
    check runs again (`recheck`) before the next status. Only if it still allows git does `gitFolderChanged` count the
    change; the git log refreshes on that, also after its own operations, so it does not read with what a change made
    git run. Until the check ran again, `diff` runs no git either. A checkout that makes an `includeIf onbranch:` file
    apply, or changes a hooks folder in the work tree (`.husky`), is found this way.
  - Not covered: what runs git between a change in `.git` and the end of its check, the watch's delay and the check
    itself (usually well under a second): a click in the git log right after a checkout in a terminal, or an editor
    reloading a changed file, and with it its diff, before the watch has noticed the change. A changed file in `.git/hooks` alone is not
    noticed (only entries directly in `.git` are watched) until the next change of `.git`; a repository with a `.git`
    file (worktrees, submodules) is not watched, so it is checked only when opened.
  - `MainWindow` opens the repository in the git log only while git is allowed, and reads the status again when git
    becomes allowed. The log area itself stays below the editor for a repository, so that the layout does not change
    when the checks end; while git is off its header says "Git is off until the folder is trusted.", and Git → Commit…
    and Push… are disabled. When a change turns git off while the log has the repository open, the log closes it as
    for another folder (`GitLogView.open(null)`): the Commit tab with its draft and the file histories are closed, a
    running fetch is stopped, any other operation finishes and its outcome is dropped
    ([ADR 0041](0041-git-write-operations.md)).

## Consequences
- Opening a normal clone asks nothing. A folder whose contents would make a tool run commands is asked about once, with
  what was found, and asked about again when that changes.
- A tool that will run commands the folder defines adds a `ProjectTrustCheck`. Its findings join the same question and
  the same stored decision.
- A repository with settings not on the allow-list asks once, also when they are harmless; the allow-list grows with
  what proves common.
