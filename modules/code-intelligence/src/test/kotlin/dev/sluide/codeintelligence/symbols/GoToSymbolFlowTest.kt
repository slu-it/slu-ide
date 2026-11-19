package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.FakeCodeEditor
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Go to symbol over a fake server: which editor's server is asked, the search and its cancels and failures, how a
 * symbol is opened or resolved, closing.
 */
internal class GoToSymbolFlowTest : SymbolFlowBase() {
    @Test
    fun `the search selects the first symbol it found`() {
        setUp(caret = 2)
        server.workspaceSymbols = { listOf(located("get_console", other, 3), located("Console", file, 0)) }
        fx.onFx { symbols.open() }
        search("console")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        assertEquals("Console", fx.onFx { symbolView.requested?.name })
    }

    @Test
    fun `symbols that differ in their kind only are all shown`() {
        setUp(caret = 2)
        server.workspaceSymbols = {
            listOf(
                located("Console", file, 0, SymbolKind.Class),
                located("Console", file, 0, SymbolKind.Variable),
                located("Console", file, 0, SymbolKind.Class),
            )
        }
        fx.onFx { symbols.open() }
        search("console")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        assertEquals(listOf(SymbolKind.Class, SymbolKind.Variable), fx.onFx { symbolView.items.map { it.kind } })
    }

    @Test
    fun `the symbol source skips closed bindings and takes the next one that answers`() {
        setUp(caret = 2)
        val second = FakeCodeEditor(other, text, host)
        fx.onFx { intelligence.extend(second) }
        val bindings = fx.onFx { intelligence.attached.mapNotNull { it.binding } }
        val first = bindings.single { it.file == file }
        val next = bindings.single { it.file == other }
        assertSame(first, fx.onFx { symbolSource(first, bindings) }, "open bindings: the focused one")
        assertSame(next, fx.onFx { symbolSource(next, bindings) })

        fx.onFx { intelligence.attached.single { it.binding === first }.close() }
        assertSame(next, fx.onFx { symbolSource(first, bindings) }, "the focused one is closed")
        assertSame(next, fx.onFx { symbolSource(null, bindings) })
        fx.onFx { intelligence.attached.single { it.binding === next }.close() }
        assertNull(fx.onFx { symbolSource(first, bindings) })
    }

    @Test
    fun `go to symbol asks the focused editor's server also when the window lists no bindings`() {
        setUp(caret = 2)
        val alone =
            fx.onFx {
                GoToSymbol(
                    intelligence.navigation,
                    { emptyList() },
                    FakeSearchView(),
                    200.milliseconds,
                    timers::schedule,
                )
            }
        assertNull(fx.onFx { alone.blocker() })
        assertTrue(fx.onFx { alone.open() })
        fx.onFx { alone.close() }
    }

    @Test
    fun `opening again while it shows for the same editor keeps the search`() {
        setUp(caret = 2)
        server.workspaceSymbols = { listOf(located("Console", file, 0)) }
        assertTrue(fx.onFx { symbols.open() })
        search("console")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        assertEquals(SymbolSearch.NONE, fx.onFx { symbolView.placeholder })

        assertTrue(fx.onFx { symbols.open() })
        assertEquals(SymbolSearch.NONE, fx.onFx { symbolView.placeholder }, "no new search")
        assertEquals(emptyList(), timers.due)
        assertEquals(listOf("console"), server.symbolQueries)
    }

    @Test
    fun `without a place for the popup go to symbol searches nothing`() {
        setUp(caret = 2)
        symbolView.hasPlace = false
        assertFalse(fx.onFx { symbols.open() })
        assertEquals(emptyList(), timers.due)
    }

    @Test
    fun `a query typed before the popup opened for a server finds nothing and says why`() {
        setUp(caret = 2)
        fx.onFx { symbolView.type("console") }
        fx.onFx { timers.runDue() }
        assertEquals(emptyList(), fx.onFx { symbolView.items })
        assertEquals("no language server running", fx.onFx { symbolView.placeholder })
        assertEquals(emptyList(), server.symbolQueries)
    }

