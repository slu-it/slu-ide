# Diagnostics

**Modules:** `modules/diagnostics` (package `dev.sluide.diagnostics`: the `Diagnostics` bean and the JFR ring buffer
`FlightRecorderBuffer`); the Help menu is `WindowHelp` in `ide` (`help`); decision in
[ADR 0127](../adr/0127-in-process-diagnostics.md)

For a sluggish IDE (high idle CPU, a late UI) that the log does not explain. A capture records from inside the app
what ran on which thread; the user can send the folder.

## Help menu
The last menu of every window.
- **Capture Diagnostics** (Shortcut+Alt+Shift+D) samples for 10 s, shown as the background task "Capturing
  diagnostics…" (its cancel ends the sampling early; the report is still written). The report opens as a tab and
  `Diagnostics report opened: <file>` is logged. The item is disabled in every window while a capture runs; a failure
  shows "Could not capture diagnostics: …".
- **Reveal Diagnostics Folder** creates `~/.sluide/diagnostics` if missing and opens it in Finder, Explorer or the file
  manager ([ADR 0093](../adr/0093-explorer-open-in-terminal-and-reveal.md)).
- **About Thoreau** shows version, Java, JavaFX, OS and the settings, log and diagnostics folders; **Copy** puts them
  on the clipboard (the dialog stays open), **Close** closes it.

## What a capture writes
A folder `~/.sluide/diagnostics/<yyyy-MM-dd_HH-mm-ss>/` (the newest 10 are kept) with:
- `report.txt`: the lines of the About dialog, then per-thread CPU time, process CPU and the "unattributed" CPU, GC,
  heap, deadlocks, the hot stacks per thread, the JavaFX thread's ping latency and pulses per window, a thread dump
  and the last 200 log lines.
- `recording.jfr`: the JFR ring buffer dumped at the end of the window: the 10 s and up to two minutes before.
- `threads.txt`: the JDK's dump of all threads, virtual threads included.

## The ring buffer
JFR runs from app start with its `default` configuration (about 1 % CPU), the last 2 minutes or 50 MB, in
`~/.sluide/diagnostics/.jfr` (owner-only). If JFR cannot start, a WARNING is logged and the report says so.

## Privacy
Environment variables, system properties, JVM arguments and the command lines of started or other processes are not
recorded (their JFR events are disabled). A capture still holds thread names, stacks, window titles (project names),
the end of the log and whatever paths and class names JFR events contain: look through it before sending it.

## Reading a report
- **Hot threads:** the threads with the most CPU in the window, with their most frequent stacks; a thread busy in
  native code shows as "in the native event loop" and is usually idle. Runs of identical frames (a recursion) print once with "(× n)"; "n of m samples answering this
  capture's pings" are the JavaFX thread running the capture itself, not app work.
- **JavaFX responsiveness:** median and max latency of a ping to the JavaFX thread and the unanswered ones; layout
  pulses per second per window show a window that relayouts all the time (an animation or a loop). The capture's own row in the background tasks adds about 1 per
  second, which the report notes.
- **Unattributed:** CPU that no listed thread accounts for: GC, the JIT compiler, JFR, other JVM threads. A large
  share points at memory pressure (see GC time) rather than at app code.
- `ForkJoinPool-*` threads may run virtual threads; see `threads.txt` and the JFR file.

Open `recording.jfr` with `jfr summary` / `jfr print --events jdk.ExecutionSample recording.jfr`, or in JDK Mission
Control.
