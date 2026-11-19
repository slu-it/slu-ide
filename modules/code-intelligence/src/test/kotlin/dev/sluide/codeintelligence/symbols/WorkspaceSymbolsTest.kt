package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.TestTimers
import dev.sluide.codeintelligence.completion.KindGroup
import dev.sluide.codeintelligence.parsedResult
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.kordamp.ikonli.javafx.FontIcon
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Go to symbol without a server: the rows of an answer, their order, the cell, and when the server is asked. */
class WorkspaceSymbolsTest {
    private val folder = Path.of("/work/project").toAbsolutePath()
    private val file = folder.resolve("pkg/console.py")
    private val at = Range(Position(4, 6), Position(4, 13))

    private fun row(
        name: String,
        kind: SymbolKind = SymbolKind.Class,
    ) = WorkspaceSymbolRow(name, null, kind, file, Position(0, 0))

    @Test
    fun `symbol information and workspace symbols become rows, a file-only location without a position`() {
        val info = SymbolInformation("Console", SymbolKind.Class, Location(LspUris.of(file), at), "rich.console")
        assertEquals(
            listOf(WorkspaceSymbolRow("Console", "rich.console", SymbolKind.Class, file, Position(4, 6))),
            workspaceSymbolRows(Either.forLeft(listOf(info))),
        )
        val located = WorkspaceSymbol("Console", SymbolKind.Class, Either.forLeft(Location(LspUris.of(file), at)))
        val fileOnly =
            WorkspaceSymbol("run", SymbolKind.Function, Either.forRight(WorkspaceSymbolLocation(LspUris.of(file))))
        val remote =
            WorkspaceSymbol("x", SymbolKind.Function, Either.forRight(WorkspaceSymbolLocation("https://a/b.py")))
        assertEquals(
            listOf(
                WorkspaceSymbolRow("Console", null, SymbolKind.Class, file, Position(4, 6), located),
                WorkspaceSymbolRow("run", null, SymbolKind.Function, file, null, fileOnly),
            ),
            workspaceSymbolRows(Either.forRight(listOf(located, fileOnly, remote))),
        )
    }

    @Test
    fun `exact names come first, then prefixes, the same case first, each in the server's order, at most the cap`() {
        val rows = listOf("get_console", "Console", "console_log", "ConsoleOptions", "console").map { row(it) }
        assertEquals(
            listOf("console", "Console", "console_log", "ConsoleOptions", "get_console"),
            rankWorkspaceSymbols(rows, "console").map { it.name },
        )
        assertEquals(listOf("console", "Console"), rankWorkspaceSymbols(rows, " console ", cap = 2).map { it.name })
        assertEquals(
            listOf("Console", "console", "ConsoleOptions", "console_log", "get_console"),
            rankWorkspaceSymbols(rows, "Console").map { it.name },
        )
    }

    @Test
    fun `within a match types and functions come before variables, as ty lists many console variables first`() {
        val rows =
            listOf(
                row("console", SymbolKind.Variable),
                row("console", SymbolKind.Variable),
                row("console", SymbolKind.Module),
                row("Console", SymbolKind.Class),
            )
        assertEquals(
            listOf(SymbolKind.Module, SymbolKind.Variable, SymbolKind.Variable, SymbolKind.Class),
            rankWorkspaceSymbols(rows, "console").map { it.kind },
        )
        assertEquals(SymbolKind.Class, rankWorkspaceSymbols(rows, "Console").first().kind)
    }

    @Test
    fun `a row shows its name, then its container and project-relative path`() {
        // Without a kind: an icon is a JavaFX node, which needs the toolkit (the UI tests show them).
        val cell = workspaceSymbolCell(WorkspaceSymbolRow("run", "Console", null, file, null), folder)
        assertEquals("run", cell.label)
        assertEquals("Console · pkg/console.py", cell.secondary)
        val outside = Path.of("/opt/lib.py").toAbsolutePath()
        assertEquals(
            outside.toString(),
            workspaceSymbolCell(WorkspaceSymbolRow("x", " ", null, outside, null), folder).secondary,
        )
    }

