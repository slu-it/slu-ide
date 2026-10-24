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
| `previews` | feature | The `javafx.web` WebView previews of Markdown, HTML, SVG and images, JSON, XML, YAML, CSV and cookie jars, notebooks, Mermaid, PEM, Bicep and GitHub Actions workflows, with their exports. | [0069](adr/0069-previews-module.md), [0075](adr/0075-github-actions-workflow-graph.md) |
| `terminal` | feature | Shells in editor tabs, and one-command terminals embedded in other modules' views. | [0007](adr/0007-terminal.md), [0086](adr/0086-embedded-command-terminals.md) |
| `docker-sandbox` | feature | `sbx` (Docker sandboxes) in terminal tabs. | [0010](adr/0010-docker-sandbox.md) |
| `http-client` | feature | `.http` files: requests run from the editor with environments and auth. | [0025](adr/0025-http-client.md), [0026](adr/0026-http-client-window-session.md) |
| `github-actions` | feature (library, no Spring beans) | GitHub Actions workflows as Mermaid flowcharts: decodes the bundled workflow parser's JSON (`model`, incl. `uses:` references and matrix counts), follows local reusable workflows and actions within the repository (`resolve`, `RepositoryFiles`; manifests read with SnakeYAML in `actions`) and remote ones in other repositories through the `RemoteSources` interface (`RemoteReader`, `RemoteText`), and draws triggers, jobs, steps, `needs` and nested expansions (`mermaid`); `previews` runs the parser and shows the chart, `ide` implements `RemoteSources`. | [0075](adr/0075-github-actions-workflow-graph.md), [0076](adr/0076-cache-of-remote-action-sources.md) |
| `command-runs` | feature | The run machinery of views that run one command per run in an embedded terminal: `RunJob` (a run and its texts), `RunState`, the `RunPane` (arguments, status line with Stop and Rerun, terminal slot), `CommandRunner` (one run at a time, the question before stopping, Ctrl+C then kill, background row, tab marker, close warning), the window's `CommandRunSession` (terminals, background tasks, stop thread) and `ShellWords`. | [0088](adr/0088-shared-command-runs.md) |
| `gradle` | feature | Gradle builds: finding the wrapper or a global `gradle` (`detect`), the bundled init script and the task model it reads (`tasks`), the project trust check (`trust`), the window's `GradleSession` (task lists per build, load claims, stale marks) and the Gradle view of build files with its task tree, its jobs (`GradleJob`) and `BuildTasks` (`view`); runs go through `command-runs`. | [0085](adr/0085-gradle-integration.md), [0088](adr/0088-shared-command-runs.md) |
| `git` | feature | Git status, diffs, the log's reads and the write operations, behind `GitCli`; the cache of remote repositories to read single files at a ref (`RemoteSourceCache`). | [0013](adr/0013-git-status.md), [0015](adr/0015-git-diff.md), [0041](adr/0041-git-write-operations.md), [0076](adr/0076-cache-of-remote-action-sources.md) |
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
`docker-sandbox`, `http-client`, `github-actions`, `command-runs`, `gradle`: what is shown as editor tabs or file views)
is one capability family, and so are `git` and `git-log` ([ADR 0047](adr/0047-module-dependencies-and-shared-modules.md), amended by
[ADR 0071](adr/0071-editor-area-module.md) and [ADR 0069](adr/0069-previews-module.md)). A member of a family that
has no UI of its own may be a plain library without Spring beans (`github-actions`, which `previews` shows). Foundation
modules are small and meant for sharing; they may be plain libraries without Spring beans.

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
  A family member without UI of its own may be a plain library without beans (`github-actions`). Beans that hold
  the state of one window (a project) are `@Scope("window")`.
- Cross-module state (e.g. the current project folder) lives in `ide` or a shared module, and modules receive it
  through their public API (e.g. `FileExplorerView.open(path)`), not by reaching into each other. Window-scoped
  services that views of another module need reach them only through `FileViewHost.windowService`, registered by
  `MainWindow` in `EditorArea.windowServices` ([ADR 0070](adr/0070-window-services.md)).

## Module dependencies
Every edge between modules, as in the `build.gradle.kts` files
([ADR 0047](adr/0047-module-dependencies-and-shared-modules.md)).
`api` means the module's public API (constructors, properties, supertypes) exposes the other module's types, so its users see them.

