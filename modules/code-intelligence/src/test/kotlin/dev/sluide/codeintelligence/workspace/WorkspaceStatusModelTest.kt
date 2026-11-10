package dev.sluide.codeintelligence.workspace

import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.WorkspaceStatus
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.property.SimpleObjectProperty
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the project status line says and when (ADR 0151); the view is `CodeIntelligenceUiTest`'s. */
class WorkspaceStatusModelTest {
    private val window = Path.of("/work")
    private val backend = window.resolve("backend")
    private val state = SimpleObjectProperty<ServerState>(ServerState.Running)
    private val folder = SimpleObjectProperty<Path?>(backend)
    private val changes = Signal()
    private var version = 1
    private var projectFolder: Path? = window
    private var served = true

    private fun model() =
        WorkspaceStatusModel(state, folder, changes, projectFolder, { served }) { f, _ ->
            WorkspaceStatus(
                f?.let { "project ${it.fileName} v$version" } ?: "no project",
                f,
                f?.let { "tip $it" },
            )
        }

    @Test
    fun `the line shows the spec's text, folder and tooltip while the server runs`() {
        val status = model().status.value
        assertEquals(WorkspaceStatus("project backend v1", backend, "tip /work/backend"), status)
    }

    @Test
    fun `a document that is not served shows no line, and the line is back once it is`() {
        served = false
        val model = model()
        assertNull(model.status.value)

        served = true
        changes.fire()
        assertEquals("project backend v1", model.status.value?.text)
        served = false
        changes.fire()
        assertNull(model.status.value)
    }

    @Test
    fun `a file without a workspace folder gets the spec's line for none`() {
        folder.value = null
        assertEquals(WorkspaceStatus("no project", null, null), model().status.value)
    }

    @Test
    fun `the line follows the file's folder and the workspace changes`() {
        val model = model()

        folder.value = window.resolve("other")
        assertEquals("project other v1", model.status.value?.text)

        version = 2
        changes.fire()
        assertEquals("project other v2", model.status.value?.text)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["Starting", "Stopped", "Failed", "Unavailable", "Untrusted"])
    fun `the line is hidden while the server does not run, and back when it runs again`(name: String) {
        val model = model()

        state.value =
            when (name) {
                "Starting" -> ServerState.Starting
                "Stopped" -> ServerState.Stopped
                "Failed" -> ServerState.Failed("exit 1")
                "Unavailable" -> ServerState.Unavailable
                else -> ServerState.Untrusted
            }
        assertNull(model.status.value)

        state.value = ServerState.Running
        assertEquals("project backend v1", model.status.value?.text)
    }

    @Test
    fun `the line is hidden without a project folder`() {
        projectFolder = null
        assertNull(model().status.value)
    }

    @Test
    fun `a closed model no longer follows`() {
        val model = model()
        model.close()
        state.value = ServerState.Stopped
        assertEquals("project backend v1", model.status.value?.text)
    }
}

/** Fires its listeners on every call, as the session's workspace changes do (no "unchanged" skip). */
private class Signal : Observable {
    private val listeners = mutableListOf<InvalidationListener>()

    override fun addListener(listener: InvalidationListener) {
        listeners += listener
    }

    override fun removeListener(listener: InvalidationListener) {
        listeners -= listener
    }

    fun fire() = listeners.toList().forEach { it.invalidated(this) }
}
