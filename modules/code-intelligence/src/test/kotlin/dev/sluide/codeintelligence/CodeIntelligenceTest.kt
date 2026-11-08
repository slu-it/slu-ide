package dev.sluide.codeintelligence

import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerState
import javafx.collections.FXCollections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The window's code intelligence and the installers: a changed command restarts a server that was started, an
 * installer that becomes ready starts the server of bound editors, and a new binding retries a server that was missing.
 */
class CodeIntelligenceTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var fx: FxServers

    @AfterEach
    fun close() = fx.close()

    private fun intelligence(spec: PySpec): CodeIntelligence {
        fx = FxServers(folder, spec)
        return fx.onFx { CodeIntelligence(fx.servers, FXCollections.observableSet()) { emptyList() } }
    }

    private fun state(spec: PySpec) = fx.onFx { fx.servers.state(spec).value }

    @Test
    fun `a changed command restarts the server only once it was started`() {
        val installer = FakeInstaller(ServerAvailability.Ready)
        val spec = PySpec(installer)
        intelligence(spec)

        fx.onFx { installer.commandChanged() }
        // Nothing to wait for: a start would reach the fake starter within milliseconds.
        Thread.sleep(200)
        assertEquals(ServerState.Stopped, state(spec), "a server never started stays stopped")
        assertEquals(0, fx.fake.started.size)

        fx.fake.awaitRunning(spec)
        fx.onFx { installer.commandChanged() }
        awaitTrue("restarted") { fx.fake.started.size == 2 }
    }

    @Test
    fun `an installer that becomes ready starts the server of a bound editor, not of a closed one`() {
        val installer = FakeInstaller(ServerAvailability.Missing(1))
        val spec = PySpec(installer)
        val intelligence = intelligence(spec)
        val states = mutableListOf<ServerState>()
        fx.onFx { fx.servers.state(spec).subscribe { _, now -> states += now } }
        val handle = fx.onFx { intelligence.extend(FakeCodeEditor(folder.resolve("a.py"), "")) }
        awaitTrue("unavailable") { fx.onFx { states.count { it == ServerState.Unavailable } } == 1 }

        fx.onFx { handle?.close() }
        fx.onFx { installer.availability.set(ServerAvailability.Ready) }
        // Nothing to wait for: a start would reach the fake starter within milliseconds.
        Thread.sleep(200)
        assertEquals(ServerState.Unavailable, state(spec), "no editor is bound")
        assertEquals(0, fx.fake.started.size)

        fx.onFx { installer.availability.set(ServerAvailability.Installing(0.5)) }
        fx.onFx { intelligence.extend(FakeCodeEditor(folder.resolve("b.py"), "")) }
        // The binding's own start found no command; only the installer turning ready starts the server now.
        awaitTrue("unavailable again") { fx.onFx { states.count { it == ServerState.Unavailable } } == 2 }
        fx.onFx { installer.availability.set(ServerAvailability.Ready) }
        awaitTrue("running") { state(spec) == ServerState.Running }
    }

    @Test
    fun `a new binding starts a server that was missing when the last editor bound`() {
        val installer = FakeInstaller(ServerAvailability.Missing(1))
        val spec = PySpec(installer)
        val intelligence = intelligence(spec)
        val handle = fx.onFx { intelligence.extend(FakeCodeEditor(folder.resolve("a.py"), "")) }
        awaitTrue("unavailable") { state(spec) == ServerState.Unavailable }
        fx.onFx { handle?.close() }
        // Installed while no editor was bound (Settings): nothing starts until an editor binds.
        fx.onFx { installer.availability.set(ServerAvailability.Ready) }

        fx.onFx { intelligence.extend(FakeCodeEditor(folder.resolve("b.py"), "")) }
        awaitTrue("running") { state(spec) == ServerState.Running }
    }

    @Test
    fun `closing unbinds every editor and extends none afterwards`() {
        val spec = PySpec()
        val intelligence = intelligence(spec)
        val editor = FakeCodeEditor(folder.resolve("a.py"), "")
        fx.onFx { intelligence.extend(editor) }
        fx.onFx { intelligence.close() }
        assertEquals(emptySet(), fx.onFx { intelligence.attached })
        assertEquals(0, editor.subscribed)
        val late = FakeCodeEditor(folder.resolve("b.py"), "")
        assertNull(fx.onFx { intelligence.extend(late) })
        assertEquals(0, late.subscribed)
    }
}
