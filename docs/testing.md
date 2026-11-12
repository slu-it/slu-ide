# Testing

## Two kinds of tests
| Kind | Where / how | Needs display |
|------|-------------|---------------|
| Unit tests | `src/test/kotlin`, Gradle task `test` (JUnit 5 + kotlin-test) | no |
| UI tests | Same source set, class annotated `@Tag("ui")`, run by Gradle task `uiTest` (TestFX) | yes |

`./gradlew build` runs both (`check` depends on `uiTest`, or on its two parts, see below). With `-q` the output is only errors and failed tests, each
with its exception and stack trace up to the test (`testLogging` in the root build); passed tests print nothing. The `uiTest` task is defined once in the root
`build.gradle.kts` and uses software rendering (`-Dprism.order=sw`). Tests of parallel modules on one display took each
other's window focus and closed each other's popups, so each running `uiTest` task gets a display of its own from a
pool (ADR 0081), given as `-Psluide.uiDisplays=:99,:100,…`; without it the pool is just `DISPLAY` and the tasks run
one at a time (CI). A module whose UI tests would take longest alone (git-log, previews) runs them as two tasks, `uiTestPart1` and
`uiTestPart2`, split by a class name pattern in the root build's `uiTestSplits` (ADR 0082). `uiTest` still runs all its
classes and takes `--tests`; `./gradlew build uiTest` would run them twice. `-x uiTest` leaves out the parts too.

Unit tests run one class after another in one JVM, with two exceptions that wait on child processes or the file
system rather than the CPU: the git module's `test` runs in three forks (`maxParallelForks`; its global log sinks are
per JVM, so forks keep them apart), and `FileWatcherTest` runs its tests concurrently (`@Execution(CONCURRENT)`,
enabled by `junit-platform.properties` in file-watcher's test resources; every other class stays same-thread).

## Running a subset
Subagents verify with subsets; the full `./gradlew build` is the main session's final gate (delegate skill).
`.claude/scripts/verify.sh` does a subset in one call: `spotlessApply`, compiles all main and test code, runs the
targets and prints a short digest (compile errors, counts per task, failures with their `dev.sluide` frames,
`check-tests.sh` findings), the full log in `build/verify/verify.log`. Targets are test class names or globs, matched
against the test file's name and the class in it that holds the tests (sent to `test` or `uiTest` by `@Tag("ui")`; a
name that exists in two modules runs in both), `:module` paths for a module's unit tests, and `--changed` for the
unit tests of every module changed against master plus the related test classes. `--list` prints what the targets
resolve to and stops before formatting and Gradle.
It sets up the display (`--display N`), uses `--no-daemon` in worktrees and ends with `verify: OK`, `FAILED` (also
when a named class has no result) or `INCOMPLETE` (UI tests not run); `OK (compiled only, no tests run)` when
`--changed` found nothing to test. `--no-format` leaves the sources alone (reviewers). Gradle arguments after `--`
must not skip test tasks (`-x`): old passing results of the skipped classes would count.
`.claude/scripts/related-tests.sh [files]` lists the test classes that name the types and top-level functions
declared in the given (by default: the changed) files, per module and task: public names in every module, `internal`
ones in the file's own module. A name used by more than 25 test files is reported as skipped; `verify.sh --changed`
shows those lines: only the unit tests of the changed modules still run, so the UI tests of such a name and its
tests in other modules must be named as targets. `--changed` refuses when it would run more than 12 UI test classes;
name the classes then.
```bash
.claude/scripts/verify.sh --display 111 --changed GitLogWritesRefsUiTest  # what an implementer runs
.claude/scripts/verify.sh 'MainWindow*' :modules:git                  # a pattern and one module's unit tests
./gradlew testClasses -q                                              # compile all main and test code
./gradlew :modules:git-log:test -q                                    # unit tests of one module
./gradlew :modules:git-log:uiTest --tests 'GitLogWritesRefsUiTest' -q # one UI test class (needs DISPLAY)
./gradlew :ide:uiTest --tests 'MainWindow*' -q                        # UI test classes by pattern
```