    @Test
    fun `a failing search shows the failure and no rows`() {
        setUp(caret = 2)
        server.failure = failure("boom")
        fx.onFx { symbols.open() }
        search("console")
        awaitTrue("the failure") { fx.onFx { symbolView.placeholder } == "Go to Symbol failed: boom" }
        assertEquals(emptyList(), fx.onFx { symbolView.items })
    }

    @Test
    fun `hiding the popup drops the search waiting and cancels the one on its way`() {
        setUp(caret = 2)
        server.holdWorkspaceSymbols = true
        fx.onFx { symbols.open() }
        fx.onFx { symbolView.type("con") }
        assertEquals(1, timers.due.size)
        fx.onFx { symbolView.hide() }
        assertEquals(emptyList(), timers.due)

        fx.onFx { symbols.open() }
        search("con")
        awaitTrue("the request") { server.heldWorkspaceSymbols.size == 1 }
        fx.onFx { symbolView.hide() }
        awaitTrue("the cancel") { server.heldWorkspaceSymbols[0].isCancelled }
    }

    @Test
    fun `typing on cancels the question on its way`() {
        setUp(caret = 2)
        server.holdWorkspaceSymbols = true
        fx.onFx { symbols.open() }
        search("con")
        awaitTrue("the request") { server.heldWorkspaceSymbols.size == 1 }
        fx.onFx { symbolView.type("cons") }
        awaitTrue("the cancel") { server.heldWorkspaceSymbols[0].isCancelled }
    }

    @Test
    fun `closing go to symbol drops its search and hides the popup, cancelling a resolve on its way`() {
        setUp(caret = 2) { SymbolServer(resolves = true) }
        server.holdResolves = true
        server.workspaceSymbols = { listOf(fileOnly()) }
        fx.onFx { symbols.open() }
        fx.onFx { symbolView.type("main") }
        fx.onFx { symbols.close() }
        assertEquals(emptyList(), timers.due)
        assertFalse(fx.onFx { symbolView.isShowing })

        fx.onFx { symbols.open() }
        search("main")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        fx.onFx { symbolView.choose(symbolView.items.single()) }
        awaitTrue("the resolve") { server.heldResolves.size == 1 }
        fx.onFx { symbols.close() }
        awaitTrue("the cancel") { server.heldResolves[0].isCancelled }
    }

    @Test
    fun `opening again cancels the resolve of the symbol taken before`() {
        setUp(caret = 2) { SymbolServer(resolves = true) }
        server.holdResolves = true
        server.workspaceSymbols = { listOf(fileOnly()) }
        fx.onFx { symbols.open() }
        search("main")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        fx.onFx { symbolView.choose(symbolView.items.single()) }
        awaitTrue("the resolve") { server.heldResolves.size == 1 }
        fx.onFx { symbols.open() }
        awaitTrue("the cancel") { server.heldResolves[0].isCancelled }
        assertEquals(emptyList(), opened)
    }

