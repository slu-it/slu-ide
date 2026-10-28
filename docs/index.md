# Thoreau documentation index

Entry point for agents: scan the tables for the words of your task, then open only the documents that match.
Rows are short on purpose; the documents hold the details.

Keep it up to date: every new ADR, feature page or idea gets a row in its table, and a changed document gets its row
updated when the summary or keywords no longer fit.
- Summary: one clause, at most ~15 words: what was decided or what the feature is. No details, limits or rejected options.
- Keywords: 4–8 search terms: the concepts a task would mention plus identifiers to grep for (modules, classes, files,
  settings keys, menu items, shortcuts). No `|` inside cells; write "Source/Split".
- Related (ADRs only): `amends N` when the ADR changes part of ADR N (and `amended by` on N's row), `see N` for close
  relatives. Read an amended ADR together with the ADRs that amend it.

## Overview
| Document | Summary | Keywords |
|---|---|---|
| [architecture.md](architecture.md) | Modules, their dependency rules, runtime startup, CI and releases | module table, dependencies, Gradle modules, startup, CI, release |
| [testing.md](testing.md) | Unit vs. UI tests, running a subset, displays, running the app headless | `uiTest`, TestFX, `@Tag("ui")`, `--tests`, Xvfb, display pool, screenshot |

## Decisions (ADRs)
| ADR | Decision | Keywords | Related |
|---|---|---|---|
| [0001](adr/0001-tech-stack.md) | Kotlin on JDK 25, Spring Boot, JavaFX, Gradle multi-module | tech stack, Kotlin, Spring Boot, JavaFX, Gradle | |
| [0002](adr/0002-spring-inside-javafx.md) | A non-web Spring context runs inside the JavaFX `Application` | Spring context, `Application`, startup, shutdown, UI beans | amended by 0014 |
| [0003](adr/0003-javafx-platform-jars.md) | JavaFX via the OpenJFX Gradle plugin; fat jars per platform | `javafx` plugin, `org.openjfx`, fat jar, platforms, releases, no Windows | |
| [0004](adr/0004-file-viewer-selection.md) | File viewers are asked in turn whether they open a file | `FileViewer`, viewer selection, first viewer that agrees, `DefaultTextFileViewer` last | amended by 0020, 0029 |
| [0005](adr/0005-syntax-highlighting.md) | RichTextFX code views tokenized by RSyntaxTextArea lexers | syntax highlighting, RichTextFX, RSyntaxTextArea, lexer, `SyntaxHighlighter`, line rules | amended by 0020, 0031 |
| [0006](adr/0006-file-watching.md) | JDK `WatchService` behind `FileWatcher`, non-recursive watches | file watching, `FileWatcher`, `WatchService`, recreated folders, macOS polling | |
| [0007](adr/0007-terminal.md) | Terminals on pty4j and the JediTerm core with an own canvas view | terminal, pty4j, JediTerm, canvas | amended by 0086 |
| [0008](adr/0008-settings.md) | Settings as beans with JavaFX properties in a properties file | settings, `settings.properties`, `SettingsPage`, settings dialog | amended by 0067 |
| [0009](adr/0009-themes.md) | Light/dark/system theme via colour-scheme preference and `theme.css` | theme, dark mode, `theme.css`, Modena, `prefers-color-scheme` | see 0016 |
| [0010](adr/0010-docker-sandbox.md) | Docker sandboxes run as a terminal tab's process, only below home | Docker sandbox, `sbx`, terminal tab, home folder | amended by 0087 |
| [0011](adr/0011-native-app-packaging.md) | jpackage app and disk image with a jlink runtime | jpackage, jlink, `Thoreau.app`, `jpackageDmg`, app icon, `render-icon.sh` | |
| [0012](adr/0012-file-icons.md) | Explorer file icons from Ikonli's Material Design Icons | file icons, Ikonli, Material Design, icon colours | |
| [0013](adr/0013-git-status.md) | Git status from `git status --porcelain=v2 --ignored`, no JGit | git status, explorer markers, refresh, F5, `.git` changes | amended by 0060, 0062 |
| [0014](adr/0014-project-windows.md) | One project per window; Spring scope `window` | windows, Open Folder, `window` scope, `ProjectWindows`, close confirmation | amends 0002; amended by 0034 |
| [0015](adr/0015-git-diff.md) | Side-by-side diff against `HEAD` from git, no diff library | diff, `git diff -U0`, `git cat-file`, side by side, filler rows | amended by 0021, 0062 |
| [0016](adr/0016-markdown-preview.md) | Markdown rendered by commonmark-java as sanitised HTML in a WebView | Markdown, commonmark-java, jsoup, WebView, javafx.web | amended by 0017 |
| [0017](adr/0017-webview-previews.md) | One `PreviewFileView` (source beside a WebView) for all rendered formats | `PreviewFileView`, WebView, scripts off, navigation, KaTeX, Mermaid, notebooks | amends 0016; amended by 0075 |
| [0018](adr/0018-html-preview-server.md) | HTML previews served by a loopback server confined to the project | HTML preview, local server, loopback, `file:` URLs, security | |
| [0019](adr/0019-word-diff.md) | Changed words by an own token LCS over git's line diff | word diff, LCS, changed words, tokens, `git diff` |  |
| [0020](adr/0020-editing-and-saving-files.md) | Editing in RichTextFX `CodeView`s with explicit save in place | `EditableFileView`, `CodeView`, undo, dirty, save, line endings, `DiskChange` | amends 0004, 0005 |
| [0021](adr/0021-change-markers-and-diffs-on-demand.md) | Files open in the editor with change markers; the diff on demand | `Gutter`, change markers, `LineMarkers`, Show Changes, Shortcut+Shift+D | amends 0015; amended by 0062 |
| [0022](adr/0022-editor-key-bindings.md) | Editor keys as a wellbehavedfx `InputMap` per code view | key bindings, `InputMap`, wellbehavedfx, `TextEdit`, undo step, find/replace | |
| [0023](adr/0023-indentation-from-editorconfig.md) | Indentation from `.editorconfig` via ec4j, else detected, else default | `.editorconfig`, ec4j, indentation, tabs, spaces | |
| [0024](adr/0024-file-operations-in-the-explorer.md) | Explorer new, rename and delete through `FileOperations` | `FileOperations`, new file, rename, delete, `FileView.moved` | amended by 0073 |
| [0025](adr/0025-http-client.md) | HTTP client module with a subset of IntelliJ's `.http` format | `.http`, http-client, variables, environments, IntelliJ format | |
| [0026](adr/0026-http-client-window-session.md) | Per-window `HttpClientSession`; public code editor API of `file-viewers` | `HttpClientSession`, `windowService`, `CodeEditors`, `CodeEditor`, `SourceFileViewer` | amended by 0070 |
| [0027](adr/0027-http-client-sending.md) | Requests on the JDK `HttpClient` off the FX thread | JDK `HttpClient`, headers, redirects, timeouts, cookies, Basic/Digest, proxy | |
| [0028](adr/0028-http-client-secrets.md) | Secrets only from env files; OAuth2 tokens and certificates per window | secrets, private env file, OAuth2, tokens, client certificates, trust-all | |
| [0029](adr/0029-file-name-and-shebang-matching.md) | Viewers match by exact name, extension, pattern, then shebang | name matching, extension, shebang, `NameMatch`, line rules | amends 0004; amended by 0031, 0074 |
| [0030](adr/0030-masking-env-values.md) | `.env` values masked by a style overlay, text untouched | `.env`, masking, `masked` style, Show values | |
| [0031](adr/0031-own-tokenizers.md) | Own `SyntaxHighlighter`s for TOML, Bicep and KQL | tokenizers, TOML, Bicep, KQL, `SyntaxHighlighter` | amends 0005, 0029; amended by 0097 |
| [0032](adr/0032-product-name-thoreau.md) | Users see the name Thoreau; the technical name `sluide` stays | Thoreau, `sluide`, naming, bundle identifier, `~/.sluide` | |
| [0033](adr/0033-zooming-images-and-diagrams.md) | Images and diagrams zoom in the page, reported via `sluideBridge` | zoom, images, SVG, Mermaid, Bicep graph, Fit, `sluideBridge` | amended by 0050, 0083 |
| [0034](adr/0034-project-picker.md) | Project picker at startup and after the last window closes | project picker, `lastOpened`, implicit exit, recent projects | amends 0014; amended by 0090 |
| [0035](adr/0035-git-log-area.md) | Git log as a collapsible area under the editor | git log area, `git-log` module, paging, `gitFolderChanged`, collapsed | amended by 0062 |
| [0036](adr/0036-commit-graph-layout.md) | Own commit-graph lane layout drawn as CSS-coloured shapes | commit graph, lanes, `Path`, `Circle`, graph colours | |
| [0037](adr/0037-ref-labels-and-commit-details.md) | Ref labels on commits and a lazily loaded details pane | ref labels, `for-each-ref`, tags, details pane, `git show` | |
| [0038](adr/0038-changed-files-and-commit-diffs.md) | Changed files of a commit and their diffs | changed files, `diff-tree`, `CommitDiffTab`, `openDiff`, Show Diff | |
| [0039](adr/0039-git-log-filters.md) | Git log filters through git's own commit limiting | filters, `--grep`, `--author`, path filter, branch filter, first parent, regex | |
| [0040](adr/0040-git-log-branches-pane.md) | Branches pane: tree of refs left of the commits | branches pane, local branches, remote branches, tags, Branch filter | |
| [0041](adr/0041-git-write-operations.md) | Checkout, new branch/tag, rename and delete from the git log | `GitBranches`, checkout, new branch, tag, delete, `sluide-git-write` | amended by 0062; see 0078 |
| [0042](adr/0042-git-log-commit-operations.md) | Cherry-pick, revert and reset with a conflict bar | `GitCommits`, cherry-pick, revert, reset, conflicts, Continue/Abort | amended by 0063, 0105 |
| [0043](adr/0043-git-log-fetch.md) | Fetch per remote, never interactive, cancellable | `GitRemotes`, fetch, `--prune`, `GIT_TERMINAL_PROMPT`, ssh BatchMode | |
| [0044](adr/0044-git-log-compare.md) | Compare with Current as a log mode; diff with the working tree | compare, `GitLogFilter.excluded`, `--left-right`, Compare with Local, working tree | |
| [0045](adr/0045-git-log-navigation.md) | One revision resolver for hash links and Go to Hash/Branch/Tag | revision resolver, `cat-file --batch-check`, hash links, `LinkText`, Shortcut+G, `loadUntil` | |
| [0046](adr/0046-git-log-file-history.md) | File history as `History:` tabs in the git log area | file history, `--follow`, renames, Show History | |
| [0047](adr/0047-module-dependencies-and-shared-modules.md) | Acyclic module dependencies; shared pieces in small modules | module dependencies, foundation modules, `ide` callbacks, `file-icons` | amended by 0069, 0071; see 0055 |
| [0048](adr/0048-commit-tab.md) | Commit tab in the git log area | Commit tab, Shortcut+K, `git commit --only`, Amend, unversioned files | |
| [0049](adr/0049-push.md) | Non-modal Push dialog, force only with lease | Push dialog, Shortcut+Shift+K, `--force-with-lease`, push target, main/master | see 0078 |
| [0050](adr/0050-focus-when-a-file-tab-is-shown.md) | One focus rule for shown file tabs | focus, `FileView.focus()`, `focusText()`, tab selection | amends 0033 |
| [0051](adr/0051-log-file.md) | Rolling log file in `~/.sluide/logs` via Logback | `sluide.log`, `logback-spring.xml`, uncaught exceptions, `sluide.log.dir` | see 0060 |
| [0052](adr/0052-hardening-how-git-is-started.md) | `GitCli` drops repository-redirecting variables and fsmonitor hooks | `GitCli`, git environment variables, `core.fsmonitor`, hardening | |
| [0053](adr/0053-project-trust.md) | Tools' trust checks ask once per folder and set of findings | project trust, `ProjectTrustCheck`, `ProjectTrust`, fingerprint, `project-trust` | amended by 0062, 0090; see 0076, 0085 |
| [0054](adr/0054-keep-source-files-small.md) | Production files under ~500 lines, split by responsibility over 600 | file size, `check-file-sizes.sh`, splitting, `GitCli`, `GitLogView` | |
| [0055](adr/0055-sub-packages-within-modules.md) | Root package holds the module API; the rest in sub-packages | packages, sub-packages, module API, `internal`, resources | amended by 0069; see 0047 |
| [0056](adr/0056-http-client-setup-from-the-explorer.md) | New HTTP Client Setup as a window-contributed explorer folder action | `FolderAction`, `HttpClientSetup`, env files, `example.http` | |
| [0057](adr/0057-visual-environment-editor.md) | Split JSON/visual editor for HTTP client environment files | `http-client.env.json`, env editor, tree table, private value locks, `TextEditableView`, `NamedFileViewer` |  |
| [0058](adr/0058-markdown-html-export.md) | Self-contained Markdown HTML export reusing the preview renderer | Markdown export, HTML, math, Mermaid, offscreen WebEngine, `<name>.css` | |
| [0059](adr/0059-svg-export-as-png.md) | SVG to PNG at 100/200/300 % via a hidden WebView | SVG, PNG export, `FileExport`, Export menu, `@2x` | |
| [0060](adr/0060-logging-conventions.md) | Logging conventions; every git command numbered | logging, log levels, `git#n`, stderr, FX watchdog | amends 0013; see 0051, 0061 |
| [0061](adr/0061-background-tasks.md) | Per-window background-task registry shown under the explorer | `BackgroundTasks`, `background-tasks`, cancel, `withdrawCancel`, `BackgroundTasks.forTests` | amended by 0106 |
| [0062](adr/0062-git-concurrency.md) | Which git work runs in parallel and which is queued | git concurrency, threads, `sluide-git-diff`, `diffAsync`, `GitCancellation`, `.git` stamps, write holds | amends 0013, 0015, 0021, 0035, 0041, 0053 |
| [0063](adr/0063-git-log-rebase.md) | Rebase the current branch onto another, with Continue/Skip/Abort | `GitRebases`, rebase, `GitPendingRebase`, pending bar, conflicts | amends 0042; amended by 0106 |
| [0064](adr/0064-json-preview.md) | JSON Source/Split/Preview with a foldable tree | JSON preview, Jackson, folds, JSON Pointer, `Preview.folds` | |
| [0065](adr/0065-xml-preview.md) | XML preview on shared fold parts, StAX with XXE off | XML preview, StAX, XXE, `fold-tree.js`, `FoldState`, `data-path` | see 0064 |
| [0066](adr/0066-scratch-files.md) | Scratch files in `~/.sluide/scratches`, shown as an explorer root | scratch files, `ExplorerRoot`, Shortcut+Shift+N, type picker | |
| [0067](adr/0067-feature-settings-in-their-module.md) | A feature's settings and pages live in its module | settings, `SettingsPage`, `SettingsStore`, module placement | amends 0008 |
| [0068](adr/0068-window-parts-in-ide.md) | A window's integration of a feature is an `internal` window part | window parts, `MainWindow`, `WindowGitLog`, `WindowTools`, menu bar | |
| [0069](adr/0069-previews-module.md) | WebView previews live in the `previews` module | `previews` module, `dev.sluide.previews`, `PreviewFileView`, `file-viewers` API, `ide` beans | amends 0047, 0055; see 0017 |
| [0070](adr/0070-window-services.md) | `WindowServices`: registry of window-scoped services for file views | `WindowServices`, `windowService`, `FileViewHost`, `EditorArea` | amends 0026; amended by 0076 |
| [0071](adr/0071-editor-area-module.md) | The tab module is `editor-area`, not `editor` | `editor-area`, `dev.sluide.editorarea`, tabs, naming | amends 0047 |
| [0072](adr/0072-csv-table-header-guess-and-state.md) | CSV table: guessed header, kept sort/filter state, comparison search | CSV, TSV, header guess, sort, column search | |
| [0073](adr/0073-explorer-multi-selection-and-moving.md) | Explorer multi-selection, multi-delete and moving by mouse drag | multi-selection, delete, drag move, GTK drag and drop, name clashes | amends 0024; amended by 0092 |
| [0074](adr/0074-path-matched-file-viewers.md) | `PathFileViewer` claims files by folder path | `PathFileViewer`, `NameMatch.PATH`, path match, `.github/workflows`, workflows | amends 0029; amended by 0079 |
| [0075](adr/0075-github-actions-workflow-graph.md) | Workflow graph via GitHub's own parser in a hidden WebEngine | GitHub Actions, workflow graph, `@actions/workflow-parser`, `github-actions`, `uses:` | amends 0017; amended by 0076, 0077 |
| [0076](adr/0076-cache-of-remote-action-sources.md) | Remote `uses:` read from a git cache of shallow bare repositories | `RemoteSourceCache`, `~/.sluide/cache/git`, Fetch References, GHES, `RemoteSources` | amends 0070, 0075; see 0053 |
| [0077](adr/0077-graph-nodes-open-their-source.md) | Workflow graph boxes open their source | `WorkflowGraph.locations`, `SourceLocation`, `openNode`, `FileView.showLine` | amends 0075 |
| [0078](adr/0078-deleting-remote-branches-and-tags.md) | Delete remote branches and remote tags from the git log | remote delete, `git push :<ref>`, `--force-with-lease`, protected main/master | see 0041, 0049 |
| [0079](adr/0079-yaml-preview.md) | YAML preview on the fold parts from SnakeYAML events | YAML preview, SnakeYAML, Copy Formatted, anchors, `---` documents | amends 0074; see 0064, 0065 |
| [0080](adr/0080-gitignore-line-effects.md) | Gutter dots for `.gitignore` lines from `git check-ignore -v` | `.gitignore`, `check-ignore`, gutter dots, `IgnoreRuleEffects`, `GitignoreFileView` | |
| [0081](adr/0081-parallel-ui-tests-on-a-display-pool.md) | Modules' `uiTest` tasks run in parallel on a display pool | `uiTest`, `DisplayService`, `sluide.uiDisplays`, Xvfb, `full-build.sh` | amended by 0082 |
| [0082](adr/0082-split-ui-tests-of-long-modules.md) | Long modules' UI tests split into two parts | `uiTestSplits`, `uiTestPart1`, `uiTestPart2`, git-log, previews | amends 0081 |
| [0083](adr/0083-zooming-document-previews.md) | Document previews zoom with `WebView.zoom` | zoom, `WebView.zoom`, document previews, Shortcut+wheel | amends 0033 |
| [0084](adr/0084-testability-logic-out-of-controls.md) | Controls are thin adapters; test each behavior at the lowest level | testability, pure functions, host interface, UI test level, wiring test | |
| [0085](adr/0085-gradle-integration.md) | Gradle integration: wrapper detection, trust, task tree, runs | `gradle` module, `gradlew`, `GradleTrustCheck`, init script, `GradleSession` | amended by 0087, 0088 |
| [0086](adr/0086-embedded-command-terminals.md) | Other views embed one-command terminals | `Terminals.embed`, `EmbeddedTerminal`, exit code, Ctrl+C | amends 0007; amended by 0087, 0088 |
| [0087](adr/0087-commands-in-an-interactive-login-shell.md) | Commands run in an interactive login shell (`$SHELL -l -i -c`) | login shell, `.zshrc`, `loginShellFinds`, SDKMAN, nvm | amends 0010, 0085, 0086 |
| [0088](adr/0088-shared-command-runs.md) | Shared run machinery in module `command-runs` | `command-runs`, `RunJob`, `RunPane`, `CommandRunner`, `CommandRunSession`, `ShellWords` | amends 0085, 0086; amended by 0091 |
| [0089](adr/0089-running-shell-scripts.md) | Module `shell-scripts` runs scripts as files in the login shell | `.sh`, Run, Shortcut+Enter, `ShellScriptFileViewer`, `ShellFileViewer`, `RunnableFileView`, chmod | amended by 0091 |
| [0090](adr/0090-per-project-metadata-store.md) | One JSON file per project in `~/.sluide/projects` | `project-data`, `~/.sluide/projects`, `shared.json`, `LegacyProjectData`, migration | amends 0034, 0053; amended by 0101 |
| [0091](adr/0091-argument-history-of-runs.md) | Per-file argument history in the shared `RunPane` | argument history, `runArguments`, `runs.history.expanded`, `RunPane` | amends 0088, 0089 |
| [0092](adr/0092-explorer-copy-cut-paste.md) | Explorer copy, cut and paste over the system clipboard | Shortcut+C/X/V, clipboard, `ShownKeys`, `-copy` names, Finder, Option-drag | amends 0073 |
| [0093](adr/0093-explorer-open-in-terminal-and-reveal.md) | Explorer Open in Terminal and Reveal in the file manager | Open in Terminal, Reveal, `open -R`, D-Bus ShowItems, `xdg-open` | |
| [0094](adr/0094-paths-in-new-file-and-folder.md) | New File/New Folder take paths and create missing folders | New File, New Folder, paths, `expandTo` | |
| [0095](adr/0095-explorer-button-bar-and-select-opened-file.md) | Explorer button bar; Select Opened File only by hand | button bar, Select Opened File, Alt+F1, Collapse All, Refresh | see 0096 |
| [0096](adr/0096-compact-middle-folders.md) | Compact Middle Folders: single-folder chains as one row | compact folders, `explorer.compactFolders`, F2 rename, middle-folder watches | see 0095 |
| [0097](adr/0097-second-file-type-round.md) | More RSyntaxTextArea languages with corrections; own tokenizers for HCL and GraphQL | languages, `CompiledLanguages`, `DynamicLanguages`, `lexerMasks`, `stringStyles`, HCL, Terraform, GraphQL | amends 0031; see 0005 |
| [0098](adr/0098-windows-1252-fallback.md) | Text that is not UTF-8 opens and saves as Windows-1252 | encoding, Windows-1252, Latin-1, `TextEncoding`, `decodeText`, Convert to UTF-8, `readTextFile` | see 0097 |
| [0099](adr/0099-linear-lexing-of-long-lines.md) | Lines with many tokens or raw strings lex in near-linear time | performance, quadratic, token pool, `TokenMakerBase`, reflection, `rawStringDelimiter`, minified, Handlebars | see 0005, 0097 |
| [0100](adr/0100-editing-commit-messages.md) | Edit the message of any commit of the current branch by rewriting the commits after it | `GitRewords`, reword, edit commit message, `hash-object`, `update-ref`, default branch, `origin/HEAD`, signatures | see 0048, 0063 |
| [0101](adr/0101-shared-data-file.md) | Shared data file `~/.sluide/shared.json` replaces outside-projects.json | shared data file, `shared.json`, `readShared`, `updateShared`, rename migration | amends 0090 |
| [0102](adr/0102-todo-panel.md) | The TODO panel: Shared and Project cards with native Markdown, collapsed at the right of the window | TODOs, `todos`, `TodoPanel`, `Todos`, native Markdown, Alt+6, Shared/Project | see 0101, 0073, 0068; amended by 0103 |
| [0103](adr/0103-todo-folders.md) | TODO folders: one level, mixed with TODOs in the same `todos` array, stored collapse (also of cards), drag with an insertion line | TODO folders, `TodoFolder`, `TodoPlace`, `dropTarget`, `ItemDrag`, collapsed, expanded, Keep TODOs | amends 0102; see 0073 |
| [0104](adr/0104-collapsible-file-explorer.md) | The explorer column collapses to a stripe at the left like the TODO panel, expanded by default, background tasks included | collapse explorer, `ExplorerPanel`, `WindowExplorer`, stripe, Alt+1, View → Explorer | see 0102, 0068, 0095, 0061 |
| [0105](adr/0105-git-log-merge.md) | Merge a branch into the current one from the log; merges in progress (also from a terminal or `git pull`) continued or aborted from the bar | `GitMerges`, merge, `GitPendingMerge`, `MergeOptions`, fast-forward, squash, `--autostash`, `merge.ff`, `merge.autoStash`, `merge.suppressDest`, octopus | amends 0042; see 0063 |
| [0106](adr/0106-git-log-pull.md) | Pull a remote branch into the current one as the log's fetch, then a merge or a fork-point rebase, chosen in a dialog | `GitPulls`, pull, Pull dialog, fork point, `ORIG_HEAD`, `--autostash`, upstream, `pull.rebase`, header button, Push dialog | amends 0061, 0063; see 0043, 0049, 0105 |
| [template](adr/template.md) | Template for new ADRs | template | |

## Features

### Window and projects
| Feature | Summary | Keywords |
|---|---|---|
| [open-folder.md](features/open-folder.md) | Open Folder or command-line folders open one window per project | Open Folder, command line, windows, unsaved files on close, running tabs |
| [project-picker.md](features/project-picker.md) | Picker of remembered projects at startup and after the last window | `projects` module, Open Project…, recent projects, missing folders |
| [project-trust.md](features/project-trust.md) | Folders whose git config or hooks would run commands ask once | Trust Folder?, git hooks, findings, Review… bar |
| [todos.md](features/todos.md) | TODO panel at the right: Shared and Project tabs of Markdown cards | TODO panel, Shared/Project tabs, Markdown cards, drag to reorder, Alt+6, `shared.json` |
| [settings.md](features/settings.md) | Settings dialog with appearance and font pages | File → Settings… (Ctrl+,), theme, fonts, OK/Apply/Cancel |
| [keyboard-shortcuts.md](features/keyboard-shortcuts.md) | Every key binding in one overview | key bindings, shortcuts, Shortcut = Ctrl/Cmd, `.claude/rules/key-bindings.md` |
| [background-tasks.md](features/background-tasks.md) | Longer work listed under the explorer with progress and cancel | background tasks, progress bar, ✕ cancel, "+N more" |

### Explorer
| Feature | Summary | Keywords |
|---|---|---|
| [file-explorer.md](features/file-explorer.md) | Project tree with file operations, multi-selection and moves | tree, New File, Rename F2, Delete, drag move, copy/paste, compact folders, Select Opened File, Reveal, Open in Terminal, collapse, stripe, Alt+1 |
| [scratch-files.md](features/scratch-files.md) | Scratch files of any type under the explorer's Scratches root | New Scratch File, Shortcut+Shift+N, `~/.sluide/scratches`, type picker |
| [file-watching.md](features/file-watching.md) | `FileWatcher` reports folder changes to explorer, editor and git | `FileWatcher`, watches, combined events, macOS polling |

### Editor
| Feature | Summary | Keywords |
|---|---|---|
| [editor-tabs.md](features/editor-tabs.md) | Tab area for files and other `EditorTab`s | tabs, unsaved ●, close confirmation, tab menu, reordering, `onFileClosed`, activity marker |
| [text-editing.md](features/text-editing.md) | Editing, saving and editor keys for code and text | save, Save All, changes on disk, line endings, indentation, find/replace, go to line, encoding, Windows-1252 |
| [file-viewers.md](features/file-viewers.md) | The `FileViewer` extension point, code viewers and supported languages | `FileViewer`, `FileView`, `CodeFileViewer`, languages, syntax highlighting, `SourceSplit`, `RunnableFileViewer`, `closeWarning`, `CodeEditors.text`, `TextEditor` |
| [env-files.md](features/env-files.md) | `.env` files with masked values | `.env`, masking, Show values, placeholder files |

### Previews
| Feature | Summary | Keywords |
|---|---|---|
| [previews.md](features/previews.md) | Rendered previews beside the source and how they zoom | HTML, SVG, CSV, notebooks, Mermaid, PEM, Bicep graph, zoom |
| [markdown-preview.md](features/markdown-preview.md) | Markdown source beside a sanitised rendered preview | Markdown, KaTeX, Mermaid, Source/Split/Preview, table of contents, scroll sync |
| [markdown-export.md](features/markdown-export.md) | Markdown exported as self-contained HTML | Export → HTML, self-contained, `<name>.css`, light/dark |
| [json-preview.md](features/json-preview.md) | Formatted, foldable view of JSON | `.json`, `.jsonc`, folds, Collapse All, Copy Formatted |
| [xml-preview.md](features/xml-preview.md) | Formatted, foldable view of XML | `.xml`, `.xsd`, `.fxml`, folds, Copy Formatted |
| [yaml-preview.md](features/yaml-preview.md) | Formatted, foldable view of YAML | `.yml`, `.yaml`, anchors, `---` documents, Copy Formatted |
| [svg-png-export.md](features/svg-png-export.md) | SVG exported as PNG at 100/200/300 % | Export → PNG, `@2x`, `@3x`, transparent background |
| [github-actions-graph.md](features/github-actions-graph.md) | Graph preview of GitHub Actions workflows | `.github/workflows`, jobs, `needs`, `uses:`, Fetch References, click to source |

### Git
| Feature | Summary | Keywords |
|---|---|---|
| [git-status.md](features/git-status.md) | Git status colours and bubbles in the explorer | status colours, unversioned, modified, ignored, folder bubbles |
| [gitignore-line-effects.md](features/gitignore-line-effects.md) | Gutter dots showing which `.gitignore` lines have an effect | `.gitignore`, gutter dots, tracked-only matches, tooltips |
| [git-diff.md](features/git-diff.md) | Change markers in the gutter and a side-by-side diff against `HEAD` | change markers, Show Changes, Shortcut+Shift+D, changed words |
| [git-log.md](features/git-log.md) | Git log area: commits, graph, details, filters, branch and commit operations | Alt+9, commit graph, filters, branches pane, checkout, cherry-pick, rebase, merge, fetch, pull |
| [file-history.md](features/file-history.md) | A file's history as a tab in the git log area | Show History, `History:` tab, renames, Compare with Local |
| [commit-and-push.md](features/commit-and-push.md) | Commit tab and Push dialog | Commit (Shortcut+K), Amend, Push (Shortcut+Shift+K), Force Push, Commit and Push, Pull after a rejection |

### Tools
| Feature | Summary | Keywords |
|---|---|---|
| [terminal.md](features/terminal.md) | Shell terminals as editor tabs; one-command terminals in other views | New Terminal, Ctrl+Shift+T, shell, scrollback, embedded terminal |
| [docker-sandbox.md](features/docker-sandbox.md) | Claude Code or an environment in a Docker sandbox terminal | `sbx`, Claude Code, `sbxenv.yaml`, Docker Sandbox menu |
| [http-client.md](features/http-client.md) | `.http` request files run from the editor | `.http`, `.rest`, variables, environments, OAuth2, response pane, New HTTP Client Setup |
| [http-client-env-editor.md](features/http-client-env-editor.md) | Visual editor for the HTTP client environment files | `http-client.env.json`, private env file, tree table, locks |
| [gradle.md](features/gradle.md) | Gradle view: task tree and task runs in an embedded terminal | `build.gradle.kts`, wrapper, task tree, run tasks, argument history, trust |
| [shell-scripts.md](features/shell-scripts.md) | Shell scripts run from their tab or the explorer | `.sh`, Run, Shortcut+Enter, argument history, make executable |

## Ideas
Explored but not decided or built; options and trade-offs, no commitment.

| Idea | Summary | Keywords |
|---|---|---|
| [code-navigation.md](ideas/code-navigation.md) | Go to Definition, Kotlin first; resolver options compared | Go to Definition, tree-sitter, name index, ctags, LSP, kotlin-lsp |
| [windows-support.md](ideas/windows-support.md) | What Windows support would take, in three scopes | Windows, CI runners, packaging, paths, file locking |
| [headless-ui-tests.md](ideas/headless-ui-tests.md) | Spike of UI tests on JavaFX's headless platform; not adopted | headless, JavaFX 27, Xvfb, parallel forks |
