package dev.sluide.editorarea

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.TextEditableView
import javafx.scene.Group
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabTextsTest {
    private class TextView(
        override var text: String,
        override var canReplaceText: Boolean = true,
        private val replaced: Boolean = true,
    ) : TextEditableView {
        override val node = Group()
        val replacements = mutableListOf<String>()

        override fun reload() {}

        override fun replaceText(text: String): Boolean {
            if (!canReplaceText) return false
            replacements += text
            return replaced
        }
    }

    private class PlainView : FileView {
        override val node = Group()

        override fun reload() {}
    }

    private val tabs = EditorTabs()
    private val opened = mutableListOf<Path>()
    private var opening: (Path) -> OpenFile? = { null }
    private val texts =
        TabTexts(tabs) { path ->
            opened.add(path)
            opening(path)
        }
    private val a = Path.of("/project/a.txt").toAbsolutePath()

    @Test
    fun `the text of an open file is found by its normalized path, a file not open has none`() {
        tabs.open(a, TextView("a text"))

        assertEquals("a text", texts.currentText(a))
        assertEquals("a text", texts.currentText(a.parent.resolve("sub/../a.txt")))
        assertNull(texts.currentText(a.parent.resolve("b.txt")))
        assertTrue(opened.isEmpty(), "reading never opens a file")
    }

    @Test
    fun `a view that cannot replace its text or is no text view gives no text and takes no edit`() {
        val readOnly = TextView("ro", canReplaceText = false)
        tabs.open(a, readOnly)
        val plain = a.parent.resolve("plain.txt")
        tabs.open(plain, PlainView())

        assertNull(texts.currentText(a))
        assertNull(texts.currentText(plain))
        assertFalse(texts.editText(a) { "x" })
        assertFalse(texts.editText(plain) { "x" })
        assertTrue(readOnly.replacements.isEmpty())
    }

    @Test
    fun `editing an open file replaces its text and returns whether that worked`() {
        val view = TextView("a")
        tabs.open(a, view)
        val failing = TextView("b", replaced = false)
        val b = a.parent.resolve("b.txt")
        tabs.open(b, failing)

        assertTrue(texts.editText(a.parent.resolve("../project/a.txt")) { "$it!" })
        assertEquals(listOf("a!"), view.replacements)
        assertFalse(texts.editText(b) { "$it!" })
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a file not open is opened unselected, and editing fails if that gives nothing`() {
        assertFalse(texts.editText(a) { "x" })
        assertEquals(listOf(a), opened)

        val view = TextView("c")
        opening = { path -> tabs.open(path, view, select = false) }
        assertTrue(texts.editText(a) { "$it+" })
        assertEquals(listOf("c+"), view.replacements)
    }
}