    /** A search over [timers] whose questions wait in [asked] for the test to answer. */
    private class Search {
        val timers = TestTimers()
        val asked = mutableListOf<Pair<String, (List<WorkspaceSymbolRow>, String?) -> Unit>>()
        val dropped = mutableListOf<String>()
        val shown = mutableListOf<Pair<List<String>, String>>()
        val search =
            SymbolSearch(
                ask = { query, done ->
                    asked += query to done
                    AutoCloseable { dropped += query }
                },
                show = { rows, placeholder -> shown += rows.map { it.name } to placeholder },
                delay = 200.milliseconds,
                schedule = timers::schedule,
            )
    }

    @Test
    fun `typing asks once the delay after the last change, with the latest query`() {
        val s = Search()
        s.search.query("C")
        s.search.query("Co")
        s.search.query("Con")
        assertEquals(listOf(200.milliseconds), s.timers.due.map { it.first }, "one question waits")
        assertEquals(emptyList(), s.asked)
        s.timers.runDue()
        assertEquals(listOf("Con"), s.asked.map { it.first })
        assertEquals(emptyList<String>() to SymbolSearch.SEARCHING, s.shown.last())

        s.asked.single().second(listOf(row("get_console"), row("Console")), null)
        assertEquals(listOf("Console", "get_console") to SymbolSearch.NONE, s.shown.last())
    }

    @Test
    fun `the latest question wins, an answer to an earlier one is dropped and not shown`() {
        val s = Search()
        s.search.query("Con")
        s.timers.runDue()
        s.search.query("Cons")
        assertEquals(listOf("Con"), s.dropped, "the question on its way is dropped")
        s.timers.runDue()
        val shownBefore = s.shown.size
        s.asked.first().second(listOf(row("Console")), null)
        assertEquals(shownBefore, s.shown.size, "the old answer shows nothing")

        s.asked.last().second(emptyList(), "Go to Symbol failed: boom")
        assertEquals(emptyList<String>() to "Go to Symbol failed: boom", s.shown.last())
    }

    @Test
    fun `a blank query asks nothing and says what to type, and stopping drops what waits`() {
        val s = Search()
        s.search.query("  ")
        assertEquals(emptyList(), s.timers.due)
        assertEquals(emptyList<String>() to SymbolSearch.TYPE, s.shown.single())

        s.search.query("Con")
        s.search.stop()
        assertEquals(emptyList(), s.timers.due)
        s.search.query("Con")
        s.timers.runDue()
        s.search.stop()
        val shownBefore = s.shown.size
        s.asked.single().second(listOf(row("Console")), null)
        assertEquals(shownBefore, s.shown.size, "an answer after stopping shows nothing")
    }

    private val place = """{"line":4,"character":6}"""
    private val jsonRange = """{"start":$place,"end":$place}"""
    private val uri = LspUris.of(file)

    /** The answer as lsp4j parses it from the server's JSON: absent fields and empty ranges as they come. */
    @Suppress("UNCHECKED_CAST")
    private fun parsed(json: String) =
        parsedResult("workspace/symbol", json) as Either<out List<SymbolInformation?>?, out List<WorkspaceSymbol?>?>?

    @Test
    fun `no answer gives no rows`() {
        assertEquals(emptyList(), workspaceSymbolRows(null))
        assertEquals(emptyList(), workspaceSymbolRows(parsed("null")))
        assertEquals(emptyList(), workspaceSymbolRows(parsed("[]")))
    }

    @Test
    fun `workspace symbols without a location, a file or a name are left out, an empty range gives no position`() {
        val rows =
            workspaceSymbolRows(
                parsed(
                    """[
                    {"name":"no location","kind":5},
                    {"kind":5,"location":{"uri":"$uri","range":$jsonRange}},
                    {"name":"no uri","kind":5,"location":{"range":$jsonRange}},
                    {"name":"remote","kind":5,"location":{"uri":"https://a/b.py","range":$jsonRange}},
                    {"name":"no start","kind":5,"location":{"uri":"$uri","range":{}}},
                    {"name":"null range","kind":5,"location":{"uri":"$uri","range":null}},
                    {"name":"placed","kind":6,"containerName":"C","location":{"uri":"$uri","range":$jsonRange}}
                    ]""",
                ),
            )
        assertEquals(
            listOf("no start" to null, "null range" to null, "placed" to Position(4, 6)),
            rows.map { it.name to it.position },
        )
        assertEquals("C", rows.last().container)
        assertEquals(SymbolKind.Method, rows.last().kind)
    }

