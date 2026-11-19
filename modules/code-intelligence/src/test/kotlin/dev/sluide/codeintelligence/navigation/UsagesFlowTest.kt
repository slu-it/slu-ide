package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Find usages at the edges the flow test of go to definition does not reach: where the name is asked (the caret at the
 * end of a name, between names), what a server that cannot find usages or answers null, null entries or an error shows,
 * and an answer for a binding that closed meanwhile.
 */
class UsagesFlowTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers
    private lateinit var server: NavigationServer
    private lateinit var intelligence: CodeIntelligence
    private val view = FakeTargetView()

    private val text = "import os\n\ndef run():\n    return run\n"
    private val file get() = folder.resolve("a.py")

    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override val projectFolder: Path get() = folder
        }

    @AfterEach
    fun close() {
        fx.onFx { intelligence.close() }
        fx.close()
    }

    /** A bound editor of `a.py` with the caret at [caret], over a server declaring [supported]. */
    private fun editor(
        caret: Int,
        supported: Set<String> = setOf(DEFINITION, REFERENCES, DOCUMENT_HIGHLIGHT),
    ): FakeCodeEditor {
        fx = FxServers(folder, spec) { NavigationServer(supported) }
        server = fx.fake.awaitRunning(spec) as NavigationServer
        intelligence =
            fx.onFx {
                CodeIntelligence(fx.servers, FXCollections.observableSet()) {
                    listOf(
                        NavigationFeature(navigation, { positions, done ->
                            done(positions.associateWith { "line ${it.line}" })
                            AutoCloseable {}
                        }, LibraryDocuments({ it.run() }, fx::dispatch)) { view },
                    )
                }.apply { navigation.isFocused = { true } }
            }
        file.writeText(text)
        val editor = FakeCodeEditor(file, text, host).apply { this.caret = caret }
        fx.onFx { intelligence.extend(editor) }
        return editor
    }

    private fun usagesAtCaret(editor: FakeCodeEditor) {
        val binding = fx.onFx { checkNotNull(intelligence.attached.mapNotNull { it.binding }.single()) }
        val actions = fx.onFx { checkNotNull(intelligence.navigation.actionsOf(binding)) }
        fx.onFx { actions.findUsages(editor.caretOffset) }
    }

    /** Waits until every answer the server sent before reached the FX thread. */
    private fun serverIdle() {
        val answered = CountDownLatch(1)
        val params = HoverParams(TextDocumentIdentifier(LspUris.of(file)), Position(0, 0))
        fx.onFx {
            fx.servers.session(spec).request(
                { it.textDocumentService.hover(params) },
                { answered.countDown() },
                { answered.countDown() },
            )
        }
        assertTrue(answered.await(5, TimeUnit.SECONDS), "the server answered")
        fx.idle()
    }

    @Test
    fun `a caret at the end of a name asks at the name's start, one between names at the caret`() {
        val editor = editor(caret = 18)
        server.holdReferences = true
        usagesAtCaret(editor)
        awaitTrue("asked at the name") { server.referenceRequests.size == 1 }
        assertEquals(Position(2, 4), server.referenceRequests[0].position)
        assertEquals(15 until 18, fx.onFx { view.range })

        fx.onFx { editor.caret = 10 }
        usagesAtCaret(editor)
        awaitTrue("asked at the caret") { server.referenceRequests.size == 2 }
        assertEquals(Position(1, 0), server.referenceRequests[1].position)
        assertEquals(10 until 10, fx.onFx { view.range }, "no name: the empty range at the caret")
        assertEquals("Finding usages…", fx.onFx { view.placeholder })
    }

    @Test
    fun `a caret inside a name asks where it is`() {
        val editor = editor(caret = 15)
        for ((count, caret) in listOf(15, 16, 17).withIndex()) {
            fx.onFx { editor.caret = caret }
            usagesAtCaret(editor)
            awaitTrue("asked at $caret") { server.referenceRequests.size == count + 1 }
            assertEquals(Position(2, caret - 11), server.referenceRequests[count].position)
        }
    }

    @Test
    fun `a server that cannot find usages is not asked, the list hides and the note names the action`() {
        val editor = editor(caret = 17, supported = setOf(DEFINITION))
        fx.onFx { view.show(15 until 18, listOf(NavigationTarget(SourcePosition(file, 0, 0), "x")), "Choose", false) }
        usagesAtCaret(editor)
        fx.onFx {
            assertEquals("Find Usages is not available (not supported)", view.placeholder)
            assertTrue(view.note)
            assertEquals(15 until 18, view.range)
        }
        assertEquals(emptyList(), server.referenceRequests)
        assertEquals(1, view.hides, "the list was hidden before the note")
    }

    @Test
    fun `an answer of null or of null entries only says no usages, null entries among others are skipped`() {
        val editor = editor(caret = 17)
        server.referencesAnswer = { null }
        usagesAtCaret(editor)
        awaitTrue("note") { fx.onFx { view.placeholder } == "No usages found" }

        fx.onFx { view.placeholder = null }
        server.referencesAnswer = { listOf(null) }
        usagesAtCaret(editor)
        awaitTrue("note again") { fx.onFx { view.placeholder } == "No usages found" }

        fx.onFx { editor.caret = 17 }
        server.referencesAnswer = { listOf(null, location(file, 3, 11), null, location(file, 3, 4)) }
        usagesAtCaret(editor)
        awaitTrue("listed") { fx.onFx { view.items.size } == 2 }
        assertEquals(
            listOf(SourcePosition(file, 3, 4), SourcePosition(file, 3, 11)),
            fx.onFx {
                view.items.map { it.position }
            },
        )
    }

    @Test
    fun `a request the server fails says so at the name`() {
        val editor = editor(caret = 17)
        server.holdReferences = true
        usagesAtCaret(editor)
        awaitTrue("asked") { server.pendingReferences.size == 1 }
        val failure = ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, "no index", null))
        server.pendingReferences.single().completeExceptionally(failure)
        awaitTrue("note") { fx.onFx { view.note } }
        fx.onFx {
            assertEquals("Find Usages failed: no index", view.placeholder)
            assertEquals(15 until 18, view.range)
        }
    }

    @Test
    fun `an answer or an error for a binding that closed meanwhile shows nothing`() {
        val editor = editor(caret = 17)
        server.holdReferences = true
        usagesAtCaret(editor)
        awaitTrue("asked") { server.pendingReferences.size == 1 }
        fx.onFx { intelligence.attached.single().close() }
        server.pendingReferences[0].complete(listOf(location(file, 3, 11)))
        serverIdle()
        assertFalse(fx.onFx { view.isShowing }, "an answer shows nothing")
        assertEquals(17, fx.onFx { editor.caret }, "no jump")
    }

    @Test
    fun `an error for a binding that closed meanwhile shows nothing`() {
        val editor = editor(caret = 17)
        server.holdReferences = true
        usagesAtCaret(editor)
        awaitTrue("asked") { server.pendingReferences.size == 1 }
        fx.onFx { intelligence.attached.single().close() }
        val failure = ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, "late", null))
        server.pendingReferences[0].completeExceptionally(failure)
        serverIdle()
        assertFalse(fx.onFx { view.isShowing }, "no note")
        assertEquals(17, fx.onFx { editor.caret })
    }
}
