# Architecture

Thoreau is a lightweight desktop IDE written in Kotlin. It is one Spring Boot application with a JavaFX UI,
assembled from Gradle modules.

## Repository layout
| Path | Purpose |
|------|---------|
| `ide/` | Application module: launcher, Spring Boot configuration, main window. Produces the fat jar (`./gradlew :ide:bootJar`). |
| `modules/<name>/` | One Gradle module per IDE capability: `file-explorer`, `editor` (tabs), `file-viewers` (views of files, change markers and side-by-side diffs; the previews of Markdown, HTML, SVG, CSV, cookie jars, notebooks, Mermaid, PEM and Bicep files and the image view are `javafx.web` WebViews; own tokenizers for TOML, Bicep and KQL), `file-watcher` (changes on disk), `terminal` (shells in editor tabs), `settings` (settings state, storage and dialog), `docker-sandbox` (`sbx` in terminal tabs), `git` (git status of the open repository, diffs against `HEAD`, pages of the git log, refs, commit details, changed files and their diffs, comparison counts and differences from the working tree, revisions resolved to commits and the branches containing a commit, the history of a file; checkouts and changes of branches and tags, cherry-pick, revert and reset; fetch), `git-log` (the git log area under the editor), `text-editing` (logic of editing text without JavaFX: line endings, changes on disk, indentation incl. `.editorconfig` via ec4j-core, line operations, comments, find and replace, go to line), `http-client` (`.http` files: requests run from the editor with environments and auth), `projects` (remembered projects and the project picker). |
| `gradle/libs.versions.toml` | Single place for dependency and plugin versions. |
| `docs/` | This documentation. Start at [index.md](index.md). |
| `.claude/` | Claude Code settings, agents, rules, scripts. |
| `.github/` | CI/CD workflows. |

## Module rules
- Modules (`modules/*`) never depend on `ide`. `ide` depends on all feature modules and wires them together.
- Modules may depend on other modules ([ADR 0047](adr/0047-module-dependencies-and-shared-modules.md)), without cycles
  (Gradle refuses cycles anyway), but only on a module of the same capability family (`git-log` -> `git`) or on a small
  foundation module meant for sharing (`settings`, `file-watcher`, `text-editing`, `file-icons`). Unrelated feature
  modules do not depend on each other: `ide` wires them through callbacks (`git-log` with `editor` and `file-viewers`,
  `git` with the explorer's markers). When two modules need the same piece of a third, it is extracted into a small
  module rather than depending on the whole feature module. A foundation module may be a plain library without Spring
  beans. The editor family (`editor`, `file-viewers`, `terminal`, `docker-sandbox`, `http-client`: what is shown as
  editor tabs or file views) is one capability family, so `editor` -> `file-viewers`, `terminal` -> `editor`,
  `docker-sandbox` -> `terminal` and `http-client` -> `file-viewers` follow the rule.
- Every module dependency is listed below with its reason and commented in the module's `build.gradle.kts`.
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
    means for edited text ([ADR 0020](adr/0020-editing-and-saving-files.md)); their keys indent, move and comment
    lines, find, replace and go to a line with its commands ([ADR 0022](adr/0022-editor-key-bindings.md)).
    `text-editing` itself depends at runtime only on ec4j-core (besides Kotlin), which reads `.editorconfig`
    ([ADR 0023](adr/0023-indentation-from-editorconfig.md)).
  - `http-client` -> `file-viewers`: `.http` files are shown in the public code editor (`CodeEditors`), and request
    bodies and scripts are highlighted with the code views' highlighters (`Highlighters.forLanguage`,
    [ADR 0005](adr/0005-syntax-highlighting.md), [ADR 0026](adr/0026-http-client-window-session.md)). The window's
    `HttpClientSession` reaches the views through `FileViewHost.windowService`, set up by `ide` (`MainWindow`), which
    also tells the session whether git would commit a file.
  - `terminal` -> `editor`: terminals are shown as `EditorTab`s ([ADR 0007](adr/0007-terminal.md)).
  - `docker-sandbox` -> `terminal`: sandboxes run in terminal tabs and look `sbx` up in the same login shell
    (`loginShellRunning`) ([ADR 0010](adr/0010-docker-sandbox.md)).
  - `git` -> `file-watcher`: the `.git` folder is watched to refresh the status
    ([ADR 0013](adr/0013-git-status.md)). Markers and diffs reach the explorer and the editor through `ide`
    (`MainWindow`), not through module dependencies ([ADR 0015](adr/0015-git-diff.md),
    [ADR 0021](adr/0021-change-markers-and-diffs-on-demand.md)).
  - `git-log` -> `git`: the git log area reads its commits, refs, commit details, changed files and file diffs with
    `GitLog` and shows `GitCommit`s, `GitRef`s, `GitCommitDetails` and `GitChangedFile`s
    ([ADR 0035](adr/0035-git-log-area.md), [ADR 0037](adr/0037-ref-labels-and-commit-details.md),
    [ADR 0038](adr/0038-changed-files-and-commit-diffs.md)), also the counts of a comparison with `HEAD` and the files
    that differ from the working tree with their diffs ([ADR 0044](adr/0044-git-log-compare.md)), resolves revisions and
    reads the branches containing a commit ([ADR 0045](adr/0045-git-log-navigation.md)) and the history of a file
    ([ADR 0046](adr/0046-git-log-file-history.md)), checks out and changes branches and tags with
    `GitBranches` ([ADR 0041](adr/0041-git-write-operations.md)), cherry-picks, reverts and resets with `GitCommits`
    ([ADR 0042](adr/0042-git-log-commit-operations.md)) and fetches with `GitRemotes`
    ([ADR 0043](adr/0043-git-log-fetch.md)); `MainWindow` tells it the followed repository and
    refreshes it on `GitStatusService.gitFolderChanged`. It does not depend on `editor` or `file-viewers`: `MainWindow`
    sets its callbacks `onOpenFile` (opened with `EditorArea.open`), `onShowDiff` (a `CommitFileDiff`, shown with
    `FileViewers.openDiff(name, current, diff)`, the diff of given texts, in a `CommitDiffTab` of `ide`) and
    `onShowWorkingTreeDiff` (a `WorkingTreeFileDiff`, shown with `FileViewers.openDiff(file, diff)`, the live file, or
    the diff of given texts for deleted files, in a `WorkingTreeDiffTab`; `false` when not shown as text), and tells it
    with `showStatus` when a file cannot be shown as text; its `beforeFileChange(action)` asks
    `EditorArea.saveBeforeGitChange`.
  - `file-explorer`, `editor`, `terminal` -> `settings`: they use the configured fonts
    ([ADR 0008](adr/0008-settings.md)).
