# 0085. Gradle integration: detection, trust and reading tasks

- Status: accepted
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
  file, and tasks with name, path, group, description) to the file named by `-Dsluide.taskList=<file>`. The load runs
  with `-Dorg.gradle.configuration-cache=false`; the task is also marked not compatible with the configuration cache
  where Gradle has that API.
- It runs through the same command as a task run (wrapper or `gradle` in the login shell), so PATH and `JAVA_HOME`
  of the user apply. Gradle 6 or newer is needed.
- Rejected: the **Tooling API**: it needs a dependency from `repo.gradle.org`, runs on Thoreau's JDK 25 unless
  configured, and has no login-shell environment. Rejected: parsing **`gradle tasks --all`**: a human format that
  changes between versions.
- The command line is built by `GradleCommand`: every word single-quoted for POSIX `sh` unless it is plain; the
  user's arguments are tokenized like a shell does.

**Logic**
- The module's logic is free of JavaFX ([ADR 0084](0084-testability-logic-out-of-controls.md)): `GradleLocator`,
  `GradleCommand`, `TaskModel`, `GradleTrustCheck`, and `GradleRuns` with `StopEscalation` (the run state machine
  and the interrupt-then-kill escalation with the grace as parameter). The global check is `loginShellFinds` of the
  terminal module, which the `sbx` check uses too.

## Consequences
- Every project with a Gradle build asks for trust when opened; the finding names what Gradle would download or run.
- The init script was checked against Gradle 9.8.1 (this repository's wrapper) on a multi-project fixture.
