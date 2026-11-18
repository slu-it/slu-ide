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
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
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

    @Test
    fun `an item without a label or a range takes the word's place with what text it has`() {
        // LSP 3.17 requires label, newText and range; the gaps of a lenient server give no text or the word's place.
        val noRange = TextEdit().also { it.newText = "x" }
        assertEquals(listOf(OffsetEdit(13, 15, "")), acceptEdits(CompletionItem(), false, place()).edits)
        assertEquals(
            listOf(OffsetEdit(13, 15, "x")),
            acceptEdits(CompletionItem("a").apply { textEdit = Either.forLeft(noRange) }, false, place()).edits,
        )
        assertEquals(
            listOf(OffsetEdit(13, 17, "")),
            acceptEdits(
                CompletionItem("a").apply { textEdit = Either.forRight(InsertReplaceEdit()) },
                replace = true,
                place(),
            ).edits,
        )
    }

    @Test
    fun `a TextEdit without new text inserts nothing at the server's range`() {
        val bare = TextEdit().also { it.range = range(1, 3, 7) }

        assertEquals(
            listOf(OffsetEdit(13, 17, "")),
            acceptEdits(CompletionItem("a").apply { textEdit = Either.forLeft(bare) }, false, place()).edits,
        )
        val right =
            InsertReplaceEdit().also {
                it.insert = range(1, 3, 5)
                it.replace = range(1, 3, 7)
            }
        assertEquals(
            listOf(OffsetEdit(13, 15, "")),
            acceptEdits(CompletionItem("a").apply { textEdit = Either.forRight(right) }, false, place()).edits,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "false, 10, 15",
        "true, 10, 17",
    )
    fun `an InsertReplaceEdit takes the start of its own ranges`(
        replace: Boolean,
        start: Int,
        end: Int,
    ) {
        // Both ranges start before the typed word: the server's start is used, not the word's.
        val item =
            CompletionItem("os.pathsep").apply {
                textEdit = Either.forRight(InsertReplaceEdit("os.pathsep", range(1, 0, 5), range(1, 0, 7)))
            }
        assertEquals(listOf(OffsetEdit(start, end, "os.pathsep")), acceptEdits(item, replace, place()).edits)
    }

    @Test
    fun `text that came in as plain text keeps its dollar signs`() {
        val item = CompletionItem("cost").apply { insertText = "\$1 and \${x}" }
        assertEquals(listOf(OffsetEdit(13, 15, "\$1 and \${x}")), acceptEdits(item, false, place()).edits)
        item.insertTextFormat = InsertTextFormat.PlainText
        assertEquals(listOf(OffsetEdit(13, 15, "\$1 and \${x}")), acceptEdits(item, false, place()).edits)
    }

    @Test
    fun `a change since the request outweighs the caret being where it was, the item then ends at the caret`() {
        // The text changed at 14 ("a" typed, then deleted again): the caret is the one of the request, the text is not.
        val wide = CompletionItem("pathsep").apply { textEdit = Either.forLeft(TextEdit(range(1, 3, 7), "pathsep")) }

        assertEquals(listOf(OffsetEdit(13, 15, "pathsep")), acceptEdits(wide, false, place(firstChange = 14)).edits)
        assertEquals(listOf(OffsetEdit(13, 17, "pathsep")), acceptEdits(wide, false, place()).edits)
    }

    @Test
    fun `a change before the server's start makes the item start at the word`() {
        // The server's range starts at line 1 column 0, behind a change in line 0: its positions are no longer true.
        val item =
            CompletionItem("os.pathsep").apply { textEdit = Either.forLeft(TextEdit(range(1, 0, 4), "os.pathsep")) }

        assertEquals(listOf(OffsetEdit(13, 15, "os.pathsep")), acceptEdits(item, false, place(firstChange = 5)).edits)
        assertEquals(listOf(OffsetEdit(13, 17, "os.pathsep")), acceptEdits(item, true, place(firstChange = 5)).edits)
        assertEquals(
            listOf(OffsetEdit(10, 15, "os.pathsep")),
            acceptEdits(item, false, place(firstChange = 14)).edits,
            "a change behind the start leaves it",
        )
    }

    @ParameterizedTest
    @CsvSource(
        // the end of the additional edit (line, column) against a change at (1, 2): kept unless it ends behind it
        "0, 9, true",
        "1, 1, true",
        "1, 2, true",
        "1, 3, false",
        "2, 0, false",
    )
    fun `an additional edit is kept unless it ends behind the first change`(
        line: Int,
        column: Int,
        kept: Boolean,
    ) {
        val item =
            CompletionItem("path").apply {
                additionalTextEdits = listOf(TextEdit(range(line, column), "x"))
            }
        val edits = acceptEdits(item, replace = false, place(firstChange = 12))

        assertEquals(if (kept) 2 else 1, edits.edits.size)
        assertEquals(if (kept) 0 else 1, edits.droppedAdditional)
    }

    @Test
    fun `an additional edit next to the item's own is kept, one inside it is not`() {
        // The item's own edit is [13, 15) here.
        fun edits(additional: TextEdit) =
            acceptEdits(
                CompletionItem("path").apply { additionalTextEdits = listOf(additional) },
                replace = false,
                place(),
            )

        assertEquals(2, edits(TextEdit(range(1, 1, 3), "x")).edits.size, "ends where the item's starts")
        assertEquals(2, edits(TextEdit(range(1, 5, 6), "x")).edits.size, "starts where the item's ends")
        assertEquals(2, edits(TextEdit(range(1, 3), "import")).edits.size, "an insertion at the item's start")
        assertEquals(2, edits(TextEdit(range(1, 5), "x")).edits.size, "an insertion at the item's end")
        assertEquals(1, edits(TextEdit(range(1, 4), "x")).edits.size, "an insertion inside")
        assertEquals(1, edits(TextEdit(range(1, 2, 4), "x")).edits.size, "overlapping its start")
        assertEquals(1, edits(TextEdit(range(1, 4, 6), "x")).edits.size, "overlapping its end")
    }

    @Test
    fun `additional edits that are null or have no text`() {
        val noText = TextEdit().also { it.range = range(0, 0) }
        val item =
            CompletionItem("path").apply {
                additionalTextEdits = listOf(null, noText)
            }

        assertEquals(
            AcceptEdits(listOf(OffsetEdit(0, 0, ""), OffsetEdit(13, 15, "path")), 0),
            acceptEdits(item, replace = false, place()),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    fun `snippet syntax of LSP 3_17 as plain text`(
        snippet: String,
        plain: String,
    ) {
        assertEquals(plain, snippetText(snippet))
    }

    companion object {
        // LSP 3.17 "Snippet Syntax": tab stops, placeholders, choices, variables; `\` escapes `$`, `}` and `\`, in a
        // choice also `,` and `|`. The cases after the spec's are what a lenient reader does with broken input.
        @JvmStatic
        fun snippets(): Stream<Arguments> =
            Stream.of(
                Arguments.of("a\\", "a\\"),
                Arguments.of("a\\n", "a\\n"),
                Arguments.of("a}b", "a}b"),
                Arguments.of("\${1:a}b}", "ab}"),
                Arguments.of("cost $", "cost $"),
                Arguments.of("\${", ""),
                Arguments.of("$ x $- y", "$ x $- y"),
                Arguments.of("\$_x1 rest", " rest"),
                Arguments.of("\$var_1x", ""),
                Arguments.of("\$1 \$2 z", "  z"),
                Arguments.of("\${1", ""),
                Arguments.of("\${1:abc", "abc"),
                Arguments.of("\${abc", ""),
                Arguments.of("\${1|only|}", "only"),
                Arguments.of("\${1|a\\,b,c|}", "a,b"),
                Arguments.of("\${1|a\\|b|}", "a|b"),
                Arguments.of("\${1|a,b|}x", "ax"),
                Arguments.of("\${1|a,b|", "a"),
                Arguments.of("\${1|a\\", "a\\"),
                Arguments.of("\${1|", ""),
                Arguments.of("\${1}after", "after"),
                Arguments.of("\${1/(.*)/x/}after", "after"),
                Arguments.of("\${1/(.*)/x/", ""),
                Arguments.of("\${1:a\\}b}c", "a}bc"),
            )
    }
}
