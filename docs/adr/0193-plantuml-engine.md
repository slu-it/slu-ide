# 0193. PlantUML through the bundled MIT library: SANDBOX, one render thread, a watchdog

- Status: accepted
- Date: 2026-11-26

## Context
Thoreau draws Mermaid but not PlantUML (`.puml` files, ```` ```plantuml ```` fences), which many projects use for
architecture and sequence diagrams. The user wants the PlantUML Java library integrated directly, and a missing Graphviz
shown instead of failing. A PlantUML source is untrusted input: its preprocessor can include files and URLs, load JSON,
images, sprites, styles and themes from anywhere, and the class-like diagrams run Graphviz's `dot`. This ADR covers the
engine, module `plantuml`; the views that show it (the `.puml` preview, fences, exports) build on its API.

## Decision
- **The MIT build, in-process.** `net.sourceforge.plantuml:plantuml-mit` (pinned in `gradle/libs.versions.toml`, 18 MB,
  no transitive dependencies) runs in Thoreau's JVM; no external `plantuml.jar`, no server. Its stdlib (C4, AWS, icons,
  themes) is inside the jar, nothing is downloaded. The MIT build lacks what only the GPL build carries: the ELK layout
  (`!pragma layout elk`), `<latex>`, JCCKit charts and QR codes.
- **API** (root package `dev.sluide.plantuml`, a library without Spring beans in the editor family): `PlantUml.render(source,
  firstLine)` returns a `CompletableFuture<Rendering>` that never completes exceptionally; a `Rendering` has one
  `Diagram` per page of every block (`newpage` starts a page; pages are exported per block with
  `exportDiagram(os, page, …)`, as `SourceStringReader.outputImage` counts across blocks), each `Drawn(lightSvg,
  darkSvg)` or `Failed(message, line)`, and `builtInLayout`. `PlantUml.shared` is the app-wide engine,
  `GraphvizLocator` finds `dot`, `plantUmlBlocks(text)` tells whether a text has a line starting with `@start` (fences
  without one are wrapped). Internals are in `engine` (`Drawing`, `DotProcesses`).
- **SANDBOX profile, no allowlist.** The engine sets `PLANTUML_SECURITY_PROFILE=SANDBOX` as PlantUML's command-line
  property (`SecurityUtils.setCommandLineProperty`) in its setup: it comes before system properties and the
  environment and makes PlantUML read the profile again, so a profile cached by an earlier use is replaced. Every draw
  checks the profile first and refuses with `Failed("PlantUML is not in its sandbox", null)`, logged, if it is not
  SANDBOX. Under SANDBOX every local read is refused and
  no URL is fetched, with PlantUML's own errors ("cannot include …", "Cannot import", "Cannot read: …", "Cannot load
  theme …", "Cannot open URL"; `%load_json` of a file gives an empty value, `<img:file>` embeds nothing,
  `%file_exists` says 0), while the stdlib works (`!include <C4/C4_Container>`, `<tupadr3/…>` sprites,
  `!theme cerulean`, `<img:data:…>`); no DNS lookup is made for the URL forms. PlantUML's ALLOWLIST profile is not
  enough: `plantuml.allowlist.path` is a raw string-prefix check (`/root` also admits `/root-evil`), `!includesub` and
  `!import` bypass the list that `getIncluded()` reports, and `%load_json`, `<img:>` and sprites read files from
  sibling folders with the same prefix (all measured when the engine was built). No
  current folder is passed to `SourceStringReader` (PlantUML then uses the JVM's working directory, which SANDBOX
  never reads).
- **Local includes are not supported in this round**: PlantUML's error text stays. A later Thoreau inliner
  (`docs/ideas/plantuml-later.md`) would put included text into the source before PlantUML sees it. Its findings so
  far: (1) an included file's `@startuml` blocks must be extracted, `file!N` and `file!ID` select a block, a file
  without `@start` is taken whole; (2) repeats: a plain `!include` of the same file twice is skipped, `!include_many`
  always includes, `!include_once` errors after any earlier include of the file; "same" means the canonical path plus
  the suffix; stdlib includes are tracked separately; (3) `!includesub file!S` pastes only the lines between
  `!startsub S` and `!endsub`; (4) nested includes resolve relative to the including file's folder, the project root
  is checked at each level, cycles stop; (5) errors in inlined text need a line map back to the `!include` line;
  (6) paths built from variables fail safe under SANDBOX; (7) includes under a false `!if` would be read and counted;
  (8) `%filename()` and `%dirpath()` are empty either way.
- **Rendering.** `SourceStringReader(Defines.createEmpty(), source, UTF_8, [], null)`, per block `getDiagram()`; a
  `PSystemError` is a `Failed`, any other diagram is exported page by page twice from the same parsed diagram: light
  (`FileFormatOption(SVG)`) and dark (`withColorMapper(ColorMapper.DARK_MODE)`, background `#1B1B1B`). Both variants
  of one parse equal fresh parses and leave nothing behind (measured), so a theme switch in the views needs no new
  render. The SVGs carry the source, encoded, in a `<?plantuml-src …?>` instruction.
- **Error lines.** The message is `getFirstError().getError()`. `getLineLocation()` is the innermost location; its
  outermost parent is in the source string, 0-based over the whole source (not the block): `line = firstLine +
  position`, so a fence passes its first content line. An error located in another file names it: "… (in
  <C4/C4_Container>, line 72)". Measured: an error inside a stdlib file has a location in that file without a parent,
  so no line of the source is known and `line` is `null`.
