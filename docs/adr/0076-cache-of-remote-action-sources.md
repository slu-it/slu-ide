# 0076. A git cache of remote action sources for the workflow graph

- Status: accepted
- Date: 2026-10-23

## Context
The workflow graph ([ADR 0075](0075-github-actions-workflow-graph.md)) follows local `uses:` references; remote ones
(`actions/checkout@v6`, `org/repo/.github/workflows/x.yml@main`) stayed black boxes. Following them means reading a
file of another repository at a ref. The user wants this for private repositories and GitHub Enterprise Server (GHES)
too, without Thoreau handling tokens: whatever lets the user's git fetch should be enough. Opening a file must not
contact a server on its own, and nothing a project defines may run.

## Decision
**(a) A cache of bare repositories, filled with the git command line** (`RemoteSourceCache`, module `git`, a
singleton bean):
- **Layout:** `~/.sluide/cache/git/<host>/<owner>/<name>.git` (host lower-case, `:` as `_`), never in a project
  folder; every command but `init --bare` with `--git-dir`, all with `LC_ALL=C` (so that failures can be told
  apart) and without the fsmonitor guard of [ADR 0052](0052-hardening-how-git-is-started.md), as a bare repository
  has no work tree. A folder is created by `init --bare --template=` into `<name>.git.new`, configured
  (`core.hooksPath=/dev/null`, `gc.auto=0`, `maintenance.auto=false`, remote `sluide-cache` with `promisor=true` and
  `partialclonefilter=blob:none`, `protocol.allow=never` with https and ssh `always`) and renamed into place
  atomically.
