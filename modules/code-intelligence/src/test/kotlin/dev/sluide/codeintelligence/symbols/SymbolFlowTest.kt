package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * File structure and go to symbol over a fake server, with the search popup as a fake view: the symbols listed with
 * the one at the caret selected, filtering, jumps recorded in Back and Forward, which server go to symbol asks, its
 * requests, and symbols sent with their file only.
 */
internal class SymbolFlowTest : SymbolFlowBase() {
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
}