| From | To | Scope | Why | ADR |
|------|----|-------|-----|-----|
| `editor-area` | `file-viewers` | `api` | Asks `FileViewers` for the view of a file; `EditorTab`s and `EditorArea` expose `FileView`, `FileViewer`, `FileViewers` and `TextDiff`. | [0004](adr/0004-file-viewer-selection.md) |
| `editor-area` | `file-watcher` | `api` | Open files are watched; `EditorArea` takes the `FileWatcher`. | [0006](adr/0006-file-watching.md) |
| `editor-area` | `settings` | `api` | Uses the configured fonts; `EditorArea` takes `FontSettings`. | [0008](adr/0008-settings.md), [0067](adr/0067-feature-settings-in-their-module.md) |
| `editor-area` | `previews` | `testImplementation` | The UI tests open files in real previews. | [0069](adr/0069-previews-module.md) |
| `file-viewers` | `text-editing` | `implementation` | Code views keep line endings, decide what a change on disk means for edited text, indent, move and comment lines, find, replace and go to a line. | [0020](adr/0020-editing-and-saving-files.md), [0022](adr/0022-editor-key-bindings.md), [0023](adr/0023-indentation-from-editorconfig.md) |
| `file-viewers` | `background-tasks` | `implementation` | Highlighting runs on `NamedThreads`, which logs what a task throws. | [0060](adr/0060-logging-conventions.md) |
| `previews` | `file-viewers` | `api` | Its viewers are `FileViewer`s; their source side is the public `CodeEditors`, highlighting comes from `Highlighters`. | [0069](adr/0069-previews-module.md) |
| `previews` | `text-editing` | `implementation` | The comment syntax of the source side. | [0069](adr/0069-previews-module.md) |
| `previews` | `background-tasks` | `implementation` | Exports and image downloads run on `NamedThreads`; exports register their task through `FileViewHost.windowService`, so no `api`. | [0060](adr/0060-logging-conventions.md), [0061](adr/0061-background-tasks.md), [0069](adr/0069-previews-module.md) |
| `previews` | `github-actions` | `implementation` | The workflow viewer draws workflows with `WorkflowGraphs`, feeding it the bundled parser it runs (`WorkflowParser`) and the window's `RemoteSources`, which it looks up as a window service (`FileViewHost.windowService`); nothing of `github-actions` is in its API. | [0075](adr/0075-github-actions-workflow-graph.md), [0076](adr/0076-cache-of-remote-action-sources.md) |
| `terminal` | `editor-area` | `api` | Terminals are shown as `EditorTab`s. | [0007](adr/0007-terminal.md) |
| `terminal` | `settings` | `api` | Uses the terminal font, stores its scroll settings with `SettingsStore` and adds a `SettingsPage`; `Terminals` takes `FontSettings`. | [0008](adr/0008-settings.md), [0067](adr/0067-feature-settings-in-their-module.md) |
| `docker-sandbox` | `terminal` | `api` | Sandboxes run in terminal tabs and look `sbx` up in the same login shell (`loginShellFinds`); `api`: `DockerSandbox` takes `Terminals` and `open()` returns an `EditorTab`. | [0010](adr/0010-docker-sandbox.md) |
| `docker-sandbox` | `background-tasks` | `implementation` | The `sbx` check runs on `NamedThreads`. | [0060](adr/0060-logging-conventions.md) |
| `command-runs` | `terminal` | `api` | Every run is a fresh `EmbeddedTerminal` from the window's `Terminals`, which `CommandRunSession` takes. | [0086](adr/0086-embedded-command-terminals.md), [0088](adr/0088-shared-command-runs.md) |
| `command-runs` | `background-tasks` | `api` | Runs are listed as `BackgroundTasks`, which `CommandRunSession` takes; the stop thread is one of `NamedThreads`. | [0061](adr/0061-background-tasks.md), [0088](adr/0088-shared-command-runs.md) |
| `command-runs` | `file-viewers` | `api` | `CommandRunner.activity` is the `ViewActivity` of the view's tab. | [0088](adr/0088-shared-command-runs.md) |
| `gradle` | `command-runs` | `api` | Task runs and loads go through a `CommandRunner` in a `RunPane`; `GradleSession` takes the window's `CommandRunSession`; the user's arguments are split and quoted with `ShellWords`. | [0088](adr/0088-shared-command-runs.md) |
| `gradle` | `file-viewers` | `api` | `build.gradle` and `build.gradle.kts` are shown by public `SourceFileViewer`/`NamedFileViewer`s around the public code editor (`CodeEditors`, `SourceSplit`); `GradleSession` reaches the views through `FileViewHost.windowService`. | [0070](adr/0070-window-services.md), [0085](adr/0085-gradle-integration.md) |
| `gradle` | `project-trust` | `api` | Gradle runs only in trusted folders; `GradleTrustCheck` is the `ProjectTrustCheck` for a project's wrappers and build scripts. | [0085](adr/0085-gradle-integration.md) |
| `gradle` | `background-tasks` | `implementation` | The session's lookups and file work run on `NamedThreads`. | [0060](adr/0060-logging-conventions.md), [0085](adr/0085-gradle-integration.md) |
| `gradle` | `terminal` | `implementation` | The global `gradle` is looked up in the login shell (`loginShellFinds`). | [0085](adr/0085-gradle-integration.md), [0087](adr/0087-commands-in-an-interactive-login-shell.md) |
| `http-client` | `file-viewers` | `api` | `.http` files are shown in the public code editor (`CodeEditors`); request bodies and scripts use `Highlighters.forLanguage`; `api`: `HttpFileViewer` and `HttpEnvironmentFileViewer` are public `SourceFileViewer`s. `HttpClientSession` reaches the views through `FileViewHost.windowService`, and `ide` sets whether git would commit a file (`wouldCommit`). | [0005](adr/0005-syntax-highlighting.md), [0026](adr/0026-http-client-window-session.md), [0070](adr/0070-window-services.md) |
| `http-client` | `background-tasks` | `api` | Response formatting and token requests run on `NamedThreads`; `HttpClientSession` registers long-running work as `BackgroundTasks`, given by its constructor. | [0060](adr/0060-logging-conventions.md), [0061](adr/0061-background-tasks.md) |
| `git` | `file-watcher` | `api` | The `.git` folder is watched to refresh the status; `GitStatusService` takes the `FileWatcher`. | [0013](adr/0013-git-status.md) |
| `git` | `project-trust` | `api` | Git runs only in trusted folders; `GitTrustCheck` is the `ProjectTrustCheck` for a repository's own configuration and hooks; `GitStatusService` takes `ProjectTrust`. | [0053](adr/0053-project-trust.md) |
| `git` | `background-tasks` | `api` | The status thread runs on `NamedThreads`; `GitStatusService` registers its work as `BackgroundTasks`, given by its constructor. | [0060](adr/0060-logging-conventions.md), [0061](adr/0061-background-tasks.md) |
| `git-log` | `git` | `api` | Reads commits, refs, details, diffs, comparisons and file history with `GitLog`, and writes with `GitBranches`, `GitCommits`, `GitRebases`, `GitRemotes` and `GitPush`; `GitLogView`'s constructor takes them. | [0035](adr/0035-git-log-area.md), [0037](adr/0037-ref-labels-and-commit-details.md), [0038](adr/0038-changed-files-and-commit-diffs.md), [0041](adr/0041-git-write-operations.md), [0042](adr/0042-git-log-commit-operations.md), [0043](adr/0043-git-log-fetch.md), [0044](adr/0044-git-log-compare.md), [0045](adr/0045-git-log-navigation.md), [0046](adr/0046-git-log-file-history.md), [0049](adr/0049-push.md), [0063](adr/0063-git-log-rebase.md) |
| `git-log` | `background-tasks` | `api` | Read and write threads run on `NamedThreads`; `GitLogView` registers its work as `BackgroundTasks`, given by its constructor. | [0060](adr/0060-logging-conventions.md), [0061](adr/0061-background-tasks.md) |
| `git-log` | `file-icons` | `implementation` | File-type icons in the commit details. | [0012](adr/0012-file-icons.md) |
| `project-trust` | `background-tasks` | `api` | The trust checks run on `NamedThreads` and as `BackgroundTasks`; `ProjectTrust`'s constructor takes the registry. | [0060](adr/0060-logging-conventions.md), [0061](adr/0061-background-tasks.md) |
| `file-explorer` | `file-watcher` | `api` | Loaded folders are watched; `FileExplorerView` takes the `FileWatcher`. | [0006](adr/0006-file-watching.md) |
| `file-explorer` | `settings` | `api` | Uses the configured fonts; `FileExplorerView` takes `FontSettings`. | [0008](adr/0008-settings.md), [0067](adr/0067-feature-settings-in-their-module.md) |
| `file-explorer` | `file-icons` | `api` | Shows files with the file-type icons (`FileIcon`/`fileIconFor`, `FontIcon.show`, `FILE_ICONS_CSS`); `ExplorerRoot.folderIcons` takes `FileIcon`s, so `ide` needs no dependency of its own. | [0012](adr/0012-file-icons.md) |
| `file-explorer` | `background-tasks` | `implementation` | Deletes run on `NamedThreads`. | [0060](adr/0060-logging-conventions.md) |
| `scratches` | `file-icons` | `implementation` | The type picker shows each type with its file-type icon. | [0066](adr/0066-scratch-files.md) |
| `ide` | every module but `file-icons` and `text-editing` | `implementation` | Wires the modules together; those two arrive through `api` edges. | [0002](adr/0002-spring-inside-javafx.md), [0068](adr/0068-window-parts-in-ide.md) |
| `ide` | `previews` | `implementation` | For the Spring beans only: the previews are `FileViewer`s found by component scan, `ide` imports nothing from them. | [0069](adr/0069-previews-module.md) |
| `ide` | `background-tasks` | `implementation` | `MainWindow` shows the window's `BackgroundTasks` as a `BackgroundTasksView` under the file explorer and hands the registry to file views through `FileViewHost.windowService`. | [0061](adr/0061-background-tasks.md) |
| `ide` | `github-actions` | `implementation` | `WindowWorkflowSources` implements `RemoteSources` over `git`'s `RemoteSourceCache` (hosts chosen from the project's origin) and `MainWindow` registers it as a window service. | [0070](adr/0070-window-services.md), [0076](adr/0076-cache-of-remote-action-sources.md) |
| `ide` | `command-runs` | `implementation` | `CommandRunSession` is a window bean, created per window (for now only as what `GradleSession` takes); the window tests create it and name it in their bean lists; `MainWindow` is to register it as a window service. | [0088](adr/0088-shared-command-runs.md) |
| `ide` | `scratches` | `implementation` | `WindowScratches` gives the explorer the scratches folder as an `ExplorerRoot`, adds File → New Scratch File…, deletes empty scratches when closed, and keeps scratch-only changes from refreshing the git status. | [0066](adr/0066-scratch-files.md) |

