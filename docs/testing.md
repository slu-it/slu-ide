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

## Manual run
For an end-to-end look at the real app in the sandbox, see the display workflow in `CLAUDE.md`.
