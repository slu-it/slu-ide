package dev.sluide.typescript

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real tsc (`SLUIDE_TEST_TSC`, skipped without it) with npm on the PATH: the configuration the spec answers keeps
 * the server from running `npm install` for type acquisition in the background, and without it the server does, which
 * proves the check can fail (ADR 0161). The server runs with a temporary `HOME`, so nothing is written to the real one.
 */
class TypeScriptTypeAcquisitionTest {
    @TempDir
    lateinit var tempDir: Path

    private val tsc: Path by lazy {
        val path = System.getenv("SLUIDE_TEST_TSC")?.let(Path::of)
        assumeTrue(path != null && Files.isExecutable(path), "SLUIDE_TEST_TSC is not set")
        checkNotNull(path)
    }

    private fun npmOnPath(): Boolean =
        System
            .getenv("PATH")
            .orEmpty()
            .split(java.io.File.pathSeparator)
            .filter { it.isNotEmpty() }
            .any { Files.isExecutable(Path.of(it, "npm")) }

    private val spec by lazy { TypeScriptFakes(tempDir).let { TypeScriptSpec(it.installer(), it.settings) } }

    @Test
    fun `the answered configuration keeps the server from starting npm`() {
        assumeTrue(npmOnPath(), "npm is not on the PATH")

        val observed = observe(answer = { spec.configuration(it) }, stopOnChild = false)

        assertEquals(emptyList(), observed.children, "the server started a child process")
        // Without this a pass could mean that the server had not yet asked for anything.
        assertTrue(
            observed.sections.any { it == "js/ts" || it == "typescript" },
            "the server asked for ${observed.sections}, not for the js/ts or typescript section",
        )
    }

    @Test
    fun `without the configuration the server runs npm, so the check above can fail`() {
        assumeTrue(npmOnPath(), "npm is not on the PATH")

        val observed = observe(answer = { null }, stopOnChild = true)

        assertTrue(observed.children.isNotEmpty(), "the server did not start npm in $WINDOW_SECONDS s")
    }

    private class Observed(
        val children: List<String>,
        val sections: List<String?>,
    )

    /**
     * The command lines of the child processes the server started during [WINDOW_SECONDS] (polled every 100 ms), and
     * the configuration sections it asked for.
     */
    private fun observe(
        answer: (org.eclipse.lsp4j.ConfigurationItem) -> Any?,
        stopOnChild: Boolean,
    ): Observed {
        val sections = CopyOnWriteArrayList<String?>()
        val home = tempDir.resolve("home").createDirectories()
        val project = tempDir.resolve("project").toAbsolutePath().createDirectories()
        project.resolve("package.json").writeText("""{"dependencies":{"lodash":"^4.17.21"}}""")
        val source = project.resolve("app.js")
        val text = "const _ = require(\"lodash\");\n"
        source.writeText(text)

        val process =
            ProcessBuilder(tsc.toString(), "--lsp", "--stdio")
                .directory(project.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply {
                    // Nothing may reach the real home or caches: a temporary HOME, and no cache or npm settings.
                    environment()["HOME"] = home.toString()
                    environment().keys.removeAll { it == "XDG_CACHE_HOME" || it.lowercase().startsWith("npm_config_") }
                }.start()
        try {
            val client =
                object : LanguageClient {
                    override fun telemetryEvent(`object`: Any?) = Unit

                    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams?) = Unit

                    override fun showMessage(messageParams: MessageParams?) = Unit

                    override fun showMessageRequest(requestParams: ShowMessageRequestParams?) =
                        CompletableFuture.completedFuture<MessageActionItem?>(null)

                    override fun logMessage(message: MessageParams?) = Unit

                    override fun workspaceFolders() = CompletableFuture.completedFuture(emptyList<WorkspaceFolder>())

                    override fun registerCapability(params: RegistrationParams?) =
                        CompletableFuture.completedFuture<Void?>(null)

                    override fun configuration(configurationParams: ConfigurationParams) =
                        CompletableFuture.completedFuture(
                            configurationParams.items.map {
                                sections += it.section
                                answer(it)
                            },
                        )
                }
            val launcher = LSPLauncher.createClientLauncher(client, process.inputStream, process.outputStream)
            launcher.startListening()
            val server = launcher.remoteProxy

            val params =
                InitializeParams().apply {
                    processId = ProcessHandle.current().pid().toInt()
                    rootUri = project.toUri().toString()
                    workspaceFolders = listOf(WorkspaceFolder(project.toUri().toString(), "project"))
                    capabilities =
                        ClientCapabilities().apply {
                            workspace = WorkspaceClientCapabilities().apply { configuration = true }
                        }
                }
            server.initialize(params).get(30, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(source.toUri().toString(), "javascript", 1, text)),
            )

            val seen = LinkedHashSet<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WINDOW_SECONDS)
            // A poll loop with a deadline: the check is that no child ever shows up, so it runs until the deadline.
            while (System.nanoTime() < deadline && !(stopOnChild && seen.isNotEmpty())) {
                process
                    .toHandle()
                    .children()
                    .forEach { seen += it.info().commandLine().orElse(it.info().command().orElse("?")) }
                Thread.sleep(100)
            }
            return Observed(seen.toList(), sections.toList())
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private companion object {
        /** The observation window of both tests: the control must see npm within the time the check watches for it. */
        const val WINDOW_SECONDS = 8L
    }
}
