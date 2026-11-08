package dev.sluide.codeintelligence.completion

import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.textediting.TextPosition
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.InsertReplaceEdit
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/**
 * What taking a completion item changes: its text from `textEdit`, `insertText` or the label, Enter and Tab on an
 * insert/replace edit, the auto-import's additional edits in the same list, items answered for an older text.
 */
class CompletionEditsTest {
    // "import os\nos.pa|th" with the caret after "pa" (offset 15); the word "path" is [13, 17).
    private val text = "import os\nos.path"

    private fun place(
        firstChange: Int? = null,
        caret: Int = 15,
        requestCaret: Int = 15,
    ) = AcceptPlace(
        wordStart = 13,
        caret = caret,
        wordEnd = 17,
        firstChange = firstChange,
        requestCaret = requestCaret,
        offsetsOf = { range ->
            val start = offsetOf(range.start)
            start until offsetOf(range.end).coerceAtLeast(start)
        },
        positionOf = { offset -> TextPosition.of(text, offset).let { Position(it.line, it.column) } },
    )

    private fun offsetOf(position: Position) = TextPosition(position.line, position.character).offsetIn(text)

    private fun range(
        line: Int,
        start: Int,
        end: Int = start,
    ) = Range(Position(line, start), Position(line, end))

    // ty puts an import after the last one.
    private val autoImport = TextEdit(range(1, 0), "import sys\n")

    @Test
    fun `without a textEdit insertText or the label replaces the typed prefix, Tab the whole word`() {
        assertEquals(
            listOf(OffsetEdit(13, 15, "pathsep")),
            acceptEdits(CompletionItem("pathsep"), replace = false, place()).edits,
        )
        val item = CompletionItem("sep").apply { insertText = "sep()" }
        assertEquals(listOf(OffsetEdit(13, 17, "sep()")), acceptEdits(item, replace = true, place()).edits)
    }

    @ParameterizedTest
    @CsvSource(
        "false, 13, 15",
        "true, 13, 17",
    )
    fun `an InsertReplaceEdit inserts on Enter and replaces on Tab`(
        replace: Boolean,
        start: Int,
        end: Int,
    ) {
        val item =
            CompletionItem("pathsep").apply {
                textEdit = Either.forRight(InsertReplaceEdit("pathsep", range(1, 3, 5), range(1, 3, 7)))
            }
        assertEquals(listOf(OffsetEdit(start, end, "pathsep")), acceptEdits(item, replace, place()).edits)
    }

    @Test
    fun `a textEdit and the additional edits of an unchanged text go into one list as the server sent them`() {
        val item =
            CompletionItem("argv").apply {
                textEdit = Either.forLeft(TextEdit(range(1, 3, 5), "argv"))
                additionalTextEdits = listOf(autoImport)
            }
        assertEquals(
            AcceptEdits(listOf(OffsetEdit(10, 10, "import sys\n"), OffsetEdit(13, 15, "argv")), 0),
            acceptEdits(item, replace = false, place()),
        )
    }

    @Test
    fun `typed on since the request, the item replaces the current prefix and keeps only edits before the change`() {
        val item =
            CompletionItem("pathsep").apply {
                // For the text "os.p" with the caret after "p": the server's range ends at the old caret.
                textEdit = Either.forLeft(TextEdit(range(1, 3, 4), "pathsep"))
                additionalTextEdits = listOf(autoImport, TextEdit(range(1, 5, 7), "x"))
            }
        // "a" typed at 14 since: the import is before it and stays, the edit behind it goes.
        assertEquals(
            AcceptEdits(listOf(OffsetEdit(10, 10, "import sys\n"), OffsetEdit(13, 15, "pathsep")), 1),
            acceptEdits(item, replace = false, place(firstChange = 14)),
        )
        // A change in the first line: the import's position no longer holds either.
        assertEquals(
            AcceptEdits(listOf(OffsetEdit(13, 17, "pathsep")), 2),
            acceptEdits(item, replace = true, place(firstChange = 5)),
        )
    }

    @Test
    fun `typed on since, the server's start in untouched text stays and only the end moves to the caret`() {
        // Asked for "os.p" (caret 14), the server replaces the whole "os.p"; "a" typed at 14 since.
        val item = CompletionItem("pathsep").apply { textEdit = Either.forLeft(TextEdit(range(1, 0, 4), "os.pathsep")) }
        assertEquals(
            listOf(OffsetEdit(10, 15, "os.pathsep")),
            acceptEdits(item, replace = false, place(firstChange = 14)).edits,
        )
        assertEquals(
            listOf(OffsetEdit(10, 17, "os.pathsep")),
            acceptEdits(item, replace = true, place(firstChange = 14)).edits,
        )
    }

    @Test
    fun `the text unchanged but the caret moved on in the word, Enter inserts up to the caret`() {
        val item = CompletionItem("pathsep").apply { textEdit = Either.forLeft(TextEdit(range(1, 3, 4), "pathsep")) }
        assertEquals(
            listOf(OffsetEdit(13, 15, "pathsep")),
            acceptEdits(item, replace = false, place(requestCaret = 14)).edits,
        )
    }

    @Test
    fun `an additional edit overlapping the item's own is left out`() {
        val item =
            CompletionItem("path").apply {
                additionalTextEdits = listOf(TextEdit(range(1, 3, 4), "x"))
            }
        assertEquals(AcceptEdits(listOf(OffsetEdit(13, 15, "path")), 1), acceptEdits(item, replace = false, place()))
    }

    @Test
    fun `a snippet is inserted as its plain text`() {
        val item =
            CompletionItem("join").apply {
                insertText = "join(\${1:a}, \${2|b,c|})$0"
                insertTextFormat = InsertTextFormat.Snippet
            }
        assertEquals(listOf(OffsetEdit(13, 15, "join(a, b)")), acceptEdits(item, replace = false, place()).edits)
    }

    @ParameterizedTest
    @CsvSource(
        "'print(\$1)\$0', 'print()'",
        "'\${1:outer \${2:inner}} end', 'outer inner end'",
        "'cost \\\$5 \\} \\\\', 'cost \$5 } \\'",
        "'\${TM_FILENAME} \$var x', '  x'",
        "'plain', 'plain'",
    )
    fun `snippet syntax becomes plain text`(
        snippet: String,
        plain: String,
    ) {
        assertEquals(plain, snippetText(snippet))
    }
}
