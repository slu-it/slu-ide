package dev.sluide.games

import dev.sluide.editorarea.EditorTab
import dev.sluide.games.solitaire.Cancellable
import dev.sluide.games.solitaire.SolitaireModel
import dev.sluide.games.solitaire.SolitairePreferences
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

class SolitaireTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `every tab shows the one game of the window`() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = SolitairePreferences(store, Duration.ZERO, Executor { it.run() })
        val direct = Executor { it.run() }
        val model =
            SolitaireModel(
                preferences,
                { _, _, _ -> error("not dealt") },
                { _, _, _ -> error("not dealt") },
                { _, _, _ -> error("not solved") },
                direct,
                direct,
                direct,
                { _, _ -> Cancellable {} },
            )
        val models = mutableListOf<SolitaireModel>()
        val solitaire =
            Solitaire(model) {
                models += it
                FakeTab()
            }

        val first = solitaire.createTab()
        val second = solitaire.createTab()

        assertNotSame(first, second)
        assertSame(model, models[0])
        assertSame(model, models[1])
        assertSame(solitaire.model, model)
    }

    private class FakeTab : EditorTab {
        override val title: ObservableValue<String> =
            object : ObservableValueBase<String>() {
                override fun getValue() = "Solitaire"
            }
        override val tooltip = ""
        override val content: Node get() = error("not shown")
    }
}
