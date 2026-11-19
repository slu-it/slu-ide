package dev.sluide.languageservers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Incremental changes on hostile text: UTF-16 positions (surrogate pairs), both ends of a line and of the text,
 * offsets at the edges of what fits, and what the document does with a change that does not fit, a change on a
 * document another one moved onto, and a move onto itself. The text has `\n` line ends, as the editor normalizes it
 * (`DocumentSync.open`): LSP 3.17 ("Text Documents") counts `\r\n` and `\r` as line ends too, which never reach it.
 */
internal class DocumentSyncChangesTest : DocumentSyncTestBase() {
    private val warnings = LogRecords(Logger.getLogger(DocumentSync::class.java.name))

    @AfterEach
    fun closeLogs() = warnings.close()

    private fun TextDocumentContentChangeEvent.describe(): String {
        val range = range ?: return "whole \"$text\""
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character} \"$text\""
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fitting")
    fun `a change that fits is sent as a range counted in UTF-16 units, line by line`(
        case: String,
        text: String,
        change: DocumentChange,
        range: String,
        after: String,
    ) {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", text)

        onFx { document.changed(change) { after } }
        session.flush(server)

        assertEquals(listOf(range), server.changes.map { it.contentChanges.single().describe() })
        assertEquals(listOf(2), server.changes.map { it.textDocument.version })
        assertEquals(after, onFx { document.text })
        assertEquals(2, onFx { document.version })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notFitting")
    fun `a change that does not fit the copy sends the editor's text whole and bumps the version once`(
        case: String,
        change: DocumentChange,
    ) {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", "abc")

        onFx { document.changed(change) { "editor text" } }
        session.flush(server)

        assertEquals(listOf("whole \"editor text\""), server.changes.map { it.contentChanges.single().describe() })
        assertEquals(listOf(2), server.changes.map { it.textDocument.version })
        assertEquals("editor text", onFx { document.text })
    }

    @Test
    fun `a change that leaves the text as it was is still a change with a new version`() {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", "abc")

        // The editor's text after the change is the copy before it: not the rest of a resynced batch.
        onFx { document.changed(DocumentChange(1, "b", "b")) { "abc" } }
        session.flush(server)

        assertEquals(listOf("0:1-0:2 \"b\""), server.changes.map { it.contentChanges.single().describe() })
        assertEquals(2, onFx { document.version })
    }

    @Test
    fun `a change that does not fit is logged once with where it was, however often the copy goes wrong`() {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", "abc")

        onFx { document.changed(DocumentChange(7, "x", "y")) { "one" } }
        // The editor moved on: this is a new batch, which does not fit either.
        onFx { document.changed(DocumentChange(9, "", "y")) { "two" } }
        session.flush(server)

        val logged = warnings.messages(Level.WARNING)
        assertEquals(
            listOf(
                "lsp test: change at 7 (1 removed) does not fit the copy of ${folder.resolve("a.py")} " +
                    "(3 chars); sending the whole text",
            ),
            logged,
        )
        assertEquals(
            listOf("whole \"one\"", "whole \"two\""),
            server.changes.map { it.contentChanges.single().describe() },
        )
        assertEquals(listOf(2, 3), server.changes.map { it.textDocument.version })
    }

    @Test
    fun `a change that does not fit a server that wants no changes only replaces the copy`() {
        val session = session({ syncing(TextDocumentSyncKind.None) })
        val server = session.running()
        val document = session.open("a.py", "abc")

        onFx { document.changed(DocumentChange(7, "x", "y")) { "editor text" } }
        session.flush(server)

        assertEquals(emptyList(), server.changes)
        assertEquals("editor text" to 2, onFx { document.text to document.version })
    }

    @Test
    fun `a document another one moved onto ignores changes and moves of its stale handle`() {
        val session = session()
        val server = session.running()
        val moving = session.open("a.py", "moving")
        val stale = session.open("b.py", "stale")
        onFx { moving.moved(folder.resolve("b.py")) }

        onFx {
            stale.changed(DocumentChange(0, "s", "S")) { "Stale" }
            stale.moved(folder.resolve("c.py"))
        }
        session.flush(server)

        assertEquals("stale" to 1, onFx { stale.text to stale.version })
        assertEquals(folder.resolve("b.py"), onFx { stale.file })
        assertFalse(onFx { session.documents.isOpen(folder.resolve("c.py")) })
        assertEquals(listOf("moving", "stale", "moving"), server.opens.map { it.textDocument.text })
        assertEquals(
            listOf(
                "lsp test: ${folder.resolve("a.py")} moved onto open ${folder.resolve("b.py")}; closing it",
            ),
            warnings.messages(Level.WARNING),
        )
    }

    @Test
    fun `moving a document onto its own path, however spelled, sends nothing`() {
        val session = session()
        val server = session.running()
        val document = session.open("a.py", "abc")

        onFx {
            document.moved(folder.resolve("a.py"))
            document.moved(folder.resolve("sub/../a.py"))
        }
        session.flush(server)

        assertEquals(
            listOf("didOpen abc"),
            server.calls.filter { it.startsWith("did") && it != "didChangeConfiguration" },
        )
        assertEquals(1, onFx { document.version })
    }

    @Test
    fun `the handle's positions and offsets are those of its copy`() {
        val session = session()
        val document = session.open("a.py", "\uD83D\uDE00b\nc")

        // The conversions themselves are TextPositionsTest's; this is that the handle uses its own text.
        assertEquals(Position(0, 2), onFx { document.positionOf(2) })
        assertEquals(4, onFx { document.offsetOf(Position(1, 0)) })
    }

    companion object {
        private const val SMILE = "😀"

        @JvmStatic
        fun fitting(): List<Arguments> =
            listOf(
                Arguments.of("insert at the start", "abc", DocumentChange(0, "", "X"), "0:0-0:0 \"X\"", "Xabc"),
                Arguments.of("replace at the start", "abc", DocumentChange(0, "a", "X"), "0:0-0:1 \"X\"", "Xbc"),
                Arguments.of("append at the end", "abc", DocumentChange(3, "", "d"), "0:3-0:3 \"d\"", "abcd"),
                Arguments.of("remove up to the end", "abc", DocumentChange(1, "bc", ""), "0:1-0:3 \"\"", "a"),
                Arguments.of("replace everything", "abc", DocumentChange(0, "abc", "x"), "0:0-0:3 \"x\"", "x"),
                Arguments.of("into an empty text", "", DocumentChange(0, "", "x"), "0:0-0:0 \"x\"", "x"),
                Arguments.of("before a line end", "ab\ncd", DocumentChange(2, "", "!"), "0:2-0:2 \"!\"", "ab!\ncd"),
                Arguments.of("after a line end", "ab\ncd", DocumentChange(3, "", "!"), "1:0-1:0 \"!\"", "ab\n!cd"),
                Arguments.of("the line end itself", "ab\ncd", DocumentChange(2, "\n", ""), "0:2-1:0 \"\"", "abcd"),
                Arguments.of("across lines", "ab\ncd\nef", DocumentChange(1, "b\ncd\ne", "-"), "0:1-2:1 \"-\"", "a-f"),
                Arguments.of(
                    "at the end of the last line",
                    "ab\n",
                    DocumentChange(3, "", "c"),
                    "1:0-1:0 \"c\"",
                    "ab\nc",
                ),
                Arguments.of(
                    "after a surrogate pair",
                    "${SMILE}b",
                    DocumentChange(2, "b", "c"),
                    "0:2-0:3 \"c\"",
                    "${SMILE}c",
                ),
                Arguments.of(
                    "a surrogate pair removed",
                    "${SMILE}b",
                    DocumentChange(0, SMILE, ""),
                    "0:0-0:2 \"\"",
                    "b",
                ),
                Arguments.of(
                    "on the next line after pairs",
                    "$SMILE$SMILE\n${SMILE}x",
                    DocumentChange(7, "x", "y"),
                    "1:2-1:3 \"y\"",
                    "$SMILE$SMILE\n${SMILE}y",
                ),
                Arguments.of("a pair inserted", "ab", DocumentChange(1, "", SMILE), "0:1-0:1 \"$SMILE\"", "a${SMILE}b"),
            )

        @JvmStatic
        fun notFitting(): List<Arguments> =
            listOf(
                Arguments.of("a negative start", DocumentChange(-1, "", "x")),
                Arguments.of("a start past the end", DocumentChange(4, "", "x")),
                Arguments.of("an end past the end", DocumentChange(2, "cd", "x")),
                Arguments.of("other text removed", DocumentChange(0, "x", "y")),
                Arguments.of("other case removed", DocumentChange(0, "A", "y")),
                Arguments.of("a longer removal that is a prefix of the copy", DocumentChange(0, "abcd", "y")),
            )
    }
}
