# Architecture

SluIde is a lightweight desktop IDE written in Kotlin. It is one Spring Boot application with a JavaFX UI,
assembled from Gradle modules.

## Repository layout
| Path | Purpose |
|------|---------|
| `ide/` | Application module: launcher, Spring Boot configuration, main window. Produces the fat jar (`./gradlew :ide:bootJar`). |
| `modules/<name>/` | One Gradle module per IDE capability: `file-explorer`, `editor` (tabs), `file-viewers` (views of files and side-by-side diffs; the previews of Markdown, HTML, SVG, CSV and notebooks and the image view are `javafx.web` WebViews), `file-watcher` (changes on disk), `terminal` (shells in editor tabs), `settings` (settings state, storage and dialog), `docker-sandbox` (`sbx` in terminal tabs), `git` (git status of the open repository and diffs against `HEAD`), `text-editing` (logic of editing text without JavaFX: line endings, changes on disk). |
| `gradle/libs.versions.toml` | Single place for dependency and plugin versions. |
| `docs/` | This documentation. Start at [index.md](index.md). |
| `.claude/` | Claude Code settings, agents, rules, scripts. |
| `.github/` | CI/CD workflows. |

## Module rules
- Feature modules (`modules/*`) never depend on `ide`. `ide` depends on all feature modules and wires them together.
- Feature modules expose UI as JavaFX nodes/controls registered as Spring beans (`@Component`), so `ide` can inject them.
  Beans that hold the state of one window (a project) are `@Scope("window")`.
- Cross-module state (e.g. the current project folder) lives in `ide` or a shared module, and modules receive it
  through their public API (e.g. `FileExplorerView.open(path)`), not by reaching into each other.
- Documented module dependencies (`api` unless noted):
  - `file-explorer` -> `file-watcher` and `editor` -> `file-watcher`: loaded folders and open files are watched
    ([ADR 0006](adr/0006-file-watching.md)).
  - `editor` -> `file-viewers`: the editor area asks `FileViewers` for the view of a file
    ([ADR 0004](adr/0004-file-viewer-selection.md)).
  - `file-viewers` -> `text-editing` (`implementation`): code views keep line endings and decide what a change on disk
    means for edited text ([ADR 0020](adr/0020-editing-and-saving-files.md)).
  - `terminal` -> `editor`: terminals are shown as `EditorTab`s ([ADR 0007](adr/0007-terminal.md)).
  - `docker-sandbox` -> `terminal`: sandboxes run in terminal tabs and look `sbx` up in the same login shell
    (`loginShellRunning`) ([ADR 0010](adr/0010-docker-sandbox.md)).
  - `git` -> `file-watcher`: the `.git` folder is watched to refresh the status
    ([ADR 0013](adr/0013-git-status.md)). Markers and diffs reach the explorer and the editor through `ide`
    (`MainWindow`), not through module dependencies ([ADR 0015](adr/0015-git-diff.md)).
  - `file-explorer`, `editor`, `terminal` -> `settings`: they use the configured fonts
    ([ADR 0008](adr/0008-settings.md)).
- Shared build configuration (JDK toolchain, test setup, Spring BOM) is in the root `build.gradle.kts`.

## Runtime
1. `SluIdeLauncher.main` calls `Application.launch(SluIdeApplication)`.
2. `SluIdeApplication.init()` starts a non-web Spring context scanning `dev.sluide` ([ADR 0002](adr/0002-spring-inside-javafx.md)).
3. `SluIdeApplication.start()` asks `ProjectWindows` to show the first window in the primary stage, with the first
   folder from the command line; further folders open in new windows.
4. Each window gets its own instances of the beans with `@Scope("window")` (`WindowScope`, [ADR 0014](adr/0014-project-windows.md)):
   `MainWindow`, `ProjectService`, `FileExplorerView`, `EditorArea`, `Terminals`, `DockerSandbox`, `GitStatusService`.
   They are created when the window opens and destroyed (`close()`) when it closes. Singletons must not depend on
   them.
5. When the last window is closed, `SluIdeApplication.stop()` closes the Spring context.

## Package convention
`dev.sluide.<module>` per module, e.g. `dev.sluide.fileexplorer`, `dev.sluide.fileviewers`, `dev.sluide.ide`. All must live below
`dev.sluide` to be found by component scanning.

## Build and release
- **CI** (`.github/workflows/ci.yml`): `./gradlew build` on Linux and macOS for every push to `main`/`master` and every PR.
- **Release** (`.github/workflows/release.yml`): pushing a tag `vX.Y.Z` publishes `dev.sluide:ide-<os>-<arch>:X.Y.Z`
  (the fat jar) to https://maven.pkg.github.com/slu-it/slu-ide. The version comes from the tag via `-PreleaseVersion`.
  Platforms: linux-x64, linux-aarch64, mac-aarch64 ([ADR 0003](adr/0003-javafx-platform-jars.md)).
  The macOS job also attaches `SluIde-X.Y.Z-mac-aarch64.dmg` to the GitHub release
  ([ADR 0011](adr/0011-native-app-packaging.md)).
- **Native app**: `./gradlew :ide:jpackageImage` (SluIde.app on macOS) and `:ide:jpackageDmg` (macOS) bundle the
  fat jar with a jlink runtime ([ADR 0011](adr/0011-native-app-packaging.md)).
- Local builds use version `0.1.0-SNAPSHOT`; the publish setup lives in `ide/build.gradle.kts`.
