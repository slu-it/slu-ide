package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.BoundEditor
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.fileviewers.TextChange
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpTriggerKind
import org.eclipse.lsp4j.SignatureInformation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The signature help flow over a fake server: a typed `(` asks at once, typing inside the call asks again after the
 * debounce as a retrigger with the shown answer, `,` at once, a null answer closes it; Shortcut+P asks. The popup is
 * a recording view; the shown label and docs are `SignatureLabelsTest`'s.
 */
class SignatureModelTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var bound: BoundEditor
    private lateinit var model: SignatureModel
    private val view = RecordingView()

    private class RecordingView : SignatureView {
        val shown = mutableListOf<Pair<Int, SignatureShown>>()
        var showing = false

        override fun show(
            anchor: Int,
            shown: SignatureShown,
        ): Boolean {
            this.shown += anchor to shown
            showing = true
            return true
        }

        override fun hide() {
            showing = false
        }
    }

    @AfterEach
    fun close() {
        if (::bound.isInitialized) bound.close()
    }

    private fun setUp(text: String) {
        bound = BoundEditor(folder, text)
        model = bound.fx.onFx { SignatureModel(bound.binding, view, 100.milliseconds, bound.timers::schedule) }
        bound.fx.onFx { bound.editor.onTextChange(model::textChanged) }
    }

    private fun type(character: String) = bound.type(character, model.keys::keyTyped)

    private fun asked(count: Int) = bound.server.signatures.also { awaitTrue("$count requests") { it.size == count } }

    private val help =
        SignatureHelp(
            listOf(
                SignatureInformation(
                    "(a: int, b: str) -> None",
                    "",
                    listOf(ParameterInformation("a: int"), ParameterInformation("b: str")),
                ),
            ),
            0,
            0,
        )

    @Test
    fun `a typed parenthesis asks at once and shows where it was asked, typing asks again as a retrigger`() {
        setUp("f")
        type("(")
        val first = asked(1).single()
        assertEquals(SignatureHelpTriggerKind.TriggerCharacter, first.params.context.triggerKind)
        assertEquals("(", first.params.context.triggerCharacter)
        assertFalse(first.params.context.isRetrigger)
        first.answer(help)
        awaitTrue("shown") { bound.fx.onFx { view.shown.size } == 1 }
        assertEquals(2, view.shown.single().first)
        assertEquals(
            1 until 7,
            view.shown
                .single()
                .second.active,
        )

        type("1")
        assertEquals(1, bound.server.signatures.size, "not before the debounce")
        bound.fx.onFx { bound.timers.runDue() }
        val second = asked(2)[1]
        assertEquals(SignatureHelpTriggerKind.ContentChange, second.params.context.triggerKind)
        assertTrue(second.params.context.isRetrigger)
        assertEquals(
            "(a: int, b: str) -> None",
            second.params.context.activeSignatureHelp.signatures
                .single()
                .label,
        )

        type(",")
        assertEquals(SignatureHelpTriggerKind.TriggerCharacter, asked(3)[2].params.context.triggerKind)
        bound.server.signatures[2].answer(SignatureHelp(help.signatures, 0, 1))
        awaitTrue("second parameter") { bound.fx.onFx { view.shown.size } == 2 }
        assertEquals(2, view.shown[1].first, "stays where it was asked first")
        assertEquals(9 until 15, view.shown[1].second.active)

        type(")")
        asked(4)[3].answer(null)
        awaitTrue("closed") { bound.fx.onFx { !model.isOpen } }
        assertFalse(view.showing)
    }

    @Test
    fun `the popup stays where it was asked as the text before it changes`() {
        setUp("f")
        type("(")
        asked(1).single().answer(help)
        awaitTrue("shown") { bound.fx.onFx { view.shown.size } == 1 }

        bound.fx.onFx { bound.editor.edit(0, 0, "x = ") }
        bound.fx.onFx { bound.timers.runDue() }
        asked(2)[1].answer(help)
        awaitTrue("shown again") { bound.fx.onFx { view.shown.size } == 2 }
        assertEquals(6, view.shown[1].first)
    }

    @ParameterizedTest
    @CsvSource(
        "edit after, 5, 7, '', x, 5",
        "insert at, 5, 5, '', x, 5",
        "insert before, 5, 2, '', xy, 7",
        "remove before, 5, 1, ab, '', 3",
        "remove across, 5, 3, abcd, '', 3",
    )
    fun `the anchor follows edits before it`(
        what: String,
        anchor: Int,
        start: Int,
        removed: String,
        inserted: String,
        expected: Int,
    ) {
        assertEquals(expected, shiftedAnchor(anchor, TextChange(start, removed, inserted)), what)
    }

    @Test
    fun `Shortcut+P asks at the caret, a change nobody typed asks nothing while closed`() {
        setUp("f(")
        bound.fx.onFx { bound.editor.typeAtCaret("(") }
        assertTrue(bound.fx.onFx { model.invoke() })
        assertEquals(
            SignatureHelpTriggerKind.Invoked,
            asked(1)
                .single()
                .params.context.triggerKind,
        )
    }
}
