package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.doubles.DoublesModel
import dev.sluide.games.doubles.DoublesPreferences
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

class DoublesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `every tab shows the one game of the window`() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = DoublesPreferences(store, Duration.ZERO, Executor { it.run() })
        val models = mutableListOf<DoublesModel>()
        val doubles =
            Doubles(preferences) { model ->
                models += model
                FakeTab()
            }

        val first = doubles.createTab()
        val second = doubles.createTab()

        assertNotSame(first, second)
        assertSame(models[0], models[1])
        assertSame(doubles.model, models[0])
    }

    private class FakeTab : EditorTab {
        override val title: ObservableValue<String> =
            object : ObservableValueBase<String>() {
                override fun getValue() = "Doubles"
            }
        override val tooltip = ""
        override val content: Node get() = error("not shown")
    }
}
