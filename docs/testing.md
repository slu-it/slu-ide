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
```bash
./gradlew testClasses -q                                              # compile all main and test code
./gradlew :modules:git-log:test -q                                    # unit tests of one module
./gradlew :modules:git-log:uiTest --tests 'GitLogWritesRefsUiTest' -q # one UI test class (needs DISPLAY)
./gradlew :ide:uiTest --tests 'MainWindow*' -q                        # UI test classes by pattern
```

## Display handling
- **Linux without `DISPLAY` and without `CI`:** `uiTest` is *skipped* (not failed), so a headless sandbox stays usable.
  Skipped is not verified: run `.claude/scripts/setup-display.sh`, `export DISPLAY=:99`, then the `uiTest` classes
  you need ("Running a subset"); only the main session's final gate runs all of them, with
  `.claude/scripts/full-build.sh`. It sets up (and if needed repairs) displays `:99` upwards first,
  `SLUIDE_UI_DISPLAYS` of them (default 8, about 9.5 GB in use at peak; set it lower on a smaller machine), and
  passes them to Gradle as the pool. `DISPLAY=:99` is enough for single classes.
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
- `.claude/scripts/check-tests.sh [files]` reports fixed sleeps without a reason, synthetic OS input,
  `waitForFxEvents()` right after `interact` and test classes over 800 lines; a hook runs it on newly written test
  code. `.claude/scripts/test-times.sh` lists task times and the slowest classes from the last test results.

## Writing UI tests
- Use `@ExtendWith(ApplicationExtension::class)`, build the real component in a `@Start` method, and inspect it
  through the scene graph (`robot.lookup(".tree-cell")`). Examples: `FileExplorerViewUiTest`, `MainWindowProjectUiTest`.
- Change state via `robot.interact { ... }`, not via synthetic mouse/keyboard input (`robot.clickOn`, `type`,
  `moveTo`, `scroll`). Synthetic input needs OS permissions on macOS and is flaky on virtual displays. `interact`
  already ends with `WaitForAsyncUtils.waitForFxEvents()` (TestFX 4.0.18); call that again only after work outside
  `interact`, e.g. a file written by the test.
- Wait on a signal, never a fixed sleep: an idle barrier of the subject's executor (`readersIdle()` in git-log,
  `commitReaderIdle()`), a latch in a fake, a log line, or a sentinel (a second watch that must have seen a later
  change before a "nothing reported" check, as in `FileWatcherTest`). A negative check that has no signal keeps a short
  sleep (about 200 ms) with a comment saying why. Debounces and delays the test does not test become constructor
  parameters with the real value as default (`CodeView(highlightPause)`, `GitCli(stopGrace)`, `dragExpandDelay`).
- To test real mouse handling (e.g. a press that must not reach a parent), fire `MouseEvent`s at the node with
  `Event.fireEvent` instead of calling `Button.fire()`, which skips the mouse handlers. Example: `fireMouse` in
  `EditorAreaUiFixture`.
- `lookup(...).queryAllAs(...)` returns nodes in no particular order; sort (e.g. by `TreeCell.index`) before asserting.
- Native dialogs (`DirectoryChooser`) cannot be driven by TestFX; test the logic behind them (`ProjectService`).
- Large UI test subjects are split by topic: an abstract `<Subject>UiFixture` (fake sources, `@AfterEach`, helpers)
  and one `<Subject><Topic>UiTest` per topic (`GitLogView*`, `GitLogWrites*`, `EditorArea*`, `FileExplorerView*`,
  `MainWindow*`).
  ApplicationExtension only finds `@Start` on the test class itself, so each topic class declares `@Start` and calls
  the fixture's `startView`. Read the fixture and the one topic class you work on; add new tests to the matching topic
  class, or a new one when a topic grows past about 800 lines.

## Running and screenshotting the real app (headless Linux sandbox)
```bash
.claude/scripts/setup-display.sh          # installs Xvfb + GTK libs + xdotool/ImageMagick, starts display :99
export DISPLAY=:99
./gradlew :ide:bootJar
java -Dprism.order=sw -jar ide/build/libs/ide-0.1.0-SNAPSHOT.jar <folder> &   # not ide-*.jar: that also matches -plain.jar
import -window root shot.png              # screenshot
xdotool key ctrl+o                         # drive the UI
```
- There is no window manager: dialogs get no keyboard focus until you run `xdotool windowfocus <id>`
  (find the id with `xdotool search --name '<title>'`) and appear partly off-screen.
- Without a window manager, popups (menus, dropdowns, context menus) close as soon as the mouse is over them, and
  context menus never appear. Start one to test them: `apt-get install -y openbox` once, then `openbox &`.
- Pass the project folder as an argument (above) instead of driving the GTK folder chooser, which opens in its
  "Recent" view. Files in the explorer open with a double-click (`xdotool mousemove X Y click --repeat 2 1`).
- `setup-display.sh` only reports a display as running if it answers (`xdpyinfo`, with a timeout). One that does not
  (Xvfb gone with its `/tmp/.X99-lock` and `/tmp/.X11-unix/X99` left over, or hung) is stopped, cleaned up and started
  again, so rerunning the script is the fix whenever UI tests fail with "Unable to open DISPLAY".
- Stop Xvfb when done; never claim the UI was verified unless you actually ran it.
