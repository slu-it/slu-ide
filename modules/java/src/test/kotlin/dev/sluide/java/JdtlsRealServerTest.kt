package dev.sluide.java

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.languageservers.LanguageServers
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.ServerState
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.ProjectSettingsOpeners
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The spec's real command on a real jdtls (`SLUIDE_TEST_JDTLS` names an unpacked jdtls folder; skipped without it) on a
 * single-module Maven project, through the real [LanguageServers]: the import finishes, a definition lands in a project
 * class, and one into `java.lang.String` is a `jdt:` URI whose `libraryDocument` fetch returns text. Needs the network
 * for Maven's plugins unless `~/.m2` has them; the server writes into a temporary data folder.
 */
class JdtlsRealServerTest {
    @TempDir
    lateinit var dir: Path

    private val aSource =
        """
        package p;

        public class A {
            B b = new B();
            String s = "";
        }
        """.trimIndent() + "\n"

    @Test
    fun `the spec's command imports a Maven project, finds a project class and fetches a JDK class`() {
        val started = System.nanoTime()
        val window = Files.createDirectories(dir.resolve("work"))
        window.resolve("pom.xml").file(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>t</groupId><artifactId>t</artifactId><version>1</version>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
              </properties>
            </project>
            """.trimIndent(),
        )
        val a = window.resolve("src/main/java/p/A.java").file(aSource)
        val b = window.resolve("src/main/java/p/B.java").file("package p;\n\npublic class B {}\n")

        withServer(window) { servers, spec ->
            val session = servers.session(spec)
            onFx { session.documents.open(a, "java", aSource) }

            awaitImported(servers, spec, window)
            println("jdtls real run: import finished after ${elapsedSeconds(started)} s")
            // The tracked state: the real JavaImports saw the progress token end and asked java.project.getAll.
            // Done first, the compliance after the java.project.getSettings answer.
            assertEquals(
                ImportState.Done("17"),
                awaitImportState(spec, window) {
                    it is ImportState.Done &&
                        it.compliance != null
                },
            )

            val project = definition(servers, spec, a, line = 3, character = 4)
            assertEquals(LspUris.of(b), project, "Go to Definition on B")
            val library = definition(servers, spec, a, line = 4, character = 4)
            assertTrue(library.startsWith("jdt:"), "Go to Definition on String gave $library")
            val document = checkNotNull(spec.libraryDocument(library))
            val text = CompletableFuture<String?>()
            onFx {
                session.requestJson(document.method, document.params, { text.complete(it?.asString) }, {
                    text.completeExceptionally(it)
                })
            }
            val source = checkNotNull(text.get(60, TimeUnit.SECONDS))
            assertTrue("class String" in source, "the fetched text of String: ${source.take(200)}")
            assertTrue(document.file.startsWith(dir.resolve("classes")))
            assertEquals("String.java", document.file.fileName.toString())
            println("jdtls real run: whole test took ${elapsedSeconds(started)} s")
        }
    }

    @Test
    fun `a Gradle build compiles into build jdtls-bin and leaves no bin folder`() {
        val started = System.nanoTime()
        val window = Files.createDirectories(dir.resolve("gradle-work"))
        // The repository's own wrapper (Gradle 9.x), whose distribution this machine has cached.
        val repository =
            Path.of("").toAbsolutePath().let {
                generateSequence(it) { p -> p.parent }.first { p ->
                    Files.exists(p.resolve("gradlew"))
                }
            }
        for (name in listOf("gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            Files.createDirectories(window.resolve(name).parent)
            Files.copy(repository.resolve(name), window.resolve(name))
        }
        window.resolve("settings.gradle").file("rootProject.name = 'g'\n")
        window.resolve("build.gradle").file("plugins { id 'java' }\n")
        val a = window.resolve("src/main/java/p/A.java").file("package p;\n\npublic class A {}\n")

        withServer(window) { servers, spec ->
            onFx { servers.session(spec).documents.open(a, "java", "package p;\n\npublic class A {}\n") }

            awaitImported(servers, spec, window)

            // The Directory property of Gradle 9 is what the init script's type check is for: a wrong type fails the import.
            awaitCondition("build/jdtls-bin/main") { Files.isDirectory(window.resolve("build/jdtls-bin/main")) }
            assertFalse(Files.exists(window.resolve("bin")), "Buildship's default output folder")
            assertFalse(Files.exists(window.resolve(".project")), "no Eclipse metadata in the project")
            println("jdtls real run: Gradle import and output folder after ${elapsedSeconds(started)} s")
        }
    }

    @Test
    fun `a malformed pom ends the import failed with no project found`() {
        val window = Files.createDirectories(dir.resolve("broken-work"))
        window.resolve("pom.xml").file("<project><oops>")
        val a = window.resolve("src/main/java/p/A.java").file("package p;\n\npublic class A {}\n")

        withServer(window) { servers, spec ->
            onFx { servers.session(spec).documents.open(a, "java", "package p;\n\npublic class A {}\n") }

            val failed = assertIs<ImportState.Failed>(awaitImportState(spec, window) { it !is ImportState.Importing })

            assertEquals("No project was found; see the log.", failed.detail)
            assertEquals(3, failed.actions.size)
        }
    }

    /** A valid pom; jdtls names a project by its artifact, so two builds of one window need two names. */
    private fun validPom(artifact: String = "t") =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>t</groupId><artifactId>$artifact</artifactId><version>1</version>
          <properties>
            <maven.compiler.release>17</maven.compiler.release>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
          </properties>
        </project>
        """.trimIndent()

    private val emptyClass = "package p;\n\npublic class A {}\n"

    /** The FX thread of these tests: what the session dispatches runs on it, one task after another. */
    private val fx = Executors.newSingleThreadExecutor { Thread(it, "test-fx").apply { isDaemon = true } }

    @AfterEach
    fun stopFx() {
        fx.shutdownNow()
    }

    /** Runs [action] on [fx] and waits for it. */
    private fun <T> onFx(action: () -> T): T = fx.submit(action).get(60, TimeUnit.SECONDS)

    @Test
    fun `Retry Import of a broken pom on an idle jdtls fails again, and after the fix ends the import done`() {
        val window = Files.createDirectories(dir.resolve("retry-work"))
        window.resolve("pom.xml").file("<project><oops>")
        val a = window.resolve("src/main/java/p/A.java").file(emptyClass)

        withServer(window) { servers, spec ->
            onFx { servers.session(spec).documents.open(a, "java", emptyClass) }
            val failed = assertIs<ImportState.Failed>(awaitImportState(spec, window) { it !is ImportState.Importing })

            // Scenario (a) of P7b: the banner's button calls access.refreshFolder(root), one notification for jdtls.
            onFx { assertIs<ProblemAction.Callback>(failed.actions[0]).perform() }
            val again =
                assertIs<ImportState.Failed>(
                    awaitImportState(spec, window) { it is ImportState.Failed && it !== failed },
                )
            window.resolve("pom.xml").file(validPom())
            onFx { assertIs<ProblemAction.Callback>(again.actions[0]).perform() }

            assertIs<ImportState.Done>(awaitImportState(spec, window) { it is ImportState.Done })
        }
    }

    @Test
    fun `Retry of a failed root while two other roots import leaves them done and the root failed again`() {
        val window = Files.createDirectories(dir.resolve("three-work"))
        val bad = window.resolve("bad")
        bad.resolve("pom.xml").file("<project><oops>")
        val badClass = bad.resolve("src/main/java/p/A.java").file(emptyClass)
        val one = window.resolve("one")
        one.resolve("pom.xml").file(validPom("one"))
        val oneClass = one.resolve("src/main/java/p/A.java").file(emptyClass)
        val two = window.resolve("two")
        two.resolve("pom.xml").file(validPom("two"))
        val twoClass = two.resolve("src/main/java/p/A.java").file(emptyClass)

        withServer(window) { servers, spec ->
            val documents = servers.session(spec).documents
            onFx {
                documents.open(badClass, "java", emptyClass)
                documents.open(oneClass, "java", emptyClass)
                documents.open(twoClass, "java", emptyClass)
            }
            val failed =
                assertIs<ImportState.Failed>(awaitImportState(spec, window, bad) { it !is ImportState.Importing })

            // The imports of `one` and `two` may still run: the job of Retry's one notification queues behind them.
            onFx { assertIs<ProblemAction.Callback>(failed.actions[0]).perform() }

            val again = awaitImportState(spec, window, bad) { it is ImportState.Failed && it !== failed }
            assertEquals("No project was found; see the log.", assertIs<ImportState.Failed>(again).detail)
            assertIs<ImportState.Done>(awaitImportState(spec, window, one) { it is ImportState.Done })
            assertIs<ImportState.Done>(awaitImportState(spec, window, two) { it is ImportState.Done })
        }
    }

    @Test
    fun `Gradle 8 on JDK 25 ends the import failed with the JDK message`() {
        val cached = Path.of(System.getProperty("user.home"), ".gradle/wrapper/dists/gradle-8.14.3-bin")
        assumeTrue(Files.isDirectory(cached), "no Gradle 8.14.3 distribution in ~/.gradle/wrapper/dists")
        assumeTrue(System.getProperty("java.version").startsWith("25"), "needs JDK 25 as Thoreau's own")
        val window = Files.createDirectories(dir.resolve("gradle8-work"))
        val repository =
            Path.of("").toAbsolutePath().let {
                generateSequence(it) { p -> p.parent }.first { p -> Files.exists(p.resolve("gradlew")) }
            }
        Files.createDirectories(window.resolve("gradle/wrapper"))
        Files.copy(
            repository.resolve("gradle/wrapper/gradle-wrapper.jar"),
            window.resolve("gradle/wrapper/gradle-wrapper.jar"),
        )
        window.resolve("gradle/wrapper/gradle-wrapper.properties").file(
            "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n" +
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip\n" +
                "zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n",
        )
        window.resolve("settings.gradle").file("rootProject.name = 'g8'\n")
        window.resolve("build.gradle").file("plugins { id 'java' }\n")
        val a = window.resolve("src/main/java/p/A.java").file("package p;\n\npublic class A {}\n")

        withServer(window) { servers, spec ->
            onFx { servers.session(spec).documents.open(a, "java", "package p;\n\npublic class A {}\n") }

            val failed = assertIs<ImportState.Failed>(awaitImportState(spec, window) { it !is ImportState.Importing })

            assertTrue(failed.detail.startsWith("Gradle 8.14.3 can't run on JDK 25"), failed.detail)
            assertTrue("newest: 24" in failed.detail, failed.detail)
        }
    }

    /** Polls the tracked import state of [window]'s root until [done] holds for it (at most 5 minutes). */
    private fun awaitImportState(
        spec: JdtlsSpec,
        window: Path,
        root: Path = window,
        done: (ImportState) -> Boolean,
    ): ImportState {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (System.nanoTime() < deadline) {
            spec
                .stateOf(window)
                .imports[root]
                ?.takeIf(done)
                ?.let { return it }
            Thread.sleep(200) // The state changes on the server's threads; there is no signal to wait on.
        }
        error("the import of $root stayed ${spec.stateOf(window).imports[root]} for 5 minutes")
    }

    /** Runs [body] with a started real [LanguageServers] for [window], and waits for the processes to end after it. */
    private fun withServer(
        window: Path,
        body: (LanguageServers, JdtlsSpec) -> Unit,
    ) {
        val unpacked = System.getenv("SLUIDE_TEST_JDTLS")
        assumeTrue(unpacked != null, "SLUIDE_TEST_JDTLS names no unpacked jdtls")
        val fakes = JdtlsFakes(dir)
        fakes.settings.apply(Path.of(checkNotNull(unpacked)).resolve("bin/jdtls").toString())
        val spec =
            JdtlsSpec(
                fakes.installer(),
                fakes.settings,
                JavaProjectSettings(
                    ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()),
                ) {
                    it.run()
                },
                JavaWorkspaceUse { it.run() },
                JavaHomes({ _, _ -> null }, emptyMap(), Path.of(System.getProperty("java.home"))),
                JavaWorkspaces(dir.resolve("workspaces")),
                ProjectSettingsOpeners(),
                dir.resolve("classes"),
                fx::execute,
                1000,
            )
        val servers =
            LanguageServers(
                listOf(spec),
                { window },
                { _, _ -> true },
                FileWatcher(fx::execute, 200.milliseconds),
                fx::execute,
            )
        try {
            body(servers, spec)
        } finally {
            onFx { servers.close() }
            // The server writes into the temporary folder until it is gone; the folder is deleted after the test.
            awaitCondition("the server to stop") { servers.state(spec).value == ServerState.Stopped }
            awaitCondition("the process to leave the data folder") { !serverRuns(dir) }
        }
    }

    /** Whether a process still runs with [folder] in its command line (jdtls and the Gradle daemon it started). */
    private fun serverRuns(folder: Path): Boolean =
        ProcessHandle.allProcesses().anyMatch { process ->
            process
                .info()
                .commandLine()
                .map { folder.toString() in it }
                .orElse(false)
        }

    private fun elapsedSeconds(since: Long) = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since)

