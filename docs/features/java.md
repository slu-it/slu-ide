# Java: jdtls

**Module:** `modules/java` (package `dev.sluide.java`: `JdtlsSpec`, `JavaSession` with `JavaImports` and
`ImportFailures`, `JavaBuilds`, `Jdks`/`JavaHomes`, `JavaProjectSettings`, `JavaProjectSettingsPage` (the JDK and Clean Java Workspace), `OutputIgnore`, `OutputIgnoreOffers` and `OutputIgnoreChecks` (the offer to ignore compiled output in git), `JdtlsLog`, `JavaWorkspaces`, and `JdtlsInstaller`,
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
  adds it again, in one notification), **Show Log** (opens jdtls's log file of the window, read-only with a banner and the caret at its end so the last lines show: `JdtlsLogExtension`, no Edit Anyway; later reloads keep the editor's behaviour, and a second Show Log on the open tab only selects it) and **Choose JDK…** (Project Settings → Java), then
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
shell (read once for the language server and once for the Project Settings page, so the page's "JAVA_HOME: …" entry can differ from the JDK jdtls uses if the shell's value changed in between), `JAVA_HOME` of Thoreau's environment, Thoreau's own JDK. A setting that is no JDK is skipped
and the tooltip says so. It runs Gradle imports and is the default runtime builds compile against; a change reaches the
running server at once. Thoreau does not choose a JDK a Gradle version can run on: that is what the failed banner's
**Choose JDK…** is for.

## Lombok
When an imported build's build files mention Lombok, jdtls restarts once for the window with the pinned Lombok jar as
its agent.

## Clean Java Workspace
File → Project Settings… → Java has **Clean Java Workspace…**, which deletes jdtls's data folder of the project
(`<workspace folder>/data`: its index and imported project models; `project-path.txt`, the config copy and the Gradle
init script stay). It asks first, with Cancel as the default, and acts at once (not on OK). While jdtls runs, the
server restarts and the delete happens before its next start: the language-server lifecycle calls `command` only after
the process it replaced has ended (ADR 0142), so nothing uses the folder, and the open builds import again. Without a
running jdtls the folder is deleted directly. A delete that fails while jdtls runs is only logged, and jdtls starts on
what is left. The page answers "Cleaned: jdtls restarts and imports the builds again.",
"Cleaned." or "Cannot clean: <reason>". Known limit: a window that closed a moment ago may have its jdtls exiting for
a few seconds after its workspace stopped counting as in use; a Clean in that gap could delete under it.

## Library classes
Definitions in JDK and dependency classes open the source or decompiled text jdtls returns (`jdt:` URIs), written to
`~/.sluide/cache/java-classes/` and opened read-only with the library banner ([library-code.md](library-code.md)). They
have no code intelligence of their own.

## Output folders
Gradle imports get an init script that compiles into `<build directory>/jdtls-bin/<source set>` and leave no `bin/`,
`.project`, `.classpath` or `.settings` in the project; Maven compiles into `target/`. `gradle clean` deletes jdtls's
output, which it builds again.

## Compiled output and .gitignore
When git does not ignore the output (`build/jdtls-bin` of each Gradle project, `target` of each Maven project below the
build root), the build's editors show a banner "build/jdtls-bin isn't ignored by git; jdtls compiles into it." (several
folders: "build/jdtls-bin and app/build/jdtls-bin aren't ignored by git; jdtls compiles into them.") with three buttons:
- **Add to .gitignore** appends one anchored line per folder (`/build/jdtls-bin/`) to the nearest `.gitignore` from the
  build root up to the project folder, or creates one in the build root. It keeps the file's line ending and byte order
  mark, adds no line that is already there, and refuses a symbolic link, a file under `.git` and one that is not inside
  the repository ("Cannot add to <path>: <reason>" in the banner). The banner goes once git agrees; if git still lists
  a folder it says "Added to <path>, but git still doesn't ignore <folders>." An open tab with unsaved edits of that
  file keeps them and offers Reload or Keep mine, as for any change on disk.
- **Not Now** hides it for that build until the window closes (a server restart keeps it).
- **Don't Ask Again** hides it for the project in every window (`java.outputIgnore.declined` in the project's data).

Git decides what is ignored, so `*.class` patterns, a `build/` line, negations and global excludes count; the question is
whether git shows any untracked, not-ignored file in the folder. Git is asked once a build's import is done and again
when jdtls finishes a build, but only about output folders that already hold a file: on a fresh clone the folder does
not exist at the end of the import, and the first build creates it; so does a module that was not yet listed. Each output
folder is settled by git's answer for it, and the offer is the unignored ones. It works
only for a project folder that is itself a git repository root, with git trusted (ADR 0053): a build in a nested
repository gets no offer. The import and failure banners come first.

## Network
A trusted import fetches without a click: the build's dependencies and source jars, the Gradle distribution and its
plugins. This is a decided exception ([safety.md](../safety.md), ADR 0179); nothing else is fetched in the background.

## Limits
Not offered (ADR 0179): a Reimport Build action, Kotlin sources in Java builds, an automatic choice of a
Gradle-compatible JDK, type errors for loose files, formatting through jdtls, refactorings that need client commands,
code intelligence in library classes, separating Maven builds that link by coordinates, eager import, Windows and Intel
macOS. A monorepo with many builds opened in one window uses the memory of every import; a build that fails shows only
the error jdtls reports and its log. A module added to `settings.gradle` or `<modules>` after the import gets the
offer to ignore compiled output after the next import of its build (Retry Import, a JDK change, or reopening the window).
