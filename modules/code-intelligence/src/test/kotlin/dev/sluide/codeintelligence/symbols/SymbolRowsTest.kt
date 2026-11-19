package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.completion.KindGroup
import dev.sluide.codeintelligence.completion.kindLook
import dev.sluide.codeintelligence.parsedResult
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.kordamp.ikonli.javafx.FontIcon
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private val pos = """{"line":1,"character":2}"""
    private val jsonRange = """{"start":$pos,"end":$pos}"""
    private val jsonPlace = Range(Position(1, 2), Position(1, 2))

    /** The answer as lsp4j parses it from the server's JSON: `null` entries and absent fields as they come. */
    @Suppress("UNCHECKED_CAST")
    private fun parsed(json: String) =
        parsedResult("textDocument/documentSymbol", json) as List<Either<SymbolInformation, DocumentSymbol>?>?

    @Test
    fun `no answer, an empty one and null entries give no rows besides the valid ones`() {
        assertEquals(emptyList(), symbolRows(null))
        assertEquals(emptyList(), symbolRows(parsed("null")))
        assertEquals(emptyList(), symbolRows(parsed("[]")))
        val rows =
            symbolRows(parsed("""[null,{"name":"a","kind":12,"range":$jsonRange,"selectionRange":$jsonRange},null]"""))
        assertEquals(listOf("a"), rows.map { it.name })
    }

    @Test
    fun `symbol information without a name or a range is left out, one with both stays`() {
        val rows =
            symbolRows(
                parsed(
                    """[
                    {"kind":12,"location":{"uri":"file:///a.py","range":$jsonRange}},
                    {"name":"no range","kind":12,"location":{"uri":"file:///a.py"}},
                    {"name":"ok","kind":6,"containerName":"C","location":{"uri":"file:///a.py","range":$jsonRange}}
                    ]""",
                ),
            )
        assertEquals(listOf(SymbolRow("ok", "C", SymbolKind.Method, 0, jsonPlace, jsonPlace)), rows)
    }

    @Test
    fun `a document symbol keeps its detail and kind, its range stands in for a missing name range`() {
        val rows =
            symbolRows(parsed("""[{"name":"f","kind":12,"detail":"(x) -> int","range":$jsonRange}]"""))
        assertEquals(listOf(SymbolRow("f", "(x) -> int", SymbolKind.Function, 0, jsonPlace, jsonPlace)), rows)
    }

    @Test
    fun `document symbols without a name, children that are null or lack a name or range are left out`() {
        val child = """{"name":"c","kind":6,"range":$jsonRange,"selectionRange":$jsonRange}"""
        val rows =
            symbolRows(
                parsed(
                    """[
                    {"kind":12,"range":$jsonRange,"selectionRange":$jsonRange},
                    {"name":"top","kind":5,"range":$jsonRange,"selectionRange":$jsonRange,
                     "children":[null,{"name":"no range","kind":6},{"kind":6,"range":$jsonRange},$child]},
                    {"name":"alone","kind":5,"range":$jsonRange,"selectionRange":$jsonRange,"children":null}
                    ]""",
                ),
            )
        assertEquals(listOf("top" to 0, "c" to 1, "alone" to 0), rows.map { it.name to it.depth })
    }

    private fun row(
        name: String,
        range: Range,
        depth: Int = 0,
    ) = SymbolRow(name, null, SymbolKind.Function, depth, range, range)

    private fun box(
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
    ) = Range(Position(startLine, startCharacter), Position(endLine, endCharacter))

    @Test
    fun `of symbols holding the caret the one that starts last wins, by line, then column, then depth`() {
        // The expected one comes last: a comparison that cannot tell them apart keeps the first.
        val byLine = listOf(row("outer", box(0, 9, 9, 0)), row("inner", box(2, 0, 4, 0)))
        assertEquals("inner", symbolAt(byLine, Position(3, 0))?.name)
        val byColumn = listOf(row("wide", box(3, 0, 3, 50)), row("narrow", box(3, 10, 3, 20)))
        assertEquals("narrow", symbolAt(byColumn, Position(3, 15))?.name)
        val byDepth = listOf(row("parent", box(1, 0, 2, 0)), row("child", box(1, 0, 2, 0), depth = 1))
        assertEquals("child", symbolAt(byDepth, Position(1, 5))?.name)
        // The line decides before the column: the later line starts at an earlier column.
        val lineFirst = listOf(row("late column", box(1, 30, 9, 0)), row("late line", box(2, 0, 9, 0)))
        assertEquals("late line", symbolAt(lineFirst, Position(5, 0))?.name)
    }

    @ParameterizedTest(name = "caret {0}:{1} in 3:5-5:2: {2}")
    @CsvSource(
        "3, 4, false",
        "3, 5, true",
        "3, 99, true",
        "4, 0, true",
        "5, 2, true",
        "5, 3, false",
        "2, 99, false",
        "6, 0, false",
    )
    fun `a range holds the caret from its start to its end, both included, across lines`(
        line: Int,
        character: Int,
        inside: Boolean,
    ) {
        val rows = listOf(row("r", box(3, 5, 5, 2)))
        assertEquals(if (inside) "r" else null, symbolAt(rows, Position(line, character))?.name)
    }

    @Test
    fun `on a single line a range holds the characters from its start to its end`() {
        val rows = listOf(row("r", box(3, 5, 3, 9)))
        assertNull(symbolAt(rows, Position(3, 4)))
        assertEquals("r", symbolAt(rows, Position(3, 5))?.name)
        assertEquals("r", symbolAt(rows, Position(3, 9))?.name)
        assertNull(symbolAt(rows, Position(3, 10)))
    }

    @Test
    fun `a symbol's icon is its kind's look in completion, none without a kind`() {
        assertNull(symbolIcon(null))
        val struct = symbolIcon(SymbolKind.Struct)
        assertEquals(kindLook(CompletionItemKind.Class)?.icon?.description, struct?.iconLiteral)
        assertTrue(KindGroup.TYPE.styleClass in struct?.styleClass.orEmpty())
        assertTrue(symbolIcon(SymbolKind.Struct) !== struct, "a new node per call")
    }

    @Test
    fun `every kind of symbol has an icon`() {
        assertEquals(emptyList(), SymbolKind.entries.filter { symbolIcon(it) == null })
    }

    @Test
    fun `a file structure row shows the name, the one-line detail, the icon and the indent`() {
        val shown = ShownSymbol(SymbolRow("run", "a\n  b", SymbolKind.Method, 2, jsonPlace, jsonPlace), 2)
        val cell = symbolCell(shown)
        assertEquals("run", cell.label)
        assertEquals("a b", cell.secondary)
        assertEquals(2, cell.indent)
        assertTrue(KindGroup.CALLABLE.styleClass in (cell.icon as FontIcon).styleClass)
        assertNull(symbolCell(ShownSymbol(shown.row.copy(kind = null, detail = null), 0)).icon)
    }
}
