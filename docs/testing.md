# Testing

## Two kinds of tests
| Kind | Where / how | Needs display |
|------|-------------|---------------|
| Unit tests | `src/test/kotlin`, Gradle task `test` (JUnit 5 + kotlin-test) | no |
| UI tests | Same source set, class annotated `@Tag("ui")`, run by Gradle task `uiTest` (TestFX) | yes |

`./gradlew build` runs both (`check` depends on `uiTest`). The `uiTest` task is defined once in the root `build.gradle.kts`
and uses software rendering (`-Dprism.order=sw`).

## Display handling
- **Linux without `DISPLAY` and without `CI`:** `uiTest` is *skipped* (not failed), so a headless sandbox stays usable.
  Skipped is not verified: run `.claude/scripts/setup-display.sh`, `export DISPLAY=:99`, then `./gradlew uiTest`.
- **CI:** `CI` is always set, so a missing display fails loudly. Linux jobs run Gradle under `xvfb-run -a`; macOS uses its native display.

## Writing UI tests
- Use `@ExtendWith(ApplicationExtension::class)`, build the real component in a `@Start` method, and inspect it
  through the scene graph (`robot.lookup(".tree-cell")`). Examples: `FileExplorerViewUiTest`, `MainWindowUiTest`.
- Change state via `robot.interact { ... }` followed by `WaitForAsyncUtils.waitForFxEvents()`, not via synthetic
  mouse/keyboard input. Synthetic input needs OS permissions on macOS and is flaky on virtual displays.
- `lookup(...).queryAllAs(...)` returns nodes in no particular order; sort (e.g. by `TreeCell.index`) before asserting.
- Native dialogs (`DirectoryChooser`) cannot be driven by TestFX; test the logic behind them (`ProjectService`).
- Keep logic out of controls so most behavior stays in fast unit tests.

## Running and screenshotting the real app (headless Linux sandbox)
```bash
.claude/scripts/setup-display.sh          # installs Xvfb + GTK libs + xdotool/ImageMagick, starts display :99
export DISPLAY=:99
./gradlew :ide:bootJar
java -Dprism.order=sw -jar ide/build/libs/ide-*.jar &
import -window root shot.png              # screenshot
xdotool key ctrl+o                         # drive the UI
```
- There is no window manager: dialogs get no keyboard focus until you run `xdotool windowfocus <id>`
  (find the id with `xdotool search --name '<title>'`) and appear partly off-screen.
- The GTK folder chooser opens in its "Recent" view: pick a sidebar bookmark and click Open instead of typing a path.
- Stop Xvfb when done; never claim the UI was verified unless you actually ran it.
