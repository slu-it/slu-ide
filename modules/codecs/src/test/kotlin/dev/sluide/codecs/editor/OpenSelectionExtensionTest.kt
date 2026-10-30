package dev.sluide.codecs.editor

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenSelectionExtensionTest {
    private val opened = mutableListOf<String>()
    private val editor = FakeEditor()

    init {
        assertNull(OpenSelectionExtension(opened::add).extend(editor))
    }

    private fun item(selected: String): MenuItem = editor.items(EditorMenuTarget(0, 0, selected)).single()

    @Test
    fun `the item is disabled without a selection`() {
        val item = item("")

        assertEquals("Open Selection in Codecs", item.text)
        assertTrue(item.isDisable)
    }

    @Test
    fun `the item opens the selected text`() {
        val item = item("aGk=")

        assertFalse(item.isDisable)
        item.fire()
        assertEquals(listOf("aGk="), opened)
    }

    private class FakeEditor : ExtensibleCodeEditor {
        lateinit var items: (EditorMenuTarget) -> List<MenuItem>

        override val file: ObservableValue<Path> = SimpleObjectProperty(Path.of("a.txt"))
        override val lineCount = 1
        override val shownLines: IntRange = IntRange.EMPTY

        override fun lineText(line: Int) = ""

        override fun onShownLinesChanged(action: () -> Unit) = Subscription {}

        override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {
            this.items = items
        }

        override fun addGutterColumn(
            width: Double,
            column: (paragraph: Int) -> Node?,
        ) = Unit

        override fun refreshGutter() = Unit
    }
}
