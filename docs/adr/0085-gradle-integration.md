# 0085. Gradle integration: detection, trust and reading tasks

- Status: accepted; amended by [0087](0087-commands-in-an-interactive-login-shell.md) (commands run in an interactive
  login shell) and [0088](0088-shared-command-runs.md) (the run machinery is shared in `command-runs`)
- Date: 2026-10-24

## Context
Thoreau opens `build.gradle` and `build.gradle.kts` as plain source. We want a view of the build's tasks that runs
them, which needs three things: finding a Gradle to run, deciding whether running it is allowed, and reading the
tasks. Running Gradle executes code of the project (the wrapper downloads and starts a distribution, build scripts
run on every configuration), so it falls under [ADR 0053](0053-project-trust.md).

## Decision
**Detection (`GradleLocator`)**
- A **wrapper** is the nearest folder from the build file's folder up to **and including the project root** that has
  both `gradlew` (a regular file) and `gradle/wrapper/gradle-wrapper.properties`. The walk never goes above the
  project root, and a build file outside the root gets no Gradle at all.
- A `gradlew` that is not executable runs as `sh gradlew`. A missing `gradle-wrapper.jar` is left to gradlew's own error.
- Without a wrapper the **global** `gradle` is used if the user's login shell finds it (`command -v gradle`, like the
  `sbx` check of the docker-sandbox module: timeout, process tree killed). It is asked only when no wrapper exists.
- Known limit: Gradle finds a `settings.gradle` above the project root on its own, so a project that is part of a
  bigger build above it lists and runs that build.

**Trust (`GradleTrustCheck`, id `gradle`)**
- It joins the prompt shown when the window opens, so **every Gradle project asks once**; the user accepted this.
- It walks the tree without running anything and covers the folders of `GradleCoverage`: no hidden folder, no
  `build`, `node_modules` or `out`, no symbolic link, at most 8 folders below the project root. The locator uses the
  same rule, so it never picks a Gradle the prompt did not cover. The walk stops at 20,000 folders; if it reaches that
  or is interrupted, the project cannot be checked (`null`), not partly.
- **Every wrapper is a finding**, also one below another wrapper's folder. Its fingerprint is the SHA-256 of `gradlew`,
  `gradle-wrapper.jar` (if present) and the `distributionUrl`, so a changed script, jar or distribution asks again.
- A folder with build scripts and no wrapper in it or above it is one finding, with the text as fingerprint (scripts
  change too often to hash); the note says the global gradle runs them. Build files without a wrapper of their own
  below a build root belong to it and add nothing.

**Reading the tasks**
- A bundled **init script** (`task-list.init.gradle`, Groovy) registers `sluideTaskList` on the root project. It walks
  `allprojects` and their tasks and writes JSON (`version`, `rootFolder`, projects with path, name, folder, build
  file, and tasks with name, path, group, description) to the file named by `-Dsluide.taskList=<file>`. The load asks
  for `:sluideTaskList`, the root path, because it runs in the build file's folder and a bare name would look in a
  subproject there. It runs with `-Dorg.gradle.configuration-cache=false`; the task is also marked not compatible with
  the configuration cache where Gradle has that API. It also runs with `--no-configure-on-demand`, since a root path
  would otherwise configure only the root project and list no tasks registered by the others.
- It runs through the same command as a task run (wrapper or `gradle` in the login shell, interactive since
  [ADR 0087](0087-commands-in-an-interactive-login-shell.md)), so PATH and `JAVA_HOME` of the user apply. Gradle 6
  or newer is needed.
- Rejected: the **Tooling API**: it needs a dependency from `repo.gradle.org`, runs on Thoreau's JDK 25 unless
  configured, and has no login-shell environment. Rejected: parsing **`gradle tasks --all`**: a human format that
  changes between versions.
- The command line is built by `GradleCommand`: every word single-quoted for POSIX `sh` unless it is plain; the
  user's arguments are tokenized like a shell does.

