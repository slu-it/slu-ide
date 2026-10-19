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
  end, git could not be started (with the exception), a write that was cancelled or interrupted, a write that exited
  non-zero (with git's message). A read that was cancelled or interrupted logs that at INFO: since
  [ADR 0062](0062-git-concurrency.md) that is routine (a diff overtaken by a newer request, a window closing). A
  command run on the JavaFX thread logs its start line as WARNING with ` on the JavaFX thread`.

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

Background threads: raw executors and threads are replaced by `NamedThreads` (module `background-tasks`), which logs
every throwable of a task at ERROR with the thread's name. Errors that used to be swallowed (`catch (_: IOException)`,
`runCatching`) are logged: WARNING for expected failures that still hurt (naming the file, or method and host of a
request, never a query, header or body), ERROR with the stack trace for unexpected ones. Work that is not git logs INFO
start and end with the duration: image downloads (host and path), explorer deletes, terminal processes (program, pid,
exit code), the `sbx` check and the start of the HTML preview server. Exports, HTTP requests and trust checks will
log their start and end as background tasks (ADR 0061).

Raw executors and threads that stay on purpose: the request executor of the HTTP client (`RequestExecutor`, it logs its
failures itself and turns every throwable into a result), the virtual-thread executor of the preview server
(`PreviewServer`), the executors of the terminal (`TerminalSession`, shared with JediTerm), the reader threads of git
processes (`GitCli`, they are part of a command that logs itself) and the watcher thread (`FileWatcher`).

Refresh loop: the explorer reports the changed paths (`onFilesChanged(Set<Path>)`, empty for F5: unknown), and the window
skips the git status refresh when every one is in the log folder, so that the log lines of a refresh cannot trigger the
next one when the log folder is inside the followed repository.

`ProjectTrust.awaitAllows` and `recheck` refuse to run on the JavaFX thread (they wait up to 30 s).

## Known FX-thread risks
Not fixed yet; the list is for prioritising. Each can block the JavaFX thread, which the watchdog above then reports.
Places are named by class and function.

- Full file reads and the first synchronous highlight when a view opens: `CodeView` (`read()` in the
  constructor, up to 10 MB, then `show` highlights the text), `ImageFileViewer` (`readBytes`, up to
  50 MB), `SideBySideDiffView` (`readText` of the current file), `EnvironmentFileView`
  (`Files.readString`). A large or slow file (network share) freezes the window while the tab opens or reloads. Fix:
  read on a `NamedThreads` thread and show a loading state, or cap what is read synchronously.
- Live preview rendering on the FX thread: Markdown (`MarkdownPreview.load` calls `MarkdownRenderer.render`, which
  inlines local images as base64 `data:` URIs in `embeddedImage`, up to 10 MB each), and the parsing of
  CSV (`CsvFileViewer`), PEM (`PemRenderer`), notebooks (`NotebookRenderer`) and Bicep (`BicepGraph`). The cost grows
  with the document and repeats on every edit. Fix: render off the thread and apply the result, dropping outdated
  renders as the HTTP client does for responses.
- Synchronous saves: `CodeView.save` (`Files.writeString`), `HttpClientSetup.create` (creating files, reading and
  appending the `.gitignore`). A slow disk or share blocks the key press that saved. Fix: write on a background
  thread, keep the tab dirty until it is done.
- Explorer: `FileTreeItem.getChildren`/`refresh` call `listDirectory`, which runs
  `Files.isDirectory` for every entry; `PathCell.updateIcon` calls `canOpenFile`, which reaches
  `EditorArea.canOpen` (`fileViewers.viewerFor`) and reads a text sample of each file while the cell is
  rendered; `FileTreeItem.forgetReadability` resets that cache for the changed paths only, or for all files
  of the folder when the folder itself is reported (events lost), so such a folder re-reads its files on the next
  rendering. `FileExplorerView.closeWatchesOfVanishedFolders` runs `Files.isDirectory` over every watch key after each change. Fix: list and
  classify on a background thread, and check the watch keys off the thread.
- Watcher callbacks that reload synchronously: `EditorArea` (`onChangedOnDisk`, `updateView`; the reload reads the
  file as in the first item). Their git diff (`showChanges` with `diffOf`) no longer runs on the FX thread: fixed by
  [ADR 0062](0062-git-concurrency.md), which moved the editor's change markers to a thread of their own.
- Process and server start: the pty process of a terminal (`TerminalSession`, `PtyProcessBuilder.start`) and
  the join on the preview server's start thread (`PreviewServer.Site`). Fix: start terminals on a thread and
  show the tab at once; start the server lazily off the thread.
- Exceptions in FX handlers do not kill the JavaFX thread: they reach the default handler, which logs them (ADR 0051).
  A handler that blocks, not one that throws, is what freezes the window.

## Consequences
- Slowness and loops show in the log: command durations, blocked UI with its stack, refresh cycles.
- Every git command writes two INFO lines; the log rolls at 10 MB (ADR 0051), so a busy session covers less time.
- Reads hold up to 4 KB of standard error in memory per command and need one more virtual thread.
