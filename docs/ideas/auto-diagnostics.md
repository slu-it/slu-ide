# Idea: automatic diagnostics capture

**Status:** idea, 2026-11-05; nothing decided or built. Today a capture is started by hand
([diagnostics.md](../features/diagnostics.md), [ADR 0127](../adr/0127-in-process-diagnostics.md)).

A sluggish episode is gone when the user thinks of the menu. The app could start a capture itself when the process CPU
stays high while nobody uses it, e.g. above 40 % for 30 s without user input, once per session.

## Sketch
- Process CPU from `OperatingSystemMXBean`, user input from a scene filter on key and mouse events.
- At most one automatic capture per session; its report opens as a notification, not a tab.
- A setting for the threshold and an on/off switch, in Settings.

## Open questions
- Thresholds that do not fire during builds, indexing or a running terminal command (those use CPU legitimately).
- Whether to ask once before capturing (the capture holds stacks and window titles).
- Should the JFR ring buffer's dump be taken at once (it holds the lead-up) rather than after 10 s of sampling?
- What to do on machines with few cores, where a GC phase alone crosses the threshold.
