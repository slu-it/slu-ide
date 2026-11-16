# Java: jdtls

**Module:** `modules/java` (package `dev.sluide.java`: `JdtlsSpec`, `JavaSession` with `JavaImports` and
`ImportFailures`, `JavaBuilds`, `Jdks`/`JavaHomes`, `JavaProjectSettings`, `JavaWorkspaces`, and `JdtlsInstaller`,
`JdtlsUpdates`, `JavaSettings` over the shared server-tool layer of `modules/server-tools`); decisions in
[ADR 0179](../adr/0179-java-via-jdtls.md) and [ADR 0159](../adr/0159-shared-server-tool-layer.md)

Java code intelligence comes from the language server [jdtls](https://github.com/eclipse-jdtls/eclipse.jdt.ls) (Eclipse),
run through the language-server client of [ADR 0142](../adr/0142-language-server-foundation.md) for files ending in
`.java` inside the project folder. The editor features are described in [code-intelligence.md](code-intelligence.md).
jdtls imports the project's Gradle or Maven build, which runs the build's scripts, plugins and extensions: the Java
server therefore waits until the project folder is trusted (the `gradle` and `maven` checks, ADR 0085 and 0179),
loose files included.

## Install
Thoreau downloads jdtls (the milestone this Thoreau was tested with) and a pinned Lombok jar only on a click, like ty
and the TypeScript server: the banner above a Java editor, or Settings → Languages → Java, which also checks for updates
(`download.eclipse.org`, on click), rolls back, deletes, and takes a custom `bin/jdtls`. Hosts: `download.eclipse.org`
and `repo1.maven.org`.

## One server per window, import on open
One jdtls runs per window, on Thoreau's own JVM with the memory of Settings → Languages → Java → Maximum memory, and
with a data folder of its own per project under `~/.sluide/jdtls/workspaces/`. A project is not imported when the
window opens: the server starts with the first Java editor, and each build is imported when the first file of it opens.
A monorepo costs one JVM per window plus a Gradle daemon per Gradle version in use while imports run; builds never opened
cost nothing.

## Build roots
The build root of a `.java` file, found inside the window folder:
- **Gradle:** the nearest folder upwards with `settings.gradle(.kts)`, else the nearest with `build.gradle(.kts)`.
- **Maven:** the nearest `pom.xml` folder, then upwards while the parent's `pom.xml` lists the folder in `<modules>`.
- A folder with both is Gradle. A build root must be covered as the trust checks see folders (at most 8 deep below the
  window folder, no hidden, output or link folder); otherwise the file is loose and the build further up is not used.
- A **loose file** has no build: jdtls gives JDK-level hover and completion and syntax errors, but no type errors.

Each root is announced to jdtls on its own and stays until the window closes, except that a re-resolution of the
roots (a new `settings.gradle` or `pom.xml` changes which folder serves a file) removes a folder, and Retry Import
removes it and adds it again in one notification. Builds are kept apart; Maven builds that
depend on each other by coordinates see each other's sources while both are imported (as in Eclipse and VS Code).
Changes to `settings.gradle(.kts)` and `gradle-wrapper.properties` reimport the build; other build files jdtls watches
itself.

## Import feedback
Thoreau tracks every root itself: importing from its announcement until the progress of its job ends (jdtls runs one
update job per folder change, removals too, one after another, and drops a waiting job that a later change makes
useless; Thoreau follows its status messages to know which progress belongs to which root), done when
`java.project.getAll` then lists a project at or below the root, otherwise failed. One case jdtls decides by a race:
when a removal and an add come together, the removal's job may have started or may be dropped. Thoreau then counts on it
running; if it was dropped, the last root's verdict comes once jdtls was silent for 10 seconds with nothing running. A
failed root that a later `getAll` lists a project for is done. When jdtls cannot answer `getAll`, Thoreau asks again 10
seconds later; a second failure fails the import with "jdtls did not list its projects: <message>". While any root
imports, banners and status lines refresh once a second.
- **Importing:** a banner above the build's editors, "Importing services/billing (Gradle)… 0:42" (the root relative to
  the window folder, or the folder's name when the root is the window folder). It has no button, not even Not Now or
  Don't Ask Again, and goes when the import is done.
- **Failed:** "Import of services/billing failed: <detail>" with **Retry Import** (removes the folder from jdtls and
  adds it again, in one notification), **Show Log** (opens jdtls's log file of the window) and **Choose JDK…** (Project Settings → Java), then
  Not Now and Don't Ask Again. The detail is, in this order: for Gradle that cannot run on the JDK, "Gradle 8.14.3
  can't run on JDK 25 (newest: 24). Choose another JDK in Project Settings → Java, or upgrade the Gradle wrapper to
  9.7.1." (the Gradle version comes from the wrapper's `distributionUrl`, a part that is unknown is left out); else the
  first error message jdtls reported on the root folder, its `pom.xml` or its Gradle files; else "No project was found;
  see the log." (a malformed `pom.xml` gives only that). A failed root has no project for jdtls to update, so it
  leaves "failed" only by Retry Import, by a change of the project's JDK, or by a change of a build file below it
  (`pom.xml`, `*.gradle(.kts)`, the wrapper properties), each of which imports it again.
- **Status line** below the text: `Java project: services/billing · Gradle · Java 17` (the compliance level jdtls
  reports once known), then ` · importing…` or ` · import failed`, then ` · Kotlin sources not visible` when the file's
  module or build root has Kotlin sources or Kotlin in its build file. A loose file says `No Java build · JDK 25`. The
  tooltip has the build root's path (or "No build: JDK classes only"), the JDK with its source, a problem with the JDK
  setting if there is one, and for Kotlin: "Kotlin sources in this build aren't visible to jdtls; references to them
  show as errors." A click on the line selects the build root in the explorer.

## The JDK
The project JDK is, in this order: the project's setting (File → Project Settings…), `JAVA_HOME` of the user's login
shell (read once per run), `JAVA_HOME` of Thoreau's environment, Thoreau's own JDK. A setting that is no JDK is skipped
and the tooltip says so. It runs Gradle imports and is the default runtime builds compile against; a change reaches the
running server at once. Thoreau does not choose a JDK a Gradle version can run on: that is what the failed banner's
**Choose JDK…** is for.

## Lombok
When an imported build's build files mention Lombok, jdtls restarts once for the window with the pinned Lombok jar as
its agent.

## Library classes
Definitions in JDK and dependency classes open the source or decompiled text jdtls returns (`jdt:` URIs), written to
`~/.sluide/cache/java-classes/` and opened read-only with the library banner ([library-code.md](library-code.md)). They
have no code intelligence of their own.

## Output folders
Gradle imports get an init script that compiles into `<build directory>/jdtls-bin/<source set>` and leave no `bin/`,
`.project`, `.classpath` or `.settings` in the project; Maven compiles into `target/`. `gradle clean` deletes jdtls's
output, which it builds again.

## Network
A trusted import fetches without a click: the build's dependencies and source jars, the Gradle distribution and its
plugins. This is a decided exception ([safety.md](../safety.md), ADR 0179); nothing else is fetched in the background.

## Limits
Not offered (ADR 0179): a Reimport Build action, Kotlin sources in Java builds, an automatic choice of a
Gradle-compatible JDK, type errors for loose files, formatting through jdtls, refactorings that need client commands,
code intelligence in library classes, separating Maven builds that link by coordinates, eager import, Windows and Intel
macOS. A monorepo with many builds opened in one window uses the memory of every import; a build that fails shows only
the error jdtls reports and its log.