Tests against a real external tool are opt-in, so that the build does not depend on what runs on the machine.
`RealEngineSmokeTest` (module `docker`) runs the Docker CLI against the local engine only with
`SLUIDE_DOCKER_SMOKE=1` set, and still skips itself when `docker` is missing, the engine does not answer or no small
image (`bash`, `debian`, `alpine`, `busybox`) is present; it creates and removes one container of its own:
`SLUIDE_DOCKER_SMOKE=1 ./gradlew :modules:docker:test --tests 'RealEngineSmokeTest' -q`.
`RealDatabaseSmokeTest` (module `databases`) runs the JDBC sessions against PostgreSQL 17 and MariaDB 11 only with
`SLUIDE_DB_SMOKE=1` set, and skips itself when `docker` or the image (`postgres:17-alpine`, `mariadb:11`) is missing;
its fixture `RealDatabases` starts one container per kind on a 127.0.0.1 port and stops it afterwards:
`SLUIDE_DB_SMOKE=1 ./gradlew :modules:databases:test --tests 'RealDatabaseSmokeTest' -q`.
`KokoroEngineRealTest` (module `speech`) synthesizes one sentence with the real Kokoro model only with
`SLUIDE_KOKORO_SMOKE=1` set, and skips itself when the model files are not in `~/.sluide/models/kokoro-v1.0/` (or
`SLUIDE_KOKORO_MODEL_DIR`) or espeak-ng is missing; `SLUIDE_KOKORO_WAV=<file>` also writes the audio:
`SLUIDE_KOKORO_SMOKE=1 ./gradlew :modules:speech:test --tests 'KokoroEngineRealTest' -q`.
`OpusMtEngineRealTest` (module `translation`) translates with the real Opus-MT models only with
`SLUIDE_OPUSMT_SMOKE=1` set, and skips a case whose Medium models (de-en, en-de, en-sv, en-zh) are not in
`~/.sluide/models/opus-mt/`; it checks the placeholder form against every probed pair and prints each translation with
its time: `SLUIDE_OPUSMT_SMOKE=1 ./gradlew :modules:translation:test --tests 'OpusMtEngineRealTest' -i`.

## Display handling
- **Linux without `DISPLAY` and without `CI`:** `uiTest` is *skipped* (not failed), so a headless sandbox stays usable.
  Skipped is not verified: `verify.sh --display N` sets up the display itself; by hand, run
  `.claude/scripts/setup-display.sh`, `export DISPLAY=:99`, then the `uiTest` classes you need ("Running a subset");
  only the main session's final gate runs all of them, with `.claude/scripts/full-build.sh`. It sets up (and if needed
  repairs) displays `:99` upwards first, `SLUIDE_UI_DISPLAYS` of them (default 8, about 9.5 GB in use at peak; set it
  lower on a smaller machine), and passes them to Gradle as the pool. `DISPLAY=:99` is enough for single classes.
- **CI:** `CI` is always set, so a missing display fails loudly. The Linux job runs Gradle under `xvfb-run -a`;
  the macOS jobs of CI and release skip `uiTest` (about 30 minutes there). The *macOS UI tests* workflow
  (`.github/workflows/macos-ui-tests.yml`, started by hand) runs them on the native macOS display.

## Choosing the test level
A behavior is tested once, at the lowest level that can see it
([ADR 0084](adr/0084-testability-logic-out-of-controls.md)).
- **Does it need a scene?** Only rendering, focus, event routing (a press that must not reach a parent), bindings
  and WebView/WebKit behavior do. Texts, enable states, validation messages, path logic, sizes, key-to-action mapping
  and what gets read or written do not: they are unit tests.
- **Logic sits in a control?** Extract it rather than write a UI test: an `internal` pure function
  (`missingMessage`, `counterText`, `zoomKeyAction`, `checkedSize`), a plain class beside the view
  (`IgnoreDotsTracker`, `EnvFileWriter`, `ScrollRouting`), or an FX-free host the view builds and a test fakes
  (`ExplorerEditHost`, a class of function properties; `FakeEditHost` in the tests). Keep one UI test per such
  connection that fails if the view stops calling the logic (save → lookup again, move → `moved`, dispose → late
  answers dropped); the logic's cases stay in the unit test.
- **Search before adding.** Grep for the asserted message, the function and its test class; extend that test instead
  of adding a second one. Shared code (the fold tree of JSON/XML/YAML, `ZoomControls`, `FoldControls`) is tested in
  one place, with one smoke test per preview that uses it. Feature behavior is tested in its module; `ide` tests only
  the wiring between modules.
- **Same test, other input:** one `@ParameterizedTest` (`@ValueSource`, `@MethodSource`, `@CsvSource`) instead of
  copies.
- **It must fail without the code.** For a test whose failure is not obvious (wiring, races, negative checks),
  break the code once, see the test fail, restore it.
