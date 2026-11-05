package dev.sluide.speech.editor

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.speech.ui.FakeHost
import dev.sluide.speech.ui.ReadAloudModel
import javafx.scene.control.IndexRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadAloudExtensionTest {
    private val read = mutableListOf<Pair<String, Boolean>>()

    private fun editor(
        file: String,
        vararg lines: String,
    ) = FakeCodeEditor(file, lines.toList()).also {
        assertNull(ReadAloudExtension(read = { text, markdown -> read += text to markdown }).extend(it))
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

    private val host = FakeHost()
    private val readings = mutableListOf<EditorReading>()

    /** The extension with in-place reading on a [FakeHost]; [read] still collects what goes to the tool. */
    private fun inPlace(
        path: String,
        text: String,
        plainText: Boolean,
    ): Pair<FakeCodeEditor, AutoCloseable?> {
        val editor = FakeCodeEditor(path, text.split("\n"), plainText)
        val handle =
            ReadAloudExtension({ t, m -> read += t to m }) { e ->
                EditorReading(ReadAloudModel(host, ownsText = false), e, { FakeControls() }, "speech.css")
                    .also { readings += it }
            }.extend(editor)
        return editor to handle
    }

    @Test
    fun `plain-text and Markdown editors read in place, other editors send to the tool`() {
        val (txt, txtHandle) = inPlace("/p/a.txt", "One. Two.", plainText = true)
        val (md, mdHandle) = inPlace("/p/a.md", "One. Two.", plainText = false)
        val (code, codeHandle) = inPlace("/p/a.py", "One. Two.", plainText = false)

        assertNotNull(txtHandle)
        assertNotNull(mdHandle)
        assertNull(codeHandle)
        assertEquals(2, readings.size)

        txt.items(EditorMenuTarget(0, 0, "")).single().fire()
        assertEquals(1, host.readings.size)
        assertEquals(emptyList(), read)
        code.items(EditorMenuTarget(0, 0, "")).single().fire()
        assertEquals(listOf("One. Two." to false), read)
        assertEquals(1, host.readings.size)
        assertEquals("Read Aloud", md.items(EditorMenuTarget(0, 0, "")).single().text)
    }

    @Test
    fun `in place the selection item plays the editor's selection`() {
        val (editor, _) = inPlace("/p/a.txt", "One. Two. Three.", plainText = true)
        editor.selected = IndexRange(5, 9)

        val item = editor.items(EditorMenuTarget(0, 0, "Two.")).single()
        item.fire()

        assertEquals("Read Selection Aloud", item.text)
        assertEquals(listOf("Two."), host.reading.chunks.map { it.text })
    }

    @Test
    fun `in place the items are disabled while that editor is reading`() {
        val (editor, _) = inPlace("/p/a.txt", "One. Two.", plainText = true)
        assertFalse(editor.items(EditorMenuTarget(0, 0, "")).single().isDisable)

        editor.items(EditorMenuTarget(0, 0, "")).single().fire()

        assertTrue(editor.items(EditorMenuTarget(0, 0, "")).single().isDisable)
        assertTrue(editor.items(EditorMenuTarget(0, 0, "One.")).single().isDisable)
    }

    @Test
    fun `the handle of an in-place editor closes its reading`() {
        val (editor, handle) = inPlace("/p/a.txt", "One.", plainText = true)
        assertTrue(editor.open.isNotEmpty())

        checkNotNull(handle).close()

        assertEquals(emptySet(), editor.open)
    }
}
