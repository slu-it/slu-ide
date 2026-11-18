package dev.sluide.codeintelligence.completion

import com.google.gson.Gson
import dev.sluide.codeintelligence.awaitTrue
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Taking a completion item through the model (what is replaced where, on a later line, with the changes made since
 * the request, with what the server resolved), the resolve of an item's documentation, and the shapes an answer can
 * have. The edits themselves are `CompletionEditsTest`'s; the flow of opening and closing `CompletionModelTest`'s.
 */
internal class CompletionAcceptTest : CompletionModelFixture() {
    private fun range(
        line: Int,
        start: Int,
        end: Int = start,
    ) = Range(Position(line, start), Position(line, end))

    private fun answerWith(vararg items: CompletionItem) {
        asked(1).single().answer(Either.forRight(CompletionList(false, items.toList())))
        awaitTrue("shown") { bound.fx.onFx { model.shown.isNotEmpty() } }
    }

    private val text get() = bound.editor.currentText

    @Test
    fun `without a textEdit Enter replaces the typed prefix of a later line and Tab the whole word`() {
        setUp("import os\nos.path")
        bound.editor.moveCaret(15)
        bound.fx.onFx { model.invoke() }
        val item = CompletionItem("pathsep")
        answerWith(item)

        bound.fx.onFx { model.accept(item, replace = false) }

        assertEquals("import os\nos.pathsepth", text)
    }

    @Test
    fun `without a textEdit Tab replaces the whole word of a later line`() {
        setUp("import os\nos.path")
        bound.editor.moveCaret(15)
        bound.fx.onFx { model.invoke() }
        val item = CompletionItem("pathsep")
        answerWith(item)

        bound.fx.onFx { model.accept(item, replace = true) }

        assertEquals("import os\nos.pathsep", text)
    }

    @Test
    fun `an unchanged text and caret take the server's range, a caret moved on the word only up to itself`() {
        setUp("import os\nos.path")
        bound.editor.moveCaret(15)
        bound.fx.onFx { model.invoke() }
        // The server replaces the whole word "path".
        val wide = CompletionItem("pathsep").apply { textEdit = Either.forLeft(TextEdit(range(1, 3, 7), "pathsep")) }
        answerWith(wide)

        bound.fx.onFx {
            bound.editor.moveCaret(14)
            model.caretMoved()
            model.accept(wide, replace = false)
        }

        assertEquals("import os\nos.pathsepath", text, "caret at 14: the end moves to the caret")
    }

    @Test
    fun `an unchanged text and caret take the server's range as it is`() {
        setUp("import os\nos.path")
        bound.editor.moveCaret(15)
        bound.fx.onFx { model.invoke() }
        val wide = CompletionItem("pathsep").apply { textEdit = Either.forLeft(TextEdit(range(1, 3, 7), "pathsep")) }
        answerWith(wide)

        bound.fx.onFx { model.accept(wide, replace = false) }

        assertEquals("import os\nos.pathsep", text)
    }

    // "ar|" is the line asked at, "bb" the one below: an additional edit there is behind what is typed at "ar".
    private fun argv() =
        CompletionItem("argv").apply {
            textEdit = Either.forLeft(TextEdit(range(1, 0, 2), "argv"))
            additionalTextEdits =
                listOf(
                    TextEdit(range(0, 0), "import sys\n"),
                    TextEdit(range(0, 6), "#"),
                    TextEdit(range(2, 0), "x"),
                )
        }

    @Test
    fun `typed on after the answer, the item replaces up to the caret and the edit behind the change is left out`() {
        setUp("import os\nar\nbb")
        bound.editor.moveCaret(12)
        bound.fx.onFx { model.invoke() }
        val item = argv()
        answerWith(item)
        type("g")

        bound.fx.onFx { model.accept(item, replace = false) }

        assertEquals("import sys\nimport# os\nargv\nbb", text)
    }

    @Test
    fun `typed on before the answer, the item replaces up to the caret and the edit behind the change is left out`() {
        setUp("import os\nar\nbb")
        bound.editor.moveCaret(12)
        bound.fx.onFx { model.invoke() }
        val request = asked(1).single()
        type("g")
        val item = argv()
        request.answer(Either.forRight(CompletionList(false, listOf(item))))
        awaitTrue("shown") { bound.fx.onFx { model.shown.isNotEmpty() } }

        bound.fx.onFx { model.accept(item, replace = false) }

        assertEquals("import sys\nimport# os\nargv\nbb", text)
    }

