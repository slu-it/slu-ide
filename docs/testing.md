# Testing

## Two kinds of tests
| Kind | Where / how | Needs display |
|------|-------------|---------------|
| Unit tests | `src/test/kotlin`, Gradle task `test` (JUnit 5 + kotlin-test) | no |
| UI tests | Same source set, class annotated `@Tag("ui")`, run by Gradle task `uiTest` (TestFX) | yes |

`./gradlew build` runs both (`check` depends on `uiTest`). With `-q` the output is only errors and failed tests, each
with its exception and stack trace up to the test (`testLogging` in the root build); passed tests print nothing. The `uiTest` task is defined once in the root
`build.gradle.kts` and uses software rendering (`-Dprism.order=sw`). Tests of parallel modules on one display took each
other's window focus and closed each other's popups, so each running `uiTest` task gets a display of its own from a
pool (ADR 0081), given as `-Psluide.uiDisplays=:99,:100,…`; without it the pool is just `DISPLAY` and the tasks run
one at a time (CI).

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
  `SLUIDE_UI_DISPLAYS` of them (default 6, about 7.7 GB in use at peak; set it lower on a smaller machine), and
  passes them to Gradle as the pool. `DISPLAY=:99` is enough for single classes.
- **CI:** `CI` is always set, so a missing display fails loudly. The Linux job runs Gradle under `xvfb-run -a`;
  the macOS jobs of CI and release skip `uiTest` (about 30 minutes there). The *macOS UI tests* workflow
  (`.github/workflows/macos-ui-tests.yml`, started by hand) runs them on the native macOS display.

## Writing UI tests
- Use `@ExtendWith(ApplicationExtension::class)`, build the real component in a `@Start` method, and inspect it
  through the scene graph (`robot.lookup(".tree-cell")`). Examples: `FileExplorerViewUiTest`, `MainWindowProjectUiTest`.
- Change state via `robot.interact { ... }` followed by `WaitForAsyncUtils.waitForFxEvents()`, not via synthetic
  mouse/keyboard input. Synthetic input needs OS permissions on macOS and is flaky on virtual displays.
- To test real mouse handling (e.g. a press that must not reach a parent), fire `MouseEvent`s at the node with
  `Event.fireEvent` instead of calling `Button.fire()`, which skips the mouse handlers. Example: `fireMouse` in
  `EditorAreaUiFixture`.
- `lookup(...).queryAllAs(...)` returns nodes in no particular order; sort (e.g. by `TreeCell.index`) before asserting.
- Native dialogs (`DirectoryChooser`) cannot be driven by TestFX; test the logic behind them (`ProjectService`).
- Keep logic out of controls so most behavior stays in fast unit tests.
- Large UI test subjects are split by topic: an abstract `<Subject>UiFixture` (fake sources, `@AfterEach`, helpers)
  and one `<Subject><Topic>UiTest` per topic (`GitLogView*`, `GitLogWrites*`, `EditorArea*`, `MainWindow*`).
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
