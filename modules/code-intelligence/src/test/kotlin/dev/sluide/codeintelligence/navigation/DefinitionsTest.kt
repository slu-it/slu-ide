package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.NavigationHistory
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitClientHandled
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.LanguageServerNotRunningException
import dev.sluide.languageservers.LibraryDocument
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Go to definition and the list of places over a bound editor, a fake server and a fake view, without the feature's
 * wiring: where the question is asked, what an answer or a failure shows, answers that arrive after the editor
 * closed, and what the list does with the line texts it waits for.
 */
class DefinitionsTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var harness: BoundEditorHarness<NavigationServer>
    private val view = FakeTargetView()
    private val opened = mutableListOf<SourcePosition>()

    /** The readers of line texts the list started: their places, and whether the list dropped them. */
    private class Read(
        val positions: List<SourcePosition>,
        val done: (Map<SourcePosition, String>) -> Unit,
    ) {
        var closed = false
    }

    private val reads = mutableListOf<Read>()
    private val lines =
        LineReader { positions, done ->
            val read = Read(positions.toList(), done)
            reads += read
            AutoCloseable { read.closed = true }
        }

    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openFile(
                file: Path,
                line: Int,
                column: Int,
            ): Boolean {
                opened += SourcePosition(file, line, column)
                return true
            }

            override fun openWebPage(uri: URI) {}
        }

    private lateinit var chooser: TargetChooser
    private lateinit var definitions: Definitions

    // Line 0 "import os", line 1 empty (offset 10), line 2 "def run():" (word `run` at 15 until 18), line 3, and the
    // empty line 4 after the last break.
    private val text = "import os\n\ndef run():\n    return run\n"
    private val file get() = harness.binding.file

    @AfterEach
    fun close() {
        if (::harness.isInitialized) harness.close()
    }

    private fun start(spec: PySpec = PySpec()) {
        harness = BoundEditorHarness(folder, text, { NavigationServer() }, host = host, spec = spec)
        harness.onFx {
            val navigation =
                Navigation(NavigationHistory(exists = { true }), { listOf(harness.binding) }, { null })
                    .apply { isFocused = { true } }
            chooser = TargetChooser(harness.binding, navigation, view, lines)
            definitions =
                Definitions(
                    harness.binding,
                    chooser,
                    navigation,
                    RequestSlot(),
                    LibraryDocuments(Executor { it.run() }) { it.run() },
                )
        }
    }

    private fun goTo(offset: Int) = harness.onFx { definitions.go(offset) }

    /** Everything the server's answers did reaches the FX thread: a request the client answers, then the thread. */
    private fun idle() = awaitClientHandled(harness.server.client) { harness.onFx(it) }

    @Test
    fun `without a word at the caret the question and the note are at the caret`() {
        start()
        goTo(10)
        awaitTrue("note") { harness.onFx { view.isShowing } }
        assertEquals(
            Position(1, 0),
            harness.server.definitionRequests
                .single()
                .position,
        )
        harness.onFx {
            assertEquals(10 until 10, view.range)
            assertEquals("No declaration found", view.placeholder)
            assertTrue(view.note)
        }
    }

    @Test
    fun `the question is asked at the caret in the word, and at the word's start when the caret is behind it`() {
        start()
        val asked = listOf(15, 16, 17, 18)
        asked.forEachIndexed { index, offset ->
            goTo(offset)
            awaitTrue("asked at $offset") { harness.server.definitionRequests.size == index + 1 }
        }
        assertEquals(
            listOf(Position(2, 4), Position(2, 5), Position(2, 6), Position(2, 4)),
            harness.server.definitionRequests.map { it.position },
        )
    }

    @Test
    fun `asking again hides the list of the question before`() {
        start()
        val other = folder.resolve("os.pyi")
        harness.server.definitionAnswer = { listOf(location(other, 1, 0), location(file, 2, 4)) }
        goTo(16)
        awaitTrue("list") { harness.onFx { view.isShowing } }

        harness.server.holdDefinitions = true
        goTo(16)
        assertFalse(harness.onFx { view.isShowing }, "hidden at once, not when the answer comes")
    }

    @Test
    fun `a failed request is a note at the word with the server's words`() {
        start()
        harness.server.holdDefinitions = true
        goTo(16)
        awaitTrue("asked") { harness.server.pendingDefinitions.size == 1 }
        val error = ResponseError(ResponseErrorCode.InternalError, "boom", null)
        harness.server.pendingDefinitions
            .single()
            .completeExceptionally(ResponseErrorException(error))
        awaitTrue("note") { harness.onFx { view.isShowing } }
        harness.onFx {
            assertEquals("Go to Definition failed: boom", view.placeholder)
            assertTrue(view.note)
            assertEquals(15 until 18, view.range)
        }
    }

    @Test
    fun `a failure that arrives after the editor closed shows no note`() {
        start()
        harness.server.holdDefinitions = true
        goTo(16)
        awaitTrue("asked") { harness.server.pendingDefinitions.size == 1 }
        harness.onFx { harness.binding.close() }
        val error = ResponseError(ResponseErrorCode.InternalError, "boom", null)
        harness.server.pendingDefinitions
            .single()
            .completeExceptionally(ResponseErrorException(error))
        idle()
        assertFalse(harness.onFx { view.isShowing })
    }

    @Test
    fun `an answer that arrives after the editor closed shows nothing and goes nowhere`() {
        start()
        harness.server.holdDefinitions = true
        goTo(16)
        awaitTrue("asked") { harness.server.pendingDefinitions.size == 1 }
        harness.onFx { harness.binding.close() }
        harness.server.pendingDefinitions
            .single()
            .complete(Either.forLeft(listOf(location(file, 2, 4))))
        idle()
        assertFalse(harness.onFx { view.isShowing })
        assertEquals(emptyList(), opened)
        assertEquals(0, harness.editor.caret)
    }

    @Test
    fun `a library document that arrives after the editor closed opens nothing`() {
        val cache = folder.resolve("cache/String.java")
        val spec =
            PySpec(library = { uri ->
                if (uri.startsWith(
                        "jdt:",
                    )
                ) {
                    LibraryDocument("java/classFileContents", mapOf("uri" to uri), cache)
                } else {
                    null
                }
            })
        start(spec)
        harness.server.holdDefinitions = true
        val fetch = CompletableFuture<Any>()
        harness.server.customAnswer = { _, _ -> fetch }
        goTo(16)
        awaitTrue("asked") { harness.server.pendingDefinitions.size == 1 }
        val library = Location("jdt://contents/java.base/String.class", Range(Position(3, 1), Position(3, 7)))
        harness.server.pendingDefinitions
            .single()
            .complete(Either.forLeft(listOf(library)))
        awaitTrue("the document is asked for") {
            harness.server.customMessages.any {
                it.first ==
                    "java/classFileContents"
            }
        }
        harness.onFx { harness.binding.close() }
        fetch.complete("class String {}")
        idle()
        assertEquals(emptyList(), opened)
        assertFalse(harness.onFx { view.isShowing })
    }

    @Test
    fun `the notes for what cannot be done and what failed`() {
        assertEquals("Find is not available (server starting)", unavailable("Find", "server starting"))
        assertEquals(
            "Find is not available (server not running)",
            failure("Find", LanguageServerNotRunningException("gone")),
        )
        assertEquals("Find failed: nope", failure("Find", IllegalStateException("nope")))
        assertEquals("Find failed: IllegalStateException", failure("Find", IllegalStateException()))
    }

    private fun placesIn(vararg lines: Int) = lines.map { SourcePosition(file, it, 0) }

    @Test
    fun `places in the editor's own file show the line as the editor has it, others are read from disk`() {
        start()
        val elsewhere = SourcePosition(folder.resolve("other.py"), 0, 0)
        val own = placesIn(-1, 0, 4, 5)
        harness.onFx { chooser.choose(0 until 0, own + elsewhere) }
        harness.onFx {
            assertEquals(
                listOf(null, "import os", "", null, null),
                view.items.map { it.lineText },
            )
        }
        assertEquals(listOf(own[0], own[3], elsewhere), reads.single().positions, "only what is not known")
    }

    @Test
    fun `nothing is read when every line is known`() {
        start()
        harness.onFx { chooser.choose(0 until 0, placesIn(0, 2)) }
        harness.onFx { assertEquals(listOf("import os", "def run():"), view.items.map { it.lineText }) }
        assertEquals(emptyList(), reads)
    }

    @Test
    fun `line texts that arrive complete the list and keep those it had`() {
        start()
        val elsewhere = SourcePosition(folder.resolve("other.py"), 3, 0)
        harness.onFx { chooser.choose(0 until 0, placesIn(2) + elsewhere) }
        harness.onFx { reads.single().done(mapOf(elsewhere to "far()", SourcePosition(file, 2, 0) to "wrong")) }
        harness.onFx { assertEquals(listOf("def run():", "far()"), view.items.map { it.lineText }) }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["note", "waiting", "choose", "hide", "close"])
    fun `a list that shows something else or goes away drops the line texts it waits for`(action: String) {
        start()
        val elsewhere = SourcePosition(folder.resolve("other.py"), 3, 0)
        harness.onFx { chooser.choose(0 until 0, listOf(elsewhere)) }
        val first = reads.single()
        assertFalse(first.closed)
        harness.onFx {
            when (action) {
                "note" -> chooser.note(0 until 0, "a note")
                "waiting" -> chooser.waiting(0 until 0, "waiting")
                "choose" -> chooser.choose(0 until 0, placesIn(0))
                "hide" -> chooser.hide()
                else -> chooser.close()
            }
        }
        assertTrue(first.closed, "the reader of the old list was dropped")
    }

    @Test
    fun `the list tells whether it shows, and closing it hides it`() {
        start()
        assertFalse(harness.onFx { chooser.isShowing })
        harness.onFx { chooser.note(0 until 0, "a note") }
        assertTrue(harness.onFx { chooser.isShowing })
        harness.onFx { chooser.close() }
        assertFalse(harness.onFx { chooser.isShowing })
        assertFalse(harness.onFx { view.isShowing })
    }
}