- `.claude/scripts/check-tests.sh [files]` reports fixed sleeps without a reason (poll intervals inside a `while`
  loop do not count), synthetic OS input without a `//` reason, `waitForFxEvents()` right after `interact` and test
  classes over 800 lines; a hook runs it on newly written test code. `.claude/scripts/test-times.sh` lists task
  times and the slowest classes from the last test results.

## Writing UI tests
- Use `@ExtendWith(ApplicationExtension::class)`, build the real component in a `@Start` method, and inspect it
  through the scene graph (`robot.lookup(".tree-cell")`). Examples: `FileExplorerViewUiTest`, `MainWindowProjectUiTest`.
- Change state via `robot.interact { ... }`, not via synthetic mouse/keyboard input (`robot.clickOn`, `type`,
  `moveTo`, `scroll`). Synthetic input needs OS permissions on macOS and is flaky on virtual displays. `interact`
  already ends with `WaitForAsyncUtils.waitForFxEvents()` (TestFX 4.0.18); call that again only after work outside
  `interact`, e.g. a file written by the test.
- Wait on a signal, never a fixed sleep: an idle barrier of the subject's executor (`readersIdle()` in git-log,
  `commitReaderIdle()`), a latch in a fake, a log line, or a sentinel (a second watch that must have seen a later
  change before a "nothing reported" check, as in `FileWatcherTest`). A short sleep as the interval of a loop that
  re-checks its condition until a deadline is such a wait. A negative check that has no signal keeps a short sleep
  (about 200 ms) with a comment saying why. Debounces and delays the test does not test become constructor
  parameters with the real value as default (`CodeView(highlightPause)`, `GitCli(stopGrace)`, `dragExpandDelay`).
- To test real mouse handling (e.g. a press that must not reach a parent), fire `MouseEvent`s at the node with
  `Event.fireEvent` instead of calling `Button.fire()`, which skips the mouse handlers. Example: `fireMouse` in
  `EditorAreaUiFixture`.
- Assert the state a view starts in, before the test changes anything: a view whose tests all add a second item
  first can hide that the first one never shows (the Databases console's results).
- Don't call a `ContextMenu`'s `onShowing` by hand in a test: JavaFX never shows an empty context menu, so a menu
  filled only on showing never opens in the app (the Databases object tree). Read its `items` as the app would.
- `lookup(...).queryAllAs(...)` returns nodes in no particular order; sort (e.g. by `TreeCell.index`) before asserting.
- Native dialogs (`DirectoryChooser`) cannot be driven by TestFX; test the logic behind them (`ProjectService`).
- Large UI test subjects are split by topic: an abstract `<Subject>UiFixture` (fake sources, `@AfterEach`, helpers)
  and one `<Subject><Topic>UiTest` per topic (`GitLogView*`, `GitLogWrites*`, `EditorArea*`, `FileExplorerView*`,
  `MainWindow*`).
  ApplicationExtension only finds `@Start` on the test class itself, so each topic class declares `@Start` and calls
  the fixture's `startView`. Read the fixture and the one topic class you work on; add new tests to the matching topic
  class, or a new one when a topic grows past about 800 lines.
- Drags inside one window use plain mouse events (`TabStrip`, the explorer), not `startDragAndDrop`: on Linux, GTK's
  drag loop leaves a pointer grab, so a dialog opened after a platform drop gets keys but no clicks. UI tests with a
  seam instead of the dialog never see this; check anything that opens a dialog after a gesture in the real app.
- Fake language servers (`FakeLanguageServers`, `FakeServer`): a request cancelled before the session's outbound
  thread sent it never reaches the fake, so "the server got n requests" after a latest-wins sequence depends on
  timing. Wait for each request to arrive before the next step cancels it (`CompletionModelTest`), or assert on the
  last request only.

## WebView pages
What a WebView or a stylesheet paints can be wrong while every data check passes. In the ebook round, tests that
asked WebKit "is the highlight set?" passed while removed highlights stayed painted, search snippets were black on
dark (a `.text` selector: a plain `Text` node has no style class) and the generic `serif` font rendered sans.
- Test a mechanism's paint once where it lives (`TextMarksUiTest` snapshots the web view), and each page stylesheet
  that styles it once more (`EbookPagePaintUiTest`, `PreviewHighlightUiTest`): sample pixels of a marked word, or
  read the computed style. Wait on frames or a pulse, never a fixed sleep.
- The WebKit of JavaFX does not invalidate the paint of a CSS Custom Highlight that is replaced or removed
  (`CSS.highlights.set/delete`): the old range stays painted until it scrolls out of view and back. `text-marks.js`
  sets `opacity: 0.999` on the spans the old and new range touch and takes it back in the next animation frame (a
  repaint without layout; `outline-color` and a no-op change do not repaint).
- A page's `<meta>` CSP is enforced with `engine.loadContent`; test the policy on a WebView once (an injected inline
  handler must not run), not only its string.

