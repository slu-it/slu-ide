package dev.sluide.codeintelligence.symbols

import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The file structure's rows: nesting flattened in document order, filtering and ranking, the symbol at the caret. */
class SymbolRowsTest {
    private fun range(
        from: Int,
        to: Int,
    ) = Range(Position(from, 0), Position(to, 0))

    private fun symbol(
        name: String,
        from: Int,
        to: Int,
        vararg children: DocumentSymbol,
    ) = DocumentSymbol(name, SymbolKind.Function, range(from, to), range(from, from), null, children.toList())

    private val tree =
        listOf(
            symbol("Console", 0, 20, symbol("__init__", 1, 5), symbol("print_json", 6, 19, symbol("inner", 8, 10))),
            symbol("get_console", 21, 30),
        ).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }

    @Test
    fun `hierarchical symbols become rows depth first with their nesting`() {
        val rows = symbolRows(tree)
        assertEquals(
            listOf("Console" to 0, "__init__" to 1, "print_json" to 1, "inner" to 2, "get_console" to 0),
            rows.map { it.name to it.depth },
        )
        assertEquals(listOf(0, 1, 1, 2, 0), shownSymbols(rows, "").map { it.indent }, "indented while unfiltered")
    }

    @Test
    fun `flat symbol information stays at the top with its container as detail`() {
        val location = Location(LspUris.of(Path.of("/p/a.py").toAbsolutePath()), range(3, 4))
        val rows = symbolRows(listOf(Either.forLeft(SymbolInformation("run", SymbolKind.Method, location, "Console"))))
        assertEquals(listOf(SymbolRow("run", "Console", SymbolKind.Method, 0, range(3, 4), range(3, 4))), rows)
    }

    @Test
    fun `nesting deeper than the limit is left out`() {
        var deepest = symbol("level${MAX_SYMBOL_DEPTH + 5}", 0, 1)
        for (level in MAX_SYMBOL_DEPTH + 4 downTo 0) deepest = symbol("level$level", 0, 1, deepest)
        val rows = symbolRows(listOf(Either.forRight(deepest)))
        assertEquals(MAX_SYMBOL_DEPTH, rows.size)
        assertEquals(MAX_SYMBOL_DEPTH - 1, rows.last().depth)
    }

    @Test
    fun `typing filters by subsequence ignoring case, prefixes first, then part starts, unindented`() {
        val rows = symbolRows(tree)
        assertEquals(
            listOf("inner" to 0, "__init__" to 0, "print_json" to 0),
            shownSymbols(rows, "IN").map { it.row.name to it.indent },
        )
        assertEquals(listOf("Console", "get_console"), shownSymbols(rows, "con").map { it.row.name })
        assertEquals(listOf("print_json"), shownSymbols(rows, " pj ").map { it.row.name })
    }

    @Test
    fun `the symbol at the caret is the innermost whose range holds it`() {
        val rows = symbolRows(tree)
        assertEquals("inner", symbolAt(rows, Position(9, 2))?.name)
        assertEquals("print_json", symbolAt(rows, Position(12, 0))?.name)
        assertEquals("Console", symbolAt(rows, Position(20, 0))?.name, "the end is inside")
        assertNull(symbolAt(rows, Position(40, 0)))
    }
}
