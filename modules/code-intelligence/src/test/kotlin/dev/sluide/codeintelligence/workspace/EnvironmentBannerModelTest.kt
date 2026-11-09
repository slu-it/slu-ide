package dev.sluide.codeintelligence.workspace

import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.ProblemAction
import dev.sluide.languageservers.ServerState
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** When the environment banner shows and what its buttons do (ADR 0151); the view is `CodeIntelligenceUiTest`'s. */
class EnvironmentBannerModelTest {
    @TempDir
    lateinit var data: Path

    private val window = Path.of("/work")
    private val backend = window.resolve("backend")
    private val other = window.resolve("other")
    private val state = SimpleObjectProperty<ServerState>(ServerState.Running)
    private val folder = SimpleObjectProperty<Path?>(backend)
    private val changes = SimpleIntegerProperty()
    private val notNow = FXCollections.observableSet<Path>()
    private val runs = mutableListOf<Triple<Path, String, String>>()
    private var problem: EnvironmentProblem? = null

    private fun prompts() =
        InstallPrompts(ProjectDataStore(data.resolve("p"), data.resolve("shared.json"), emptyList()), Runnable::run)

    private fun problemOf(
        at: Path,
        withAction: Boolean = true,
    ) = EnvironmentProblem(
        at,
        "No virtual environment for ${at.fileName}.",
        if (withAction) ProblemAction("Run uv sync", "uv sync (${at.fileName})", "uv sync") else null,
    )

    private fun model(prompts: InstallPrompts = prompts()) =
        EnvironmentBannerModel(
            state,
            folder,
            changes,
            window,
            "python.environment",
            notNow,
            prompts,
            { problem },
        ) { f, n, c -> runs += Triple(f, n, c) }

    @Test
    fun `the notice shows the problem while the server runs`() {
        problem = problemOf(backend)
        assertEquals(problem, model().problem.value)
    }

    @Test
    fun `no problem, no notice`() {
        assertNull(model().problem.value)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["Starting", "Stopped", "Failed", "Unavailable", "Untrusted"])
    fun `the notice is hidden while the server does not run, and back when it runs`(name: String) {
        problem = problemOf(backend)
        val model = model()
        state.value =
            when (name) {
                "Starting" -> ServerState.Starting
                "Stopped" -> ServerState.Stopped
                "Failed" -> ServerState.Failed("x")
                "Unavailable" -> ServerState.Unavailable
                else -> ServerState.Untrusted
            }
        assertNull(model.problem.value)

        state.value = ServerState.Running
        assertNotNull(model.problem.value)
    }

    @Test
    fun `the notice vanishes when the problem is gone after the workspace changed`() {
        problem = problemOf(backend)
        val model = model()

        problem = null
        changes.value++

        assertNull(model.problem.value)
    }

    @Test
    fun `Not Now hides the notice of that folder only`() {
        problem = problemOf(backend)
        val model = model()

        model.notNow()
        assertNull(model.problem.value)

        // Another file of another problem folder still shows its notice.
        problem = problemOf(other)
        folder.value = other
        assertEquals(problemOf(other), model.problem.value)
    }

    @Test
    fun `Don't Ask Again hides the notice and is kept under the spec's environment id`() {
        problem = problemOf(backend)
        val prompts = prompts()
        val model = model(prompts)

        model.dontAskAgain()

        assertNull(model.problem.value)
        assertEquals(setOf("python.environment"), prompts.declined.toSet())
        assertEquals(setOf("python.environment"), prompts().declined.toSet())
        assertNull(model(prompts).problem.value)
    }

    @Test
    fun `Run opens the action's constant command in the problem's folder`() {
        problem = problemOf(backend)
        model().run()
        assertEquals(listOf(Triple(backend, "uv sync (backend)", "uv sync")), runs)
    }

    @Test
    fun `a problem without an action has nothing to run`() {
        problem = problemOf(backend, withAction = false)
        val model = model()
        assertNull(model.problem.value?.action)
        model.run()
        assertEquals(emptyList(), runs)
    }

    @Test
    fun `a closed model no longer follows its sources`() {
        problem = problemOf(backend)
        val prompts = prompts()
        val model = model(prompts)

        model.close()
        state.value = ServerState.Stopped
        problem = null
        changes.value++
        notNow.add(backend)
        prompts.decline("python.environment")

        assertEquals(problemOf(backend), model.problem.value)
    }
}
