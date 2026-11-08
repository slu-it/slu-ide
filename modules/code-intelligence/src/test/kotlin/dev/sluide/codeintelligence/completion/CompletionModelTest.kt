package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.BoundEditor
import dev.sluide.codeintelligence.awaitTrue
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionTriggerKind
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The completion flow over a fake server: what opens it and what it asks, filtering while typing without waiting,
 * the latest answer winning, closing, resolving docs, and taking an item as one edit with its auto-import. The
 * popup is a recording view; the ranking and the edits have their own tests.
 */
class CompletionModelTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var bound: BoundEditor
    private lateinit var model: CompletionModel
    private val view = RecordingView()

    private class RecordingView : CompletionView {
        lateinit var model: CompletionModel
        val shown = mutableListOf<List<String>>()
        var showing = false
        var refreshes = 0

        override fun show(
            wordStart: Int,
            items: List<CompletionItem>,
        ): Boolean {
            shown += items.map { it.label }
            showing = true
            return true
        }

        override fun hide() {
            if (!showing) return
            showing = false
            model.viewHidden()
        }

        override fun refreshDetail() {
            refreshes++
        }

        override val isShowing: Boolean get() = showing
    }

    @AfterEach
    fun close() = bound.close()

    private fun setUp(
        text: String,
        resolves: Boolean = false,
    ) {
        bound = BoundEditor(folder, text, resolves)
        model = bound.fx.onFx { CompletionModel(bound.binding, view, 100.milliseconds, bound.timers::schedule) }
        view.model = model
        bound.fx.onFx { bound.editor.onTextChange(model::textChanged) }
    }

    private fun type(character: String) = bound.type(character, model.keys::keyTyped)

    private fun asked(count: Int) = bound.server.completions.also { awaitTrue("$count requests") { it.size == count } }

    private fun list(
        vararg labels: String,
        incomplete: Boolean = true,
    ): Either<List<CompletionItem>, CompletionList> =
        Either.forRight(CompletionList(incomplete, labels.map { CompletionItem(it) }))

    private fun lastShown(): List<String>? = bound.fx.onFx { view.shown.lastOrNull() }

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
}
