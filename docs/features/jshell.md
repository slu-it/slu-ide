# JShell

`.jsh` files open as Java source whose snippets run in a live JShell, with the transcript of the session beside
them. The engine (the agent on the chosen JDK, the protocol): [ADR 0191](../adr/0191-jshell-engine.md). What is left
for later: [ideas/jshell-later.md](../ideas/jshell-later.md).

## Which files
- Files with the extension `.jsh` (module `jshell`, `JShellFileViewer`), highlighted as Java, `Shortcut+/` comments
  with `//`. They are not "runnable files": the explorer's context menu has no Run for them.
- New JShell scratch files: the type "JShell" of the scratch list ([scratch-files.md](scratch-files.md)).

## The view
- Opens as **Split**: the source on the left, the transcript headed by what was run on the right; the focus goes to
  the source. The modes **Source | Split | Output** are at the right end of the toolbar, before them the JDK menu.
- The toolbar, left to right: [Soft Wrap](soft-wrap.md), **Run**, **Run All**, **Stop**, **Restart**, **Clear**; the
  buttons are not focus traversable and a click leaves the focus in the source. The toolbar wraps onto more rows when
  the view is narrow.
- **Run** (`Shortcut+Enter` in the source) evaluates the snippets of the selection, or the snippet at the caret (the
  one the caret is in, else the last one ending before it, else the first). **Run All** (`Shortcut+Shift+Enter`)
  restarts the session and evaluates the whole text. Run and Run All are disabled while a snippet runs, a session
  starts, or no JDK with JShell was found. **Stop** is enabled only while a snippet runs; the snippets after it do not
  run. **Restart** ends the session; the next run starts a new one (nothing happens without a session). **Clear**
  empties the transcript.
- A run from **Source** switches to **Split**; Split and Output stay. The tab shows its activity mark while a snippet
  runs; closing the tab then asks ("... is still running a JShell snippet."), closing an idle one does not.
- The text evaluated is the editor's, saved or not: a run never asks to save.
- Clicking an entry of the transcript that has a position (a header, a diagnostic, an exception with a frame in the
  file) goes to its line in the source, shown beside the transcript (Output switches to Split). The line is the one
  of the evaluation; edits made since do not move it, and a line past the end goes to the last line.
- The session belongs to the view: closing the tab, **Restart**, a change of JDK and the end of the program end it.
  Moving or renaming the file keeps it.

## The JDK
- The snippets run on the JDK chosen for the file, else the project's: `JShellJdks` takes the file's choice
  (remembered in the project's data, so it moves with the file), then the chain the Java support uses (the project's
  JDK setting, `JAVA_HOME` of the login shell, `JAVA_HOME` of Thoreau's environment, the JDK Thoreau itself runs on),
  skipping any without JShell (JDK 9 or newer with `jdk.jshell`).
- The packaged app's runtime has JShell and `bin/java`, so it is the last resort and works without any installed JDK.
- The **JDK menu** button shows `JDK <major> · <where it came from>` (chosen, project setting, `JAVA_HOME (shell)`,
  `JAVA_HOME`, Thoreau); its tooltip has the JDK's version and home and, if a JDK before it in line was skipped, why.
  Its items, read when it opens: a radio "Project JDK (JDK 25 · project setting)", which names what the project's
  chain gives ("Project JDK (none)" when it gives nothing; choosing it forgets the file's choice), and one radio per
  installed JDK with JShell, "JDK <version> — <home>"; the current choice is selected. The menu is off while a snippet
  runs.
- Choosing a JDK, or a change of the project's JDK setting, resolves again; a session on another JDK ends, and the
  next run starts one on the new JDK and says so (`Session restarted on JDK 21`, then the start note, as Run All does).
  A change that comes while a snippet runs waits: the session ends when that snippet ends (finished, stopped or the
  session over), unless the JDK is the session's again by then. A session that is still starting on the old JDK is
  dropped and replaced by a start on the new JDK, which the run asked for waits for (no restart note: no session was
  shown; the start note names the new JDK); if no JDK is left, that run is cancelled.
- Run and Run All right after the view opened (a scratch that code opens and runs at once) wait until the JDK is
  looked up, and then run.
- A banner above the source: "No JDK with JShell (JDK 9 or newer) was found." with **Choose JDK…** (opens Project
  Settings, Java; left out in a window without a project folder) while there is none, or the reason a JDK was
  skipped, which can be dismissed.

## Notes in the transcript
Notes are the session's own words between the entries:
- `JShell 25.0.5 (the project's JDK)` at each start of a session.
- `Session restarted` after Restart; `Session restarted on JDK 25` when Run All ended a session, and before the next
  start after a change of JDK ended it.
- The start failure's own message ("JShell could not start on JDK 25 (<home>): ...") with the last 10 lines of the
  agent's standard error; there is no session then.
- `Session ended (exit 3)` after `System.exit(3)` (`Session ended` where the status is unknown); the next run starts a
  new session. `JShell stopped unexpectedly` with the last 10 lines of the agent's standard error when it died.
- Output of threads a snippet started shows when it arrives, outside any entry.

## For other code
`JShellFileView` (`runAll()`, `complete(caret)`) is what code outside the module uses; the window's `JShellWindow`
(JDKs, the installed JDKs, Choose JDK…, the session start) is registered by `ide` as a window service
(`JShellWiring`, [ADR 0070](../adr/0070-window-services.md)) and told by `projectJdkChanged()` when the project's JDK
setting changes.

## Out of scope
See [ideas/jshell-later.md](../ideas/jshell-later.md): the project's class path, Enable Preview, results aligned with
the lines, input for `System.in`, a Tools menu entry, diagnostics while typing.
