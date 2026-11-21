package dev.sluide.jshell

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JShellEditorExtensionsTest {
    private val opened = mutableListOf<String>()
    private val editor = FakeEditor()

    init {
        assertNull(JShellEditorExtensions.evaluateSelection(opened::add).extend(editor))
    }

    private fun items(selected: String = "x") = editor.items(EditorMenuTarget(0, 0, selected))

    @ParameterizedTest
    @ValueSource(strings = ["A.java", "b.JAVA", "/p/src/Main.java"])
    fun `a Java file's menu has the item`(name: String) {
        editor.file.set(Path.of(name))

        assertEquals(listOf("Evaluate Selection in JShell"), items().map { it.text })
    }

    @ParameterizedTest
    @ValueSource(strings = ["notes.txt", "A.kt", "script.jsh", "java", "A.java.txt"])
    fun `the menu of other files has no item`(name: String) {
        editor.file.set(Path.of(name))

        assertEquals(emptyList(), items().map { it.text })
    }

    @Test
    fun `the item is disabled without a selection`() {
        assertEquals(listOf(true), items("").map { it.isDisable })
    }

    @Test
    fun `the item hands over the selected text`() {
        val item = items("int x = 5; x * 2").single()

        assertFalse(item.isDisable)
        item.fire()
        assertEquals(listOf("int x = 5; x * 2"), opened)
    }

    @Test
    fun `a file renamed to another type loses the item at the next menu show`() {
        assertEquals(1, items().size)

        editor.file.set(Path.of("A.txt"))

        assertEquals(0, items().size)
    }

    private class FakeEditor : ExtensibleCodeEditor {
        lateinit var items: (EditorMenuTarget) -> List<MenuItem>

        override val file = SimpleObjectProperty(Path.of("A.java"))
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
