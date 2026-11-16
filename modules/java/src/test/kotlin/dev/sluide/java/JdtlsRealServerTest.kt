package dev.sluide.java

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.languageservers.LanguageServers
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ServerState
import dev.sluide.projectdata.ProjectDataStore
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            session.documents.open(a, "java", aSource)

            awaitImported(servers, spec, window)
            println("jdtls real run: import finished after ${elapsedSeconds(started)} s")

            val project = definition(servers, spec, a, line = 3, character = 4)
            assertEquals(LspUris.of(b), project, "Go to Definition on B")
            val library = definition(servers, spec, a, line = 4, character = 4)
            assertTrue(library.startsWith("jdt:"), "Go to Definition on String gave $library")
            val document = checkNotNull(spec.libraryDocument(library))
            val text = CompletableFuture<String?>()
            session.requestJson(document.method, document.params, { text.complete(it?.asString) }, {
                text.completeExceptionally(it)
            })
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
            servers.session(spec).documents.open(a, "java", "package p;\n\npublic class A {}\n")

            awaitImported(servers, spec, window)

            // The Directory property of Gradle 9 is what the init script's type check is for: a wrong type fails the import.
            awaitCondition("build/jdtls-bin/main") { Files.isDirectory(window.resolve("build/jdtls-bin/main")) }
            assertFalse(Files.exists(window.resolve("bin")), "Buildship's default output folder")
            assertFalse(Files.exists(window.resolve(".project")), "no Eclipse metadata in the project")
            println("jdtls real run: Gradle import and output folder after ${elapsedSeconds(started)} s")
        }
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
                dir.resolve("classes"),
                Runnable::run,
                1000,
            )
        val servers =
            LanguageServers(
                listOf(spec),
                { window },
                { _, _ -> true },
                FileWatcher(Runnable::run, 200.milliseconds),
                Runnable::run,
            )
        try {
            body(servers, spec)
        } finally {
            servers.close()
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
            servers.session(spec).request(
                { it.workspaceService.executeCommand(ExecuteCommandParams("java.project.getAll", emptyList())) },
                { result.complete(it) },
                { result.complete(null) },
            )
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
            servers.session(spec).request(
                { it.textDocumentService.definition(params) },
                { either ->
                    result.complete(
                        if (either.isLeft) either.left.firstOrNull()?.uri else either.right.firstOrNull()?.targetUri,
                    )
                },
                { result.completeExceptionally(it) },
            )
            result.get(60, TimeUnit.SECONDS)?.let { return it }
            check(System.nanoTime() < deadline) { "no definition at $line:$character of $file" }
            Thread.sleep(500) // The server has no signal for "built"; asked again.
        }
    }
}