    /** Polls `java.project.getAll` until it lists a project below [window] (the import's end, ADR 0179). */
    private fun awaitImported(
        servers: LanguageServers,
        spec: JdtlsSpec,
        window: Path,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (System.nanoTime() < deadline) {
            val result = CompletableFuture<Any?>()
            onFx {
                servers.session(spec).request(
                    { it.workspaceService.executeCommand(ExecuteCommandParams("java.project.getAll", emptyList())) },
                    { result.complete(it) },
                    { result.complete(null) },
                )
            }
            val projects = (result.get(30, TimeUnit.SECONDS) as? List<*>).orEmpty()
            // jdtls writes `file:/path/`, not `file:///path`.
            if (projects.any { Path.of(java.net.URI(it.toString())).startsWith(window) }) return
            Thread.sleep(500) // Polling for the import; the server has no signal per root.
        }
        error("no project below $window after 5 minutes")
    }

    /** The first definition URI at the position; asked again for a minute, as the server may still be building. */
    private fun definition(
        servers: LanguageServers,
        spec: JdtlsSpec,
        file: Path,
        line: Int,
        character: Int,
    ): String {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
        while (true) {
            val result = CompletableFuture<String?>()
            val params = DefinitionParams(TextDocumentIdentifier(LspUris.of(file)), Position(line, character))
            onFx {
                servers.session(spec).request(
                    { it.textDocumentService.definition(params) },
                    { either ->
                        val left = either.left?.firstOrNull()?.uri
                        result.complete(if (either.isLeft) left else either.right.firstOrNull()?.targetUri)
                    },
                    { result.completeExceptionally(it) },
                )
            }
            result.get(60, TimeUnit.SECONDS)?.let { return it }
            check(System.nanoTime() < deadline) { "no definition at $line:$character of $file" }
            Thread.sleep(500) // The server has no signal for "built"; asked again.
        }
    }
}
