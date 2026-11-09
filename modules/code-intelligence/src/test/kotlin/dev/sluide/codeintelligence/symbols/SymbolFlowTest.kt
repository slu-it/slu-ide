package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.CodeIntelligence
import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.TestTimers
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.codeintelligence.ui.SearchView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.LspUris
import javafx.collections.FXCollections
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * File structure and go to symbol over a fake server, with the search popup as a fake view: the symbols listed with
 * the one at the caret selected, filtering, jumps recorded in Back and Forward, which server go to symbol asks, its
 * requests, and symbols sent with their file only.
 */
class SymbolFlowTest {
    @TempDir
    lateinit var folder: Path

    private val spec = PySpec()
    private lateinit var fx: FxServers
    private lateinit var server: SymbolServer
    private lateinit var intelligence: CodeIntelligence
    private lateinit var editor: FakeCodeEditor
    private val structureView = FakeSearchView<ShownSymbol>()
    private val symbolView = FakeSearchView<WorkspaceSymbolRow>()
    private val notes = CopyOnWriteArrayList<Pair<Int, String>>()
    private val opened = CopyOnWriteArrayList<SourcePosition>()
    private val timers = TestTimers()
    private var focused = true
    private lateinit var symbols: GoToSymbol

    private val text = "class Console:\n    def run(self):\n        pass\n\ndef main():\n    pass\n"
    private val file get() = folder.resolve("a.py")
    private val other get() = folder.resolve("pkg/b.py")

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

    @AfterEach
    fun close() {
        fx.onFx {
            symbols.close()
            intelligence.close()
        }
        fx.close()
    }

    /** A bound editor of `a.py` with the caret at [caret] over a server with [newServer]'s symbols. */
    private fun setUp(
        caret: Int,
        bind: Boolean = true,
        newServer: () -> SymbolServer = { SymbolServer() },
    ) {
        fx = FxServers(folder, spec) { newServer() }
        server = fx.fake.awaitRunning(spec) as SymbolServer
        val structure =
            BindingFeature { binding ->
                val shown =
                    FileStructure(binding, navigation(), structureView) { offset, note ->
                        notes +=
                            offset to note
                    }
                val registered = navigation().registerStructure(binding, shown::show)
                AutoCloseable {
                    registered.close()
                    shown.close()
                }
            }
        intelligence =
            fx.onFx {
                CodeIntelligence(fx.servers, FXCollections.observableSet()) { listOf(structure) }.apply {
                    navigation.isFocused = { focused }
                }
            }
        symbols =
            fx.onFx {
                GoToSymbol(
                    intelligence.navigation,
                    { intelligence.attached.mapNotNull { it.binding } },
                    symbolView,
                    200.milliseconds,
                    timers::schedule,
                )
            }
        file.writeText(text)
        editor = FakeCodeEditor(file, text, host).apply { this.caret = caret }
        if (bind) fx.onFx { intelligence.extend(editor) }
    }

    private fun navigation() = intelligence.navigation

    private fun range(
        line: Int,
        start: Int,
        end: Int = start,
    ) = Range(Position(line, start), Position(line, end))

    private fun symbol(
        name: String,
        from: Int,
        to: Int,
        nameColumn: Int,
        vararg children: DocumentSymbol,
    ) = DocumentSymbol(
        name,
        SymbolKind.Function,
        Range(Position(from, 0), Position(to, 0)),
        range(from, nameColumn, nameColumn + name.length),
        null,
        children.toList(),
    )

    private val tree =
        listOf(symbol("Console", 0, 3, 6, symbol("run", 1, 3, 8)), symbol("main", 4, 6, 4))

