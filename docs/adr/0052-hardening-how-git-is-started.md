# 0052. Hardening how git is started: no repository variables, no fsmonitor hooks on reads

- Status: accepted
- Date: 2026-10-18

## Context
`GitCli` ([ADR 0013](0013-git-status.md)) runs git with Thoreau's own environment, and it runs git by itself:
`git status` as soon as a folder with `.git` is opened, and other reads for the git log and the editor. A safety review
of the git modules found two ways in which that runs git differently from what the user expects:

- **Variables that point git at another repository.** `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and their kin
  override where git finds the repository, whatever folder it runs in. Thoreau started from a git hook, `git rebase -x`
  or a shell that exported them (a dotfiles setup) would pass them on, so every command, a hard reset too, would act on
  that repository instead of the opened one. Git itself clears these variables when it switches repositories, e.g. for
  submodules; `git rev-parse --local-env-vars` lists them (15 with git 2.53). Two of the listed ones git keeps when it
  switches (checked with git 2.53, `git submodule foreach`): `GIT_CONFIG_PARAMETERS` and `GIT_CONFIG_COUNT`, the
  user's configuration given in the environment (`git -c` of an outer git; `GIT_CONFIG_COUNT` with its keys and
  values, e.g. `safe.directory` in a container).
- **A `core.fsmonitor` hook runs on reads.** When `core.fsmonitor` is a command instead of a boolean, git runs it
  whenever it refreshes the index, also for `git status` with `--no-optional-locks` (checked with git 2.53). A
  repository's own `.git/config` can set it, so opening a downloaded repository (a zip with its `.git`) would run
  whatever it says, without the user doing anything. `safe.directory` does not help: it only refuses repositories owned
  by other users.

## Decision
- **Repository variables are removed from git's environment** (`GitCli.environment`, `repositoryVariables`): once, when
  `GitCli` is created, every variable `git rev-parse --local-env-vars` lists, and those git 2.53 lists
  (`KNOWN_REPOSITORY_VARIABLES`) in case a git cannot be asked; as for git, not `GIT_CONFIG_PARAMETERS` and
  `GIT_CONFIG_COUNT`. Git is asked only if one of the known ones is set, so normally no process runs for it; it is
  given none of them, and has 3 s. The log names the variables removed.
  Rejected: a fixed list alone, which a newer git could outgrow; removing the configuration variables too, which would
  take the user's own configuration away (a `safe.directory` of a container would make every read fail).
- **Reads run no `core.fsmonitor` hook** (`GitCli.fsmonitorGuard`): before a read in a folder, `git config --type=bool
  --get core.fsmonitor` (which runs nothing, and sees `include` and `includeIf` files) tells a boolean or unset value
  (exit 0 or 1) from a command (exit 128, "bad boolean config value"). For a command the read gets
  `-c core.fsmonitor=false`; if the value cannot be read (e.g. it takes longer than 3 s), too. Git's own daemon
  (`true`) is kept, as it runs nothing of the repository's and keeps `git status` fast in large repositories.
  - The answer is kept per folder while the content of its `.git/config` (or of a `.git` file, a linked worktree) is
    the same, by its SHA-256: so a changed setting, or another repository extracted where one was, is read again. Not
    times or inodes, which an archive restores and a file system reuses. Not kept: an answer for a folder without
    `.git` (one may be extracted there) and a read that failed.
  - `git diff --no-index` in a temporary folder is not guarded: without an index there is no fsmonitor.
  - Writes are not guarded: the user starts them, and they run the repository's hooks anyway.
  Rejected: always `core.fsmonitor=false` (repositories using git's daemon would lose its speed in Thoreau); a trust
  prompt per folder as VS Code has (a feature of its own; see below).

## Consequences
- An IDE started from a hook or with an exported `GIT_DIR` works on the opened folder; configuration given in the
  environment still reaches git, as it does for git's submodules.
- A downloaded repository cannot run code through `core.fsmonitor` by being opened. A hook the user configured
  globally (Watchman's `fsmonitor-watchman`) is not run by Thoreau's reads either; they are slower in very large
  repositories then, and git's daemon (`core.fsmonitor=true`) is the fast choice that stays.
- Still open, automatic: a repository's filters. `git status` runs the clean filter (`filter.<name>.clean` of its
  configuration, for the paths `.gitattributes` gives it) for files whose index entry does not match them, which is
  every file of a repository copied to a new place (checked with git 2.53); the editor's diff runs the smudge filter
  (`cat-file --filters`). Also open, on what the user starts: hooks and `core.sshCommand` of an untrusted repository
  when committing, checking out or fetching. A folder trust prompt would be the next step for both.
- Tests: scripts that stand in for git start with `FAKE_GIT_START`, which fails the two questions `GitCli` asks on its
  own, so that the scripts see only the commands under test.
