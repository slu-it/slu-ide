# Architecture

Thoreau is a lightweight desktop IDE written in Kotlin. It is one Spring Boot application with a JavaFX UI,
assembled from Gradle modules.

## Repository layout
| Path | Purpose |
|------|---------|
| `ide/` | Application module: launcher, Spring Boot configuration, main window. Produces the fat jar (`./gradlew :ide:bootJar`). |
| `modules/<name>/` | One Gradle module per capability; see [Modules](#modules) below. |
| `gradle/libs.versions.toml` | Single place for dependency and plugin versions. |
| `docs/` | This documentation. Start at [index.md](index.md). |
| `.claude/` | Claude Code settings, agents, rules, scripts. |
| `.github/` | CI/CD workflows. |

## Modules
| Module | Kind | Purpose | ADR |
|--------|------|---------|-----|
| `editor-area` | feature | Tabs of the editor area: `EditorTab`s, opening and saving files, asking `FileViewers` for the view of a file. | [0071](adr/0071-editor-area-module.md), [0004](adr/0004-file-viewer-selection.md) |
| `file-viewers` | feature | Views of files: code views with highlighting, keys and find, change markers and side-by-side diffs, language viewers, `.env`, the Bicep graph and parameter files, own tokenizers for TOML, Bicep and KQL. | [0004](adr/0004-file-viewer-selection.md), [0005](adr/0005-syntax-highlighting.md), [0031](adr/0031-own-tokenizers.md), [0069](adr/0069-previews-module.md) |
| `previews` | feature | The `javafx.web` WebView previews of Markdown, HTML, SVG and images, JSON, XML, CSV and cookie jars, notebooks, Mermaid, PEM and Bicep, with their exports. | [0069](adr/0069-previews-module.md) |
| `terminal` | feature | Shells in editor tabs. | [0007](adr/0007-terminal.md) |
| `docker-sandbox` | feature | `sbx` (Docker sandboxes) in terminal tabs. | [0010](adr/0010-docker-sandbox.md) |
| `http-client` | feature | `.http` files: requests run from the editor with environments and auth. | [0025](adr/0025-http-client.md), [0026](adr/0026-http-client-window-session.md) |
| `git` | feature | Git status, diffs, the log's reads and the write operations, behind `GitCli`. | [0013](adr/0013-git-status.md), [0015](adr/0015-git-diff.md), [0041](adr/0041-git-write-operations.md) |
| `git-log` | feature | The git area under the editor (user-facing name "Git Log"): log and graph, branches pane, file history, compare, the Commit tab and the Push dialog, and the write operations; `gitlog.data` is the model and reading layer. | [0035](adr/0035-git-log-area.md), [0048](adr/0048-commit-tab.md), [0049](adr/0049-push.md) |
| `file-explorer` | feature | The file tree of the open folder with file operations. | [0024](adr/0024-file-operations-in-the-explorer.md) |
| `projects` | feature | Remembered projects and the project picker. | [0034](adr/0034-project-picker.md) |
| `scratches` | feature | The scratch files shared by all windows in `~/.sluide/scratches`: their naming, the deletion of empty ones and the type picker popup. | [0066](adr/0066-scratch-files.md) |
| `settings` | foundation | Settings state, storage and dialog; features add their own `SettingsPage`s. | [0008](adr/0008-settings.md), [0067](adr/0067-feature-settings-in-their-module.md) |
| `file-watcher` | foundation | Changes on disk. | [0006](adr/0006-file-watching.md) |
| `text-editing` | foundation (library, no Spring beans) | Logic of editing text without JavaFX: line endings, changes on disk, indentation incl. `.editorconfig` via ec4j-core, line operations, comments, find and replace, go to line. | [0020](adr/0020-editing-and-saving-files.md), [0023](adr/0023-indentation-from-editorconfig.md) |
| `file-icons` | foundation (library, no Spring beans) | File-type icons and their colours, shared by the explorer, the git log and the scratches. | [0012](adr/0012-file-icons.md), [0047](adr/0047-module-dependencies-and-shared-modules.md) |
| `project-trust` | foundation | Whether the tools of an opened folder may run what it defines: the `ProjectTrustCheck` extension point, the combined decision and its storage. | [0053](adr/0053-project-trust.md) |
| `background-tasks` | foundation | Per-window background tasks (registry and indicator) and logged named threads (`NamedThreads`). | [0060](adr/0060-logging-conventions.md), [0061](adr/0061-background-tasks.md) |
| `ide` | app | Launcher, Spring Boot configuration, main window and its window parts; wires the modules together. | [0002](adr/0002-spring-inside-javafx.md), [0068](adr/0068-window-parts-in-ide.md) |

Feature modules are one IDE capability each. The editor family (`editor-area`, `file-viewers`, `previews`, `terminal`,
`docker-sandbox`, `http-client`: what is shown as editor tabs or file views) is one capability family, and so are `git`
and `git-log` ([ADR 0047](adr/0047-module-dependencies-and-shared-modules.md), amended by
[ADR 0071](adr/0071-editor-area-module.md) and [ADR 0069](adr/0069-previews-module.md)). Foundation modules are small
and meant for sharing; they may be plain libraries without Spring beans.

## Module rules
- Modules (`modules/*`) never depend on `ide`. `ide` depends on all feature modules and wires them together.
- Modules may depend on other modules ([ADR 0047](adr/0047-module-dependencies-and-shared-modules.md)), without cycles
  (Gradle refuses cycles anyway), but only on a module of the same capability family (`git-log` -> `git`) or on a small
  foundation module meant for sharing (`settings`, `file-watcher`, `text-editing`, `file-icons`, `project-trust`,
  `background-tasks`).
  Unrelated feature modules do not depend on each other: `ide` wires them through callbacks (`git-log` with `editor-area`
  and `file-viewers`, `git` with the explorer's markers). When two modules need the same piece of a third, it is
  extracted into a small module rather than depending on the whole feature module. The families are defined under
  [Modules](#modules).
- Every module dependency is listed in [Module dependencies](#module-dependencies) with its reason and commented in
  the module's `build.gradle.kts`.
- Feature modules expose UI as JavaFX nodes/controls registered as Spring beans (`@Component`), so `ide` can inject them.
  Beans that hold the state of one window (a project) are `@Scope("window")`.
- Cross-module state (e.g. the current project folder) lives in `ide` or a shared module, and modules receive it
  through their public API (e.g. `FileExplorerView.open(path)`), not by reaching into each other. Window-scoped
  services that views of another module need reach them only through `FileViewHost.windowService`, registered by
  `MainWindow` in `EditorArea.windowServices` ([ADR 0070](adr/0070-window-services.md)).
- Documented module dependencies (`api` unless noted):
  - `file-explorer` -> `file-watcher` and `editor-area` -> `file-watcher`: loaded folders and open files are watched
    ([ADR 0006](adr/0006-file-watching.md)).
  - `editor-area` -> `file-viewers`: the editor area asks `FileViewers` for the view of a file
    ([ADR 0004](adr/0004-file-viewer-selection.md)).
  - `file-viewers` -> `text-editing` (`implementation`): code views keep line endings and decide what a change on disk
    means for edited text ([ADR 0020](adr/0020-editing-and-saving-files.md)); their keys indent, move and comment
    lines, find, replace and go to a line with its commands ([ADR 0022](adr/0022-editor-key-bindings.md)).
    `text-editing` itself depends at runtime only on ec4j-core (besides Kotlin), which reads `.editorconfig`
    ([ADR 0023](adr/0023-indentation-from-editorconfig.md)).
  - `previews` -> `file-viewers` (`api`): its public viewers are `FileViewer`s; their source side is the public
    `CodeEditors` and their highlighting comes from `Highlighters`
    ([ADR 0069](adr/0069-previews-module.md)).
  - `previews` -> `text-editing`, `background-tasks` (`implementation`): the comment syntax of the source side;
    exports and image downloads.
  - `ide` -> `previews` (`implementation`): for the Spring beans only, the previews are found by component scan.
  - `editor-area` -> `previews` (`testImplementation`): the UI tests open files in real previews.
  - `http-client` -> `file-viewers`: `.http` files are shown in the public code editor (`CodeEditors`), and request
    bodies and scripts are highlighted with the code views' highlighters (`Highlighters.forLanguage`,
    [ADR 0005](adr/0005-syntax-highlighting.md), [ADR 0026](adr/0026-http-client-window-session.md)). The window's
    `HttpClientSession` reaches the views through `FileViewHost.windowService`, set up by `ide` (`MainWindow`), which
    also tells the session whether git would commit a file.
  - `terminal` -> `editor-area`: terminals are shown as `EditorTab`s ([ADR 0007](adr/0007-terminal.md)).
  - `docker-sandbox` -> `terminal`: sandboxes run in terminal tabs and look `sbx` up in the same login shell
    (`loginShellRunning`) ([ADR 0010](adr/0010-docker-sandbox.md)).
  - `git` -> `file-watcher`: the `.git` folder is watched to refresh the status
    ([ADR 0013](adr/0013-git-status.md)). Markers and diffs reach the explorer and the editor through `ide`
    (`MainWindow`), not through module dependencies ([ADR 0015](adr/0015-git-diff.md),
    [ADR 0021](adr/0021-change-markers-and-diffs-on-demand.md)).
  - `git` -> `project-trust`: git's tools run only in folders the user trusts; `GitTrustCheck` is the
    `ProjectTrustCheck` that says what a repository's own configuration and hooks would make git run
    ([ADR 0053](adr/0053-project-trust.md)).
  - `git`, `git-log`, `project-trust`, `file-viewers`, `previews`, `http-client`, `file-explorer` and
    `docker-sandbox` ->
    `background-tasks` (`api` for `git`, `git-log`, `project-trust` and `http-client`, `implementation` for the
    others): their background work runs on `NamedThreads`, which logs what a task throws
    ([ADR 0060](adr/0060-logging-conventions.md)): the status thread of `git`; the read and write threads of `git-log`;
    the trust checks of `project-trust`; highlighting of `file-viewers`; exports and image downloads of
    `previews`; response formatting and token requests of `http-client`; deletes of `file-explorer`; the `sbx`
    check of `docker-sandbox`. `git`, `git-log`, `project-trust` and `http-client` also register their
    long-running work as tasks of the window's `BackgroundTasks`
    ([ADR 0061](adr/0061-background-tasks.md)), given to `GitStatusService`, `GitLogView`, `ProjectTrust` and
    `HttpClientSession` by their public constructors (hence `api`), and `previews` registers its exports through
    `FileViewHost.windowService`, so `previews` needs only `implementation`.
  - `ide` -> `background-tasks` (`implementation`): `MainWindow` shows the window's `BackgroundTasks` as a
    `BackgroundTasksView` under the file explorer (`leftPane`) and hands the registry to file views through
    `FileViewHost.windowService` ([ADR 0061](adr/0061-background-tasks.md)).
  - `git-log` -> `git`: the git log area reads its commits, refs, commit details, changed files and file diffs with
    `GitLog` and shows `GitCommit`s, `GitRef`s, `GitCommitDetails` and `GitChangedFile`s
    ([ADR 0035](adr/0035-git-log-area.md), [ADR 0037](adr/0037-ref-labels-and-commit-details.md),
    [ADR 0038](adr/0038-changed-files-and-commit-diffs.md)), also the counts of a comparison with `HEAD` and the files
    that differ from the working tree with their diffs ([ADR 0044](adr/0044-git-log-compare.md)), resolves revisions and
    reads the branches containing a commit ([ADR 0045](adr/0045-git-log-navigation.md)) and the history of a file
    ([ADR 0046](adr/0046-git-log-file-history.md)), checks out and changes branches and tags with
    `GitBranches` ([ADR 0041](adr/0041-git-write-operations.md)), cherry-picks, reverts and resets with `GitCommits`
    ([ADR 0042](adr/0042-git-log-commit-operations.md)) and fetches with `GitRemotes`
    ([ADR 0043](adr/0043-git-log-fetch.md)); `WindowGitLog` in `ide` tells it the followed repository and
    refreshes it on `GitStatusService.gitFolderChanged`. It does not depend on `editor-area` or `file-viewers`: `WindowGitLog`
    sets its callbacks `onOpenFile` (opened with `EditorArea.open`), `onShowDiff` (a `CommitFileDiff`, shown with
    `FileViewers.openDiff(name, current, diff)`, the diff of given texts, in a `CommitDiffTab` of `ide`, by `GitDiffTabs`) and
    `onShowWorkingTreeDiff` (a `WorkingTreeFileDiff`, shown with `FileViewers.openDiff(file, diff)`, the live file, or
    the diff of given texts for deleted files, in a `WorkingTreeDiffTab`; `false` when not shown as text), and tells it
    with `showStatus` when a file cannot be shown as text; its `beforeFileChange(action)` asks
    `EditorArea.saveBeforeGitChange`.
  - `file-explorer` -> `file-icons` (`api`): the explorer shows files with the file-type icons of
    `FileIcon`/`fileIconFor`, `FontIcon.show` and `FILE_ICONS_CSS`, and `ExplorerRoot.folderIcons` takes `FileIcon`s,
    so `ide` needs no dependency of its own. `git-log` -> `file-icons` (`implementation`): the same icons in the
    commit details. `file-icons` is a plain library without Spring beans ([ADR 0012](adr/0012-file-icons.md),
    [ADR 0047](adr/0047-module-dependencies-and-shared-modules.md)).
  - `scratches` -> `file-icons` (`implementation`): the type picker shows each type with its file-type icon
    ([ADR 0066](adr/0066-scratch-files.md)).
  - `ide` -> `scratches` (`implementation`): `WindowScratches` passes the scratches folder to the explorer as an
    `ExplorerRoot`, adds File → New Scratch File… with the type picker, reveals and opens new scratches, deletes empty
    ones on `EditorArea.onFileClosed` unless another window has them open, and keeps scratch-only changes from
    refreshing the git status ([ADR 0066](adr/0066-scratch-files.md)).
  - `file-explorer`, `editor-area`, `terminal` -> `settings`: they use the configured fonts; terminals use the terminal font,
    store their scroll settings with `SettingsStore` and add a `SettingsPage` ([ADR 0008](adr/0008-settings.md),
    [ADR 0067](adr/0067-feature-settings-in-their-module.md)).
- Shared build configuration (JDK toolchain, test setup, Spring BOM) is in the root `build.gradle.kts`.

## Runtime
1. `SluIdeLauncher.main` calls `Application.launch(SluIdeApplication)`.
2. `SluIdeApplication.init()` starts a non-web Spring context scanning `dev.sluide` ([ADR 0002](adr/0002-spring-inside-javafx.md)).
3. `SluIdeApplication.start()` turns off JavaFX's implicit exit, themes all windows (`ThemedWindows.install()`), gives
   every stage the logo (`WindowIcons.install()`) and calls `ProjectWindows.start`: the first folder from the command
   line opens in the primary stage, further folders in new windows; without folders the project picker is shown
   instead ([ADR 0034](adr/0034-project-picker.md)).
4. Each window gets its own instances of the beans with `@Scope("window")` (`WindowScope`, [ADR 0014](adr/0014-project-windows.md)):
   `MainWindow`, `ProjectService`, `FileExplorerView`, `EditorArea`, `Terminals`, `DockerSandbox`, `GitStatusService`,
   `GitLogView`, `HttpClientSession`, `ProjectTrust`, `BackgroundTasks`. `MainWindow` coordinates; the integration of each
   feature is a window part class in `ide` ([ADR 0068](adr/0068-window-parts-in-ide.md)).
   They are created when the window opens and destroyed (`close()`) when it closes. Singletons must not depend on
   them.
5. When the last window is closed, `ProjectWindows` shows the project picker. Cancelling the picker while no window
   is open calls `Platform.exit()`, then `SluIdeApplication.stop()` closes the Spring context, which destroys
   the beans of any remaining windows without showing the picker; their stages end with the JavaFX toolkit.
   On SIGTERM or Ctrl+C, Spring's shutdown hook closes the context instead, while JavaFX ends its toolkit in a hook of
   its own; `ProjectWindows` then leaves the windows alone, since no thread may touch them, and what they hold ends
   with the process (a terminal's shell gets a hangup when its pty closes); a git process still running finishes on
   its own instead of being stopped.

## Package convention
`dev.sluide.<module>` per module, e.g. `dev.sluide.fileexplorer`, `dev.sluide.fileviewers`, `dev.sluide.ide`. All must live below
`dev.sluide` to be found by component scanning.

The root package of a module holds its API: what other modules' main code imports, plus launch points. Everything
else lives in sub-packages of files that belong together, by feature or by layer, e.g. `dev.sluide.previews.markdown`,
`dev.sluide.git.cli`, `dev.sluide.gitlog.push`; beans that only scanning finds included. Small modules stay flat
([ADR 0055](adr/0055-sub-packages-within-modules.md)).

Within a package, one file holds one responsibility; production files stay under about 500 lines
([ADR 0054](adr/0054-keep-source-files-small.md)). A large class is split without changing its callers, for example `GitCli`
into its process core plus `internal` extension read files by area (`git.cli`: `GitCliLog.kt`, `GitCliRefs.kt`, …),
and `GitLogView` into a coordinator plus `internal` components in the sub-packages of `dev.sluide.gitlog`
(`area.CompareBar`, `area.CommitDiffs`, …).

## Build and release
- **CI** (`.github/workflows/ci.yml`): `./gradlew build --continue` on Linux, without `uiTest` on macOS, for every push to `main`/`master` and every PR.
- **Release** (`.github/workflows/release.yml`): pushing a tag `vX.Y.Z` publishes `dev.sluide:ide-<os>-<arch>:X.Y.Z`
  (the fat jar) to https://maven.pkg.github.com/slu-it/slu-ide after `./gradlew build` (without `uiTest` on macOS).
  The version comes from the tag via `-PreleaseVersion`.
  Platforms: linux-x64, linux-aarch64, mac-aarch64 ([ADR 0003](adr/0003-javafx-platform-jars.md)).
  The macOS job also attaches `Thoreau-X.Y.Z-mac-aarch64.dmg` to the GitHub release
  ([ADR 0011](adr/0011-native-app-packaging.md)).
- **Native app**: `./gradlew :ide:jpackageImage` (Thoreau.app on macOS) and `:ide:jpackageDmg` (macOS) bundle the
  fat jar with a jlink runtime ([ADR 0011](adr/0011-native-app-packaging.md)).
- Local builds use version `0.1.0-SNAPSHOT`; the publish setup lives in `ide/build.gradle.kts`.
