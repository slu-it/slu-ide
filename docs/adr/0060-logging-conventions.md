# 0060. Logging conventions

- Status: accepted
- Date: 2026-10-19

## Context
After navigating the git log the UI became slower and slower with CPU spikes, and `~/.sluide/logs/sluide.log` (ADR 0051)
had nothing to explain it: `GitCli` logged no commands, durations or exit codes and discarded the standard error of
reads. Without log lines that tell what runs, how long and on which thread, such problems cannot be diagnosed.

## Decision
Levels:
- INFO: start and end of work we start, with the duration.
- WARNING: failures with a message for the user (git refused, timeout, network) and git run on the JavaFX thread.
- ERROR: unexpected exceptions, always with the stack trace.
- Hot paths such as highlighting log only when slow (> 200 ms).
- Never log secrets: standard input of git, HTTP bodies, query strings, headers.

Git commands (`GitCommandLog`, `dev.sluide.git.cli`): every command gets a process-wide number.
- Start (INFO): `git#42 start [<thread>] <arguments> (in <directory>)`, arguments cut at 300 characters, without the
  executable and the fsmonitor guard options; ` (stdin <n> bytes)` if there is input, never its content.
- End (INFO): `git#42 exit <code> in <ms> ms, <size>`. A read that fails also has the start of its standard error
  (` stderr: …`, at most 500 characters, newlines as ` | `); a non-zero exit of a read stays INFO because probes
  (`rev-parse --verify MERGE_HEAD`) are expected to fail.
- WARNING naming `git#n`, the arguments and the duration: timeout, more output than allowed, output not read to the
  end, git could not be started (with the exception), cancelled, interrupted, a write that exited non-zero (with git's
  message). A command run on the JavaFX thread logs its start line as WARNING with ` on the JavaFX thread`.

Standard error of reads: it used to be dropped (ADR 0013). Now the first 4 KB are kept, for the log only; the rest is
discarded as it is read so the pipe never blocks. Its reader does not count towards the completeness of the output.
A read waits at most 100 ms for its standard error after git ends, so that of a slow child may be missing from the log;
a child that keeps standard error open keeps that reader thread until it exits.
This partly supersedes the dropping of ADR 0013; writes were not affected.

`.git` and status: every batch of changes in `.git` is logged (`Changes in .git of <root>: <up to 5 paths>[, … (+N
more)]`), and every status refresh logs one start and one end line (`Git status cycle for <root>: trust recheck|awaited
<ms> ms, status <ms> ms, changed|unchanged`, or why it ended early). A refresh loop shows as a repeating pair.

FX watchdog (`FxWatchdog`, ide): a daemon thread `sluide-fx-watchdog` posts a ping to the JavaFX thread every 250 ms.
A ping not handled within 1 s gives one WARNING `JavaFX thread blocked for <ms> ms` with the stack trace of the JavaFX
thread attached; when it is handled, an INFO line `JavaFX thread responsive again after <ms> ms blocked`. It logs with
`System.Logger` and is stopped before the Spring context closes, so it does not log during shutdown (ADR 0051).

## Consequences
- Slowness and loops show in the log: command durations, blocked UI with its stack, refresh cycles.
- Every git command writes two INFO lines; the log rolls at 10 MB (ADR 0051), so a busy session covers less time.
- Reads hold up to 4 KB of standard error in memory per command and need one more virtual thread.
