# Idea: Java through jdtls

**Status:** decided in [ADR 0179](../adr/0179-java-via-jdtls.md) (2026-11-15) after a second spike on a scratch
monorepo, whose findings are in the ADR's context; this page keeps the first spike and what was left out. Follows the candidate list in
[language-servers-later.md](language-servers-later.md) ("More languages on the foundation").

## Server choice

[Eclipse JDT LS (jdtls)](https://github.com/eclipse-jdtls/eclipse.jdt.ls) is the Java server of VS Code (Red Hat),
Neovim, Emacs, Helix and Zed; EPL-2.0. Alternatives: Apache NetBeans' `nbcode` (Oracle's VS Code extension, rarely
used outside it) and georgewfraser/java-language-server (javac-based, mostly unmaintained, guesses the classpath).

## Spike setup

A throwaway Python client over stdio (initialize, wait for `language/status` `ServiceReady`, open
`OwnerController.java`, seven requests, RSS sampled every second including Gradle daemons) against
[spring-petclinic](https://github.com/spring-projects/spring-petclinic) (50 Java files, Spring Boot 4.1) in three
copies: Maven build only, Gradle build only (wrapper 9.5.1), no build file. jdtls 1.63.0 milestone, run on the
sandbox's JDK 25 (Linux aarch64, proxied network), `-Xmx1G`. Timings are from the sandbox; macOS is unmeasured.

## Results

**Distribution:** `download.eclipse.org/jdtls/milestones/<version>/jdt-language-server-<version>-<timestamp>.tar.gz`
with a `.sha256` next to it (verified); a `snapshots/latest.txt` names the newest build. 51 MB packed, 53 MB unpacked,
one tree for all platforms (`config_linux`, `config_mac`, `config_mac_arm`, ...). Runs on Thoreau's own JDK 25: no
runtime to install. Started with `java -jar plugins/org.eclipse.equinox.launcher_*.jar -configuration <copy of
config_<os>> -data <workspace dir>` plus `--add-modules=ALL-SYSTEM` and two `--add-opens`.

| Project | Ready (cold) | Ready (warm) | Peak RSS cold | Peak RSS warm | Notes |
|---|---|---|---|---|---|
| Maven | 36 s | 1.0 s | 1.2 GB | 0.5 GB | cold includes downloading all dependencies into an empty `~/.m2` (embedded Maven, m2e, no extra process) |
| Gradle, toolchain 17 | failed after 559 s | – | 1.0 GB (0.4 jdtls + 0.6 daemon) | – | 534 s silently downloading the wrapper distribution, then `ToolchainProvisioningException`: no JDK 17, no toolchain repositories |
| Gradle, toolchain 25 | 38 s | 1.3 s | 1.7 GB (1.2 jdtls + 0.55 daemon) | 0.5 GB | distribution and plugins cached; warm start does not touch Gradle at all (unchanged build files) |
| No build file | 0.95 s | 0.96 s | 0.9 GB | 0.4 GB | nothing resolves beyond the JDK, wrong source root guessed; useless for real projects |

**Requests** (Maven and Gradle alike, warm):

| Request | Result | Time |
|---|---|---|
| Definition of a project class | `Owner.java` | ~0.9 s first request, then ms |
| Definition of a library method | the project's override in `OwnerRepository.java` | 0.02 s |
| Definition of `String` | `jdt://contents/java.base/java.lang/String.java?...`; `java/classFileContents` returns 67 KB (decompiled by FernFlower because the sandbox's Debian JDK has no `src.zip`) | 0.5–1 s + 0.2 s |
| Hover on a project method | signature plus source link, Markdown | 0.03 s |
| Hover on a library type | Javadoc from a source jar | 5 s cold (source jar download), 0.2 s warm |
| References | 13 | 0.1 s |
| Completion after `owner.` | 28 items | 0.06 s |
| Diagnostics after a type error typed in | "Type mismatch: cannot convert from int to String" | 0.58 s after `didChange` |
| Quick fixes, organize imports | plain `edit`s in the code actions | – |

## Findings for Thoreau

- **Gradle needs a Gradle daemon, as for Kotlin.** jdtls (Buildship, Tooling API) starts one, it runs the build
  scripts, and it **stays alive after jdtls exits** (default idle timeout of three hours). Thoreau would have to stop
  it or pass a short idle timeout. Trust: `GradleTrustCheck` exists ([ADR 0085](../adr/0085-gradle-integration.md)).
- **Maven runs inside jdtls**, but it downloaded and loaded plugin jars (native-maven-plugin, cyclonedx, checkstyle)
  during the import. That is project-controlled code too, so Maven projects need the trust question as well.
- **Gradle toolchains:** jdtls passes on the build's toolchain failure. A project asking for a JDK that is not
  installed and has no toolchain repository fails to import, and every request returns nothing. The only signal is
  a diagnostic on the project folder URI and `ProjectStatus: WARNING`. Thoreau needs a banner for that.
- **No progress during the wrapper download.** jdtls reported nothing for nine minutes (on the sandbox's slow proxy).
  A banner needs its own "importing…" state with a timeout or elapsed time, not one built on progress events.
- **`workspace/applyEdit` is not needed for the basics.** With `applyEdit: false` declared, jdtls never asked for it,
  and quick fixes and organize imports came back as edits (refactorings beyond these are untested).
- **Library sources:** Go to Definition into JDK and library classes returns `jdt://` URIs; the client fetches the
  text with `java/classFileContents` after declaring `extendedClientCapabilities.classFileContentsSupport`. That fits
  the read-only library view ([ADR 0150](../adr/0150-library-code-read-only.md)), with a URI scheme of its own.
- **Workspace data per project:** `-data` holds indexes and the import cache (157 MB for petclinic). It belongs under
  `~/.sluide` per project; the configuration folder has to be a writable copy per user.
- **jdtls downloads source jars on its own** (170 for petclinic) into `~/.m2` and the Gradle cache.
- **Odd diagnostic:** "At least one of the problems in category 'unused' is not analysed due to a compiler option
  being ignored" on a project file, in every build variant; it would need filtering or a compiler setting.
- **Memory:** about 0.5 GB warm, 1.2 GB during a cold import, plus about 0.55 GB for a Gradle daemon. jdtls defaults
  to `-Xmx1G`; a setting for it is useful.

## Open questions

- macOS startup and memory; real JDK sources from `lib/src.zip` instead of the decompiler.
- Behaviour when `build.gradle` or `pom.xml` changes while running (needs `didChangeWatchedFiles`, ADR 0142).
- A project with both `pom.xml` and `build.gradle`: which importer wins.
- Large multi-module builds (import time, memory).
- Lombok (`-javaagent`), annotation processors, and Kotlin and Java mixed in one Gradle build.

## Left out of the first round (ADR 0179)
- A Reimport Build action for one build root (today: Retry Import after a failure, automatic reimport on build file
  changes, Clean Java Workspace).
- Kotlin sources in Java builds (needs a Kotlin server next to jdtls) and Kotlin code intelligence itself.
- Choosing a Gradle-compatible JDK automatically from the wrapper's version.
- Full diagnostics for loose `.java` files: an invisible project per folder compiles everything below it and writes its
  output there.
- Formatting through jdtls; refactorings that need `workspace/applyEdit` or client-side commands
  (`java.action.applyRefactoringCommand`: Change signature).
- Code intelligence inside library classes opened from `jdt://` URIs.
- Keeping Maven builds that depend on each other by coordinates apart (m2e's `resolveWorkspaceProjects` also cuts
  reactor links; one server per build would cost a JVM each).
- `workspace/textDocumentContent` (LSP 3.18; jdtls declares the `jdt` scheme) instead of `java/classFileContents`.
