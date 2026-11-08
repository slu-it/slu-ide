package dev.sluide.codeintelligence.symbols

import dev.sluide.codeintelligence.TestTimers
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** Go to symbol without a server: the rows of an answer, their order, the cell, and when the server is asked. */
class WorkspaceSymbolsTest {
    private val folder = Path.of("/work/project").toAbsolutePath()
    private val file = folder.resolve("pkg/console.py")
    private val at = Range(Position(4, 6), Position(4, 13))

    private fun row(name: String) = WorkspaceSymbolRow(name, null, SymbolKind.Class, file, Position(0, 0))

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
    fun `exact names come first, then prefixes, each in the server's order, at most the cap`() {
        val rows = listOf("get_console", "Console", "console_log", "ConsoleOptions", "console").map(::row)
        assertEquals(
            listOf("Console", "console", "console_log", "ConsoleOptions", "get_console"),
            rankWorkspaceSymbols(rows, "console").map { it.name },
        )
        assertEquals(listOf("Console", "console"), rankWorkspaceSymbols(rows, " console ", cap = 2).map { it.name })
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
}
