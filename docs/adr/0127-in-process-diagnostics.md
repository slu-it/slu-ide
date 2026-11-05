# 0127. In-process diagnostics: an always-on JFR ring buffer and a sampled capture report

- Status: proposed; see [0060](0060-logging-conventions.md), [0061](0061-background-tasks.md), [0051](0051-log-file.md)
- Date: 2026-11-05

## Context
The IDE sometimes becomes sluggish without freezing: idle CPU stays high or the UI reacts late (the commit view in
November 2026). The log had nothing to explain it: git was quiet, and the FX watchdog of
[ADR 0060](0060-logging-conventions.md) only reports a JavaFX thread that does not answer for 1 s, which a sluggish
but answering UI never does. The sandbox could not reproduce it, and the packaged runtime (jlink, jpackage) has no
`jcmd`, `jstack` or `jfr` to look from outside. What burns the CPU has to be captured from inside the process, at the
moment the user notices it.

## Decision
A module `diagnostics` (bean `Diagnostics`, one per app) with two parts:

- **An always-on JFR ring buffer** (`FlightRecorderBuffer`): started at app start on a background thread (JFR takes a
  few hundred ms to start), JFR's `default` configuration (about 1 % overhead), at most the last 2 minutes and 50 MB.
  Its chunks are kept in `~/.sluide/diagnostics/.jfr`, made owner-only, not in JFR's default repository in the temp
  folder, which other users can read; it is set with the `JFR.configure repositorypath=` diagnostic command (the
  `DiagnosticCommand` MBean, there is no API; it reports a path it cannot use in its output, which is checked), and if
  that fails, one WARNING and JFR keeps its default. At the start, folders there of processes that have ended and
  were not modified for 24 h (left by a crash, up to 50 MB each) are deleted. Events that can hold secrets are
  disabled (`.claude/rules/safety.md`, "Secrets"):
  `jdk.InitialEnvironmentVariable` (tokens in the environment), `jdk.InitialSystemProperty`, `jdk.JVMInformation`
  (the JVM's arguments: every `-D`, also from `JAVA_TOOL_OPTIONS`, e.g. proxy passwords), `jdk.ProcessStart` (the
  command lines of the processes the IDE starts, such as `git ls-remote https://user:token@…`) and
  `jdk.SystemProcess` (the command lines of all processes of the machine). If JFR cannot start (also when `jdk.jfr`
  is missing from the runtime), one WARNING and captures say "no JFR recording (<reason>)".
- **A capture on request** (Help → Capture Diagnostics): a folder `~/.sluide/diagnostics/<yyyy-MM-dd_HH-mm-ss>/`
  (owner-only where POSIX; `-2`, `-3`… when the second is taken) gets, after 10 s of sampling on one background
  thread, `threads.txt`, `recording.jfr` and `report.txt`. The ring buffer is dumped at the end of the window, so it
  holds the window itself and up to 2 minutes before it in all (about 1:50 before the press). `threads.txt` is the
  JDK's plain-text dump of all threads (`HotSpotDiagnosticMXBean.dumpThreads`), virtual threads included, which the
  `ThreadMXBean` of the samples does not see. The report has:
  - per-thread CPU time over the window (`ThreadMXBean`, batch read where the JDK's bean offers it, read at every
    sample so that a thread that ends in the window keeps its last value and is listed as "ended"), process CPU, the
    CPU no listed thread accounts for ("unattributed": GC, JIT compiler, JFR and other JVM threads), the sampling
    thread's own CPU, GC count and time per collector, heap, deadlocked threads; a note that the CPU of
    `ForkJoinPool-*` carrier threads may be virtual-thread work, see `threads.txt` and `recording.jfr`;
  - all thread stacks every 250 ms (40 frames), aggregated per thread: the up to 3 most frequent stacks (25 frames)
    of the 5 busiest threads and always of the JavaFX thread, with "n of m samples running or blocked". Only samples
    in which the thread runs or is blocked count. Native waits that Java reports as RUNNABLE (the JavaFX event loop,
    selectors) are counted apart ("in the native event loop or a selector wait in n of m samples"), and so is a
    nested event loop ("in a nested event loop (modal dialog open)"). They are usually idle, but may be native work:
    for a thread that used at least 5 % of one core, their most frequent stacks are shown too;
  - the JavaFX thread's responsiveness: a `Platform.runLater` ping every 100 ms (median and max latency, unanswered
    pings), and layout pulses per second per window (`Scene.addPostLayoutPulseListener`, installed for the window
    only, noted when installed late because the JavaFX thread was busy);
  - a full thread dump of the platform threads at the end, and the last 200 lines of `sluide.log`, read backwards from
    the end of the file.

  The report is plain text about 120 columns wide, headed by the caller's lines (version, Java, JavaFX, OS, paths),
  the folder and whether JFR and `threads.txt` are in it, and written through a temp file. The sampling thread leaves
  itself out of the threads and stacks. JavaFX-thread samples that run the capture's own ping are counted apart
  ("n of m samples answering this capture's pings"), not as running stacks. Runs of identical consecutive frames
  print once with "(× n)" and the stack budget counts printed lines (samples take 100 frames), so the origin below a
  recursion stays visible. Hot stacks are grouped by their folded head, so samples at different recursion depths count
  as different stacks.
- The capture's row is a determinate bar (`BackgroundTask.progress`) updated once a second, so the row causes about
  one pulse per second instead of one per frame and "Pulses per second" stays meaningful; the report notes the 1/s.
- **One capture at a time**, app-wide (`capturing`). A capture is a background task "Capturing diagnostics…"
  ([ADR 0061](0061-background-tasks.md)) of the window it was started in; its cancel stops the sampling early and the
  report is still written, with "Cancelled after N s" (a cancel after the sampling has ended changes nothing). Closing
  the app cancels a running capture the same way. A failure ends the task failed and keeps what was written. A
  finished capture logs `Diagnostics captured to <folder> in <s> s` at INFO.
- **Retention:** after a capture, the newest 10 folders whose names match the time-stamp pattern are kept and older
  ones deleted; nothing else in the folder is touched (not `.jfr`). A folder that cannot be deleted is a WARNING, not
  a failed capture.

Rejected:
- **A single thread dump** on request: it shows one moment, not what keeps a thread busy over seconds, and no CPU.
- **JFR only on request:** it starts recording at the press and misses what led to it; the ring buffer costs little.
- **Automatic captures** (e.g. when idle CPU stays high): later idea, it needs thresholds that do not fire during
  builds and indexing.
- **External tools** (`jcmd`, async-profiler): not in the packaged runtime, and the user would have to run them at
  the right moment.

## Consequences
- A sluggish episode can be diagnosed from one folder the user can send: what ran on which thread, how late the
  JavaFX thread answered, and up to two minutes of JFR events ending with the window.
- JFR runs in every session (about 1 % CPU, up to 50 MB in `~/.sluide/diagnostics/.jfr`). `jdk.jfr` and
  `jdk.management` come into the packaged runtime through jdeps.
- Captures contain thread names, stacks, window titles (project names) and the end of the log; no environment,
  system properties, JVM arguments or command lines of started or other processes. JFR events that are not disabled
  can still hold paths and class names of the project.
- Added to [../index.md](../index.md).
