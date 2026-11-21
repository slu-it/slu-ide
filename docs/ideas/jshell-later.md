# JShell: ideas left for later

What the first version of [JShell](../features/jshell.md) ([ADR 0191](../adr/0191-jshell-engine.md)) does not do.

- **The project's class path.** The spike showed that `addToClasspath` works, with folders and jars, when the session
  starts. The project's compiled classes and dependencies would come from the Java support (jdtls) or a build; the
  open questions are where the list comes from, and that a changed class path needs a restart.
- **Class path entries of the user.** A per-file or per-project list of folders and jars, next to the JDK choice.
- **Enable Preview.** A toggle for `--enable-preview` on the session's JDK, which restarts the session.
- **Results aligned with the lines.** Showing a snippet's value beside its line in the source instead of only in the
  transcript.
- **Input for `System.in`.** Today `System.in` is at its end (`-1`, `hasNextLine()` false); a field for typing input
  while a snippet runs.
- **A Tools menu entry** to open an empty JShell without a file (a scratch of type JShell is the way today).
- **Diagnostics while typing.** JShell's analysis marks errors in the source before a run.
- **A Restart that keeps the old transcript apart,** e.g. a separator or a collapsed earlier session, instead of notes
  in one stream.
- **macOS trackpad steps.** Sub-pixel scroll steps of the transcript with a trackpad follow the end less smoothly
  than a wheel; to check on a Mac.