## Portability: CI runs Linux x64 and macOS
The sandbox is Linux on ARM with fast cores; CI (`.github/workflows/ci.yml`) runs the full `build` on slower x64 Linux
runners and `build -x uiTest` on macOS, and the user only sees those failures after pushing. Every case below broke
CI once.
- **Linked temp folders:** on macOS `/var/folders/...` is a link to `/private/var/folders/...`. When the code resolves
  links (the file watcher reports real paths), compare against `tempDir.toRealPath()`. A missing file has no real
  path, so code comparing real paths handles missing files first; test such code with a project reached through a
  `Files.createSymbolicLink` (`BindingRenameFileStatesTest`), which fails on Linux too.
- **Few cores:** the macOS runner has 3 cores, so the common `ForkJoinPool` has 2 threads. Code that must not stall
  never waits on `Process.onExit()` or other default async completions (git cancel once hung until its timeout).
  Reproduce with `-XX:ActiveProcessorCount=3`.
- **Stack depth:** x64 runners overflowed a recursion of 1000 levels that passes here. Keep nesting caps modest
  (previews use 200-250) and try a deep case with `-Xss512k`.
- **Time limits:** runners are 4-9 times slower than the sandbox. A performance test asserts how time grows (e.g. 2x
  input, at most ~3x time) or keeps a margin of 10x over the local time. Measure the growth in the test thread's CPU
  time (`ManagementFactory.getThreadMXBean().currentThreadCpuTime`), not `System.nanoTime()`: the larger input
  triggers young GCs the smaller one misses, and their pauses grow with fewer GC threads (a linear scanner measured
  9x for 4x the text on the 3-core macOS runner). Reproduce with `-XX:ActiveProcessorCount=3` and `-Xlog:gc`.
- **Git encodings:** git converts commit messages to `i18n.commitEncoding` differently per OS (iconv). Tests read
  messages with `--encoding=UTF-8`, as the app does.
- **File watching:** the macOS JDK polls (every 2 s, by path, mtime per entry): late events, no events for a folder
  deleted and recreated in between. Tests with a real `FileWatcher` hold under that or are skipped on macOS with an
  `os.name` assumption and a reason.
- **Gaps between FX steps:** a test thread that polls a state set on the FX thread (`awaitRunning`) and then queues
  work races whatever the background thread dispatches next; under load the work lands in between (a binding opened
  and pulled, then re-opened and pulled again by the late connection announcement). Announce what belongs together in one FX step, and reproduce by
  widening the gap with a temporary `Thread.sleep` on the background thread; a regression test drains a queued
  dispatch one step at a time (`LanguageServerRacesTest`).
- **State before side effects:** code that publishes a state and then, still in the same step, schedules or kills
  something (a crashed language server shows `Restarting` before its retry is scheduled and before the old process
  is killed) lets a polling test see the state first; on the macOS runner `runNext()` found no retry due. Wait on
  the side effect itself (`FakeScheduler.runNext` waits for a due task; `awaitTrue { destroyed }`), not on the state
  alone. Reproduce with a temporary `Thread.sleep` right after the state is set.
- **Media playback:** JavaFX Media on Linux plays through ALSA, and CI runners and the sandbox have no sound card:
  every `MediaPlayer` then fails with "Could not create player!" ("Could not open audio device" with
  `-Djfxmedia.loglevel=debug`). So the root `build.gradle.kts` points the test tasks of the modules that play sound
  (`nullAlsaProjects`: `media`, and `speech` for its audio sink) at a null default device (`ALSA_CONFIG_PATH` with
  `gradle/alsa/null-default.conf`) when `/dev/snd` is missing. The null device has no
  clock: a file "plays" to its end within milliseconds, so the UI tests check states, not positions or timing.
  There are no ffmpeg libraries here, so only WAV and AIFF load; the tests generate a sine WAV (`writeSineWav`).
  To play media in the real app in the sandbox, start it with the same `ALSA_CONFIG_PATH`
  ([ADR 0123](adr/0123-media-playback.md)). A Read Aloud reading on the null device advances sentence by sentence
  (the sentence highlight shows), but its played-frames count jumps per write, so the word highlight cannot be seen
  there. For a real reading, link `~/.sluide/models/kokoro-v1.0` into the throwaway home `run-app.sh` prints
  (espeak-ng is installed with apt): Download in the tab then finds the files and the notice goes away.
  For a real translation, link the pair folders of `~/.sluide/models/opus-mt/` (`de-en`, ...) into
  `<home>/.sluide/models/opus-mt/`; a model the app downloads itself lands in the throwaway home and is gone after
  `--stop`. The catalog's URLs and sha256 values download them by hand (`curl -L`, then `sha256sum`).