    @Test
    fun `taking an item ends the completion before its edit, so the edit asks for nothing more`() {
        setUp("import os\nar")
        bound.fx.onFx { model.invoke() }
        val item = CompletionItem("argv")
        asked(1).single().answer(Either.forRight(CompletionList(true, listOf(item))))
        awaitTrue("shown") { bound.fx.onFx { model.shown.isNotEmpty() } }

        bound.fx.onFx { model.accept(item, replace = false) }

        assertEquals("import os\nargv", text)
        assertFalse(isOpen)
        assertEquals(emptyList(), bound.timers.due)
        assertEquals(1, bound.server.completions.size)
    }

    @Test
    fun `an item with docs is not asked for, one without is`() {
        setUp("import os\nos", resolves = true)
        type(".")
        val documented =
            CompletionItem("path").apply { documentation = Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "d")) }
        val plain = CompletionItem("sep")
        asked(1).single().answer(Either.forLeft(listOf(documented, plain)))
        awaitTrue("shown") { bound.fx.onFx { model.shown.size == 2 } }

        assertSame(documented, bound.fx.onFx { model.detailOf(documented) })
        // A negative check without a signal: a request for it would reach the server within moments, and the
        // detailOf below would cancel it before it was sent.
        Thread.sleep(200)
        assertEquals(emptyList(), bound.server.resolved)
        bound.fx.onFx { model.detailOf(plain) }

        awaitTrue("asked") { bound.server.resolved.size == 1 }
        assertEquals(
            "sep",
            bound.server.resolved
                .single()
                .params.label,
            "the documented one was not asked for",
        )
    }

    @Test
    fun `an item whose resolve is on its way is not asked for again`() {
        val path = openWithPlainItem()
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }

        bound.fx.onFx { model.detailOf(path) }

        // A negative check without a signal: a second request would cancel the first one and reach the server.
        Thread.sleep(200)
        assertEquals(1, bound.server.resolved.size)
        assertFalse(
            bound.server.resolved
                .single()
                .future.isCancelled,
        )
    }

    @Test
    fun `a server that cannot resolve is not asked`() {
        setUp("import os\nos")
        type(".")
        val plain = CompletionItem("sep")
        asked(1).single().answer(Either.forLeft(listOf(plain)))
        awaitTrue("shown") { bound.fx.onFx { model.shown.size == 1 } }

        bound.fx.onFx { model.detailOf(plain) }
        settle()

        assertEquals(emptyList(), bound.server.resolved)
    }

    private fun openWithPlainItem(): CompletionItem {
        setUp("import os\nos", resolves = true)
        type(".")
        val path = CompletionItem("path")
        asked(1).single().answer(Either.forRight(CompletionList(true, listOf(path))))
        awaitTrue("shown") { lastShown() == listOf("path") }
        return path
    }

    @Test
    fun `an item whose resolve failed is asked for again`() {
        val path = openWithPlainItem()
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }

        bound.server.resolved
            .single()
            .future
            .completeExceptionally(ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "no", null)))

        awaitTrue("asked again") {
            bound.fx.onFx { model.detailOf(path) }
            bound.server.resolved.size >= 2
        }
    }

    @Test
    fun `an item the server resolved to nothing is asked for again and nothing is refreshed`() {
        val path = openWithPlainItem()
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }

        bound.server.resolved
            .single()
            .future
            .complete(null)

        awaitTrue("asked again") {
            bound.fx.onFx { model.detailOf(path) }
            bound.server.resolved.size >= 2
        }
        assertEquals(0, bound.fx.onFx { view.refreshes })
    }

    @Test
    fun `a resolve answered after the binding closed is not used`() {
        val path = openWithPlainItem()
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }
        bound.fx.onFx { bound.binding.close() }

        bound.server.resolved
            .single()
            .answer(CompletionItem("path").apply { detail = "resolved" })
        settle()

        assertEquals(0, bound.fx.onFx { view.refreshes })
        assertSame(path, bound.fx.onFx { model.detailOf(path) })
    }

    @Test
    fun `a resolve answered while nothing is open is kept but shows nothing`() {
        setUp("import os\nos", resolves = true)
        val path = CompletionItem("path")
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }
        val resolved = CompletionItem("path").apply { detail = "resolved" }

        bound.server.resolved
            .single()
            .answer(resolved)
        settle()

        assertEquals(0, bound.fx.onFx { view.refreshes })
        assertEquals("resolved", bound.fx.onFx { model.detailOf(path).detail })
    }

    @Test
    fun `a fresh answer forgets what was resolved before`() {
        val path = openWithPlainItem()
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }
        bound.server.resolved
            .single()
            .answer(CompletionItem("path").apply { detail = "resolved" })
        awaitTrue("refreshed") { bound.fx.onFx { view.refreshes } == 1 }

        type("p")
        bound.fx.onFx { bound.timers.runDue() }
        asked(2)[1].answer(Either.forRight(CompletionList(true, listOf(path))))
        settle()
        bound.fx.onFx { model.detailOf(path) }

        awaitTrue("resolved again") { bound.server.resolved.size == 2 }
    }

    @Test
    fun `the popup's hide notice while nothing is open does not cancel a resolve on its way`() {
        setUp("import os\nos", resolves = true)
        val path = CompletionItem("path")
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }

        bound.fx.onFx { model.viewHidden() }
        bound.server.resolved
            .single()
            .answer(CompletionItem("path").apply { detail = "resolved" })

        awaitTrue("kept") { bound.fx.onFx { model.detailOf(path).detail } == "resolved" }
    }

    @Test
    fun `a new completion forgets what was resolved for the one before`() {
        val path = openWithPlainItem()
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }
        bound.server.resolved
            .single()
            .answer(CompletionItem("path").apply { detail = "resolved" })
        awaitTrue("refreshed") { bound.fx.onFx { view.refreshes } == 1 }
        bound.fx.onFx { model.close() }

        bound.fx.onFx { model.invoke() }

        assertSame(path, bound.fx.onFx { model.detailOf(path) })
        awaitTrue("asked again") { bound.server.resolved.size == 2 }
    }

    @Test
    fun `an item taken after its resolve brings what the server added, such as an import`() {
        setUp("import os\nar", resolves = true)
        bound.fx.onFx { model.invoke() }
        val argv = CompletionItem("argv")
        asked(1).single().answer(Either.forLeft(listOf(argv)))
        awaitTrue("shown") { lastShown() == listOf("argv") }
        bound.fx.onFx { model.detailOf(argv) }
        awaitTrue("asked") { bound.server.resolved.size == 1 }
        bound.server.resolved.single().answer(
            CompletionItem("argv").apply {
                documentation = Either.forLeft("the arguments")
                additionalTextEdits = listOf(TextEdit(range(0, 0), "import sys\n"))
            },
        )
        awaitTrue("refreshed") { bound.fx.onFx { view.refreshes } == 1 }

        bound.fx.onFx { model.accept(argv, replace = false) }

        assertEquals("import sys\nimport os\nargv", text)
    }

    private fun jsonList(json: String): Either<List<CompletionItem>, CompletionList> =
        Either.forRight(Gson().fromJson(json, CompletionList::class.java))

    companion object {
        // What the server sends: LSP 3.17 allows `null`, an array or a CompletionList; the others are lenient or
        // hostile answers (null entries and fields JSON can carry past lsp4j's constructors).
        @JvmStatic
        fun answers(): List<org.junit.jupiter.params.provider.Arguments> =
            listOf(
                answer("null", null, null),
                answer(
                    "an array with a null entry",
                    Either.forLeft(listOf(null, CompletionItem("path"))),
                    listOf("path"),
                ),
                answer("an empty array", Either.forLeft(emptyList()), null),
                answer("a null array", Either.forLeft(null), null),
                answer("a null list", Either.forRight(null), null),
            )

        private fun answer(
            name: String,
            result: Either<List<CompletionItem?>?, CompletionList?>?,
            shown: List<String>?,
        ) = org.junit.jupiter.params.provider.Arguments
            .of(name, result, shown)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answers")
    fun `an answer of any shape shows its items or ends the completion`(
        name: String,
        result: Either<List<CompletionItem>, CompletionList>?,
        shown: List<String>?,
    ) {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }

        asked(1).single().future.complete(result)

        if (shown == null) {
            awaitTrue("ended") { !isOpen }
        } else {
            awaitTrue("shown") { lastShown() == shown }
        }
    }

    @Test
    fun `a list without items or with null items among them`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().future.complete(jsonList("""{"isIncomplete":false,"items":null}"""))
        awaitTrue("ended") { !isOpen }

        bound.fx.onFx { model.invoke() }
        asked(2)[1].future.complete(
            jsonList("""{"isIncomplete":false,"items":[null,{"label":"path"}]}"""),
        )
        awaitTrue("shown") { lastShown() == listOf("path") }
        assertNotNull(bound.fx.onFx { model.shown.single() })
        assertTrue(isOpen)
    }
}
