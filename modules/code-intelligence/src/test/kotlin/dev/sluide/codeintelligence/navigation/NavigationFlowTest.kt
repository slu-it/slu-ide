package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FIND_USAGES_KEYS
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.GO_TO_DEFINITION_KEYS
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitServerHandled
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.LibraryDocument
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Go to definition and find usages of a bound editor over a fake server, with the list as a fake view: none, one and
 * several answers, the order of usages, what the keys and the context menu offer, and Back after a jump.
 */
class NavigationFlowTest {
    @TempDir
    lateinit var folder: Path

    /** What the spec says about non-file URIs: `jdt:` ones are fetched into [cached]. */
    private var libraryOf: (String) -> LibraryDocument? = { uri ->
        if (uri.startsWith("jdt:")) LibraryDocument("java/classFileContents", mapOf("uri" to uri), cached) else null
    }
    private val spec = PySpec(library = { libraryOf(it) })
    private val cached get() = folder.resolve("cache/java.base/String.java")
    private lateinit var fx: FxServers
    private lateinit var server: NavigationServer
    private lateinit var intelligence: CodeIntelligence
    private val view = FakeTargetView()
    private val opened = CopyOnWriteArrayList<SourcePosition>()
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

            override val projectFolder: Path get() = folder
        }

    /** The writer of cache files: runs a task where it is given, or keeps it in [heldWrites] while [holdWrites] is set. */
    @Volatile
    private var holdWrites = false
    private val heldWrites = CopyOnWriteArrayList<Runnable>()
    private val io = Executor { task -> if (holdWrites) heldWrites += task else task.run() }

    /** While set, line texts wait in [deferredLines] instead of arriving at once. */
    private var deferLines = false
    private val deferredLines = mutableListOf<() -> Unit>()

    /** Line texts of other files, as the disk would give them. */
    private val lines =
        LineReader { positions, done ->
            val texts = positions.associateWith { "line ${it.line} of ${it.file.fileName}" }
            if (deferLines) deferredLines += { done(texts) } else done(texts)
            AutoCloseable {}
        }

    /** The editor that has the focus; the fake editors have no scene. */
    private var focused: FakeCodeEditor? = null

    private val text = "import os\n\ndef run():\n    return run\n"
    private val file get() = folder.resolve("a.py")

    @AfterEach
    fun close() {
        fx.onFx { intelligence.close() }
        fx.close()
    }

    /**
     * A bound editor of `a.py` (or [name]) with the caret at [caret] and the focus, over a server declaring
     * [supported]; the server and the code intelligence start with the first editor.
     */
    private fun editor(
        caret: Int,
        supported: Set<String> = setOf(DEFINITION, REFERENCES, DOCUMENT_HIGHLIGHT),
        name: String = "a.py",
    ): FakeCodeEditor {
        start(supported)
        val path = folder.resolve(name)
        path.writeText(text)
        val editor = FakeCodeEditor(path, text, host).apply { this.caret = caret }
        fx.onFx { intelligence.extend(editor) }
        focused = editor
        return editor
    }

    /** The servers and the code intelligence, started once; the first editor starts them, a test may do it without. */
    private fun start(supported: Set<String> = setOf(DEFINITION, REFERENCES, DOCUMENT_HIGHLIGHT)) {
        if (::fx.isInitialized) return
        fx = FxServers(folder, spec) { NavigationServer(supported) }
        server = fx.fake.awaitRunning(spec) as NavigationServer
        intelligence =
            fx.onFx {
                CodeIntelligence(fx.servers, FXCollections.observableSet()) {
                    listOf(NavigationFeature(navigation, lines, LibraryDocuments(io, fx::dispatch)) { view })
                }.apply { navigation.isFocused = { it.editor === focused } }
            }
    }

    private fun actions(editor: FakeCodeEditor) =
        fx.onFx {
            val binding = checkNotNull(intelligence.attached.mapNotNull { it.binding }.single { it.editor === editor })
            checkNotNull(intelligence.navigation.actionsOf(binding))
        }

    private fun definitionAtCaret(editor: FakeCodeEditor) {
        val actions = actions(editor)
        fx.onFx { actions.goToDefinition(editor.caretOffset) }
    }

    private fun usagesAtCaret(editor: FakeCodeEditor) {
        val actions = actions(editor)
        fx.onFx { actions.findUsages(editor.caretOffset) }
    }

    /** Waits until every answer the server sent before reached the FX thread: one more request, answered in order. */
    private fun serverIdle() = awaitServerHandled(fx.servers.session(spec)) { fx.onFx(it) }

    /** Holds the definition answers and asks one at the caret of [editor]. */
    private fun heldDefinition(editor: FakeCodeEditor) {
        server.holdDefinitions = true
        val before = server.pendingDefinitions.size
        definitionAtCaret(editor)
        awaitTrue("asked") { server.pendingDefinitions.size == before + 1 }
    }

    /** Nothing came of an answer: no list or note, no jump, nothing recorded. */
    private fun assertNothingShown(
        editor: FakeCodeEditor,
        caret: Int,
    ) {
        assertFalse(fx.onFx { view.isShowing }, "nothing shows")
        assertEquals(caret, fx.onFx { editor.caret }, "the caret stays")
        assertEquals(emptyList(), opened, "no file opened")
        assertEquals(emptyList(), intelligence.history.places.first, "no jump recorded")
    }

    @Test
    fun `no declaration shows a note at the name, asked at the caret`() {
        val editor = editor(caret = 34)
        definitionAtCaret(editor)
        awaitTrue("note") { fx.onFx { view.isShowing } }
        assertEquals(Position(3, 12), server.definitionRequests.single().position)
        fx.onFx {
            assertEquals("No declaration found", view.placeholder)
            assertTrue(view.note)
            assertEquals(33 until 36, view.range)
            assertEquals(emptyList(), view.items)
        }
        assertEquals(34, fx.onFx { editor.caret }, "the caret stays")
    }

    @Test
    fun `one declaration in the file moves the caret there, and Back returns to where it was`() {
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(location(file, 2, 4, 3)) }
        definitionAtCaret(editor)
        awaitTrue("jump") { fx.onFx { editor.caret } == 15 }
        assertFalse(fx.onFx { view.isShowing })
        assertEquals(listOf(SourcePosition(file, 3, 12)), intelligence.history.places.first)

        assertTrue(fx.onFx { intelligence.back() })
        assertEquals(34, fx.onFx { editor.caret })
        assertTrue(fx.onFx { intelligence.forward() })
        assertEquals(15, fx.onFx { editor.caret })
        assertEquals(emptyList(), opened, "no file opened for places in the editor's own file")
    }

    @Test
    fun `one declaration in another file opens it at its line and column`() {
        val other = folder.resolve("lib.py")
        val editor = editor(caret = 2)
        server.definitionAnswer = { listOf(location(other, 7, 4)) }
        definitionAtCaret(editor)
        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(listOf(SourcePosition(other, 7, 4)), opened)
        assertEquals(2, fx.onFx { editor.caret })
    }

    @Test
    fun `several declarations are offered in the server's order with their lines, and the one taken is jumped to`() {
        val stub = folder.resolve("os.pyi").also { it.createFile() }
        val editor = editor(caret = 8)
        server.definitionAnswer = { listOf(location(stub, 4, 0), location(file, 2, 4)) }
        definitionAtCaret(editor)
        awaitTrue("chooser") { fx.onFx { view.isShowing } }
        fx.onFx {
            assertFalse(view.note)
            assertEquals(7 until 9, view.range)
            assertEquals(
                listOf(
                    NavigationTarget(SourcePosition(stub, 4, 0), "line 4 of os.pyi"),
                    NavigationTarget(SourcePosition(file, 2, 4), "def run():"),
                ),
                view.items,
            )
            view.choose(0)
        }
        assertEquals(listOf(SourcePosition(stub, 4, 0)), opened)
        assertEquals(listOf(SourcePosition(file, 0, 8)), intelligence.history.places.first)
    }

    @Test
    fun `a server without definitions gets no request, the note and the menu item say why`() {
        val editor = editor(caret = 34, supported = setOf(REFERENCES))
        definitionAtCaret(editor)
        fx.onFx {
            assertEquals("Go to Definition is not available (not supported)", view.placeholder)
            assertTrue(view.note)
        }
        assertEquals(emptyList(), server.definitionRequests)
        val items = fx.onFx { editor.menuItems() }
        assertEquals(listOf("Go to Definition (not supported)", "Find Usages"), items.map { it.text })
        assertEquals(listOf(true, false), items.map { it.isDisable })
        assertEquals(listOf(GO_TO_DEFINITION_KEYS, FIND_USAGES_KEYS), items.map { it.accelerator })
        assertTrue(editor.keyBindings.containsAll(listOf(GO_TO_DEFINITION_KEYS, FIND_USAGES_KEYS)))
    }

    @Test
    fun `usages show Finding usages while asked without the declaration, then this file first, then by path`() {
        val other = folder.resolve("b.py")
        val editor = editor(caret = 17)
        server.holdReferences = true
        usagesAtCaret(editor)
        awaitTrue("asked") { server.pendingReferences.isNotEmpty() }
        fx.onFx {
            assertEquals("Finding usages…", view.placeholder)
            assertFalse(view.note)
            assertEquals(15 until 18, view.range)
        }
        assertFalse(
            server.referenceRequests
                .single()
                .context.isIncludeDeclaration,
        )

        server.pendingReferences.single().complete(listOf(location(other, 1, 0), location(file, 3, 11)))
        awaitTrue("listed") { fx.onFx { view.items.size } == 2 }
        assertEquals(
            listOf(
                NavigationTarget(SourcePosition(file, 3, 11), "return run"),
                NavigationTarget(SourcePosition(other, 1, 0), "line 1 of b.py"),
            ),
            fx.onFx { view.items },
        )
    }

    @Test
    fun `a single usage is jumped to and none says so`() {
        val editor = editor(caret = 17)
        server.referencesAnswer = { listOf(location(file, 3, 11)) }
        usagesAtCaret(editor)
        awaitTrue("jump") { fx.onFx { editor.caret } == 33 }
        assertFalse(fx.onFx { view.isShowing })

        server.referencesAnswer = { emptyList() }
        usagesAtCaret(editor)
        awaitTrue("note") { fx.onFx { view.placeholder } == "No usages found" }
        assertTrue(fx.onFx { view.note })
    }

    @Test
    fun `Back and Forward with the focus outside the editor of the place go through the host, which selects its tab`() {
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(location(file, 2, 4, 3)) }
        definitionAtCaret(editor)
        awaitTrue("jump") { fx.onFx { editor.caret } == 15 }

        focused = null
        assertTrue(fx.onFx { intelligence.back() })
        assertEquals(listOf(SourcePosition(file, 3, 12)), opened)
        assertEquals(15, fx.onFx { editor.caret }, "not moved in an editor without the focus")

        editor(caret = 0, name = "b.py")
        assertTrue(fx.onFx { intelligence.forward() })
        assertEquals(listOf(SourcePosition(file, 3, 12), SourcePosition(file, 2, 4)), opened)
        assertEquals(15, fx.onFx { editor.caret })
    }

    @Test
    fun `a jump within the file of an editor without the focus goes through the host`() {
        val editor = editor(caret = 34)
        focused = null
        server.definitionAnswer = { listOf(location(file, 2, 4, 3)) }
        definitionAtCaret(editor)
        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(listOf(SourcePosition(file, 2, 4)), opened)
        assertEquals(34, fx.onFx { editor.caret })
    }

    @Test
    fun `a jump from a captured focused editor is recorded and Back returns to its caret`() {
        val editor = editor(caret = 34)
        val start = fx.onFx { intelligence.jumpStart() }
        focused = null
        assertTrue(fx.onFx { intelligence.jump(start, file, 2, 4) }, "opened through the host")
        assertEquals(listOf(SourcePosition(file, 2, 4)), opened)
        assertEquals(listOf(SourcePosition(file, 3, 12)), intelligence.history.places.first)

        assertTrue(fx.onFx { intelligence.back() })
        assertEquals(listOf(SourcePosition(file, 2, 4), SourcePosition(file, 3, 12)), opened)
    }

    @Test
    fun `a jump from a captured editor that closed meanwhile opens without a record`() {
        editor(caret = 34)
        val start = fx.onFx { intelligence.jumpStart() }
        fx.onFx { intelligence.attached.single().close() }
        fx.onFx { intelligence.jump(start, file, 2, 4) }
        assertEquals(listOf(SourcePosition(file, 2, 4)), opened)
        assertEquals(emptyList(), intelligence.history.places.first)
    }

    @Test
    fun `a jump without a focused editor opens without a record`() {
        editor(caret = 34)
        focused = null
        val start = fx.onFx { intelligence.jumpStart() }
        fx.onFx { intelligence.jump(start, file, 2, 4) }
        assertEquals(listOf(SourcePosition(file, 2, 4)), opened)
        assertEquals(emptyList(), intelligence.history.places.first)
    }

    @Test
    fun `a jump in a window where no editor was ever bound goes nowhere and says so`() {
        start()
        val start = fx.onFx { intelligence.jumpStart() }
        assertFalse(fx.onFx { intelligence.jump(start, file, 2, 4) }, "no host to open it through")
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `an answer that arrives after an edit is dropped`() {
        val editor = editor(caret = 34)
        heldDefinition(editor)
        fx.onFx { editor.edit(0, 0, "#\n") }
        server.pendingDefinitions.single().complete(Either.forLeft(listOf(location(file, 2, 4, 3))))
        serverIdle()
        assertNothingShown(editor, caret = 34)
    }

    @Test
    fun `a new request replaces the one on its way`() {
        val other = folder.resolve("lib.py")
        val editor = editor(caret = 34)
        heldDefinition(editor)
        fx.onFx { editor.caret = 8 }
        heldDefinition(editor)
        server.pendingDefinitions[0].complete(Either.forLeft(listOf(location(file, 2, 4, 3))))
        server.pendingDefinitions[1].complete(Either.forLeft(listOf(location(other, 1, 0))))
        serverIdle()
        assertEquals(listOf(SourcePosition(other, 1, 0)), opened)
        assertEquals(8, fx.onFx { editor.caret }, "the first answer did not jump")
        assertEquals(listOf(SourcePosition(file, 0, 8)), intelligence.history.places.first)
    }

    @Test
    fun `hiding the list while usages are asked drops the request`() {
        val editor = editor(caret = 17)
        server.holdReferences = true
        usagesAtCaret(editor)
        awaitTrue("asked") { server.pendingReferences.isNotEmpty() }
        fx.onFx { view.hide() }
        server.pendingReferences.single().complete(listOf(location(file, 3, 11)))
        serverIdle()
        assertNothingShown(editor, caret = 17)
    }

    @Test
    fun `an answer for an editor that was disposed meanwhile is dropped`() {
        val editor = editor(caret = 34)
        heldDefinition(editor)
        fx.onFx { intelligence.attached.single().close() }
        server.pendingDefinitions.single().complete(Either.forLeft(listOf(location(file, 2, 4, 3))))
        serverIdle()
        assertNothingShown(editor, caret = 34)
    }

    private val jdtUri = "jdt://contents/java.base/java.lang/String.class"
    private val classText = "package java.lang;\r\npublic final class String {}\r\n"

    private fun jdt(
        uri: String = jdtUri,
        line: Int = 30,
        column: Int = 7,
    ) = Location(uri, Range(Position(line, column), Position(line, column + 6)))

    private fun answerLibrary(answer: (params: String) -> CompletableFuture<*>) {
        server.customAnswer = { method, params ->
            // Only the document request: the barrier's test/idle is answered null at once.
            when (method) {
                "java/classFileContents" -> answer(params.toString())
                else -> CompletableFuture.completedFuture(null)
            }
        }
    }

    @Test
    fun `a target in a library document is fetched from the server, written to the cache and opened at its position`() {
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(jdt()) }
        answerLibrary { CompletableFuture.completedFuture(classText) }

        definitionAtCaret(editor)

        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(listOf(SourcePosition(cached, 30, 7)), opened)
        assertContentEquals(classText.toByteArray(), cached.readBytes(), "exactly the server's text, \\r\\n kept")
        assertEquals(
            listOf("java/classFileContents {\"uri\":\"$jdtUri\"}"),
            server.customMessages.map { "${it.first} ${it.second}" },
        )
        assertEquals(listOf(SourcePosition(file, 3, 12)), intelligence.history.places.first)
        assertFalse(fx.onFx { view.isShowing })
    }

    @Test
    fun `a library document is listed with local targets in the server's order and opened when chosen`() {
        val editor = editor(caret = 8)
        server.definitionAnswer = { listOf(location(file, 2, 4), jdt()) }
        answerLibrary { CompletableFuture.completedFuture(classText) }

        definitionAtCaret(editor)

        awaitTrue("chooser") { fx.onFx { view.isShowing } }
        assertEquals(
            listOf(SourcePosition(file, 2, 4), SourcePosition(cached, 30, 7)),
            fx.onFx { view.items.map { it.position } },
        )
        fx.onFx { view.choose(1) }
        assertEquals(listOf(SourcePosition(cached, 30, 7)), opened)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["object", "number", "boolean", "array", "null"])
    fun `an answer that is no string drops the target and says no declaration was found`(kind: String) {
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(jdt()) }
        val answer: Any? =
            when (kind) {
                "object" -> mapOf("text" to classText)
                "number" -> 42
                "boolean" -> true
                "array" -> listOf(classText)
                else -> null
            }
        answerLibrary { CompletableFuture.completedFuture(answer) }

        definitionAtCaret(editor)

        awaitTrue("note") { fx.onFx { view.isShowing } }
        assertEquals("No declaration found", fx.onFx { view.placeholder })
        assertFalse(cached.exists())
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a failed request drops the target and says no declaration was found`() {
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(jdt()) }
        answerLibrary { CompletableFuture.failedFuture<Any?>(IllegalStateException("no such class")) }

        definitionAtCaret(editor)

        awaitTrue("note") { fx.onFx { view.isShowing } }
        assertEquals("No declaration found", fx.onFx { view.placeholder })
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a target the spec cannot fetch is dropped without a request`() {
        val editor = editor(caret = 34)
        libraryOf = { null }
        server.definitionAnswer = { listOf(jdt()) }

        definitionAtCaret(editor)

        awaitTrue("note") { fx.onFx { view.isShowing } }
        assertEquals("No declaration found", fx.onFx { view.placeholder })
        assertEquals(emptyList(), server.customMessages.toList())
    }

    @Test
    fun `a text over the limit is refused and the target dropped`() {
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(jdt()) }
        answerLibrary { CompletableFuture.completedFuture("a".repeat(MAX_LIBRARY_DOCUMENT_BYTES + 1)) }

        definitionAtCaret(editor)

        awaitTrue("note") { fx.onFx { view.isShowing } }
        assertEquals("No declaration found", fx.onFx { view.placeholder })
        assertFalse(cached.exists())
    }

    @Test
    fun `a target that fails does not stop the others, which are jumped to`() {
        val editor = editor(caret = 34)
        val good = "jdt://contents/good.class"
        val goodFile = folder.resolve("cache/Good.java")
        libraryOf = { uri -> LibraryDocument("java/classFileContents", mapOf("uri" to uri), goodFile) }
        server.definitionAnswer = { listOf(jdt("jdt://contents/bad.class"), jdt(good, 2, 3)) }
        answerLibrary { params ->
            if ("bad" in params) {
                CompletableFuture.failedFuture<Any?>(IllegalStateException("no such class"))
            } else {
                CompletableFuture.completedFuture(classText)
            }
        }

        definitionAtCaret(editor)

        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(listOf(SourcePosition(goodFile, 2, 3)), opened)
        assertFalse(fx.onFx { view.isShowing }, "one target left: no list")
    }

    @Test
    fun `an answer for an editor disposed while the document was fetched opens nothing and writes nothing`() {
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(jdt()) }
        val held = CompletableFuture<Any?>()
        answerLibrary { held }
        definitionAtCaret(editor)
        awaitTrue("requested") { server.customMessages.isNotEmpty() }

        fx.onFx { intelligence.attached.single().close() }
        serverIdle() // the barrier is answered while the fetch is still open
        held.complete(classText)
        serverIdle()

        assertNothingShown(editor, caret = 34)
        assertFalse(cached.exists())
    }

    /** Asks for the jdt target at the caret with its answer in and its write held; the write waits in [heldWrites]. */
    private fun definitionWithHeldWrite(editor: FakeCodeEditor) {
        server.definitionAnswer = { listOf(jdt()) }
        answerLibrary { CompletableFuture.completedFuture(classText) }
        holdWrites = true
        definitionAtCaret(editor)
        awaitTrue("write held") { heldWrites.isNotEmpty() }
    }

    /** Lets the held write run, and everything it handed to the FX thread. */
    private fun releaseWrites() {
        holdWrites = false
        heldWrites.forEach { it.run() }
        fx.idle()
    }

    @Test
    fun `an edit drops a document whose write is on its way`() {
        val editor = editor(caret = 34)
        definitionWithHeldWrite(editor)

        fx.onFx { editor.edit(0, 0, "#\n") }
        releaseWrites()

        assertNothingShown(editor, caret = 34)
        assertFalse(cached.exists(), "not written either")
    }

    @Test
    fun `Find Usages drops a document whose write is on its way`() {
        val editor = editor(caret = 34)
        definitionWithHeldWrite(editor)
        server.referencesAnswer = { emptyList() }

        usagesAtCaret(editor)
        awaitTrue("usages answered") { fx.onFx { view.placeholder } == "No usages found" }
        releaseWrites()

        assertEquals(emptyList(), opened)
        assertEquals("No usages found", fx.onFx { view.placeholder }, "no stale note over it")
    }

    @Test
    fun `a second request made while a write is held opens only its own target`() {
        val other = folder.resolve("lib.py")
        val editor = editor(caret = 34)
        definitionWithHeldWrite(editor)
        server.definitionAnswer = { listOf(location(other, 1, 0)) }

        definitionAtCaret(editor)
        awaitTrue("second opened") { opened.isNotEmpty() }
        releaseWrites()

        assertEquals(listOf(SourcePosition(other, 1, 0)), opened)
        assertFalse(cached.exists())
    }

    @Test
    fun `a write that fails with a runtime exception drops the target like any failure`() {
        val editor = editor(caret = 34)
        // A root has no parent folder to write into.
        libraryOf = { uri -> LibraryDocument("java/classFileContents", mapOf("uri" to uri), Path.of("/")) }
        server.definitionAnswer = { listOf(jdt()) }
        answerLibrary { CompletableFuture.completedFuture(classText) }

        definitionAtCaret(editor)

        awaitTrue("note") { fx.onFx { view.isShowing } }
        assertEquals("No declaration found", fx.onFx { view.placeholder })
    }

    @Test
    fun `a new request cancels the request of a document fetch at the server`() {
        val other = folder.resolve("lib.py")
        val editor = editor(caret = 34)
        // The document's request is one lsp4j knows (a definition), so that the fake hands its own future to the test:
        // its answers to other methods go through a wrapper of lsp4j that swallows `$/cancelRequest`.
        libraryOf = { _ ->
            val at =
                mapOf(
                    "textDocument" to mapOf("uri" to LspUris.of(file)),
                    "position" to mapOf("line" to 0, "character" to 0),
                )
            LibraryDocument("textDocument/definition", at, cached)
        }
        server.holdDefinitions = true
        definitionAtCaret(editor)
        awaitTrue("definition at the server") { server.pendingDefinitions.size == 1 }
        // Its answer makes the fetch, the second request at the server.
        server.pendingDefinitions[0].complete(Either.forLeft(listOf(jdt())))
        awaitTrue("fetch at the server") { server.pendingDefinitions.size == 2 }

        definitionAtCaret(editor)
        awaitTrue("second definition at the server") { server.pendingDefinitions.size == 3 }
        server.pendingDefinitions[2].complete(Either.forLeft(listOf(location(other, 1, 0))))

        awaitTrue("\$/cancelRequest of the fetch") { server.pendingDefinitions[1].isCancelled }
        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(listOf(SourcePosition(other, 1, 0)), opened)
    }

    @Test
    fun `a new request replaces a document fetch on its way`() {
        val other = folder.resolve("lib.py")
        val editor = editor(caret = 34)
        server.definitionAnswer = { listOf(jdt()) }
        val held = CompletableFuture<Any?>()
        answerLibrary { held }
        definitionAtCaret(editor)
        awaitTrue("requested") { server.customMessages.isNotEmpty() }
        server.definitionAnswer = { listOf(location(other, 1, 0)) }

        definitionAtCaret(editor)
        awaitTrue("second opened") { opened.isNotEmpty() }
        held.complete(classText)
        serverIdle()

        assertEquals(listOf(SourcePosition(other, 1, 0)), opened)
    }

    private val stub get() = folder.resolve("os.pyi")

    private fun twoUsages() {
        server.referencesAnswer = { listOf(location(stub, 4, 0), location(file, 2, 4)) }
    }

    @Test
    fun `the selection drives the preview, once for each position`() {
        val editor = editor(caret = 8)
        twoUsages()
        usagesAtCaret(editor)
        awaitTrue("chooser") { fx.onFx { view.items.isNotEmpty() } }
        // The editor's own file is listed first.
        assertEquals(listOf<SourcePosition?>(SourcePosition(file, 2, 4)), view.previewed, "the first one shows")

        fx.onFx { view.select(1) }
        assertEquals(SourcePosition(stub, 4, 0), view.previewed.last())
        assertEquals(2, view.previewed.size)
        fx.onFx { view.select(1) }
        assertEquals(2, view.previewed.size, "the same position is not shown again")
        fx.onFx { view.onSelected(null) }
        assertEquals(listOf(null), view.previewed.takeLast(1), "no item: the preview is emptied")
    }

    @Test
    fun `line texts arriving for the list do not preview again`() {
        val editor = editor(caret = 8)
        deferLines = true
        twoUsages()
        usagesAtCaret(editor)
        awaitTrue("chooser") { fx.onFx { view.items.isNotEmpty() } }
        fx.onFx { view.select(1) }
        assertEquals(2, view.previewed.size)

        fx.onFx { deferredLines.single()() }
        assertEquals("line 4 of os.pyi", fx.onFx { view.items[1].lineText }, "the update arrived")
        fx.onFx { view.onSelected(view.items[1]) }
        assertEquals(2, view.previewed.size, "the update's copies are the same positions")
    }

    @Test
    fun `a new list previews its first place again, also when the list before showed it`() {
        val editor = editor(caret = 8)
        server.definitionAnswer = { listOf(location(file, 2, 4), location(stub, 4, 0)) }
        definitionAtCaret(editor)
        awaitTrue("chooser") { fx.onFx { view.items.isNotEmpty() } }
        assertEquals(1, view.previewed.size)

        definitionAtCaret(editor)
        awaitTrue("again") { view.previewed.size == 2 }
        assertEquals(SourcePosition(file, 2, 4), view.previewed.last(), "the new list's first place")
    }

    @Test
    fun `line texts that arrive after the list hid are not shown`() {
        val stub = folder.resolve("os.pyi")
        val editor = editor(caret = 8)
        deferLines = true
        server.definitionAnswer = { listOf(location(stub, 4, 0), location(file, 2, 4)) }
        definitionAtCaret(editor)
        awaitTrue("chooser") { fx.onFx { view.isShowing } }
        fx.onFx { view.hide() }
        fx.onFx { deferredLines.single()() }
        assertEquals(null, fx.onFx { view.items.first().lineText }, "the hidden list kept its items")
    }
}
