# 0179. Java through jdtls: one server per window, a workspace folder per build, imported lazily

- Status: accepted; amends [0142](0142-language-server-foundation.md) (session extensions, several trust checks,
  workspace folders announced one by one), [0151](0151-workspace-folders-per-project.md) (no window folder for
  specs in roots-only mode), [0160](0160-document-gate-and-kept-diagnostics.md) (several actions per environment
  problem), [0150](0150-library-code-read-only.md) (Java library classes),
  [0159](0159-shared-server-tool-layer.md) (a launch function, no version command); see [0053](0053-project-trust.md), [0085](0085-gradle-integration.md), [0090](0090-per-project-metadata-store.md)
- Date: 2026-11-15

## Context
The round "Java code intelligence through jdtls with monorepo support" adds Java as the third language on the
language-server foundation. Two spikes with jdtls 1.63.0 (Eclipse JDT Language Server) on JDK 25, linux-aarch64,
2026-11-15, decided the design (details and timings: [java-language-server.md](../ideas/java-language-server.md)).

**Server.** The only mature Java server outside JetBrains; EPL-2.0. It is a 51 MB tar.gz for every platform on
`download.eclipse.org/jdtls/milestones/<version>/`, with a bare-hash `.sha256` beside it and no redirect. It runs on
Thoreau's JDK 25. Maven builds import inside it (m2e, Maven embedded); Gradle builds through Buildship and the
Tooling API, which starts a Gradle daemon as its child. Measured on spring-petclinic: Maven 36 s cold (with all
dependency downloads), 1.0 s warm; Gradle 38 s cold, 1.3 s warm; 0.5 GB resident warm, 1.2 GB during a cold import, a
Gradle daemon about 0.55 GB. Requests answer in milliseconds; diagnostics follow an edit after about 0.6 s.

**Monorepos.**
- jdtls imports only the first of the workspace folders it gets in `initialize`. Started with none and given each
  build root by its own `workspace/didChangeWorkspaceFolders`, it imports exactly those roots, one after the other,
  and leaves sibling builds alone; removing a folder drops its projects.
- Every import writes `.project`, `.classpath` and `.settings/` into the build folder, unless jdtls's JVM gets
  `-Djava.import.generatesMetadataFilesAtProjectRoot=false`; then they live in its data folder. Compiled classes stay
  inside the project: Maven's `target/classes`, Buildship's `bin/main` and `bin/test`. An output folder outside the
  project fails the import ("must denote location inside project"). A Gradle init script setting
  `eclipse.classpath.baseSourceOutputDir` (Gradle 8.5 to 9.x have it) moves Gradle's classes to a folder below the
  build directory.
- Gradle builds stay separate. Two imported Maven builds where one depends on the other's coordinates link to each
  other's sources (m2e workspace resolution); the only switch, m2e's per-project `resolveWorkspaceProjects`, also
  cuts the links between the modules of one reactor.
- A `.java` file outside every imported build lands in jdtls's default project: hover and completion against the JDK,
  diagnostics for syntax only. A folder of its own as workspace folder gives full diagnostics but an invisible project
  that compiles everything below it and writes its output there.
- `pom.xml` and `build.gradle` in one folder: Gradle wins (importer order Gradle, Maven, Eclipse, invisible).

