# 0062. What git work runs in parallel and what is queued

- Status: accepted
- Date: 2026-10-19

## Context
Thoreau runs git for many things at once: the status of the open folder (ADR 0013), the trust checks of git's
configuration (ADR 0053), the diffs behind the editor's change markers (ADRs 0015, 0021), the git log and its writes
(ADRs 0035, 0041). Each needs a rule for the thread it runs on, what it may wait behind, and what happens to a request
that comes while an older one for the same thing still runs.

The user saw the UI get slower and slower, with CPU spikes. The log of ADR 0060 warned "git on the JavaFX thread" for
the change markers: `GitStatusService.diff(file)` ran two git processes, `cat-file --filters HEAD:<path>` (which runs
smudge filters such as LFS) and `diff -U0 HEAD -- <path>`, each with a 3 s timeout, synchronously on the JavaFX thread:
for every open modified file whenever the git status changed, when a file was opened or changed on disk. ADRs 0015 and
0021 had accepted this on the assumption that it takes milliseconds; with filters, a large repository or many open
files it does not.

The slowdown also grew with every change of `.git`. Each batch of the watch of `.git` ran a whole cascade: git's trust
check again (`config --list`, `rev-parse`, `ls-files --stage` over the whole index, per submodule up to 50), `git
status --ignored`, then `gitFolderChanged`, on which the git log reloaded (about nine git processes), the commit details
were read again (compared by row object, which a reload replaces), then branch containment, file histories and the
Commit tab. Batches that matter to nothing triggered it as well: `.git/objects/**`, `.git/logs/**`, lock files,
fsmonitor sockets, and on macOS the polling watcher reporting changed times of subfolders. Every write of the git log
ran it twice: once told (`afterWrite` → `gitChanged`), once more for the watch's batch of the same write. And a newer
read skipped queued older ones but never stopped a git that was already running.

## Decision
The rules are given per kind of git work, one section each: its thread, and what becomes of requests that overtake
each other. Kinds without a section here keep the rules of their own ADRs.

### Editor change markers off the JavaFX thread
- `GitStatusService.diffAsync(file, done)` runs both git commands on a thread of its own per window,
  `sluide-git-diff` (`NamedThreads.single`), not on `sluide-git`, so markers never wait behind a slow status run or
  trust check. The conditions are those of before: a followed repository, git allowed by the folder's trust and no
  change of `.git` whose trust check is still outstanding; the worker reads them (volatile and atomic fields,
  `ProjectTrust.allows` is synchronized). The answer goes to `done` through the service's `dispatch` (the JavaFX
  thread). The thread is shut down with the service's other executor in `close`; the synchronous `diff(file)` is gone,
  since nothing needs it.
- **Newest request per file wins.** Each request has a `GitCancellation`; a newer request for the same file, or
  `cancelDiff(file)`, cancels the older one: it does not start if it is still queued, its running git is stopped as a
  timeout would stop it (`GitCli.headContent` and `diffToHead` take the cancellation), and a result that was found
  anyway is dropped on the JavaFX thread. So is a result for a repository no longer followed (after another `open`,
  whose empty snapshot asks the editor again). At most one diff git runs per window.
- `GitDiffs.textDiff(file, done)` checks the file's status in the snapshot on the JavaFX thread: only a modified file
  goes to git; any other gets `null` at once, without a thread hop, and cancels a request still running for it.
