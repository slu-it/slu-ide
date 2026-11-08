package dev.sluide.codeintelligence.server

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.languageservers.ServerState
import javafx.collections.FXCollections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** "Restart Python Language Server" in a bound editor's context menu, and when it cannot act. */
class RestartServerItemTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private val fx by lazy { FxServers(folder, spec) }

    @AfterEach
    fun close() = fx.close()

    @ParameterizedTest(name = "{0}")
    @MethodSource("blockers")
    fun `the item says why it cannot restart`(
        state: ServerState,
        reason: String?,
    ) {
        assertEquals(reason, restartBlocker(state))
    }

    @Test
    fun `the item restarts the running server of the editor's language`() {
        fx.fake.awaitRunning(spec)
        val intelligence =
            fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { listOf(RestartServerItem) } }
        val editor = FakeCodeEditor(folder.resolve("a.py"), "")
        fx.onFx { intelligence.extend(editor) }
        val item = fx.onFx { editor.menuItems().single() }
        assertEquals("Restart Python Language Server", item.text)
        assertFalse(item.isDisable)

        fx.onFx { item.fire() }
        awaitTrue("restarted") { fx.fake.started.size == 2 }
    }

    companion object {
        @JvmStatic
        fun blockers(): List<Arguments> =
            listOf(
                Arguments.of(ServerState.Stopped, "not started"),
                Arguments.of(ServerState.Unavailable, "not installed"),
                Arguments.of(ServerState.Untrusted, "project not trusted"),
                Arguments.of(ServerState.Starting, null),
                Arguments.of(ServerState.Running, null),
                Arguments.of(ServerState.Restarting(1), null),
                Arguments.of(ServerState.Failed("exit 1"), null),
            )
    }
}
