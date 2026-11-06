package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.sudoku.SudokuModel
import dev.sluide.games.sudoku.SudokuPreferences
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

class SudokuTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `every tab shows the one game of the window`() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = SudokuPreferences(store, Duration.ZERO, Executor { it.run() })
        val direct = Executor { it.run() }
        val model = SudokuModel(preferences, { _, _ -> error("not generated") }, direct, direct)
        val models = mutableListOf<SudokuModel>()
        val sudoku =
            Sudoku(model) {
                models += it
                FakeTab()
            }

        val first = sudoku.createTab()
        val second = sudoku.createTab()

        assertNotSame(first, second)
        assertSame(model, models[0])
        assertSame(model, models[1])
        assertSame(sudoku.model, model)
    }

    private class FakeTab : EditorTab {
        override val title: ObservableValue<String> =
            object : ObservableValueBase<String>() {
                override fun getValue() = "Sudoku"
            }
        override val tooltip = ""
        override val content: Node get() = error("not shown")
    }
}