- **The remote** is named `sluide-cache`, so that a global `remote.origin.url` cannot win; its URL is never stored but
  passed per command (`-c remote.sluide-cache.url=…`). **The URL must match** the folder (`RemoteRepository`): https
  without credentials or query, `ssh://` or `user@host:path`; the host equal ignoring case (https with its port, ssh by
  hostname only), the path `owner/name[.git][/]` ignoring case; no subgroups. Refused: `file://` (allowed for tests
  only), `ext::`, `git://`, `http://`, local paths, a leading `-` ("unsupported repository URL", "repository URL does
  not match"). A folder thus never holds another repository's commits.
- **Protocols:** the cache sets `GIT_ALLOW_PROTOCOL` itself: `https:ssh` with the network, empty without. It beats an
  inherited value and every `protocol.*` setting, also an `insteadOf` rewrite to `file://`; the `-c protocol.*`
  settings are an extra layer.
- **Nothing asks:** ssh runs in batch mode (`GIT_SSH_COMMAND="ssh -o BatchMode=yes"`, which replaces the user's
  `core.sshCommand` for the cache), `GIT_TERMINAL_PROMPT=0`, `GIT_ASKPASS=""` (no askpass program),
  `SSH_ASKPASS_REQUIRE=never` (none for ssh either), `GCM_INTERACTIVE=never`. The exception: if the user's
  environment sets `GIT_SSH_COMMAND`, `GIT_SSH` or `GIT_SSH_VARIANT`, their ssh runs as they chose, without batch
  mode; it can still read a passphrase from the terminal Thoreau was started from, if there is one, until the
  network timeout.
- **Fetch:** `fetch --depth=1 --filter=blob:none --no-tags --recurse-submodules=no --end-of-options sluide-cache
  <ref>`, then `rev-parse FETCH_HEAD^{commit}`: one commit's trees, no file contents (a partial clone). A server that
  ignores the filter sends the files too (git warns); there is no retry without it. A full commit SHA fetched before
  is answered from the cache without the network. Fetches of one repository take turns, in the JVM (a lock per
  repository) and with other running apps (a file lock `<name>.git.lock`); waiting for either can be cancelled.
  Reads take no lock. A cancelled fetch stores nothing. A folder a failed or cancelled fetch created (or created anew)
  is deleted again under the lock; the lock file stays.
- **Storage of refs:** `sluide-refs.properties` in the folder (`<ref>=<commit> <epoch ms>`, written to a temporary
  file and moved atomically), and `refs/sluide/cache/<sha256 of the ref>` keeps the commit from being pruned.
- **Read:** `rev-parse` of the commit (without the network a missing commit is "not cached"; with it the commit is
  fetched shallow), `ls-tree -z <commit> -- <path>` taken literally (no blob: missing), `cat-file blob` of at most
  1 MiB ("over 1 MB"), decoded as UTF-8 with replacement characters. With the network allowed, git fetches a missing
  file content lazily from the promisor remote.
- **Offline guarantee:** a read without the network passes no URL, allows no protocol (`GIT_ALLOW_PROTOCOL=""`,
  `protocol.*.allow=never`) and sets `GIT_NO_LAZY_FETCH=1`: git 2.44 and later start no lazy fetch at all, older git's
  attempt fails at once, which reads as "not cached" (or as a failure if `cat-file -e` finds the object after all). A
  test pins it with a poisoned configuration and a server hook that logs every connection.
- **Recovery:** only after a failed fetch, and only if the local `rev-parse --git-dir` exits with 128 (so that a
  server's message cannot trigger it), the folder is moved to `.old`, deleted and created again, and the fetch is
  tried once more. Deletes stay below `<cache>/<host>/`.
- **Bounds:** 2 minutes for a command that uses the network, 10 s for a local one; stderr is kept up to 64 KB. A
  download is bounded by its timeout only.
- **Failure reasons** are short and for the user, classified line by line from the openings of git's and ssh's
  messages (lines of the server, `remote:`, are ignored): "ref not found", "network error", "repository not found or
  no access" (HTTP 4xx, failed authentication, host key verification, …), "timed out", "cancelled", "fetch failed",
  "could not read the file", "over 1 MB", "cache folder not writable", "git is not available", "git could not be
  run", "'<ref>' is no commit" and the refusals above.
- **Logging:** one INFO (or WARNING) line per fetch with the ref, repository, commit or reason and duration; git's full
  stderr goes only to the git command log. `GitRemotes.url` may return credentials; they are never logged or shown.

**(b) Drawings read the cache only; the network is used only on Fetch References.** Opening or editing a workflow
never contacts a server: `RemoteSources.reader(network = false)` reads what is cached, and a reference that is not is
drawn `not fetched`. Only the preview's Fetch References button draws the graph again with
`reader(network = true)`. As the cache runs nothing a project defines (its own folders, hooks off, no project
configuration), it does not depend on the folder's trust ([ADR 0053](0053-project-trust.md)); only reading the
project's origin does.

**(c) Which host, and how it is reached** (`WindowWorkflowSources` and `RemoteHosts.kt` in `ide`, registered as the
window service `RemoteSources`, [ADR 0070](0070-window-services.md)):
- A reference `owner/repo` is looked for on the project's origin host first, if the folder's trust allows git, the
  origin's URL can be read (`GitRemotes.url`, `remote.origin.url`, at most once per reader) and its host is not
  github.com: GHES, which may also serve github.com's actions through GitHub Connect. Then on github.com. Readers run
  off the FX thread, so the trust is asked with `awaitAllows("git", cancelled)`, which waits for the checks of a folder
  just opened (a graph restored with the window would otherwise miss its origin) until the reader is cancelled, its
  view edited or closed; a reader cancelled while it waits reads nothing. Each view parses on a thread of its own
  (`WorkflowPreview`, shut down with the view), so a view that waits holds up no other view's graph.
- Each host is reached as the origin is, if it is that host: ssh with the origin's user and port
  (`git@host:owner/repo.git`, `ssh://user@host:port/owner/repo.git`), otherwise https (with the origin's https port;
  an `http://` origin is reached by https on the default port). github.com with an origin elsewhere, or none, is
  reached by https. Only the scheme, ssh user, host and port are taken from the origin, never its credentials, path or
  query. An origin that is not `https://`, `http://`, `ssh://` or `[user@]host:path` of a plain host name counts as
  none, so do `git+ssh://` and `ssh+git://`, an ssh user with a password, and IPv6 addresses.
- Owner and name are lower-cased, as GitHub ignores their case, so that one repository has one cache folder.
- Without the network the first host that has the ref cached answers. With it, each host, repository and ref is
  fetched at most once per reader (a branch or tag again with every Fetch References, a full commit SHA only if not
  cached); if the origin's host fails, github.com is tried; if both fail, the reason says which host failed how. A
  host that could not be reached ("network error", "timed out") is not asked again by that reader, so a GHES host off
  the VPN costs one timeout per Fetch References, not one per reference; its cached commits are read without the
  network. A virtual thread checks every 200 ms whether the drawing was cancelled and then stops the running git
  (`GitCancellation`).
- A branch or tag shows the time it was fetched (`as of`), a full commit SHA none.
  (Amended 2026-10-23: the graph no longer shows the fetch date, and `RemoteText.Found` no longer carries it.)

**(d) The graph** (`github-actions`, `previews`; details in
[the feature page](../features/github-actions-graph.md#remote-uses)): remote actions and reusable workflows are read
through `RemoteReader` and drawn nested like local ones, under `owner/repo[/path]@ref`. Inside a remote file, a
reusable workflow's `./.github/workflows/…` is read from the same repository and ref, and a step's `./…` refers to the
caller's workspace and stays a black box `workspace-relative`. Boxes say `not fetched` (dashed), `not found` or the
reader's reason; the note line counts the references not fetched. Fetch References runs on its own thread as a
`BackgroundTasks` task of the window (cancellable there; editing or closing the file stops it), and is a preview's
own toolbar button through the generic `Preview.actions` extension.

Rejected:
- **GitHub's REST API** (contents endpoint): needs token handling for private repositories and GHES, is limited to 60
  requests an hour without a token, and GHES versions differ.
- **raw.githubusercontent.com:** rate-limited, no private repositories without a token, nothing for GHES.
- **Fetching when a file is opened:** a server would learn which workflows the user opens, and the network would be
  used by surprise (proxies, metered connections, slow servers).
- **Full clones:** far larger than one commit's trees plus the files read.

## Consequences
- Private repositories and GHES work with whatever the user's git uses (ssh keys and agent, credential helpers),
  without tokens in Thoreau.
- The cache grows without eviction; there is no cleanup yet. `~/.sluide/cache/git` can be deleted by hand at any time
  (when no fetch runs). A repository that could not be fetched leaves only its lock file (and the host and
  owner folders).
- Batch mode replaces a `core.sshCommand` the user configured, for the cache only; an ssh chosen in the environment
  (`GIT_SSH_COMMAND`, `GIT_SSH`, `GIT_SSH_VARIANT`) is kept and may ask on the terminal Thoreau was started from.
- Opening a folder with a workflow graph that has remote references waits for the trust checks before the graph
  shows (usually well under a second, at most 30 s). The wait holds up only that view's graph, and it stops when the
  workflow is edited (the new text waits anew) or its view closes. Fetch References draws on one thread shared by all
  views, so a fetch that waits for the checks holds up other fetches, never parsing.
- Nothing asks on a terminal, and Git Credential Manager opens no window, but a configured credential helper still
  runs: the macOS keychain may ask whether git may read an entry another app stored, and a helper of another kind may
  show its own UI. An ssh agent may ask for a confirmation or a security key touch.
- Downloads are bounded by the 2-minute timeout only; a server that ignores the filter sends a whole commit's files.
- Not on Windows: the hooks path `/dev/null` assumes a Unix-like system.
- Listed in [../index.md](../index.md); amends [ADR 0075](0075-github-actions-workflow-graph.md) (remote `uses:` are
  followed) and [ADR 0070](0070-window-services.md) (`RemoteSources` is a window service).
