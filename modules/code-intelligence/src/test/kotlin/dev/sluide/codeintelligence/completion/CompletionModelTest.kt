package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.FeatureServer
import dev.sluide.codeintelligence.awaitTrue
import javafx.scene.control.IndexRange
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionTriggerKind
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
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The completion flow over a fake server: what opens it and what it asks, filtering while typing without waiting,
 * the latest answer winning, closing, resolving docs, and taking an item as one edit with its auto-import. The
 * popup is a recording view; the ranking and the edits have their own tests.
 */
internal class CompletionModelTest : CompletionModelFixture() {
    @Test
    fun `a typed dot asks with it, typing filters at once and asks again, and only the latest answer shows`() {
        setUp("import os\nos")
        type(".")
        val first = asked(1).single()
        assertEquals(CompletionTriggerKind.TriggerCharacter, first.params.context.triggerKind)
        assertEquals(".", first.params.context.triggerCharacter)
        assertEquals(Position(1, 3), first.params.position)
        first.answer(list("sep", "path", "getcwd", "pathsep"))
        awaitTrue("shown") { lastShown() == listOf("getcwd", "path", "pathsep", "sep") }

        type("p")
        assertEquals(listOf("path", "pathsep", "sep"), lastShown(), "filtered before any answer")
        bound.fx.onFx { bound.timers.runDue() }
        val second = asked(2)[1]
        assertEquals(CompletionTriggerKind.TriggerForIncompleteCompletions, second.params.context.triggerKind)

        type("a")
        assertEquals(listOf("path", "pathsep"), lastShown())
        bound.fx.onFx { bound.timers.runDue() }
        val third = asked(3)[2]
        second.answer(list("paxos"))
        third.answer(list("path", "pa_new"))
        awaitTrue("latest shown") { lastShown() == listOf("pa_new", "path") }
        bound.fx.idle()
        assertFalse(bound.fx.onFx { view.shown.flatten() }.contains("paxos"), "the cancelled answer never shows")
    }

    @Test
    fun `Escape before the first answer ends the completion, so neither the debounce nor the answer opens it`() {
        setUp("import os\nos")
        type(".")
        val pending = asked(1).single()
        bound.fx.onFx { model.escape() }
        pending.answer(list("path"))
        bound.fx.idle()
        assertEquals(emptyList(), bound.fx.onFx { view.shown.toList() }, "the answer after Escape shows nothing")

        type("p")
        bound.fx.onFx { model.escape() }
        assertEquals(emptyList(), bound.timers.due, "Escape cancelled the debounce")
        assertEquals(1, bound.server.completions.size)
    }

    @Test
    fun `a word char asks after the debounce, a change nobody typed asks nothing`() {
        setUp("x = ")
        // An undo or paste of one word char: no key typed it.
        bound.fx.onFx { bound.editor.typeAtCaret("y") }
        assertEquals(emptyList(), bound.timers.due)

        type("o")
        assertEquals(
            100.milliseconds,
            bound.timers.due
                .single()
                .first,
        )
        assertEquals(emptyList(), bound.server.completions, "not before the debounce")
        bound.fx.onFx { bound.timers.runDue() }
        assertEquals(
            CompletionTriggerKind.Invoked,
            asked(1)
                .single()
                .params.context.triggerKind,
        )
    }

    @Test
    fun `Ctrl+Space shows Searching at once, leaving the word or a fresh answer without matches closes it`() {
        setUp("import os\nos.pa")
        assertTrue(bound.fx.onFx { model.invoke() })
        assertEquals(emptyList(), lastShown(), "the placeholder")
        asked(1).single().answer(list("path"))
        awaitTrue("shown") { lastShown() == listOf("path") }

        bound.fx.onFx {
            bound.editor.moveCaret(3)
            model.caretMoved()
        }
        assertFalse(bound.fx.onFx { model.isOpen })
        assertFalse(view.showing)

        bound.fx.onFx { bound.editor.moveCaret(15) }
        assertTrue(bound.fx.onFx { model.invoke() })
        asked(2)[1].answer(list("sep"))
        awaitTrue("closed: nothing matches pa") { bound.fx.onFx { !model.isOpen } }
        assertFalse(view.showing)
    }

