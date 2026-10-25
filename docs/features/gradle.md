# Gradle

`build.gradle` and `build.gradle.kts` open in the Gradle view: the source beside the build's tasks and a terminal that
runs them. Decisions: [ADR 0085](../adr/0085-gradle-integration.md); the terminal: [ADR 0086](../adr/0086-embedded-command-terminals.md).

## The view
- Modes **Source | Split | Gradle**, top right; **Split** is the default: the source on the left, on the right the task
  tree above the run pane. The divider positions are kept while a side is hidden.
- The modes appear only once a Gradle was found for the file; until then (and for good if there is none) the view
  looks like plain source: Kotlin highlighting for `build.gradle.kts`, Groovy for `build.gradle`. Only these two names
  get the view; settings files and other `*.gradle(.kts)` scripts stay plain source.
- Shown again (tab selected), the view focuses the source, or the task tree while it is shown alone. Going to a line
  (e.g. from a search) while only the tasks show switches to Split first.
- The Gradle side (filter, notes, tree, arguments, status) uses the UI font, not the editor font; the terminal keeps
  its own.

## Detection
- A build file uses the **wrapper** of the nearest folder, from its own folder up to and including the project root,
  that has `gradlew` and `gradle/wrapper/gradle-wrapper.properties`. A `gradlew` that is not executable runs as
  `sh gradlew`.
- Without a wrapper it uses the **global** `gradle` if the login shell finds it (`command -v gradle`, asked once per
  window, off the FX thread).
- Files outside the project root (e.g. scratches) get no Gradle. Neither do files in hidden folders, in `build`,
  `node_modules` or `out`, below a symbolic link, or more than 8 folders below the project root: the trust check does
  not look there either.
- Detection runs when the view opens and on Refresh; nothing watches the files.

## Trust
- Gradle is a tool of the project trust prompt (`gradle`). It shows one entry per wrapper, "Gradle wrapper in
  <folder>" (what the wrapper downloads and runs), and one per folder with build scripts and no wrapper in or above it,
  "Gradle build scripts in <folder>" (run by the global gradle).
- A changed `gradlew`, `gradle-wrapper.jar` or `distributionUrl` asks again.
- A project with more than 20,000 folders cannot be checked, and its Gradle stays off.
- While the folder's trust is being checked, the task tree says "Checking project trust…". While Gradle is off, it says
  "Gradle is off until the folder is trusted." with **Review…**, which opens the trust dialog. Once the folder is
  trusted, the tasks load without reopening the file.

## Loading the tasks
- The tasks are read with a bundled init script, run like a task (same wrapper or `gradle`, in the login shell): the
  load is **visible in the run pane's terminal**, with the distribution download and script errors. The tree says
  "Loading tasks…" meanwhile and "Could not load tasks — see the output below." when it fails; a stopped load says
  "Loading the tasks was stopped — Refresh to load them.", and a load not started because the user kept a running
  task "The tasks are not loaded — Refresh to load them."
- Tasks load **once per build and window**: every build file view of that build in the window shows the same list.
  A view loads when it first finds Gradle, the folder is trusted, and no list of its build is loaded yet. Views of the
  same build (the same wrapper folder, or the project root for the global `gradle`) never load at the same time: while
  one loads, the others say "Loading tasks…" and show its result, or "Another view could not load the tasks — Refresh
  to try again." when it failed, was stopped or was not started.
- **Refresh** finds Gradle again and reloads the list, for every view of the build.
- After a build file of a loaded build is saved (`*.gradle`, `*.gradle.kts`, `gradle.properties`,
  `*.versions.toml`), the tree says "Build files changed since loading — Refresh to reload." Nothing reloads by itself.

## The task tree
- **Project → group → task.** A view shows its own project and the projects below it (`build.gradle` of the root:
  all). The root project is labelled with its name and `(:)`, others with their path (`:app:core`).
- The view's own project is expanded with its groups; tasks without a group are under **other**, last and collapsed.
- Each task shows its description dimmed after its name; the tooltip shows its path and description.
- **Filter tasks** keeps the tasks whose name contains the text (ignoring case), with their groups and projects, all
  expanded.

## Running
- **Double-click** a task or press **Enter** with tasks selected: the selected tasks run in one invocation, in
  tree order. Tasks of the view's own project are passed by **name** (`clean`): Gradle runs them in that project and
  its subprojects, like `./gradlew clean` in the build file's folder. Tasks of the child projects listed below keep
  their **full path** (`:app:clean`) and run only that task. A build file that no project owns, or that is not in its
  project's folder (custom `buildFile`), passes all by path (the run starts in the build file's folder, which is where
  Gradle takes the project from); so does a task name starting with `-`.
  The tooltip of a task row says which: "clean (this project and its subprojects)" or ":app:clean (only this task)",
  then the description. Projects and groups in the selection are ignored. The focus stays
  where it was: the run's terminal takes it only when clicked (e.g. to answer a prompt).
- The words in the **Arguments:** field above the terminal are added to every run of the view (e.g. `--info -x test`).
  They are split into words like a shell does (spaces separate, quotes group), but each word is passed quoted: there is no `$VAR`, `~` or
  glob expansion. An unclosed quote is reported in the status line and nothing runs.
- Every run is a fresh process (`$SHELL -l -i -c '<gradle> <arguments> <tasks>'`) in the build file's folder, in a
  fresh terminal that replaces the previous run's output. The interactive login shell reads the same startup files as
  a terminal tab (e.g. `.zshrc`, where SDKMAN sets `JAVA_HOME` and `PATH`), so Gradle finds the same Java
  ([ADR 0087](../adr/0087-commands-in-an-interactive-login-shell.md)); what those files print shows above Gradle's
  output.
- Starting a run while another one (or the load) is going asks: "test is still running. Stop it and run
  build?" Stopping closes the old terminal at once.
- **Stop** sends Ctrl+C (Gradle cancels the build) and ends the process if it has not stopped after 5 seconds.
  **Rerun** runs the last run's tasks with the current arguments.

## Status
A run shows in three places:
- the **status line** of the run pane: "▶ test — running 0:42", "✔ test — finished in 1:03",
  "✖ test — failed (exit 1) after 0:12", "■ test — stopped" (its tooltip shows it whole when it is cut);
- a **background task** row ("Gradle: test", "Gradle: loading tasks") whose ✕ stops the run;
- a **marker on the editor tab**: running, or failed until the tab is next shown.

Closing the tab or the window while a run or load is going asks first ("build.gradle.kts is still running test.").

## Known limits
- Gradle 6 or newer (the init script).
- Gradle finds a `settings.gradle` above the project root itself: a project that is part of a bigger build lists and
  runs that build.
- With configure-on-demand, projects Gradle does not configure may list fewer tasks.
- Build files that belong to no project of the root build (`buildSrc`, included builds) are not found in its loaded
  list: their view loads on its own once the build's load has ended. What that load lists depends on where Gradle
  finds the settings; a failed load never replaces or clears the shared list.
- `file:line` in the output is not clickable yet.
