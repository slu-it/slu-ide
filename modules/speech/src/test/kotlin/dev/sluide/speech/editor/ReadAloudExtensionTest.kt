package dev.sluide.speech.editor

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

class ReadAloudExtensionTest {
    private val read = mutableListOf<Pair<String, Boolean>>()

    private fun editor(
        file: String,
        vararg lines: String,
    ) = FakeEditor(Path.of(file), lines.toList()).also {
        assertNull(ReadAloudExtension { text, markdown -> read += text to markdown }.extend(it))
    }

    @Test
    fun `with a selection the item reads the selection`() {
        val item = editor("/p/a.txt", "one", "two").items(EditorMenuTarget(0, 0, "two")).single()
        item.fire()

        assertEquals("Read Selection Aloud", item.text)
        assertEquals(listOf("two" to false), read)
    }

    @Test
    fun `without a selection the item reads the whole text`() {
        val item = editor("/p/a.txt", "one", "two", "").items(EditorMenuTarget(0, 0, "")).single()
        item.fire()

        assertEquals("Read Aloud", item.text)
        assertEquals(listOf("one\ntwo\n" to false), read)
    }

    @Test
    fun `a Markdown file reads as Markdown, by its name`() {
        editor("/p/README.md", "# Hi").items(EditorMenuTarget(0, 0, "")).single().fire()
        editor("/p/x.MARKDOWN", "y").items(EditorMenuTarget(0, 0, "")).single().fire()
        editor("/p/x.mdx", "z").items(EditorMenuTarget(0, 0, "")).single().fire()

        assertEquals(listOf("# Hi" to true, "y" to true, "z" to false), read)
    }

    @Test
    fun `text over 1 MiB disables the item, the selection is measured on its own`() {
        val big = "a".repeat(1024 * 1024 + 1)
        val editor = editor("/p/a.txt", big)

        val whole = editor.items(EditorMenuTarget(0, 0, "")).single()
        val selected = editor.items(EditorMenuTarget(0, 0, "small")).single()
        val tooBig = editor.items(EditorMenuTarget(0, 0, big)).single()

        assertTrue(whole.isDisable)
        assertEquals("Read Aloud (larger than 1 MiB)", whole.text)
        assertFalse(selected.isDisable)
        assertEquals("Read Selection Aloud (larger than 1 MiB)", tooBig.text)
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
