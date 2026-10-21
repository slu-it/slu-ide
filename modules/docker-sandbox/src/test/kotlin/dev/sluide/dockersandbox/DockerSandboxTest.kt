package dev.sluide.dockersandbox

import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The checks only; starting a terminal needs JavaFX, see `DockerSandboxUiTest`. */
class DockerSandboxTest {
    @TempDir
    lateinit var root: Path

    private val home by lazy { root.resolve("home").createDirectories() }
    private val project by lazy { home.resolve("project").createDirectories() }
    private val terminals by lazy {
        val store = SettingsStore(root.resolve("settings.properties"))
        Terminals(FontSettings(store, FontSettings.systemDefaults()), TerminalScrollSettings(store))
    }
    private val sandbox by lazy { sandbox(CompletableFuture.completedFuture(true)) }

    private fun sandbox(sbxInstalled: CompletableFuture<Boolean>) =
        DockerSandbox(terminals, home, System.getenv(), sbxInstalled)

    @AfterEach
    fun close() = terminals.close()

    @Test
    fun `nothing runs without an open folder`() {
        for (action in SandboxAction.entries) {
            assertEquals("No folder is open.", sandbox.problem(action, null))
            assertEquals(
                "No folder is open.",
                assertFailsWith<SandboxNotAllowedException> { sandbox.open(action, null) }.message,
            )
        }
        assertEquals(0, terminals.openCount)
    }

    @Test
    fun `Claude Code runs in folders below the home folder only`() {
        assertNull(sandbox.problem(SandboxAction.CLAUDE_CODE, project))
        assertNotNull(sandbox.problem(SandboxAction.CLAUDE_CODE, home))
        assertNotNull(sandbox.problem(SandboxAction.CLAUDE_CODE, root))
    }

    @Test
    fun `Environment also needs an sbxenv yaml in the folder`() {
        assertEquals("There is no sbxenv.yaml in $project.", sandbox.problem(SandboxAction.ENVIRONMENT, project))

        project.resolve("sbxenv.yaml").createFile()

        assertNull(sandbox.problem(SandboxAction.ENVIRONMENT, project))
        home.resolve("sbxenv.yaml").createFile()
        assertNotNull(sandbox.problem(SandboxAction.ENVIRONMENT, home), "not in the home folder, even with the file")
    }

    @Test
    fun `opening checks the folder again and starts no terminal where it may not run`() {
        home.resolve("sbxenv.yaml").createFile()
        for (action in SandboxAction.entries) {
            for (folder in listOf(home, home.parent, root.resolve("outside").createDirectories())) {
                assertFailsWith<SandboxNotAllowedException> { sandbox.open(action, folder) }
            }
        }
        assertFailsWith<SandboxNotAllowedException> { sandbox.open(SandboxAction.ENVIRONMENT, project) }
        assertEquals(0, terminals.openCount)
    }

    @Test
    fun `nothing runs without sbx, or before the check has found it`() {
        project.resolve("sbxenv.yaml").createFile()
        val check = CompletableFuture<Boolean>()
        for (sandbox in listOf(sandbox(CompletableFuture.completedFuture(false)), sandbox(check))) {
            for (action in SandboxAction.entries) {
                for (folder in listOf(project, null)) {
                    assertEquals(SBX_MISSING, sandbox.problem(action, folder))
                    assertEquals(
                        SBX_MISSING,
                        assertFailsWith<SandboxNotAllowedException> { sandbox.open(action, folder) }.message,
                    )
                }
            }
        }
        assertEquals(0, terminals.openCount)

        check.complete(true)

        assertNull(sandbox(check).problem(SandboxAction.ENVIRONMENT, project))
    }

    @Test
    fun `a failed check counts as not installed and throws nothing`() {
        val failed = CompletableFuture<Boolean>().apply { completeExceptionally(IllegalStateException("boom")) }
        val sandbox = sandbox(failed)

        assertEquals(SBX_MISSING, sandbox.problem(SandboxAction.CLAUDE_CODE, project))
        assertEquals(false, sandbox.sbxInstalled.toCompletableFuture().get())
    }
}

private const val SBX_MISSING = "sbx is not installed (not found on the PATH of your login shell)."
