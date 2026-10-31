package dev.sluide.converting.editor

import dev.sluide.converting.model.ConvertingModel
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

class OpenInConvertingExtensionTest {
    private val opened = mutableListOf<Pair<String, String?>>()

    private fun editor(
        file: String,
        vararg lines: String,
    ) = FakeEditor(Path.of(file), lines.toList()).also {
        assertNull(OpenInConvertingExtension { text, extension -> opened += text to extension }.extend(it))
    }

    @Test
    fun `with a selection the item opens the selection with the extension of the file`() {
        val editor = editor("/p/app.yml", "a: 1", "b: 2")

        val item = editor.items(EditorMenuTarget(0, 0, "b: 2")).single()
        item.fire()

        assertEquals("Open Selection in Converting", item.text)
        assertEquals(listOf<Pair<String, String?>>("b: 2" to "yml"), opened)
    }

    @Test
    fun `without a selection the item opens the whole text`() {
        val editor = editor("/p/data.json", "{", "}", "")

        val item = editor.items(EditorMenuTarget(0, 0, "")).single()
        item.fire()

        assertEquals("Open in Converting", item.text)
        assertEquals(listOf<Pair<String, String?>>("{\n}\n" to "json"), opened)
    }

    @Test
    fun `a file without an extension opens without one`() {
        editor("/p/Makefile", "x").items(EditorMenuTarget(0, 0, "")).single().fire()
        editor("/p/.env.", "y").items(EditorMenuTarget(0, 0, "")).single().fire()

        assertEquals(listOf<Pair<String, String?>>("x" to null, "y" to null), opened)
    }

    @Test
    fun `text over 1 MiB disables the item, the selection is measured on its own`() {
        val big = "a".repeat(ConvertingModel.MAX_FILE_SIZE + 1)
        val editor = editor("/p/data.json", big)

        val whole = editor.items(EditorMenuTarget(0, 0, "")).single()
        val selected = editor.items(EditorMenuTarget(0, 0, "small")).single()
        val tooBig = editor.items(EditorMenuTarget(0, 0, big)).single()

        assertTrue(whole.isDisable)
        assertEquals("Open in Converting (larger than 1 MiB)", whole.text)
        assertFalse(selected.isDisable)
        assertTrue(tooBig.isDisable)
        assertEquals("Open Selection in Converting (larger than 1 MiB)", tooBig.text)
    }

    private class FakeEditor(
        path: Path,
        private val lines: List<String>,
    ) : ExtensibleCodeEditor {
        lateinit var items: (EditorMenuTarget) -> List<MenuItem>

        override val file: ObservableValue<Path> = SimpleObjectProperty(path)
        override val lineCount get() = lines.size
        override val shownLines: IntRange = IntRange.EMPTY

        override fun lineText(line: Int) = lines[line]

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
