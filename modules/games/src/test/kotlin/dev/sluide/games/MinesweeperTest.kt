package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.minesweeper.MinesweeperModel
import dev.sluide.games.minesweeper.MinesweeperPreferences
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

class MinesweeperTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `every tab shows the one game of the window`() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = MinesweeperPreferences(store, Duration.ZERO, Executor { it.run() })
        val models = mutableListOf<MinesweeperModel>()
        val minesweeper =
            Minesweeper(preferences) { model ->
                models += model
                FakeTab()
            }

        val first = minesweeper.createTab()
        val second = minesweeper.createTab()

        assertNotSame(first, second)
        assertSame(models[0], models[1])
        assertSame(minesweeper.model, models[0])
    }

    private class FakeTab : EditorTab {
        override val title: ObservableValue<String> =
            object : ObservableValueBase<String>() {
                override fun getValue() = "Minesweeper"
            }
        override val tooltip = ""
        override val content: Node get() = error("not shown")
    }
}