- Shared build configuration (JDK toolchain, test setup, Spring BOM) is in the root `build.gradle.kts`.

## Runtime
1. `SluIdeLauncher.main` calls `Application.launch(SluIdeApplication)`.
2. `SluIdeApplication.init()` starts a non-web Spring context scanning `dev.sluide` ([ADR 0002](adr/0002-spring-inside-javafx.md)).
3. `SluIdeApplication.start()` turns off JavaFX's implicit exit, themes all windows (`ThemedWindows.install()`) and
   calls `ProjectWindows.start`: the first folder from the command line opens in the primary stage, further folders
   in new windows; without folders the project picker is shown instead ([ADR 0034](adr/0034-project-picker.md)).
4. Each window gets its own instances of the beans with `@Scope("window")` (`WindowScope`, [ADR 0014](adr/0014-project-windows.md)):
   `MainWindow`, `ProjectService`, `FileExplorerView`, `EditorArea`, `Terminals`, `DockerSandbox`, `GitStatusService`,
   `GitLogView`, `HttpClientSession`.
   They are created when the window opens and destroyed (`close()`) when it closes. Singletons must not depend on
   them.
5. When the last window is closed, `ProjectWindows` shows the project picker. Cancelling the picker while no window
   is open calls `Platform.exit()`, then `SluIdeApplication.stop()` closes the Spring context, which destroys
   the beans of any remaining windows without showing the picker; their stages end with the JavaFX toolkit.

## Package convention
`dev.sluide.<module>` per module, e.g. `dev.sluide.fileexplorer`, `dev.sluide.fileviewers`, `dev.sluide.ide`. All must live below
`dev.sluide` to be found by component scanning.

## Build and release
- **CI** (`.github/workflows/ci.yml`): `./gradlew build` on Linux and macOS for every push to `main`/`master` and every PR.
- **Release** (`.github/workflows/release.yml`): pushing a tag `vX.Y.Z` publishes `dev.sluide:ide-<os>-<arch>:X.Y.Z`
  (the fat jar) to https://maven.pkg.github.com/slu-it/slu-ide. The version comes from the tag via `-PreleaseVersion`.
  Platforms: linux-x64, linux-aarch64, mac-aarch64 ([ADR 0003](adr/0003-javafx-platform-jars.md)).
  The macOS job also attaches `Thoreau-X.Y.Z-mac-aarch64.dmg` to the GitHub release
  ([ADR 0011](adr/0011-native-app-packaging.md)).
- **Native app**: `./gradlew :ide:jpackageImage` (Thoreau.app on macOS) and `:ide:jpackageDmg` (macOS) bundle the
  fat jar with a jlink runtime ([ADR 0011](adr/0011-native-app-packaging.md)).
- Local builds use version `0.1.0-SNAPSHOT`; the publish setup lives in `ide/build.gradle.kts`.
