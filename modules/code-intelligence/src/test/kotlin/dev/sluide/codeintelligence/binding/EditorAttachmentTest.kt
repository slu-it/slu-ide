package dev.sluide.codeintelligence.binding

import dev.sluide.codeintelligence.CompletionSource
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The attachment of one editor: closed once for good, which closes its binding and the document on the server; the
 * completion of a source attached once while the same source applies.
 */
class EditorAttachmentTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers

    @AfterEach
    fun close() = fx.close()

    /** The sources whose completion is attached now; a closed handle removes its source. */
    private val attached = mutableListOf<CompletionSource>()

    /** How often a source's completion was attached. */
    private var attaches = 0

    private fun attachment(
        editor: FakeCodeEditor,
        sourceFor: (Path) -> CompletionSource? = { null },
        closed: () -> Unit,
    ) = EditorAttachment(editor, fx.servers, emptyList(), { null }, { emptyList() }, sourceFor, { _, source ->
        attached += source
        attaches++
        AutoCloseable { attached -= source }
    }, closed)

    @Test
    fun `a rename or a change of the sources keeps the completion of the same source, another source replaces it`() {
        fx = FxServers(folder, spec)
        val jsh = CompletionSource { _, _ -> null }
        val other = CompletionSource { _, _ -> null }
        val editor = FakeCodeEditor(folder.resolve("a.jsh"), "x")
        val attachment =
            fx.onFx { attachment(editor, { if (it.toString().endsWith(".jsh")) jsh else other }) {} }
        assertEquals(listOf(jsh), attached)

        fx.onFx {
            editor.fileProperty.set(folder.resolve("b.jsh"))
            attachment.sourcesChanged()
        }
        assertEquals(listOf(jsh), attached)
        assertEquals(1, attaches, "attached once")

        fx.onFx { editor.fileProperty.set(folder.resolve("b.txt")) }
        assertEquals(listOf(other), attached)
        assertEquals(2, attaches)
    }

    @Test
    fun `a closed attachment attaches no source when the sources change`() {
        fx = FxServers(folder, spec)
        val jsh = CompletionSource { _, _ -> null }
        val other = CompletionSource { _, _ -> null }
        val editor = FakeCodeEditor(folder.resolve("a.jsh"), "x")
        val attachment =
            fx.onFx { attachment(editor, { if (it.toString().endsWith(".jsh")) jsh else other }) {} }

        fx.onFx {
            attachment.close()
            editor.fileProperty.set(folder.resolve("a.txt"))
            attachment.sourcesChanged()
        }

        assertEquals(emptyList(), attached)
        assertEquals(1, attaches)
    }

    @Test
    fun `closing unbinds the editor, tells the owner once and ignores later renames`() {
        fx = FxServers(folder, spec)
        val server = fx.fake.awaitRunning(spec)
        val editor = FakeCodeEditor(folder.resolve("a.py"), "x")
        var closed = 0
        val attachment =
            fx.onFx { attachment(editor) { closed++ } }
        val binding = assertNotNull(fx.onFx { attachment.binding })

        fx.onFx {
            attachment.close()
            attachment.close()
        }

        assertEquals(1, closed, "told once")
        assertTrue(binding.isClosed)
        assertNull(fx.onFx { attachment.binding })
        awaitTrue("the document was closed on the server") {
            server.documentNotifications.filterIsInstance<DidCloseTextDocumentParams>().size == 1
        }
        fx.onFx { editor.fileProperty.set(folder.resolve("b.py")) }
        assertNull(fx.onFx { attachment.binding }, "a renamed editor of a closed attachment is not bound again")
    }

    @Test
    fun `an editor of a file no server serves has no binding, and closing it only tells the owner`() {
        fx = FxServers(folder, spec)
        val editor = FakeCodeEditor(folder.resolve("notes.txt"), "x")
        var closed = 0
        val attachment =
            fx.onFx { attachment(editor) { closed++ } }
        assertNull(fx.onFx { attachment.binding })

        fx.onFx { attachment.close() }

        assertEquals(1, closed)
    }

    /** A file property that counts its listeners, to see that closing the attachment let go of it. */
    private class CountingFile(
        initial: Path,
    ) : SimpleObjectProperty<Path>(initial) {
        var listeners = 0

        override fun addListener(listener: ChangeListener<in Path>) {
            listeners++
            super.addListener(listener)
        }

        override fun removeListener(listener: ChangeListener<in Path>) {
            listeners--
            super.removeListener(listener)
        }
    }

    @Test
    fun `closing lets go of the editor's file property`() {
        fx = FxServers(folder, spec)
        val file = CountingFile(folder.resolve("a.py"))
        val editor =
            object : FakeCodeEditor(folder.resolve("a.py"), "x") {
                override val file: ObservableValue<Path> = file
            }
        val attachment = fx.onFx { attachment(editor) {} }
        assertEquals(1, file.listeners, "the attachment follows the file")

        fx.onFx { attachment.close() }

        assertEquals(0, file.listeners)
    }
}