    @Test
    fun `symbol information without a location, a file, a name or a range is left out or has no position`() {
        // lsp4j's setters refuse null; its parser, which sets the fields, does not.
        fun info(json: String): SymbolInformation =
            MessageJsonHandler(emptyMap()).gson.fromJson(json, SymbolInformation::class.java)
        val range = """{"start":$place,"end":$place}"""
        val rows =
            workspaceSymbolRows(
                Either.forLeft(
                    listOf(
                        null,
                        info("""{"name":"no location","kind":5}"""),
                        info("""{"name":"no uri","kind":5,"location":{"range":$range}}"""),
                        info("""{"name":"remote","kind":5,"location":{"uri":"https://a/b.py","range":$range}}"""),
                        info("""{"kind":5,"location":{"uri":"$uri","range":$range}}"""),
                        info("""{"name":"no range","kind":5,"location":{"uri":"$uri"}}"""),
                        info("""{"name":"placed","kind":5,"location":{"uri":"$uri","range":$range}}"""),
                    ),
                ),
            )
        assertEquals(listOf("no range" to null, "placed" to Position(4, 6)), rows.map { it.name to it.position })
    }

    @Test
    fun `names rank by exactness then prefix, the same case first, and declarations before variables in each`() {
        // Reversed: the server sends the worst first, a variable before its declaration in each group.
        val names = listOf("xcon", "ConA", "conA", "Con", "con")
        val rows =
            names.flatMap { name ->
                listOf(row(name, SymbolKind.Variable), row(name, SymbolKind.Class), row(name, SymbolKind.Constant))
            }
        assertEquals(
            listOf("con", "Con", "conA", "ConA", "xcon").flatMap { name ->
                listOf(SymbolKind.Class, SymbolKind.Variable, SymbolKind.Constant).map { name to it }
            },
            rankWorkspaceSymbols(rows, "con").map { it.name to it.kind },
        )
    }

    @Test
    fun `hundreds of unranked rows keep their server order within a rank, also past 32 rows`() {
        // Four blocks of 8 declarations: prefix matches, exact names, prefix matches, exact names.
        val names = (0 until 8).map { "xa$it" } + List(8) { "x" } + (0 until 8).map { "xb$it" } + List(8) { "x" }
        val rows =
            names.mapIndexed { i, name -> WorkspaceSymbolRow(name, "c$i", SymbolKind.Function, file, Position(i, 0)) }
        assertEquals(
            rows.filter { it.name == "x" } + rows.filter { it.name != "x" },
            rankWorkspaceSymbols(rows, "x"),
        )
    }

    @Test
    fun `each kind of declaration ranks before a variable`() {
        val declarations =
            listOf(
                SymbolKind.Class,
                SymbolKind.Interface,
                SymbolKind.Struct,
                SymbolKind.Enum,
                SymbolKind.Function,
                SymbolKind.Method,
                SymbolKind.Constructor,
                SymbolKind.Module,
                SymbolKind.Namespace,
                SymbolKind.Package,
            )
        for (kind in declarations) {
            val ranked = rankWorkspaceSymbols(listOf(row("x", SymbolKind.Variable), row("x", kind)), "x")
            assertEquals(listOf(kind, SymbolKind.Variable), ranked.map { it.kind })
        }
    }

    @Test
    fun `a row with a kind has its icon, one without a container shows the path alone`() {
        val cell = workspaceSymbolCell(WorkspaceSymbolRow("Console", null, SymbolKind.Class, file, null), folder)
        assertEquals("pkg/console.py", cell.secondary)
        assertTrue(KindGroup.TYPE.styleClass in (cell.icon as FontIcon).styleClass)
    }

    @Test
    fun `closing the search drops what waits and what is on its way`() {
        val s = Search()
        s.search.query("Con")
        s.search.close()
        assertEquals(emptyList(), s.timers.due)

        s.search.query("Con")
        s.timers.runDue()
        s.search.close()
        assertEquals(listOf("Con"), s.dropped)
        val shownBefore = s.shown.size
        s.asked.single().second(listOf(row("Console")), null)
        assertEquals(shownBefore, s.shown.size, "an answer after closing shows nothing")
    }
}
