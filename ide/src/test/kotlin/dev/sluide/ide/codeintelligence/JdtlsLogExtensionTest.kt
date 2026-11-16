package dev.sluide.ide.codeintelligence

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.layout.Region
import javafx.util.Subscription
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The lock, banner and caret of [JdtlsLogExtension] on an editor that only records what it is asked. */
class JdtlsLogExtensionTest {
    private val root = Path.of("/w/workspaces")
    private val log = root.resolve("app-1/data/.metadata/.log")
    private val other = Path.of("/w/Main.java")

    private class FakeEditor(
        file: Path,
        var content: String,
    ) : ExtensibleCodeEditor {
        override val file = SimpleObjectProperty(file)
        val locks = mutableListOf<Boolean>()
        val banners = mutableListOf<Pair<Node, Boolean>>()
        val carets = mutableListOf<Int>()
        val textListeners = mutableListOf<() -> Unit>()

        override val lineCount: Int get() = content.split("\n").size

        override fun lineText(line: Int) = content.split("\n")[line]

        override val text: String get() = content

        override val shownLines: IntRange get() = IntRange.EMPTY

        override fun onShownLinesChanged(action: () -> Unit) = Subscription.EMPTY

        override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {}

        override fun addGutterColumn(
            width: Double,
            column: (paragraph: Int) -> Node?,
        ) {}

        override fun refreshGutter() {}

        override fun onTextChanged(action: () -> Unit): Subscription {
            textListeners += action
            return Subscription { textListeners -= action }
        }

        override fun readOnlyLock(): AutoCloseable {
            locks += true
            val index = locks.size - 1
            return AutoCloseable { locks[index] = false }
        }

        override fun addBanner(node: Node): AutoCloseable {
            banners += node to true
            val index = banners.size - 1
            return AutoCloseable { banners[index] = node to false }
        }

        override fun moveCaret(offset: Int) {
            carets += offset
        }

        fun change(newText: String) {
            content = newText
            textListeners.toList().forEach { it() }
        }

        val lockedNow get() = locks.count { it }
        val bannersNow get() = banners.count { it.second }
    }

    private fun extension() = JdtlsLogExtension(root) { Region() }

    @Test
    fun `an editor of the log is locked, bannered and its caret goes to the end`() {
        val editor = FakeEditor(log, "line1\nline2\n")
        extension().extend(editor)
        assertEquals(1, editor.lockedNow)
        assertEquals(1, editor.bannersNow)
        assertEquals(listOf(12), editor.carets)
    }

    @Test
    fun `a text whose first line is empty still counts as shown`() {
        val editor = FakeEditor(log, "\nabc")
        extension().extend(editor)
        assertEquals(listOf(4), editor.carets)
    }

    @Test
    fun `an editor of another file gets nothing`() {
        val editor = FakeEditor(other, "text")
        extension().extend(editor)
        assertEquals(0, editor.locks.size)
        assertEquals(0, editor.banners.size)
        assertEquals(emptyList(), editor.carets)
        assertEquals(0, editor.textListeners.size)
    }

    @Test
    fun `an editor without text yet moves the caret when the first text arrives, once`() {
        val editor = FakeEditor(log, "")
        extension().extend(editor)
        assertEquals(emptyList(), editor.carets)
        editor.change("abc")
        assertEquals(listOf(3), editor.carets)
        editor.change("abcdef")
        assertEquals(listOf(3), editor.carets)
        assertEquals(0, editor.textListeners.size)
    }

    @Test
    fun `a change that leaves the text empty does not move the caret`() {
        val editor = FakeEditor(log, "")
        extension().extend(editor)
        editor.change("")
        assertEquals(emptyList(), editor.carets)
        editor.change("x")
        assertEquals(listOf(1), editor.carets)
    }

    @Test
    fun `a reload of an editor that showed its text keeps the caret alone`() {
        val editor = FakeEditor(log, "abc")
        extension().extend(editor)
        editor.change("abcdef")
        assertEquals(listOf(3), editor.carets)
        assertEquals(0, editor.textListeners.size)
    }

    @Test
    fun `moving the file away releases the lock and the banner, moving back takes them again`() {
        val editor = FakeEditor(log, "abc")
        extension().extend(editor)
        editor.file.set(other)
        assertEquals(0, editor.lockedNow)
        assertEquals(0, editor.bannersNow)
        editor.file.set(log)
        assertEquals(1, editor.lockedNow)
        assertEquals(1, editor.bannersNow)
        // The caret moved once for the editor, not again for the file's return.
        assertEquals(listOf(3), editor.carets)
    }

    @Test
    fun `text arriving after the file moved away does not move the caret`() {
        val editor = FakeEditor(log, "")
        extension().extend(editor)
        editor.file.set(other)
        editor.change("abc")
        assertEquals(emptyList(), editor.carets)
    }

    @Test
    fun `an editor that left the log while waiting waits again when it returns`() {
        val editor = FakeEditor(log, "")
        extension().extend(editor)
        editor.file.set(other)
        editor.file.set(log)
        editor.change("abc")
        assertEquals(listOf(3), editor.carets)
    }

    @Test
    fun `a move to another log while waiting for the text waits once`() {
        val editor = FakeEditor(log, "")
        extension().extend(editor)
        editor.file.set(root.resolve("app-2/data/.metadata/.log"))
        editor.change("abc")
        assertEquals(listOf(3), editor.carets)
        assertEquals(0, editor.textListeners.size)
    }

    @Test
    fun `the caret moved for text that arrived later is not moved again when the file returns`() {
        val editor = FakeEditor(log, "")
        extension().extend(editor)
        editor.change("abc")
        editor.file.set(other)
        editor.file.set(log)
        assertEquals(listOf(3), editor.carets)
    }

    @Test
    fun `moving another file onto the log takes the lock`() {
        val editor = FakeEditor(other, "abc")
        extension().extend(editor)
        editor.file.set(log)
        assertEquals(1, editor.lockedNow)
        assertEquals(1, editor.bannersNow)
        assertEquals(listOf(3), editor.carets)
    }

    @Test
    fun `a move within the log's folders keeps one lock and one banner`() {
        val editor = FakeEditor(log, "abc")
        extension().extend(editor)
        editor.file.set(root.resolve("app-2/data/.metadata/.log"))
        assertEquals(1, editor.lockedNow)
        assertEquals(1, editor.bannersNow)
        assertEquals(1, editor.locks.size)
        assertEquals(1, editor.banners.size)
    }

    @Test
    fun `closing the handle releases lock and banner and stops following the file`() {
        val editor = FakeEditor(log, "abc")
        extension().extend(editor)?.close()
        assertEquals(0, editor.lockedNow)
        assertEquals(0, editor.bannersNow)
        editor.file.set(other)
        editor.file.set(log)
        assertEquals(1, editor.locks.size)
    }

    @Test
    fun `closing the handle of an editor waiting for its text stops the wait`() {
        val editor = FakeEditor(log, "")
        extension().extend(editor)?.close()
        assertEquals(0, editor.textListeners.size)
    }
}