    @Test
    fun `file structure lists the symbols nested, the one at the caret selected, and filters by typing`() {
        setUp(caret = 37)
        server.documentSymbols = tree
        assertTrue(fx.onFx { intelligence.fileStructure() })
        assertTrue(fx.onFx { structureView.isShowing })
        awaitTrue("symbols") { fx.onFx { structureView.items.isNotEmpty() } }
        fx.onFx {
            assertEquals(
                listOf("Console" to 0, "run" to 1, "main" to 0),
                structureView.items.map {
                    it.row.name to
                        it.indent
                },
            )
            assertEquals("run", structureView.selected?.row?.name, "the symbol at the caret")
            assertEquals("No symbols", structureView.placeholder)
            structureView.type("m")
            assertEquals(listOf("main" to 0), structureView.items.map { it.row.name to it.indent })
            structureView.type("")
            assertEquals("run", structureView.selected?.row?.name, "the caret's symbol again once the filter is gone")
        }
    }

    @Test
    fun `taking a symbol moves the caret to its name and Back returns`() {
        setUp(caret = 2)
        server.documentSymbols = tree
        fx.onFx { intelligence.fileStructure() }
        awaitTrue("symbols") { fx.onFx { structureView.items.isNotEmpty() } }
        fx.onFx { structureView.choose(structureView.items.last()) }
        assertEquals(text.indexOf("main"), fx.onFx { editor.caret })
        assertEquals(listOf(SourcePosition(file, 0, 2)), intelligence.history.places.first)
        assertTrue(fx.onFx { intelligence.back() })
        assertEquals(2, fx.onFx { editor.caret })
    }

    @Test
    fun `a server without document symbols gets no request, the note and the menu say why`() {
        setUp(caret = 5) { SymbolServer(symbols = false) }
        assertEquals("not supported", fx.onFx { intelligence.fileStructureBlocker() })
        fx.onFx { intelligence.fileStructure() }
        assertEquals(listOf(5 to "File Structure is not available (not supported)"), notes)
        assertFalse(fx.onFx { structureView.isShowing })
    }

    @Test
    fun `go to symbol asks after the delay with the typed query and shows exact and prefix matches first`() {
        setUp(caret = 2)
        server.workspaceSymbols = { listOf(located("get_console", other, 3), located("Console", file, 0)) }
        assertTrue(fx.onFx { symbols.open() })
        assertEquals(SymbolSearch.TYPE, fx.onFx { symbolView.placeholder })
        fx.onFx { symbolView.type("console") }
        assertEquals(emptyList(), server.symbolQueries, "not before the delay")
        fx.onFx { timers.runDue() }
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        assertEquals(listOf("console"), server.symbolQueries)
        assertEquals(listOf("Console", "get_console"), fx.onFx { symbolView.items.map { it.name } })

        fx.onFx { symbolView.choose(symbolView.items.last()) }
        assertEquals(listOf(SourcePosition(other, 3, 4)), opened)
        assertEquals(listOf(SourcePosition(file, 0, 2)), intelligence.history.places.first, "from the caret")
    }

    @Test
    fun `go to symbol shows a symbol the server sends twice once, and keeps symbols that differ in place or name`() {
        setUp(caret = 2)
        server.workspaceSymbols = {
            listOf(
                located("Console", file, 0),
                located("Console", file, 0),
                located("Console", file, 4),
                located("Console", other, 0),
                located("ConsoleX", file, 0),
            )
        }
        fx.onFx { symbols.open() }
        fx.onFx { symbolView.type("console") }
        fx.onFx { timers.runDue() }
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }

