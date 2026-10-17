# 0043. Git log fetches each remote with the git CLI, non-interactive and cancellable

- Status: accepted
- Date: 2026-10-17

## Context
The git log changes branches, tags and commits ([ADR 0041](0041-git-write-operations.md),
[ADR 0042](0042-git-log-commit-operations.md)) but left everything that needs the network for later. IntelliJ's log
fetches all remotes from its toolbar and one remote from the branches pane. A fetch differs from the other operations:
it talks to servers (credentials, ssh host keys, passphrases), may take minutes, and is often stopped by the user. It
changes no files and no local branch: git updates the remote-tracking refs and tags one by one, so stopping it halfway
leaves some refs updated and the repository consistent. Thoreau may be started from a shell, whose terminal ssh would
use to ask for a passphrase or a host key (it reads `/dev/tty`, not its standard input), where nobody looks.
Git 2.53 behaves as follows (checked in tests):
- `git fetch` follows tags by default: a tag pointing into the fetched history arrives, one pointing elsewhere does not,
  and an existing local tag that differs on the server is left alone. Only with an explicit tag refspec (e.g.
  `remote.<name>.tagOpt=--tags`) does git refuse to move such a tag: exit 1, "would clobber existing tag", while the
  other refs are updated.
- `--prune` deletes the remote-tracking refs of branches deleted on the server, under the remote's refspec
  (`refs/remotes/<remote>/`), not tags (unless the user configured `fetch.pruneTags`/`remote.<name>.pruneTags`).
- `git fetch` keeps `refs/remotes/<remote>/HEAD` (a symbolic ref) up to date.