**Import feedback.** No status per root: each `didChangeWorkspaceFolders` opens one `$/progress` token ("Updating
workspace folders") whose end comes for success and failure alike. Success shows as projects under the root in
`java.project.getAll`. A Gradle import that cannot run on the JDK sends `language/eventNotification` type 300 with the
project URI, the message ("Can't use Java 25.0.5 and Gradle 8.14.3 to import Gradle project x."), the highest Java
version and a recommended Gradle version; other failures publish severity-1 diagnostics on the root folder URI or on
`pom.xml`. A malformed pom fails without any signal.

**Other findings.** Settings come through `initializationOptions.settings.java` and `workspace/didChangeConfiguration`
(jdtls asks `workspace/configuration` only for formatting). Changes of `pom.xml` and `*.gradle(.kts)` reimport by
themselves with `updateBuildConfiguration: automatic` (jdtls registers watchers for them); `settings.gradle` and the
wrapper's properties need the notification `java/projectConfigurationUpdate` with the build file's URI. A Gradle daemon
idle timeout works only as a build argument (`java.import.gradle.arguments`, a JSON list of strings, one argument each,
honoured: the daemon's context shows `idleTimeout=900000`; `-Dorg.gradle.daemon.idletimeout=…`); the daemon is jdtls's
child while jdtls runs and orphaned once it exits. `java.configuration.runtimes` picks the JDK a build compiles against
(its name is not checked against the JDK). `java.project.getSettings` answers the compliance level and the JDK path.
With `applyEdit` off and code-action resolve for `edit`, every quick fix arrives as data resolved to an edit; only
"Change signature" carries a client-side command (`java.action.applyRefactoringCommand`). Lombok 1.18.48 as `-javaagent`
works on JDK 25. Go to Definition into the JDK or a library returns `jdt://` URIs, whose text `java/classFileContents`
returns (decompiled when no source exists).

## Decision
**Server and install.** Thoreau runs jdtls on its own JVM (`java.home`/bin/java) with `-Xmx` from Settings → Languages
→ Java → Maximum memory (default 2 GB), `-Djava.import.generatesMetadataFilesAtProjectRoot=false`, and a data folder
per project `~/.sluide/jdtls/workspaces/<folder name>-<12 hex of sha256(window folder)>/` (`data/`, a copy of
`config_<os>`, `project-path.txt`). It installs like ty and TypeScript (ADR 0159), launched through the tool's
launch function with no version command (the smoke test only runs the handshake): the pinned milestone on a click,
size and sha256 checked; Check for Updates parses the milestones index (versions sorted as numbers) and
`<version>/latest.txt`; Update, Roll Back, Delete and a custom jdtls (`java.jdtls.path`, the `bin/jdtls` file of an
unpacked jdtls, from which Thoreau finds the folder). A pinned Lombok jar
from Maven Central (sha256 pinned; Maven Central publishes only sha1, checked when the pin is bumped) is installed and
deleted with it. Hosts: `download.eclipse.org`, `repo1.maven.org`, only on a click.

**One server per window, a workspace folder per build root, imported when a file of it opens.** The spec runs in a
new foundation mode, `WorkspaceFolderMode.ROOTS_ONLY`: `initialize` gets no workspace folder, and each root reaches
the server in a `didChangeWorkspaceFolders` of its own, in order, also after a restart. The build root of a `.java`
file, inside the window folder: for Gradle the nearest folder upwards with `settings.gradle(.kts)`, else the nearest
with `build.gradle(.kts)`; for Maven the nearest `pom.xml` folder, then upwards while the parent's `pom.xml` lists the
folder in `<modules>` (parsed without DTDs, size-limited); a folder with both is Gradle. The build root the rules
pick must be covered, as the trust checks see folders (at most 8 deep below the window folder, no hidden, output or
link segment; Java also skips `target` and `bin`); otherwise the file is loose and nothing is imported, and the build
further up is not used instead. The Maven check also fingerprints the poms of the modules a reactor lists, wherever
they are. Files with no root are loose: the default project, JDK-level hover and completion, syntax-only diagnostics. Folders whose documents all closed stay
until the window closes (ADR 0151).

**Separation.** Gradle builds stay apart; Maven builds that depend on each other by coordinates see each other's
sources while both are imported (accepted; Eclipse and VS Code behave the same).

**Output folders.** Gradle imports get an init script that moves the compiled classes to
`<buildDirectory>/jdtls-bin/<source set>` (`baseSourceOutputDir`, guarded for Gradle versions without it, which keep
`bin/`; a lazy `Directory` property in Gradle 9 and a `File` property in 8.x, so the script sets it by type; the default
output folder `jdtls-bin/default` is set after the build script ran). Measured with 8.5, 8.14.3 and 9.5.1: classes land
in `build/jdtls-bin/main|test`, no `bin/`, and no `.project`, `.classpath` or `.settings` in the project. Maven compiles
into `target/`. After a build's import, Thoreau asks git (`git check-ignore`) whether its output folder is ignored; if
not, the build's editors show a banner "<folder> isn't ignored by git; jdtls compiles into it." with **Add to
.gitignore** (a line anchored to the folder, appended to the nearest `.gitignore` from the build root up to the
repository root, or a new `.gitignore` in the build root; asks first when that file has unsaved changes), Not Now, Don't
Ask Again. Thoreau never writes into `.git`.

**JDK.** The project JDK is `JAVA_HOME` from the login shell (read once per app run, 10 s timeout), else Thoreau's
environment, else Thoreau's JDK; File → Project Settings… → Java overrides it per project (`java.jdk` in the project's
data, ADR 0090). It runs Gradle imports (`java.import.gradle.java.home`) and is the default runtime every build compiles
against (`java.configuration.runtimes`, named `JavaSE-<major>` from the JDK's `release` file). A change goes to the
running server through `didChangeConfiguration`.

**Trust.** Imports run build scripts, plugins and extensions, so the spec needs the `gradle` check (ADR 0085) and a new
`maven` check (one finding per reactor root, fingerprint over its poms and `.mvn/extensions.xml`). In an untrusted
folder the whole Java server waits, loose files included. Network during a trusted import, without a click: the
project's dependencies, their source jars, the Gradle distribution and plugins (an exception to `docs/safety.md`,
next to ADR 0085's).

**Gradle daemons** get `-Dorg.gradle.daemon.idletimeout=900000` (15 minutes) as a build argument; closing the server
kills them as its descendants (ADR 0142).

**Import feedback.** Thoreau tracks each root itself: importing from its folder's announcement (an add, or a refresh:
Retry and the JDK change send one notification that removes and adds the folder, amending ADR 0151 decision 4) until no
job that adds it is left; done if `java.project.getAll` then has a project under the root, else failed. jdtls 1.63.0
(`ProjectsManager.updateWorkspaceFolders`, and traces of every case, P7b) sends a `language/status` for each
notification, cancels the jobs that have not started and whose added folders equal its removed ones, and schedules a job
titled "Updating workspace folders"; the jobs hold the workspace root, run one after another, and each sends one
progress token, its end before the next one's begin. Thoreau mirrors that queue (`UpdateJobs`): the k-th status answers
the k-th notification (both arrive in order, a status may come first and then waits for its notification, with what came
after it); a status cancels the matching jobs behind the oldest one, never one whose token began. Whether the oldest
job, its begin not yet come, had started when a status cancelled it is a race inside jdtls (the same input gave both
outcomes in the traces), so the mirror keeps it: if it was cancelled, the verdicts come one job late, and the last job
is settled once jdtls sent nothing and had no token open for 10 seconds, while that doubt is open. That is the one
time-based rule; two guards use the same 10 seconds for what never happened in the traces: a status that waits that long
for its notification is dropped as a mismatch, and a `getAll` that fails is asked once more after it (a second failure
fails the roots waiting for their verdict: "jdtls did not list its projects: …"). A failed root (not by event 300) whose
project a later `getAll` lists is done. Counts that do not fit start the mirror over (the begun jobs keep their tokens,
the importing roots are queued). The detail of a failure: event 300 ("Gradle 8.14.3 can't run on JDK 25 (newest: 24).
Choose another JDK in Project Settings → Java, or upgrade the Gradle wrapper to 9.7.1."), else the root's severity-1
diagnostics (kept, ADR 0160), else "No project was found; see the log". Banners in the build's editors: "Importing
services/billing (Gradle)… 0:42" while it runs; on failure the detail with **Retry Import** (removes and adds the
folder, one notification), **Show Log** (opens jdtls's log), **Choose JDK…** (Project Settings → Java). The status line:
`Java project: services/billing · Gradle · Java 17` (compliance from `java.project.getSettings`), `No Java build · JDK
25`, with `· importing…`, `· import failed` and `· Kotlin sources not visible` (the file's module or build root has
`src/<set>/kotlin` or a build file mentioning `org.jetbrains.kotlin`/`kotlin(`) appended; the tooltip names the build
root and the JDK with its source. Build file changes reimport automatically; `settings.gradle(.kts)` and
`gradle-wrapper.properties` changes send `java/projectConfigurationUpdate`.

**Lombok.** When an imported build's build files mention Lombok, jdtls restarts once with `-javaagent:<pinned jar>`.

**Library classes.** Definition, declaration and type definition results with a `jdt:` URI are fetched with
`java/classFileContents`, written to `~/.sluide/cache/java-classes/` and opened read-only with a library banner
("Java library class"), without code intelligence.

**Code actions** whose command the server does not offer (`executeCommandProvider`) are not shown.

**Foundation changes** (`language-servers`, `code-intelligence`, `library-code`): `trustCheckIds`,
`WorkspaceFolderMode`, `SessionExtension` and `SessionAccess` (custom notifications, progress, folder announcements
with a refresh as one call, commands, notifications, kept diagnostics, refresh of a folder, restart), `ProblemAction` as `Terminal`, `OpenFile` or
`Callback` with several per problem, `libraryDocument` for non-file URIs, `LibraryKind.JAVA_CLASS`. New: File →
Project Settings… (`ProjectSettingsPage`), no key binding.

**Out of scope** (to [java-language-server.md](../ideas/java-language-server.md)): a Reimport Build action, Kotlin
sources in Java builds, an automatic choice of a Gradle-compatible JDK, full diagnostics for loose files, formatting
through jdtls, refactorings that need `workspace/applyEdit` or client commands, code intelligence in library classes,
separating Maven builds that link by coordinates, eager import, Windows and Intel macOS.

## Consequences
- A monorepo costs one JVM per window plus a daemon per Gradle version in use while imports run; builds never opened
  cost nothing.
- Maven builds in one window that depend on each other by coordinates resolve to each other's sources, unlike a
  command-line build that would use the installed jar.
- Gradle builds get a `jdtls-bin` folder under their build directory, Maven builds compiled classes in `target/`;
  `gradle clean` deletes jdtls's output, which it compiles again.
- A malformed pom gives only "No project was found" and the log.
- Loose files show no type errors.
- A removal and an add sent together (a re-resolution of the roots) can leave the last import's verdict 10 seconds
  after jdtls went quiet, when jdtls dropped the removal's job; it also leaves the removed folder's project in jdtls
  (jdtls's own behaviour, seen in the P7b traces).
- Listed in [../index.md](../index.md).
