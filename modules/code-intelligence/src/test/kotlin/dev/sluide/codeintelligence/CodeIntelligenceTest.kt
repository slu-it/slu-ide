package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.completion.CompletionFeature
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerState
import javafx.collections.FXCollections
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The window's code intelligence and the installers: a changed command restarts a server that was started, an
 * installer that becomes ready starts the server of bound editors, and a new binding retries a server that was missing.
 * Completion sources reach the editors no server serves, following renames and the source's handle.
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

    /** The editors and carets the [source] was asked at; it never answers. */
    private val sourceCalls: MutableList<Pair<ExtensibleCodeEditor, Int>> =
        Collections.synchronizedList(
            mutableListOf(),
        )
    private val source =
        CompletionSource { editor, caret ->
            sourceCalls += editor to caret
            CompletableFuture()
        }

    /** Code intelligence with completion from the server and from sources, its delays never running. */
    private fun completing(spec: PySpec): CodeIntelligence {
        fx = FxServers(folder, spec) { FeatureServer() }
        val timers = TestTimers()
        return fx.onFx {
            CodeIntelligence(
                fx.servers,
                FXCollections.observableSet(),
                CompletionFeature(schedule = timers::schedule, dispatch = fx::dispatch),
            ) { listOf(CompletionFeature(schedule = timers::schedule)) }
        }
    }

    /** An editor of [name] with [text], the caret at its end. */
    private fun editor(
        name: String,
        text: String,
    ) = FakeCodeEditor(folder.resolve(name), text).apply { moveCaret(text.length) }

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

    @Test
    fun `a source completes the editors no server serves, a served editor asks only its server`() {
        val spec = PySpec()
        val intelligence = completing(spec)
        val server = fx.fake.awaitRunning(spec) as FeatureServer
        fx.onFx { intelligence.addCompletionSource({ true }, source) }
        val py = editor("a.py", "os")
        val jsh = editor("a.jsh", "s")
        fx.onFx {
            intelligence.extend(py)
            intelligence.extend(jsh)
        }

        fx.onFx { py.typeKey(".") }
        awaitTrue("the server asked") { server.completions.size == 1 }
        fx.onFx { jsh.typeKey(".") }

        assertEquals(listOf<Pair<ExtensibleCodeEditor, Int>>(jsh to 2), sourceCalls.toList())
        assertEquals(1, server.completions.size, "the editor of the source did not ask the server")
        assertNull(fx.onFx { intelligence.bindingFor(jsh.file.value) }, "no binding, so no other feature")
    }

    @Test
    fun `an editor renamed away from the source loses its completion, gains it back, and loses it with the window`() {
        val intelligence = completing(PySpec())
        fx.onFx { intelligence.addCompletionSource({ it.toString().endsWith(".jsh") }, source) }
        val jsh = editor("a.jsh", "s")
        fx.onFx { intelligence.extend(jsh) }
        fx.onFx { jsh.typeKey(".") }
        assertEquals(1, sourceCalls.size)

        fx.onFx { jsh.fileProperty.set(folder.resolve("a.txt")) }
        assertEquals(0, fx.onFx { jsh.subscribed }, "the completion let go of the editor")
        fx.onFx { jsh.typeKey(".") }
        assertEquals(1, sourceCalls.size)

        fx.onFx { jsh.fileProperty.set(folder.resolve("b.jsh")) }
        fx.onFx { jsh.typeKey(".") }
        assertEquals(2, sourceCalls.size)

        fx.onFx { intelligence.close() }
        assertEquals(0, fx.onFx { jsh.subscribed })
    }

    @Test
    fun `a source added after the editors opened reaches the one no server serves, closing its handle takes it away`() {
        val spec = PySpec()
        val intelligence = completing(spec)
        val server = fx.fake.awaitRunning(spec) as FeatureServer
        val py = editor("a.py", "os")
        val jsh = editor("a.jsh", "s")
        fx.onFx {
            intelligence.extend(py)
            intelligence.extend(jsh)
        }
        val handle = fx.onFx { intelligence.addCompletionSource({ true }, source) }
        fx.onFx { py.typeKey(".") }
        awaitTrue("the server asked") { server.completions.size == 1 }
        fx.onFx { jsh.typeKey(".") }
        assertEquals(listOf<Pair<ExtensibleCodeEditor, Int>>(jsh to 2), sourceCalls.toList())

        fx.onFx { handle.close() }
        assertEquals(0, fx.onFx { jsh.subscribed })
        fx.onFx { jsh.typeKey(".") }
        assertEquals(1, sourceCalls.size)
    }
}