    @Test
    fun `taking an item applies it with its auto-import in one edit, shifted to the prefix typed since the request`() {
        setUp("import os\nar")
        bound.fx.onFx { model.invoke() }
        val request = asked(1).single()
        type("g")
        val argv =
            CompletionItem("argv").apply {
                // For "ar": the range ends at the old caret.
                textEdit = Either.forLeft(TextEdit(Range(Position(1, 0), Position(1, 2)), "argv"))
                additionalTextEdits = listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "import sys\n"))
            }
        request.answer(Either.forRight(CompletionList(false, listOf(argv))))
        awaitTrue("shown") { lastShown() == listOf("argv") }

        bound.fx.onFx { model.accept(argv, replace = false) }
        assertEquals("import sys\nimport os\nargv", bound.editor.currentText)
        assertEquals(1, bound.editor.appliedEdits.size, "one undo step")
        assertFalse(bound.fx.onFx { model.isOpen })
    }

    @Test
    fun `an item without docs is resolved once when selected, and the detail shows again`() {
        setUp("import os\nos", resolves = true)
        type(".")
        val path = CompletionItem("path")
        asked(1).single().answer(Either.forLeft(listOf(path)))
        awaitTrue("shown") { lastShown() == listOf("path") }

        assertEquals(path, bound.fx.onFx { model.detailOf(path) })
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("resolve asked") { bound.server.resolved.size == 1 }
        val documented =
            CompletionItem("path").apply {
                documentation =
                    Either.forRight(MarkupContent(MarkupKind.MARKDOWN, "doc"))
            }
        bound.server.resolved
            .single()
            .answer(documented)
        awaitTrue("refreshed") { bound.fx.onFx { view.refreshes } == 1 }
        assertEquals(documented, bound.fx.onFx { model.detailOf(path) })
        assertEquals(1, bound.server.resolved.size)
    }

    @Test
    fun `an item left before its resolve answered is resolved again when selected again`() {
        setUp("import os\nos", resolves = true)
        type(".")
        val path = CompletionItem("path")
        val sep = CompletionItem("sep")
        asked(1).single().answer(Either.forLeft(listOf(path, sep)))
        awaitTrue("shown") { lastShown() == listOf("path", "sep") }

        // Each resolve reaches the server before the next selection cancels it: a request cancelled before it is
        // sent never arrives, so the count would depend on the outbound thread's timing.
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("path asked") { bound.server.resolved.size == 1 }
        bound.fx.onFx { model.detailOf(sep) }
        awaitTrue("sep asked") { bound.server.resolved.size == 2 }
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("path asked again") { bound.server.resolved.size == 3 }
        assertEquals(listOf("path", "sep", "path"), bound.server.resolved.map { it.params.label })
    }

    @Test
    fun `typing before the first answer asks again after the debounce, one delay at a time`() {
        setUp("x = ")
        type("o")
        type("p")
        assertEquals(1, bound.timers.due.size, "the second change replaced the first one's delay")
        bound.fx.onFx { bound.timers.runDue() }
        assertEquals(
            CompletionTriggerKind.Invoked,
            asked(1)
                .single()
                .params.context.triggerKind,
        )

        type("q")
        assertEquals(
            100.milliseconds,
            bound.timers.due
                .single()
                .first,
        )
        bound.fx.onFx { bound.timers.runDue() }
        assertEquals(CompletionTriggerKind.Invoked, asked(2)[1].params.context.triggerKind)
    }

    @Test
    fun `a request asked for now takes the place of a delayed one`() {
        setUp("x = ")
        type("o")
        assertEquals(1, bound.timers.due.size)

        bound.fx.onFx { model.invoke() }

        assertEquals(emptyList(), bound.timers.due)
        asked(1)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["a complete list", "an array"])
    fun `an answer that is complete asks for nothing more while typing`(shape: String) {
        setUp("import os\nos")
        type(".")
        val items = listOf(CompletionItem("path"), CompletionItem("pathsep"))
        asked(1).single().answer(
            if (shape == "an array") Either.forLeft(items) else Either.forRight(CompletionList(false, items)),
        )
        awaitTrue("shown") { lastShown() == listOf("path", "pathsep") }

        type("p")

        assertEquals(emptyList(), bound.timers.due)
        assertEquals(listOf("path", "pathsep"), lastShown())
        assertEquals(1, bound.server.completions.size)
    }

    @Test
    fun `a typed space ends the completion`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().answer(list("path", incomplete = false))
        awaitTrue("shown") { lastShown() == listOf("path") }

        type(" ")

        assertFalse(isOpen)
        assertFalse(view.showing)
        assertEquals(emptyList(), bound.timers.due)
        assertEquals(1, bound.server.completions.size)
    }

    @Test
    fun `a typed character with text selected opens nothing`() {
        setUp("x = ")
        bound.editor.selected = IndexRange(0, 2)

        type("o")

        assertEquals(emptyList(), bound.timers.due)
        assertFalse(isOpen)
    }

    @Test
    fun `a typed character inside a word opens nothing, at its end it does`() {
        setUp("pt")
        bound.editor.moveCaret(1)
        type("a")
        assertEquals(emptyList(), bound.timers.due, "t follows the caret")
        assertFalse(isOpen)

        bound.editor.moveCaret(3)
        type("h")

        assertEquals(1, bound.timers.due.size)
    }

    @Test
    fun `a server without completion is not asked, and Ctrl+Space is not taken`() {
        setUp("x = ", newServer = { FeatureServer(completion = false) })

        type("o")
        assertEquals(emptyList(), bound.timers.due)
        assertFalse(bound.fx.onFx { model.invoke() })
        assertFalse(isOpen)
        assertEquals(emptyList(), view.shown)
    }

    @Test
    fun `a server that names no trigger characters is asked for words only`() {
        // LSP 3.17: CompletionOptions.triggerCharacters is optional.
        setUp("import os\nos", newServer = { FeatureServer(completionTriggers = null) })

        type(".")
        assertEquals(emptyList(), bound.timers.due)
        assertEquals(emptyList(), bound.server.completions)
        type("p")

        assertEquals(1, bound.timers.due.size)
    }

    @Test
    fun `Ctrl+Space with text selected is not taken`() {
        setUp("import os\nos.pa")
        bound.editor.selected = IndexRange(1, 3)

        assertFalse(bound.fx.onFx { model.invoke() })

        assertFalse(isOpen)
        assertEquals(emptyList(), bound.server.completions)
    }

    @Test
    fun `Ctrl+Space where the popup cannot show asks nothing and ends at once`() {
        setUp("import os\nos.pa")
        view.canShow = false

        assertTrue(bound.fx.onFx { model.invoke() })

        assertFalse(isOpen)
        assertEquals(emptyList(), bound.server.completions)
    }

    @Test
    fun `a second Ctrl+Space shows Searching again instead of the items of the first`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().answer(list("path"))
        awaitTrue("shown") { lastShown() == listOf("path") }

        bound.fx.onFx { model.invoke() }

        assertEquals(emptyList(), lastShown(), "the placeholder, not the old items")
        assertEquals(emptyList(), bound.fx.onFx { model.shown })
        asked(2)
    }

    @Test
    fun `Escape while the popup shows is the popup's`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        assertTrue(view.showing)

        bound.fx.onFx { model.escape() }

        assertTrue(isOpen)
    }

    @Test
    fun `when the popup hides the completion ends and its request is cancelled`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        val request = asked(1).single()

        bound.fx.onFx { view.hide() }

        assertFalse(isOpen)
        assertEquals(emptyList(), bound.fx.onFx { model.shown })
        awaitTrue("the server was told") { request.future.isCancelled }
    }

    @Test
    fun `closing cancels the request and the resolve on their way`() {
        setUp("import os\nos.pa", resolves = true)
        bound.fx.onFx { model.invoke() }
        val request = asked(1).single()
        val path = CompletionItem("path")
        request.answer(Either.forLeft(listOf(path)))
        awaitTrue("shown") { lastShown() == listOf("path") }
        bound.fx.onFx { model.detailOf(path) }
        awaitTrue("resolve asked") { bound.server.resolved.size == 1 }
        bound.fx.onFx { model.invoke() }
        val second = asked(2)[1]

        bound.fx.onFx { model.close() }

        awaitTrue("the resolve was cancelled") {
            bound.server.resolved
                .single()
                .future.isCancelled
        }
        awaitTrue("the request was cancelled") { second.future.isCancelled }
    }

    @Test
    fun `closing what is not open hides nothing`() {
        setUp("x = ")

        bound.fx.onFx { model.close() }

        assertEquals(0, view.hides)
    }

    @Test
    fun `moving the caret inside the word filters for the prefix before it, to its start shows them all`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().answer(list("path", "pa_x", "pdb", incomplete = false))
        awaitTrue("shown") { lastShown() == listOf("pa_x", "path") }

        bound.fx.onFx {
            bound.editor.moveCaret(14)
            model.caretMoved()
        }
        assertEquals(listOf("pa_x", "path", "pdb"), lastShown())

        bound.fx.onFx {
            bound.editor.moveCaret(13)
            model.caretMoved()
        }
        assertEquals(listOf("pa_x", "path", "pdb"), lastShown())
        assertTrue(isOpen, "the caret at the start of the word is still in it")
    }

    @Test
    fun `moving the caret to the dot before the word ends the completion`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().answer(list("path", incomplete = false))
        awaitTrue("shown") { lastShown() == listOf("path") }

        bound.fx.onFx {
            bound.editor.moveCaret(12)
            model.caretMoved()
        }

        assertFalse(isOpen)
        assertFalse(view.showing)
    }

    @Test
    fun `a caret that moves when nothing is open opens nothing`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().answer(list("path", incomplete = false))
        awaitTrue("shown") { lastShown() == listOf("path") }
        bound.fx.onFx { view.hide() }
        val shownBefore = bound.fx.onFx { view.shown.size }

        bound.fx.onFx {
            bound.editor.moveCaret(14)
            model.caretMoved()
        }

        assertEquals(shownBefore, bound.fx.onFx { view.shown.size })
        assertFalse(isOpen)
    }

    @Test
    fun `typing what matches nothing of a complete answer ends the completion`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().answer(list("path", incomplete = false))
        awaitTrue("shown") { lastShown() == listOf("path") }

        type("z")

        assertFalse(isOpen)
    }

    @Test
    fun `typing what matches nothing of an incomplete answer keeps the list until the next answer`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().answer(list("path"))
        awaitTrue("shown") { lastShown() == listOf("path") }

        type("z")

        assertTrue(isOpen)
        assertEquals(
            listOf("path"),
            bound.fx.onFx { model.shown.map { it.label } },
            "the list stays while more may come",
        )
        bound.fx.onFx { bound.timers.runDue() }
        asked(2)[1].answer(list(incomplete = false))
        awaitTrue("a fresh answer without matches ends it") { !isOpen }
    }

    @Test
    fun `a popup that cannot show the answer ends the completion`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        val request = asked(1).single()
        view.canShow = false

        request.answer(list("path"))

        awaitTrue("ended") { !isOpen }
    }

    @Test
    fun `a first request that fails ends the completion, a failing request for more keeps the list`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        asked(1).single().future.completeExceptionally(failure())
        awaitTrue("ended") { !isOpen }

        bound.fx.onFx { model.invoke() }
        asked(2)[1].answer(list("path"))
        awaitTrue("shown") { lastShown() == listOf("path") }
        type("t")
        bound.fx.onFx { bound.timers.runDue() }
        asked(3)[2].future.completeExceptionally(failure())
        settle()

        assertTrue(isOpen)
        assertEquals(listOf("path"), bound.fx.onFx { model.shown.map { it.label } })
    }

    private fun failure() =
        ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "content modified", null))

    @Test
    fun `an answer after the binding closed shows nothing`() {
        setUp("import os\nos.pa")
        bound.fx.onFx { model.invoke() }
        val request = asked(1).single()
        bound.fx.onFx { bound.binding.close() }

        request.answer(list("path"))
        settle()

        assertEquals(listOf(emptyList()), bound.fx.onFx { view.shown.toList() }, "only the placeholder")
    }

    @Test
    fun `the lowest offset changed since a request is kept`() {
        val tracker = ChangeTracker(5)
        assertEquals(5, tracker.caret)
        assertEquals(null, tracker.firstChange)

        tracker.changed(10)
        assertEquals(10, tracker.firstChange)
        tracker.changed(7)
        assertEquals(7, tracker.firstChange)
        tracker.changed(9)
        assertEquals(7, tracker.firstChange)
    }
}