## Decision
- **One remote at a time:** `GitRemotes` (`modules/git`, a singleton bean) runs
  `git fetch --prune --recurse-submodules=no --end-of-options <remote>` for each remote in git's order (`git remote`;
  git refuses line breaks in remote names), leaving out those with `remote.<name>.skipFetchAll` true as
  `git fetch --all` does (read with `git config -z --type=bool --get-regexp`; the last value counts, as for git, so a
  repository's `false` overrides a global `true`). Not `git fetch --all`: it goes on after
  a failing remote too, but gives one exit value and one interleaved output for all of them, so which remote failed
  could only be parsed from git's (translated) messages. One process per remote gives each failure with its remote's
  name, what each changed, the remote being fetched in the header, and a cancel that skips the rest. A remote name the
  JVM cannot pass, or starting with `-`, fails for that remote without running git (the `refusal` of ADR 0041).
- **Submodules are not fetched** (`--recurse-submodules=no`): the log shows the repository's own refs, and fetching
  every submodule could take far longer than the user expects from "Fetch".
- **Tags: git's default only.** No `--tags`, which would make `--prune` delete local tags that are not on the server,
  and no `--prune-tags`, which deletes unpushed local tags. A tag that git refuses to move (with the user's tag refspec)
  fails that remote with git's message, while the summary counts what did change. The user's configuration applies as
  in a terminal (`fetch.pruneTags`, `remote.<name>.tagOpt`, refspecs, `fetch.writeCommitGraph`).
- **Never interactive:** `GIT_TERMINAL_PROMPT=0` (as for every git command of Thoreau) keeps git from asking for a user
  name or password; credential helpers keep working. Ssh runs with `GIT_SSH_COMMAND="ssh -o BatchMode=yes"`, so it
  never waits for a passphrase, password or host-key answer on a terminal; it fails at once with ssh's message instead.
  The variable is set only where git would run plain `ssh` anyway: not if `GIT_SSH_COMMAND`, `GIT_SSH` or
  `GIT_SSH_VARIANT` is in the environment, nor if `core.sshCommand` or `ssh.variant` is configured (one
  `git config --get-regexp` before each fetch, which also sees `-c` options in `GIT_CONFIG_PARAMETERS`): the variable
  would override the user's command, and a variant such as `plink` would not take `-o BatchMode=yes`. If that
  configuration cannot be read, batch mode is used: never waiting on a terminal matters more. A pure function
  (`fetchEnvironment`) decides it.
- **Batch mode also disables `SSH_ASKPASS`:** a key needs an ssh agent (or no passphrase), a host needs to be in
  `known_hosts` already; otherwise the fetch fails with ssh's message ("Permission denied (publickey)", "Host key
  verification failed"), which says what to do in a terminal. Https remotes are unaffected (credential helpers).
- **Timeout:** 10 minutes per remote (`GitCli.FETCH_TIMEOUT`); then git and what it started are stopped as for every
  write (SIGTERM, killed after 2 s) and the remote fails with "git did not finish within 10 min."
- **Stopping gives git's children the grace period too** (for every write, on a timeout or a cancel): git ends on
  SIGTERM at once, but what it started (a hook, a git a hook runs, a remote helper) may take a moment to clean up, e.g.
  remove its `index.lock`. The descendants are read before the SIGTERM; all of them, git included, share one deadline
  of 2 s, and those still running then are killed, also when they outlived git and are no longer its descendants.
- **Cancel, only for fetch:** while a fetch runs the header shows Cancel next to its progress ("Fetching origin…").
  `GitCli`'s write runner takes an optional `GitCancellation` (thread-safe, cancelled once and for good): it waits for
  git's end or the cancellation, whichever comes first, and on a cancel stops git exactly as on a timeout, its
  descendants (ssh, remote helpers, an upload pack) included. Cancelled before git starts, git is not run; if git has
  ended by the time the cancel is seen, its result stands. `RefWriter.cancel()` (FX thread, never blocks) acts only on
  an operation that says it is `cancellable`; the remaining remotes are skipped. Opening another folder and closing the
  window cancel a running fetch too, unlike any other write, which is never interrupted (a checkout stopped halfway
  would leave a half-switched working tree, ADR 0041). Stopping a fetch is harmless: refs git updated stay updated,
  the others stay as they were, on SIGTERM git removes its lock files.
- **Summary from the refs, not from git's (translated) output:** before and after each remote, the refs its fetch
  refspecs write and `refs/tags/` are read (`for-each-ref`, symbolic refs left out) and compared: new, updated and
  pruned branches (any ref but a tag, e.g. `refs/remotes/origin/*`, a mirror's prefix or Gerrit's `refs/changes/`),
  new, updated and pruned tags, summed over the remotes, also those that failed or were
  cancelled. The header says "Fetched origin: 1 new branch, 2 branches updated, 1 branch pruned, 3 new tags.",
  "Fetched 2 remotes: nothing new.", "Fetched 1 of 2 remotes: …" when others failed, "Fetched origin with errors: …"
  when git failed but changed refs, "Fetch cancelled." with "Fetched so far: …", "No remotes to fetch.". A failure
  shows the error dialog "Fetch Failed" with "origin could not be fetched." (or "2 remotes could not be fetched: a,
  b.") and git's messages, each under its remote's name when there are several; also after a cancel, for the remotes
  that failed before it. The cancel itself shows no dialog.
- **Where a refspec writes:** the right-hand sides of `remote.<name>.fetch` (`git config --get-all`), without `+`; a
  glob up to its `*` is a prefix whose `*` matches any rest (also across `/`, as in a refspec), anything else the one
  ref; negative refspecs (`^…`) and those without a destination (only `FETCH_HEAD`) write nothing. Without any
  destination, `refs/remotes/<remote>/`. `for-each-ref` matches whole path components, so the folders are read and
  filtered. A `*` in the middle (`refs/a/*/b`) counts everything below `refs/a/`.
- **Where:** a Fetch button (down arrow, "Fetch All Remotes") in the log's header next to Refresh, also while the log is
  collapsed, and Fetch in the branches pane's context menu on a remote, a folder of a remote or a remote branch (that
  remote only). The pane's tree splits remote branch names at their first `/`
  ([ADR 0040](0040-git-log-branches-pane.md)), but which remote a node belongs to is matched against the names of all
  remotes (`git remote`, also those with `skipFetchAll`, fetched when asked for, as `git fetch <remote>` does): the
  longest remote whose name is the node's (a remote or folder below Remote, `team/a`) or starts the node's name followed
  by `/` (a remote branch `team/a/main`). Without one (a remote that was removed while its remote branches stay, or
  `team` when only `team/a` is a remote), Fetch is disabled. Fetch runs through the `RefWriter` on `sluide-git-write`
  like every other operation, so meanwhile they are all disabled; it changes no files, so no unsaved editors are asked
  about. The log is refreshed afterwards, also after a failure or cancel.
- **No remotes:** the names of all remotes are read with every first page of the log, on `sluide-git-log` with the
  refs and what git is in the middle of; the header's Fetch is disabled once that found none. Before the log was first
  expanded nothing is read (ADR 0035), so Fetch is enabled then; a fetch reads the remotes to fetch itself when it runs
  and says "No remotes to fetch." if there are none (also when every remote has `skipFetchAll`).

## Consequences
- A fetch never hangs on a prompt nobody sees; ssh keys with a passphrase need an ssh agent, unknown hosts a first
  connection from a terminal.
- A failing remote does not hide the result of the others; its error names it.
- Local tags are never deleted by Thoreau's fetch, and tags moved on the server are not moved locally (unless the user's
  configuration says so).
- The JVM may end without stopping a fetch when the application quits while one runs: the cancel is acted upon on the
  write thread, a daemon thread.
- Limits: no "Update" of local branches (fast-forward of tracking branches), no pull or push (push:
  [ADR 0049](0049-push.md)), no fetch of submodules,
  no progress percentage (git writes none without a terminal; the header names the remote being fetched).
