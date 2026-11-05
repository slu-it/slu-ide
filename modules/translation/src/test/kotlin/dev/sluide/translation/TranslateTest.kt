package dev.sluide.translation

import dev.sluide.editorarea.EditorTab
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.translation.session.FakeDetector
import dev.sluide.translation.session.FakeEngine
import dev.sluide.translation.session.FakeInstalls
import dev.sluide.translation.session.ManualScheduler
import dev.sluide.translation.session.TranslateModel
import dev.sluide.translation.session.german
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tool's numbered tabs (ADR 0129) with fake tabs, models on fake schedulers; no toolkit. */
class TranslateTest {
    @TempDir
    lateinit var dir: Path

    private class FakeTab(
        name: String,
        private val onClose: () -> Unit,
    ) : EditorTab {
        override val title: ObservableValue<String> = SimpleStringProperty(name)
        override val tooltip = name
        override val content: Node get() = error("not shown")

        override fun dispose() = onClose()
    }

    private val schedulers = mutableListOf<ManualScheduler>()
    private val models = mutableListOf<TranslateModel>()
    private val opened = mutableListOf<EditorTab>()

    private fun translate(): Translate {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = TranslationPreferences(store, java.time.Duration.ZERO) { it.run() }
        val newModel = {
            val scheduler = ManualScheduler().also { schedulers += it }
            TranslateModel(
                FakeEngine(),
                FakeDetector(german),
                FakeInstalls(),
                preferences,
                Runnable::run,
                scheduler,
                { it.run() },
            ).also { models += it }
        }
        return Translate(newModel, { title, _, _, onClose -> FakeTab(title, onClose) }, Runnable::run)
            .apply { openTab = { opened += it } }
    }

    @Test
    fun `tabs are numbered without reuse, closing one ends only its model`() {
        val translate = translate()
        val first = translate.createTab()
        val second = translate.createTab()
        assertEquals(listOf("Translate 1", "Translate 2"), listOf(first, second).map { it.title.value })

        first.dispose()

        assertTrue(schedulers[0].shut)
        assertFalse(schedulers[1].shut)
        assertEquals("Translate 3", translate.createTab().title.value)
    }

    @Test
    fun `translate opens a new tab whose model holds the text`() {
        val translate = translate()
        translate.createTab()

        translate.translate("hello", false)

        assertEquals("Translate 2", opened.single().title.value)
        assertEquals(2, models.size)
        assertEquals("hello", models.last().text)
    }

    @Test
    fun `close ends the models of the tabs still open`() {
        val translate = translate()
        translate.createTab().dispose()
        translate.createTab()
        translate.translate("hello", false)

        translate.close()

        assertEquals(listOf(true, true, true), schedulers.map { it.shut })
    }
}
