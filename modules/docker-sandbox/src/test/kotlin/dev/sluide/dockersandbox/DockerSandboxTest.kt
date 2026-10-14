package dev.sluide.dockersandbox

import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.Terminals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
        Terminals(FontSettings(SettingsStore(root.resolve("settings.properties")), FontSettings.systemDefaults()))
    }
    private val sandbox by lazy { DockerSandbox(terminals, home, System.getenv()) }

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
}