    /** Takes the file-only symbol `main` of a server that resolves and answers as [configure] sets; what opened. */
    private fun chooseResolving(
        nulls: Boolean = false,
        configure: SymbolServer.() -> Unit,
    ): List<SourcePosition> {
        setUp(caret = 2) { SymbolServer(resolves = true).apply { serializeNulls = nulls } }
        server.configure()
        server.workspaceSymbols = { listOf(fileOnly()) }
        fx.onFx { symbols.open() }
        search("main")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        fx.onFx { symbolView.choose(symbolView.items.single()) }
        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(1, server.resolved.size, "the resolve was asked")
        return opened.toList()
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = ["failure", "null", "uri only", "remote file", "no start", "no uri", "null range", "no location"],
    )
    fun `a symbol whose resolve gives no place opens at the first line of its file`(answer: String) {
        val places =
            chooseResolving(nulls = answer in setOf("null range", "no location")) {
                when (answer) {
                    "failure" -> {
                        resolveFailure = failure("boom")
                    }

                    "null" -> {
                        resolveAnswer = { null }
                    }

                    "uri only" -> {
                        resolveAnswer =
                            {
                                WorkspaceSymbol(
                                    it.name,
                                    it.kind,
                                    Either.forRight(WorkspaceSymbolLocation(LspUris.of(other))),
                                )
                            }
                    }

                    "remote file" -> {
                        resolveAnswer =
                            {
                                WorkspaceSymbol(
                                    it.name,
                                    it.kind,
                                    Either.forLeft(Location("https://a/b.py", range(7, 4, 8))),
                                )
                            }
                    }

                    "no start" -> {
                        resolveAnswer =
                            { WorkspaceSymbol(it.name, it.kind, Either.forLeft(Location(LspUris.of(other), Range()))) }
                    }

                    "null range" -> {
                        // With null fields on the wire the location parses as a Location whose range is null.
                        resolveAnswer =
                            {
                                WorkspaceSymbol(
                                    it.name,
                                    it.kind,
                                    Either.forLeft(Location().apply { uri = LspUris.of(other) }),
                                )
                            }
                    }

                    "no location" -> {
                        resolveAnswer = { WorkspaceSymbol().apply { name = it.name } }
                    }

                    else -> {
                        resolveAnswer =
                            {
                                WorkspaceSymbol(
                                    it.name,
                                    it.kind,
                                    Either.forLeft(Location().apply { range = range(7, 4, 8) }),
                                )
                            }
                    }
                }
            }
        assertEquals(listOf(SourcePosition(other, 0, 0)), places)
    }

    @Test
    fun `a symbol taken after the popup's editor closed opens at its first line without a resolve`() {
        setUp(caret = 2) { SymbolServer(resolves = true) }
        server.workspaceSymbols = { listOf(fileOnly()) }
        fx.onFx { symbols.open() }
        search("main")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        val row = fx.onFx { symbolView.items.single() }
        fx.onFx { intelligence.attached.single().close() }
        fx.onFx { symbolView.onChoose(row) }
        awaitTrue("opened") { opened.isNotEmpty() }
        assertEquals(listOf(SourcePosition(other, 0, 0)), opened)
        assertEquals(emptyList(), server.resolved)
    }

    @Test
    fun `a symbol without its symbol object or taken before any server was asked opens at its first line`() {
        setUp(caret = 2) { SymbolServer(resolves = true) }
        val bare = WorkspaceSymbolRow("main", null, SymbolKind.Function, other, null, null)
        fx.onFx { symbolView.onChoose(bare) }
        assertEquals(listOf(SourcePosition(other, 0, 0)), opened, "no server asked yet")

        fx.onFx { symbols.open() }
        fx.onFx { symbolView.onChoose(bare) }
        assertEquals(listOf(SourcePosition(other, 0, 0), SourcePosition(other, 0, 0)), opened, "no symbol to resolve")
        assertEquals(emptyList(), server.resolved)
    }

    @Test
    fun `closing go to symbol drops its waiting search also when the popup went away unannounced`() {
        setUp(caret = 2)
        fx.onFx { symbols.open() }
        fx.onFx { symbolView.type("con") }
        fx.onFx { symbolView.isShowing = false }
        fx.onFx { symbols.close() }
        assertEquals(emptyList(), timers.due)
    }

    @Test
    fun `opening for another editor while the popup shows starts over from that editor`() {
        setUp(caret = 2)
        val second = FakeCodeEditor(other, text, host).apply { caret = 5 }
        fx.onFx { intelligence.extend(second) }
        var focusedFile = file
        fx.onFx { intelligence.navigation.isFocused = { it.file == focusedFile } }
        server.workspaceSymbols = { listOf(located("Console", folder.resolve("c.py"), 3)) }
        assertTrue(fx.onFx { symbols.open() })
        focusedFile = other
        assertTrue(fx.onFx { symbols.open() })
        search("console")
        awaitTrue("rows") { fx.onFx { symbolView.items.isNotEmpty() } }
        fx.onFx { symbolView.choose(symbolView.items.single()) }
        assertEquals(listOf(SourcePosition(folder.resolve("c.py"), 3, 4)), opened)
        assertEquals(listOf(SourcePosition(other, 0, 5)), intelligence.history.places.first, "from the second editor")
    }
}
