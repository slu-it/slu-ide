package dev.sluide.codeintelligence.binding

import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The attachment of one editor: closed once for good, which closes its binding and the document on the server. */
class EditorAttachmentTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers

    @AfterEach
    fun close() = fx.close()

    @Test
    fun `closing unbinds the editor, tells the owner once and ignores later renames`() {
        fx = FxServers(folder, spec)
        val server = fx.fake.awaitRunning(spec)
        val editor = FakeCodeEditor(folder.resolve("a.py"), "x")
        var closed = 0
        val attachment =
            fx.onFx { EditorAttachment(editor, fx.servers, emptyList(), { null }, { emptyList() }) { closed++ } }
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
            fx.onFx { EditorAttachment(editor, fx.servers, emptyList(), { null }, { emptyList() }) { closed++ } }
        assertNull(fx.onFx { attachment.binding })

        fx.onFx { attachment.close() }

        assertEquals(1, closed)
    }
}