## Running and screenshotting the real app (headless Linux sandbox)
```bash
.claude/scripts/run-app.sh <folder>                 # dark theme on display :107 (outside the gate's :99-:106); prints pid=, display=, home=, log=
.claude/scripts/run-app.sh --theme light --display 108 <folder>
.claude/scripts/run-app.sh --setting python.ty.path=/path/to/ty <folder>   # extra settings.properties lines
DISPLAY=:107 import -window root <scratchpad>/shot.png   # screenshot, then look at it with Read
DISPLAY=:107 xdotool key ctrl+o                      # drive the UI
.claude/scripts/run-app.sh --stop <pid>
```
- The script builds `:ide:bootJar` when the sources are newer, sets up the display (`setup-display.sh`) and openbox,
  and starts the app with a throwaway `user.home` holding only the theme: settings, projects and scratches start
  empty, git and shells still use the real `HOME`. A folder with a build tool therefore asks "Trust Folder?" on
  every start: answer it with `xdotool` (or check the dialog itself).
- Check UI work in the dark theme (the default); add `--theme light` when theme CSS or colours changed.
- Pass the project folder as an argument instead of driving the GTK folder chooser, which opens in its "Recent" view.
  Files in the explorer open with a double-click (`xdotool mousemove X Y click --repeat 2 1`).
- Without a window manager, dialogs get no keyboard focus and popups close at once; the script starts openbox. If a
  dialog still has no focus: `xdotool windowfocus <id>` (id from `xdotool search --name '<title>'`).
- openbox binds Ctrl+Alt+Left/Right (and other Ctrl+Alt arrows) to desktop switching, so those keys never reach the
  app on this display (Back/Forward of the Navigate menu): use the menu, or a UI test, to check them.
- `xdotool key F2` (and other function keys) arrives with Alt held on this display's keymap (`xev` shows `Alt_L`,
  state `0x8`), so a view that ignores keys with Alt never sees it. Check such keys with a UI test instead.
- `setup-display.sh` only reports a display as running if it answers (`xdpyinfo`, with a timeout). One that does not
  (Xvfb gone with its `/tmp/.X99-lock` and `/tmp/.X11-unix/X99` left over, or hung) is stopped, cleaned up and started
  again, so rerunning the script is the fix whenever UI tests fail with "Unable to open DISPLAY".
- The sandbox's `xclip`/`xsel` are shims for the host clipboard, not the X clipboard: to see what the app copied,
  paste it into an editor tab of the app. Never write with them (it lands on the user's clipboard). To put an image
  (or other content) on the X clipboard, run a small JavaFX program on the app's display that sets it and stays alive
  (`--module-path` with the `javafx-base`/`javafx-graphics` linux jars from `~/.gradle/caches`; the sandbox JDK is
  headless, so AWT's clipboard is not available).
- The sandbox JDK is headless (no `libawt_xawt`), and the app runs with `.headless(false)`: anything that touches
  `BufferedImage`/`ImageIO` (pasted images, SVG → PNG) fails with an `UnsatisfiedLinkError` while `DISPLAY` is set.
  Start the app with `JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djava.awt.headless=true" run-app.sh …` for such checks
  (Spring keeps a value that is already set).
- Never claim the UI was verified unless you actually ran it.
- **Secrets leaving memory** (features that wipe plaintext or passwords, ADR 0162): tests can only check our own
  fields; check the running app. Type marker strings, do what should wipe them (e.g. Lock), then
  `jcmd <pid> GC.run` twice and `jcmd <pid> GC.heap_dump <file>` (live objects only) and count the markers as Latin-1
  (`grep -a -c`) and UTF-16. A hit is still referenced: trace its referrers with an HPROF parser (an `explorer` can
  write one in Python). Found this way: a node kept across Lock holds the old subtree through its render peer's
  `parent`; `PasswordField.clear()` keeps the bytes (overwrite with an ASCII filler first: a non-Latin-1 one makes the
  builder switch arrays); jsoup's thread-local pools keep parsed text. Delete the dumps afterwards.