        assertEquals(
            listOf(file to 0, file to 4, other to 0, file to 0),
            fx.onFx { symbolView.items.map { it.file to it.position?.line } },
        )
    }

    @Test
    fun `go to symbol keeps same-named symbols of different containers sent with their file only`() {
        setUp(caret = 2)

        fun fileOnly(container: String) =
            WorkspaceSymbol(
                "run",
                SymbolKind.Method,
                Either.forRight(WorkspaceSymbolLocation(LspUris.of(other))),
            ).apply { containerName = container }
        server.workspaceSymbols = { listOf(fileOnly("A"), fileOnly("B"), fileOnly("A")) }
        fx.onFx { symbols.open() }
        fx.onFx { symbolView.type("run") }
        fx.onFx { timers.runDue() }
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }

        assertEquals(listOf("A", "B"), fx.onFx { symbolView.items.map { it.container } })
    }

    @Test
    fun `go to symbol asks a running server also when no bound editor has the focus, and records no way back then`() {
        setUp(caret = 2)
        focused = false
        server.workspaceSymbols = { listOf(located("main", other, 1)) }
        assertNull(fx.onFx { symbols.blocker() })
        assertTrue(fx.onFx { symbols.open() })
        fx.onFx { symbolView.type("main") }
        fx.onFx { timers.runDue() }
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        fx.onFx { symbolView.choose(symbolView.items.single()) }
        assertEquals(listOf(SourcePosition(other, 1, 4)), opened)
        assertEquals(emptyList(), intelligence.history.places.first)
    }

    @Test
    fun `without a server to ask go to symbol shows a note and the menu item says why`() {
        setUp(caret = 2) { SymbolServer(symbols = false) }
        assertEquals("not supported", fx.onFx { symbols.blocker() })
        assertFalse(fx.onFx { symbols.open() })
        assertEquals("Go to Symbol is not available (not supported)", fx.onFx { symbolView.note })

        focused = false
        assertEquals("no language server running", fx.onFx { symbols.blocker() })
    }

    @Test
    fun `a symbol sent with its file only is resolved if the server offers it, else opened at its first line`() {
        setUp(caret = 2) { SymbolServer(resolves = true) }
        val fileOnly =
            WorkspaceSymbol("main", SymbolKind.Function, Either.forRight(WorkspaceSymbolLocation(LspUris.of(other))))
        server.workspaceSymbols = { listOf(fileOnly) }
        server.resolveAnswer =
            { WorkspaceSymbol(it.name, it.kind, Either.forLeft(Location(LspUris.of(other), range(7, 4, 8)))) }
        fx.onFx { symbols.open() }
        fx.onFx { symbolView.type("main") }
        fx.onFx { timers.runDue() }
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        fx.onFx { symbolView.choose(symbolView.items.single()) }
        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(listOf(SourcePosition(other, 7, 4)), opened)
        assertEquals(listOf("main"), server.resolved.map { it.name })
    }

    @Test
    fun `without resolve a symbol sent with its file only opens at its first line`() {
        setUp(caret = 2)
        val fileOnly =
            WorkspaceSymbol("main", SymbolKind.Function, Either.forRight(WorkspaceSymbolLocation(LspUris.of(other))))
        server.workspaceSymbols = { listOf(fileOnly) }
        fx.onFx { symbols.open() }
        fx.onFx { symbolView.type("main") }
        fx.onFx { timers.runDue() }
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        fx.onFx { symbolView.choose(symbolView.items.single()) }
        assertEquals(listOf(SourcePosition(other, 0, 0)), opened)
        assertEquals(emptyList(), server.resolved)
    }

    private fun located(
        name: String,
        path: Path,
        line: Int,
    ) = WorkspaceSymbol(
        name,
        SymbolKind.Class,
        Either.forLeft(Location(LspUris.of(path), range(line, 4, 4 + name.length))),
    )
}

/** A [SearchView] that keeps what it shows; the test types and chooses. */
internal class FakeSearchView<T> : SearchView<T> {
    override var isShowing = false
    override var query = ""
    override var placeholder = ""
    override var onQuery: (String) -> Unit = {}
    override var onChoose: (T) -> Unit = {}
    override var onHidden: () -> Unit = {}
    var items: List<T> = emptyList()
    var selected: T? = null
    var note: String? = null

    override fun open(): Boolean {
        if (!isShowing) query = ""
        isShowing = true
        return true
    }

    override fun setItems(
        items: List<T>,
        selected: T?,
    ) {
        this.items = items
        this.selected = selected?.takeIf { it in items } ?: this.selected?.takeIf { it in items } ?: items.firstOrNull()
    }

    override fun note(text: String): Boolean {
        note = text
        return true
    }

    /** The user typed so that the field holds [text]. */
    fun type(text: String) {
        query = text
        onQuery(text)
    }

    /** The user took [item]: the popup hides first. */
    fun choose(item: T) {
        hide()
        onChoose(item)
    }

    override fun hide() {
        if (!isShowing) return
        isShowing = false
        onHidden()
    }

    override fun close() = hide()
}