- **Graphviz.** `GraphvizLocator` (like `EspeakLocator`, ADR 0124, without a setting and without invalidation) tries the
  login shell's `command -v dot` (`loginShellPrints`; a lookup that throws, pty4j's exceptions or a missing native
  library, is logged and counts as no answer), then `/opt/homebrew/bin`, `/usr/local/bin`, `/opt/local/bin`,
  `/usr/bin`; only an executable file counts. The file found last has to pass a check: run as `dot -V` (an argument
  list, at most 5 s, killed after that), it must exit with 0 and print a Graphviz version (to stderr, which is merged);
  else it is not used, and a log line says why (exit code, no version, no answer in time, not startable). PlantUML
  keeps the answer of the JVM's first `dot -V` for good and lays out with Smetana, without a word, when that answer had
  no version: the check keeps such a `dot` from PlantUML, so that the note about the built-in layout shows. The result,
  also "not found", is kept for the app run, as PlantUML caches what it learnt about `dot` per JVM. Found: each render sets
  `GraphvizRuntimeEnvironment.setDotExecutable(path)` (one JVM-wide value, so every engine sets its own before each
  render). Not found: every `TitledDiagram` gets `setUseSmetana(true)` before export, PlantUML's built-in Java layout,
  and `builtInLayout` is true when a diagram reported `GRAPHVIZ_ENGINE_REQUIRED` before that (forcing Smetana turns it
  to `NONE`); sequence diagrams, mind maps, JSON and the like need no note.
- **One render thread and a watchdog.** PlantUML keeps global state (the `dot` path) and interrupts do not cancel it;
  a `dot` that keeps its output open blocks the render forever (measured). Renders run one at a time, in call order, on
  one daemon thread, which ends when nothing is queued. A render longer than the timeout (20 s) is given up, in one
  step under the engine's lock: its thread is marked abandoned, the child processes of this JVM whose
  `ProcessHandle.Info.command()` is the `dot` that was set, as set or as its real path, are killed (never a search by
  name), and only then do the renders queued behind it start on a fresh thread, so their `dot` is never among the
  killed. The process table is read just before the lock, so that `render` never waits for it. Then the watchdog
  completes the future with `Failed("Drawing took longer than 20 s", null)`, outside the lock (completing runs the
  callers' callbacks). Exactly one side delivers: the worker, after its draw, completes the future only if it was not
  marked abandoned, read under the lock, so the abandoned draw's late result, usually cut short by the check below
  (a one-page source returns no diagram at all), never reaches the caller. Linux reports the executable's real path (`/proc/<pid>/exe`, measured; Homebrew's `dot`
  is a link into the Cellar); that macOS reports the path the process was started with is from the JDK's source
  (`KERN_PROCARGS2`), not measured. After the kill the abandoned draw goes on (the killed `dot`'s export returns an
  error image), so it asks before every export (light and dark, every page and block) whether it was given up, and
  stops. One window stays open: a draw that passed its check just before the watchdog marked it starts that export's
  `dot`, and if that process starts after the kill listed the children, it is not killed and a hanging one blocks
  the abandoned thread for good. No exact way closes it: the process is the same command as a fresh render's `dot`,
  and nothing tells which thread started it. PlantUML's own wait for `dot` (`GlobalConfigKey.TIMEOUT_MS`) is set to
  15 s; it only starts once `dot` closed its output. A throwable of PlantUML becomes `Failed("PlantUML failed: …",
  null)`, logged. The timers of all engines share one daemon thread; a render's timer is cancelled and removed when
  it finishes.
- **Size cap.** A source over 512 KiB (UTF-8 bytes) is not parsed: one `Failed("Too large to draw: N KiB, at most 512
  KiB", null)`. A 100,000-line sequence diagram took 1 GB of heap and gave a 50 MB SVG (measured).

## Consequences
- Diagrams draw without any installation; Graphviz improves class, component, state and use-case layouts when present.
- The tests run the real library in-process. The test with the real `dot` runs only where `dot` is installed (the
  sandbox has `/usr/bin/dot`; the macOS CI runner may not). The watchdog test compiles a fake `dot` with `cc` (skipped
  without a C compiler): it answers `-V` like Graphviz and hangs on the layout with its output open; as a native
  program its `command()` is its own path, where a shell script would report its interpreter. Every `dot` a test sets
  answers `-V`: PlantUML keeps the JVM's first answer for good, and pitest runs all tests in one JVM. A C compiler
  that is there but fails the build fails the test.
- Known gaps: `@startdot` blocks fail ("Syntax Error? (Assumed diagram type: dot)"); `@startditaa` draws PlantUML's
  notice that this build has no ditaa; dark mode turns default text white also on explicit light fills; PlantUML
  prints stack traces to stderr when it crashes (an abandoned render that resumes after its `dot` was killed prints
  one); one DNS lookup of the own host name per JVM, origin unknown. A `dot` that passes the locator's `-V` check
  but then fails PlantUML's own first `-V` (hangs past PlantUML's 15 s or fails only then) still leaves PlantUML on
  Smetana for the rest of the run, without the note; a `dot` that passes `-V` but fails every layout gives PlantUML's
  error images.
  `%load_json("<C4/nothing.json>")` throws out of the parser (a `Failed`, "PlantUML failed: …"): when a stdlib file is
  not in the jar, PlantUML falls back to reading `stdlib/<lib>/<channel>.spm` relative to the working directory, a
  fixed-name local read that SANDBOX does not cover.
- PlantUML's profile is a process-wide static. The engine's setup replaces whatever was cached before, and every draw
  checks it. The tests do not depend on order: `PlantUmlTest` goes through the engine, `DrawingTest` and `SandboxTest`
  call `Drawing` directly, whose first use runs the same setup; the tests that switch to another profile put SANDBOX
  back.
- Related: [0060](0060-logging-conventions.md) (logging), [0124](0124-read-aloud-speech.md) (`EspeakLocator`),
  [0165](0165-ebook-reader.md) (untrusted documents).
