---
paths:
  - "modules/*/src/main/**/*.kt"
  - "ide/src/main/**/*.kt"
---

# Safety: the user's files, commands and secrets

Thoreau runs inside the user's projects. These are the conventions for new code, taken from the features that follow
them; a feature that deviates says why in its ADR. Known deviations are listed at the end. There are no shared helpers
for any of this: the named functions are patterns to copy (most are `private` or `internal`).

## Files
- Never overwrite or lose a file silently. Moves and renames never replace (no `REPLACE_EXISTING`); clashes are
  skipped after a question (ADR 0073). A name the user typed that is taken is refused ("already exists"); a name the
  app generates (copies `-copy`, `-copy2`, ... before the first extension, ADR 0092; scratch files) is created with
  `CREATE_NEW` and retried under the next name.
- Replacing an existing target the app picked (export, Convert to ▸) asks "Replace X?" with Cancel as the default,
  then writes a temp file and moves it with `ATOMIC_MOVE`, keeping the permissions (`writeReplacing` in
  `previews/.../FileExport.kt`, `writeAtomically` in `ide/.../conversions/AtomicWrite.kt`; ADR 0058, 0115). Editor
  Save writes in place by decision (ADR 0020).
- Unsaved editor content: an action that reads the file from disk either asks ("Save and <Verb>",
  "<Verb> Saved Version", Cancel; ADR 0089) or says it uses the saved file. Git operations that change files first
  ask through `EditorArea.saveBeforeGitChange` (Save, "<Action> Anyway", Cancel; ADR 0041, 0042).

## Destructive actions
- Deleting, removing, resetting, dropping, pruning, killing and force operations ask first. The text names what is
  lost and that it is permanent; Cancel is the default button and has the focus, so Enter keeps the data (copy
  `cancelFirst` in git-log's `RefDialogs.kt`, or the explorer's `DeleteConfirmation`). Reversible actions (start,
  stop, fetch, plain push) do not ask.
- Branch protection: force push and remote delete are refused for branches named `main` and `master`
  (`isProtectedBranch`; ADR 0049, 0078) and use `--force-with-lease` with the expected hash. Reword is refused for
  every commit on the default branch (`origin/HEAD` of each remote, else `main`/`master`; ADR 0100).

## Running what the project configures
- Code or tools the project configures that would run without an explicit click (on open, on a file change, on
  save: git status with its filters and hooks, Gradle's task list) register a `ProjectTrustCheck` and run only after
  `ProjectTrust.allows(id)` / `awaitAllows` (ADR 0053). An explicit Run by the user needs no trust check (ADR 0089).
  Reading or rendering project files (previews, parsers) is not running them. A new integration's ADR lists what it
  runs without a click.
- Values from the user or the project never become shell code. Background CLI calls (git, the Docker tab) get
  argument lists through `ProcessBuilder`, never a shell string; names starting with `-` are refused and git writes
  use `--end-of-options` (ADR 0041). Commands shown in a terminal (`$SHELL -l -i -c`, ADR 0087) quote every value
  (`ShellWords.quote` in `command-runs`; docker's `shellQuoted`); typed arguments are tokenized, never expanded.
- Background CLI calls are never interactive (`GIT_TERMINAL_PROMPT=0`, `GIT_EDITOR=false`, ssh `BatchMode`; ADR 0043)
  and have a timeout or a cancel; runs in a terminal are interactive by design (ADR 0087) and have Stop. Neither runs
  on the FX thread.

## Secrets
- Never log secrets, stdin, HTTP bodies, headers or query strings; name tasks without paths that could hold tokens
  (ADR 0060, 0061). Remote URLs can carry credentials: never show or log them (ADR 0076).
- Show secret values masked by default: `.env` values with a per-tab Show values toggle that is never stored
  (ADR 0030), HTTP auth headers always masked in the Request tab (ADR 0028), Docker environment tables with Show
  values (`docs/features/docker.md`, `docker-compose.md`). PEM views never show key material.
- Values that must not appear on a command line go into the terminal environment under private names (`SLUIDE_*`)
  that the shell expands (ADR 0120). Copying something that includes private values asks first (ADR 0116).
- Tools don't store pasted text (Codecs, Converting). Values remembered in `~/.sluide/projects` are plain text
  (owner-only only because `JsonFiles` writes through `createTempFile`); the feature page says they are plain text.
- A secret that is stored at all is opt-in per value ("Save password") and goes through `Secrets` (module `secrets`,
  ADR 0122): AES-GCM encrypted with a context naming where it belongs, one key in the OS store. The key is read only
  off the FX thread (a Keychain prompt can block), only "missing" creates a key, an unreadable value counts as nothing
  saved, and a disabled Save box (no store, access denied) neither saves nor removes what is saved (databases'
  `EntrySessions`, http-client's `SslContexts`). A module that saves values lists them for Settings → Secrets as a
  `SavedSecretSource` unless they are managed where they are used.

## Network
- Fetching from remotes, registries or the web is an explicit action (ADR 0043, 0076): no background fetch.
  Decided exceptions on open: Markdown and HTML previews load remote images and resources (ADR 0016, 0017, 0018);
  a trusted Gradle task-list load may download the wrapper's distribution (ADR 0085). A new exception needs an ADR.
- Local servers bind `127.0.0.1` with a token in the path and serve only the project folder, or the file's own folder
  when it is outside any project, without hidden files (ADR 0018).

## Known deviations (fix when touching them, or decide in an ADR)
- Enter confirms: `FxDockerDialogs.ask` (Remove, Kill, Prune), `ComposeView.confirm` ("Down and Remove Volumes"),
  http-client `EnvDialogs.confirm`, the Gradle stop question.
- Codecs and Converting "Save as…" write directly on the FX thread, without temp file or atomic move.
- Gradle's trust check runs only at window open (no `recheck`), so a wrapper that arrives or changes later loads
  without a new question (contradicts ADR 0085).
- `docker compose config` runs on attach and save without a trust check (ADR 0119); it follows `extends`, `env_file`
  and `include:` to any path, and remote `include:` entries reach the network on open (not decided anywhere).
- Plain-text storage is stated only on `docs/features/docker.md`, not on the Compose, Dockerfile or shell-script pages.