**The view (`GradleFileView`)**
- Only `build.gradle` and `build.gradle.kts` get it, through two named viewers (Groovy and Kotlin highlighting), which
  beat the extension viewers. Modes **Source | Split | Gradle** (`SourceSplit`), default **Split**: the source beside
  the task tree above the run pane. The modes appear only once the locator found a Gradle, so a file without one looks
  like plain source.
- **One task list per build and window**, kept by the window-scoped `GradleSession` (a window service, ADR 0070) by the
  real path of the build's root folder: every view of the build shows it, and a load or Refresh of one view updates
  all. The session also asks for the global `gradle` once per window and extracts the init script once.
- **The load is visible**: it runs through the same runner and terminal as tasks, so a distribution download or a
  script error shows in the run pane; the tree shows a note meanwhile or on failure.
- **One load per build at a time**: the session tracks running loads by what is known before loading (the wrapper's
  folder, or the project root for the global `gradle`). Other views of that key wait for its end and show its list,
  or say that it failed; they load themselves only if the stored list does not contain their file (e.g. `buildSrc`).
- **Reload only on Refresh.** A saved build file (`*.gradle`, `*.gradle.kts`, `gradle.properties`, `*.versions.toml`)
  under a loaded build marks it stale, which its views note; nothing reloads by itself, since a load runs the build's
  configuration. Detection also reruns only on opening and Refresh.
- **One fresh process per run** (ADR 0086): the arguments of the view and the selected tasks in tree order. A run while
  another one (or the load) goes **asks whether to stop it**; the old terminal is closed at once. Stop is Ctrl+C, then
  the process is ended after 5 s.
- **Own project's tasks by name, the others by path.** Gradle matches a bare task name in the project of the working
  folder and all its subprojects, a task path (`:clean`) selects that one task. A user expects what `./gradlew clean`
  in the build file's folder does (also IntelliJ), so tasks of the view's own project (the project whose build file
  it shows) are passed by name, tasks of the child projects below it by full path. The command runs in the build
  file's folder, which is where Gradle takes the project from, so a project owns the view only if the build file is
  in the project's folder. A build file that no project owns (e.g. `buildSrc`), or one outside its project's folder
  (a custom `buildFile`), has no own project: all its tasks go by path, as do task names starting with `-`.
- **Status in three places**: a status line with Stop and Rerun, a background task row whose ✕ stops (ADR 0061), and
  the tab marker (`FileView.activity`: running; failed until the tab is shown again). `FileView.closeWarning` asks
  before the tab or window closes during a run.
- While the folder's trust keeps Gradle off, the tree says so with Review…, which opens the window's trust dialog; the
  view follows the trust state and loads once it is trusted.

**Logic**
- The module's logic is free of JavaFX ([ADR 0084](0084-testability-logic-out-of-controls.md)): `GradleLocator`,
  `GradleCommand`, `TaskModel`, `GradleTrustCheck`, and `GradleRuns` with `StopEscalation` (the run state machine
  and the interrupt-then-kill escalation with the grace as parameter). The global check is `loginShellFinds` of the
  terminal module, which the `sbx` check uses too. The view's decisions are pure functions beside it: the task tree
  (`taskTree`) and the status, question and warning texts (`RunTexts`); the session's lookups and stale rule need no
  JavaFX either.

## Consequences
- Every project with a Gradle build asks for trust when opened; the finding names what Gradle would download or run.
- The init script was checked against Gradle 9.8.1 (this repository's wrapper) on a multi-project fixture.
- A list can be out of date until the user presses Refresh; the stale note says when a build file was saved, but not
  when one changed outside Thoreau.
- Build files of `buildSrc` or included builds belong to no project of the root build's list, so their views load on
  their own after the root build's load; a failed load never replaces or clears a stored list.
- The view keeps its decisions in plain parts: `BuildTasks` (detection, trust, loading and following the session)
  and `GradleRunner` (runs), beside the pure `taskTree` and `RunTexts`; file system work runs on the session's
  thread.
- The editor area reports every saved file (`EditorArea.onFileSaved`), which the window passes to the session.
