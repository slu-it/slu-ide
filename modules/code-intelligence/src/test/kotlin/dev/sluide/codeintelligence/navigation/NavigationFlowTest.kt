package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FIND_USAGES_KEYS
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.GO_TO_DEFINITION_KEYS
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
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

    private val spec = PySpec()
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
        if (!::fx.isInitialized) {
            fx = FxServers(folder, spec) { NavigationServer(supported) }
            server = fx.fake.awaitRunning(spec) as NavigationServer
            intelligence =
                fx.onFx {
                    CodeIntelligence(fx.servers, FXCollections.observableSet()) {
                        listOf(NavigationFeature(navigation, lines) { view })
                    }.apply { navigation.isFocused = { it.editor === focused } }
                }
        }
        val path = folder.resolve(name)
        path.writeText(text)
        val editor = FakeCodeEditor(path, text, host).apply { this.caret = caret }
        fx.onFx { intelligence.extend(editor) }
        focused = editor
        return editor
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

/** A [TargetView] that records what it shows. */
internal class FakeTargetView : TargetView {
    override var isShowing = false
    override var onChoose: (NavigationTarget) -> Unit = {}
    override var onHidden: () -> Unit = {}
    var range: IntRange? = null
    var items: List<NavigationTarget> = emptyList()
    var placeholder: String? = null
    var note = false

    override fun show(
        range: IntRange,
        items: List<NavigationTarget>,
        placeholder: String,
        note: Boolean,
    ) {
        this.range = range
        this.items = items
        this.placeholder = placeholder
        this.note = note
        isShowing = true
    }

    override fun update(items: List<NavigationTarget>) {
        this.items = items
    }

    override fun hide() {
        if (!isShowing) return
        isShowing = false
        onHidden()
    }

    override fun close() = hide()

    /** Takes the item at [index], as Enter would. */
    fun choose(index: Int) {
        val target = items[index]
        hide()
        onChoose(target)
    }
}