`NamedThreads` and `BackgroundTasks` are two things of `background-tasks`: every consumer above uses `NamedThreads`,
and `git`, `git-log`, `project-trust`, `http-client` and `command-runs` also register tasks. Their constructors expose the registry
(hence `api`); `previews` gets it through `FileViewHost.windowService` (hence `implementation`).

### Wiring through `ide`
Unrelated feature modules do not depend on each other; `ide` connects them. `git-log` depends on neither `editor-area`
nor `file-viewers`: `WindowGitLog` sets its callbacks `onOpenFile` (opened with `EditorArea.open`), `onShowDiff` and
`onShowWorkingTreeDiff` (diffs shown with `FileViewers.openDiff` in tabs of `ide`, by `GitDiffTabs`) and
`beforeFileChange` (`EditorArea.saveBeforeGitChange`), tells it the followed repository, and refreshes it on
`GitStatusService.gitFolderChanged`; `showStatus` tells when a file cannot be shown as text. The details are in
[ADR 0038](adr/0038-changed-files-and-commit-diffs.md), [ADR 0042](adr/0042-git-log-commit-operations.md),
[ADR 0044](adr/0044-git-log-compare.md) and [ADR 0068](adr/0068-window-parts-in-ide.md). Likewise `git` does not
depend on `file-explorer` or `editor-area`: its markers and diffs reach them through `ide` (`MainWindow`)
([ADR 0015](adr/0015-git-diff.md), [ADR 0021](adr/0021-change-markers-and-diffs-on-demand.md)), and which
`.gitignore` lines have an effect reaches `file-viewers` through the `IgnoreRuleEffects` window service
([ADR 0080](adr/0080-gitignore-line-effects.md)).

Shared build configuration (JDK toolchain, test setup, Spring BOM) is in the root `build.gradle.kts`.

## Runtime
1. `SluIdeLauncher.main` calls `Application.launch(SluIdeApplication)`.
2. `SluIdeApplication.init()` starts a non-web Spring context scanning `dev.sluide` ([ADR 0002](adr/0002-spring-inside-javafx.md)).
3. `SluIdeApplication.start()` turns off JavaFX's implicit exit, themes all windows (`ThemedWindows.install()`), gives
   every stage the logo (`WindowIcons.install()`) and calls `ProjectWindows.start`: the first folder from the command
   line opens in the primary stage, further folders in new windows; without folders the project picker is shown
   instead ([ADR 0034](adr/0034-project-picker.md)).
4. Each window gets its own instances of the beans with `@Scope("window")` (`WindowScope`, [ADR 0014](adr/0014-project-windows.md)):
   `MainWindow`, `ProjectService`, `FileExplorerView`, `EditorArea`, `Terminals`, `DockerSandbox`, `GitStatusService`,
   `GitLogView`, `HttpClientSession`, `CommandRunSession`, `GradleSession`, `ProjectTrust`, `BackgroundTasks`. `MainWindow` coordinates; the integration of each
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
