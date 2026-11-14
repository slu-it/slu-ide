package dev.sluide.barcodes.editor

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

class ShowSelectionExtensionTest {
    private val opened = mutableListOf<String>()
    private val editor = FakeEditor()

    init {
        assertNull(ShowSelectionExtension(opened::add).extend(editor))
    }

    private fun item(selected: String): MenuItem = editor.items(EditorMenuTarget(0, 0, selected)).single()

    @Test
    fun `the item is disabled without a selection`() {
        val item = item("")

        assertEquals("Show Selection as Barcode", item.text)
        assertTrue(item.isDisable)
    }

    @Test
    fun `a selection of 4096 characters is accepted and 4097 is disabled as too long`() {
        val fits = item("x".repeat(4_096))
        assertFalse(fits.isDisable)
        assertEquals("Show Selection as Barcode", fits.text)

        val tooLong = item("x".repeat(4_097))
        assertTrue(tooLong.isDisable)
        assertEquals("Show Selection as Barcode (too long)", tooLong.text)
    }

    @Test
    fun `the item opens the selected text`() {
        val item = item("https://example.com")

        assertFalse(item.isDisable)
        item.fire()
        assertEquals(listOf("https://example.com"), opened)
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
