package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.pipepuzzle.PipePuzzleModel
import dev.sluide.games.pipepuzzle.PipePuzzlePreferences
import dev.sluide.projectdata.ProjectDataStore
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class PipePuzzleTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `every tab shows the one game of the window`() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = PipePuzzlePreferences(store, Duration.ZERO, Executor { it.run() })
        val sync = Executor { it.run() }
        val model = PipePuzzleModel(preferences, { _, _ -> error("not asked") }, sync, sync)
        val models = mutableListOf<PipePuzzleModel>()
        val pipePuzzle =
            PipePuzzle(model) { shown ->
                models += shown
                FakeTab()
            }

        val first = pipePuzzle.createTab()
        val second = pipePuzzle.createTab()

        assertNotSame(first, second)
        assertSame(model, models[0])
        assertSame(model, models[1])
        assertSame(model, pipePuzzle.model)
    }

    private class FakeTab : EditorTab {
        override val title: ObservableValue<String> =
            object : ObservableValueBase<String>() {
                override fun getValue() = "Pipe Puzzle"
            }
        override val tooltip = ""
        override val content: Node get() = error("not shown")
    }
}
