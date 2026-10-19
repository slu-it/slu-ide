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

## Consequences
- Git no longer runs on the JavaFX thread for markers; ADR 0060's warning does not appear for them.
- Markers appear once git has answered: shortly after the text of a file that is opened or reloaded.
- With many modified files open, a status change queues one diff per file on one thread; their markers update one by
  one. A file asked for again before its turn costs nothing more: only its newest request runs.
- Stopping a read is routine now: a cancelled or interrupted read (e.g. as the window closes and the thread is shut
  down) is logged at INFO, no longer as a WARNING (ADR 0060); a cancelled or interrupted write stays a WARNING.- Partly supersedes [ADR 0015](0015-git-diff.md) (diffs synchronous on the JavaFX thread) and
  [ADR 0021](0021-change-markers-and-diffs-on-demand.md) (`diffOf` returning the diff).