- `EditorArea.diffOf` is asynchronous, `(Path, (TextDiff?) -> Unit) -> Unit`, its callback called on the JavaFX
  thread. Each open file counts its requests (`OpenFile.diffRequests`); an answer is taken only if it is for the
  newest request and the tab is still open (a replaced or closed tab's `OpenFile` is not). Until then the editor keeps
  its markers and a shown side-by-side diff stays as it is: no flicker to "no markers" between the request and the
  answer. An answer of `null` hides a shown diff, as before. A file that moved drops its diff at once, since it is of
  the old path, and keeps its markers until the new path's answer.
- The side-by-side diff on demand (View → Show Changes, a click on a marker) shows the last answer (`OpenFile.diff`)
  and runs no git itself; it follows the answers that arrive later.

Rejected:
- **Diffs on `sluide-git`:** a marker would wait behind status runs and trust rechecks.
- **A pool of threads:** parallel diffs of many open files compete for CPU and disk with no gain for the one file
  looked at; requests that are overtaken are skipped anyway.
- **Clearing the markers when asking:** they would flicker on every change of the status.

### `.git` stamps: only relevant changes cascade
- A batch of the watch of `.git` still schedules a cycle of `GitStatusService` on `sluide-git`, but the cycle first
  takes two cheap stamps of the git folder (`GitFolderStamps`), both SHA-256 over what they cover. The stamps are taken
  before anything runs in the cycle, so that a change while the trust check or `git status` runs differs next time.
  - **State stamp:** size, modification time and file key (inode) of files, no content; git writes files through a
    lock file it renames, so a rewrite gets a new inode and changes the stamp even within the file system's time
    resolution. It covers what the status, the log and the refs read: `HEAD`, `index`, `packed-refs`, every file below
    `refs/` and `reftable/` (repositories with `--ref-format=reftable`), `MERGE_HEAD`, `MERGE_MSG`, `CHERRY_PICK_HEAD`, `REVERT_HEAD`, `ORIG_HEAD`, `FETCH_HEAD`, `AUTO_MERGE`,
    `shallow`, `commondir`, `config`, `config.worktree`, `info/exclude`, `info/attributes`, `info/sparse-checkout`,
    `info/grafts`, `objects/info/alternates`, and everything below `rebase-merge/`, `rebase-apply/`, `sequencer/` and
    `modules/` (the submodules' git folders, whose `HEAD`, index and refs `git status` reads), except folders named
    `objects`, `logs` and `lfs` there. Not objects, reflogs, hooks or lock files. A `.git` file (linked worktrees,
    submodules) has no state stamp: nothing resolves its git folder outside git, and it is not watched anyway; every
    cycle then counts as changed.
  - **Trust stamp:** what git's trust check (ADR 0053) read, recorded by `GitTrustCheck` with every check that could
    check (`inputs(folder)`), for the opened repository and each submodule it looked at:
    - by content (SHA-256; above 1 MB by size, time and inode) and permissions (POSIX, else `unix:mode` and
      `unix:ctime`, else whether executable), links followed: the files `git rev-parse --git-path` names for `config`,
      `config.worktree`, `HEAD` (`includeIf "onbranch:"`), `commondir` and `gitdir` (where a linked worktree's git
      folder leads); every file an entry of the `local` or `worktree` scope comes from (`--show-origin`); every file an
      `include.path` or `includeIf.*.path` names, also one that does not exist or does not apply yet, resolved as git
      resolves it (relative to the including file, `~/` in the user's home); and every entry of the hooks folder (also a
      `core.hooksPath` in the work tree, which is where `--git-path hooks` points). So an edit in place that keeps size
      and time (`touch -r`), a file system without inodes or with coarse times, and a `chmod +x` on a hook all change
      it. Only a regular file (after following links) is opened, and read at most 1 MB + 1 byte: one that grew past
      1 MB meanwhile is being written, and the stamp is left out (the check runs again); a FIFO, which would block the
      thread, a device such as a link to `/dev/zero`, which never ends, and anything else count by their type and
      attributes;
    - by size, time and inode only: the index (the check runs `ls-files --stage`; it is large, and the gitlinks are read
      anew by every check);
    - by where it leads: the `.git` of the repository and of each submodule's folder, a folder as such, a link by its
      target, a `gitdir:` file by its content.

    **Security rule:** whatever the check may depend on is in it; if a file cannot be named (an include of `~user/`),
    the last check could not check, or there was no check yet, there is no stamp, and every change of `.git` runs the
    check again, as before. So it is beyond 256 entries of hooks folders or 16 MB read in all.

    The inputs are kept per folder in the singleton `GitTrustCheck`, which the windows share; each check is numbered
    when it starts, and a slower check that started earlier does not replace what a later one recorded.
- **The cycle:**
  - git's trust check runs again (`recheck`) only if the trust stamp differs from the one taken before the last
    recheck that allowed git; otherwise it is awaited (`awaitAllows`) as for any cycle. A recheck that keeps git off
    (or did not run: interrupted, timed out) keeps no stamp, so the next change checks again. The first change after
    opening the folder always rechecks: the first check ran on its own thread, so its stamp is not known.
  - `gitFolderChanged`, which drives the reloads of the log, the file histories and the Commit tab, counts only if the
    state stamp differs from the one taken when it last counted (the first cycle after opening takes it).
  - `git status` runs for every cycle with a reason: the explorer, F5, `refresh()`, `gitChanged()`, a change of either
    stamp. A cycle that only a batch of `.git` asked for and that changed neither stamp runs nothing; it logs
    `Git status cycle for <root>: .git unchanged, nothing runs` after the batch's line with its paths.
  - `gitChanged()` (a write of the window, below) counts as a change of both stamps and records the stamps taken at
    its start. The batch of the watch for the same write, 0.2 to 1 s later, then finds them unchanged and runs
    nothing: one cycle per write instead of two.
  - "A change of `.git` whose trust check is outstanding hides the editor's diffs" (`confirmedChanges`) stays; a
    batch that runs nothing confirms its change at once.

Rejected:
- **Filtering the watch's paths** (ignore `objects/`, `logs/`): the watch reports a folder for lost events, and on
  macOS the polling watcher reports changed times of folders, whatever changed in them; a stamp says what changed.
- **Content hashes for the state:** reading the index (often megabytes) on every batch costs what the stamps save. The
  trust inputs are hashed because they are small and a missed change there is a security hole.
- **Delaying the refresh after a write by the watch's latency,** to catch its batch in the same cycle: a visible lag
  after every operation, and still two cycles when the watch is slower.

### Writes hold refreshes
- `RefWriter` (every operation of the git log, the Commit tab and the Push dialog; no git write runs outside it) tells
  the start of each write on the JavaFX thread before git runs, with whether it holds the status, and its end through
  `dispatch` from a `finally` on `sluide-git-write`, also when the operation threw, its outcome is dropped (another
  folder) or the thread rejected it. `GitLogView.onWriteStarted(holdsStatus)` and `onWriteFinished()` pass them on;
  `MainWindow` sets them to `GitStatusService.writeStarted(holdStatus)` and `writeFinished()`, which replace the former
  `afterWrite` → `gitChanged()`. Without the window, the log refreshes itself at a write's end, as `afterWrite` did.
- **Holds the status** (`RefOperation.holdsStatus`): an operation that changes files or takes them as they are on disk
  (`fileChange`: checkout, new branch with checkout, cherry-pick, revert, their continue, abort and skip, a hard reset,
  a commit). A fetch, a push and the operations on refs alone do not.
- While a write runs, cycles for changes of `.git` wait and only remember that they are due; `gitFolderChanged` does
  not count (also not from a cycle that started before the write); with a write that holds the status, `refresh()`
  waits too. Otherwise (a fetch, a push) a refresh runs `git status` (with a trust recheck if its stamp changed) but
  leaves the state's count to the end. The first cycle that waits logs `Git status cycle for <root> waits for a git
  write`.
- **The editor's diffs during a write:** they run while no change of `.git` is outstanding for them
  (`confirmedChanges`). A cycle that waits for the write still takes the trust stamp: if it is the one of the last
  recheck that allowed git, the changes so far are confirmed and diffs keep running, e.g. during a fetch or push, which
  change refs only. If it differs (a write or a terminal changed the configuration, a hook, the index of a checkout or
  commit), there is none (before the first recheck after opening, or after one that kept git off) or it cannot be
  computed, diffs answer `null` until the cycle at the write's end has checked again (logged once per write: `… trust
  inputs changed, diffs wait for the git write`); an editor that asks meanwhile (a file opened, or changed on
  disk by a checkout) hides its markers until it asks again, which the snapshot of that cycle makes it do. Without a
  new snapshot during a write that holds the status, editors only ask for files opened or changed on disk.
- `writeFinished()` runs `gitChanged()`: one cycle for the write and everything that waited, with a trust recheck,
  `gitFolderChanged` and `git status`. The log reloads once per operation, on that count (ADR 0041's refresh after
  every operation, also a failed one).
- A write whose end is never told is let go after 11 minutes (longer than the longest write timeout of 10 minutes,
  ADR 0041) with a WARNING, followed by the same cycle. Closing the window during a write holds nothing: the service is
  closed, and the end told later finds nothing to run.

Rejected:
- **Running the cycles during a write:** a checkout of many files changes `.git` and the work tree all the time;
  every cycle would read a state halfway and reload the log for it.

### Reads: newest wins, superseded git killed
- The pattern of the editor's diffs above applies to the readers of the git log: each request has a `GitCancellation`;
  a newer request (or closing) cancels the older one's, which then does not start if it is still queued, has its git
  stopped if it runs (the read is killed, ADR 0041), and its late result is dropped.
  - `GitLogPages`: a reload, a change of the filter or comparison, `open` and `close` stop the first page or further
    page still loading, also one `loadUntil` asked for; a newer `loadUntil` keeps a page that is loading, which it
    needs as well, and `stopLoadingUntil` lets it arrive (it extends the rows).
  - `CommitDetails`: the details (`show`), the hash words (`cat-file --batch-check`) and the changed files
    (`diff-tree`) of the selected commit.
  - `CommitBranches` (branch containment), `FileDiffs` (a changed file's diff), `PushReader` (the Push dialog's reads
    and the files of a row).
- `GitLogSource` and the reads of `GitLog`, `GitRemotes`, `GitCommits` and `GitPush` they use take an optional
  `cancellation`, passed to every git command they run (`GitCli.run`, `runBytes`, `execute`). A cancelled read answers
  `null`; it is logged at INFO (ADR 0060).
- Background tasks (ADR 0061): a page of the log whose git was stopped ends its task as cancelled
  (`task#n cancelled after …`), one end line, no ✕ added; the other readers have no tasks.
- `CommitDetails` compares the selected row by its commit's hash: the row of the same commit in the new rows of a
  reload replaces the old one in the state (its refs may have changed), and its details and changed files, which a
  commit cannot change, are not read again; after a failure of either, both are. The links of its message (words that
  name a commit, ADR 0045) are kept as read: a commit fetched since that a word names is linked only once the commit is
  selected anew (reading them on every reload would bring back a git process per refresh). `CommitBranches` already
  compared by hash, and reads again when the refs change.

Rejected:
- **A cancellation per thread** (a thread-local the reads would pick up): less to change, but invisible at the call,
  and a write on that thread could pick it up as well.

## Consequences
- Git no longer runs on the JavaFX thread for markers; ADR 0060's warning does not appear for them.
- Markers appear once git has answered: shortly after the text of a file that is opened or reloaded.
- With many modified files open, a status change queues one diff per file on one thread; their markers update one by
  one. A file asked for again before its turn costs nothing more: only its newest request runs.
- Stopping a read is routine now: a cancelled or interrupted read (e.g. as the window closes and the thread is shut
  down) is logged at INFO, no longer as a WARNING (ADR 0060); a cancelled or interrupted write stays a WARNING.
- A batch of `.git` that changed nothing anything reads (objects, reflogs, lock files) costs two stamps and one log
  line. A ref changed only below `.git/refs/` (`git branch x` in a terminal), which the watch does not see, is now
  counted with the next batch of `.git`, whatever it was.
- The trust check runs again only when its inputs changed: a hook changed by hand (also only made executable) is
  checked with the next batch of `.git` (the watch does not see `hooks/`), as before; a change of an included file
  outside `.git` is noticed only with a batch of `.git`, as before. A batch reads the trust inputs, small files, once
  more.
- Every write is one cycle and one reload of the log; during a checkout or commit the explorer's markers wait for its
  end, and the editors' diffs as soon as it changed what the trust check reads (the index of a checkout or commit
  does).
- Superseded reads of the log stop their git; stepping quickly through commits or reloading during a long first page
  no longer leaves git processes running to their end.
- Partly supersedes [ADR 0015](0015-git-diff.md) (diffs synchronous on the JavaFX thread),
  [ADR 0021](0021-change-markers-and-diffs-on-demand.md) (`diffOf` returning the diff),
  [ADR 0013](0013-git-status.md) and [ADR 0035](0035-git-log-area.md) (what a change of `.git` refreshes),
  [ADR 0041](0041-git-write-operations.md) (the refresh after an operation) and
  [ADR 0053](0053-project-trust.md) (when the trust check runs again).
