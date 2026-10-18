# 0052. Hardening how git is started: no repository variables

- Status: accepted
- Date: 2026-10-18

## Context
`GitCli` ([ADR 0013](0013-git-status.md)) runs git with Thoreau's own environment, and it runs git by itself:
`git status` as soon as a folder with `.git` is opened, and other reads for the git log and the editor. A safety review
of the git modules found that this runs git differently from what the user expects:

- **Variables that point git at another repository.** `GIT_DIR`, `GIT_WORK_TREE`, `GIT_INDEX_FILE` and their kin
  override where git finds the repository, whatever folder it runs in. Thoreau started from a git hook, `git rebase -x`
  or a shell that exported them (a dotfiles setup) would pass them on, so every command, a hard reset too, would act on
  that repository instead of the opened one. Git itself clears these variables when it switches repositories, e.g. for
  submodules; `git rev-parse --local-env-vars` lists them (15 with git 2.53). Two of the listed ones git keeps when it
  switches (checked with git 2.53, `git submodule foreach`): `GIT_CONFIG_PARAMETERS` and `GIT_CONFIG_COUNT`, the
  user's configuration given in the environment (`git -c` of an outer git; `GIT_CONFIG_COUNT` with its keys and
  values, e.g. `safe.directory` in a container).

## Decision
- **Repository variables are removed from git's environment** (`GitCli.environment`, `repositoryVariables`): once, when
  `GitCli` is created, every variable `git rev-parse --local-env-vars` lists, and those git 2.53 lists
  (`KNOWN_REPOSITORY_VARIABLES`) in case a git cannot be asked; as for git, not `GIT_CONFIG_PARAMETERS` and
  `GIT_CONFIG_COUNT`. Git is asked only if one of the known ones is set, so normally no process runs for it; it is
  given none of them, and has 3 s. The log names the variables removed.
  Rejected: a fixed list alone, which a newer git could outgrow; removing the configuration variables too, which would
  take the user's own configuration away (a `safe.directory` of a container would make every read fail).

## Consequences
- An IDE started from a hook or with an exported `GIT_DIR` works on the opened folder; configuration given in the
  environment still reaches git, as it does for git's submodules.
- Tests: scripts that stand in for git start with `FAKE_GIT_START`, which fails the question `GitCli` asks on its own,
  so that the scripts see only the commands under test.
