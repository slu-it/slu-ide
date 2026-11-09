package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.minigolf.MiniGolfModel
import dev.sluide.games.minigolf.MiniGolfPreferences
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

class MiniGolfTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `every tab shows the one round of the window`() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = MiniGolfPreferences(store, Duration.ZERO, Executor { it.run() })
        val models = mutableListOf<MiniGolfModel>()
        val miniGolf =
            MiniGolf(preferences) { model ->
                models += model
                FakeTab()
            }

        val first = miniGolf.createTab()
        val second = miniGolf.createTab()

        assertNotSame(first, second)
        assertSame(models[0], models[1])
        assertSame(miniGolf.model, models[0])
    }

    private class FakeTab : EditorTab {
        override val title: ObservableValue<String> =
            object : ObservableValueBase<String>() {
                override fun getValue() = "Mini Golf"
            }
        override val tooltip = ""
        override val content: Node get() = error("not shown")
    }
}
