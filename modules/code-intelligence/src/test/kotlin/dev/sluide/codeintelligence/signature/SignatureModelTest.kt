package dev.sluide.codeintelligence.signature

import dev.sluide.codeintelligence.BoundEditor
import dev.sluide.codeintelligence.FeatureServer
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.fileviewers.TextChange
import dev.sluide.languageservers.ServerState
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
        var hides = 0

        /** `false`: the popup cannot show (not on screen, no focus). */
        var accepts = true

        override fun show(
            anchor: Int,
            shown: SignatureShown,
        ): Boolean {
            this.shown += anchor to shown
            showing = accepts
            return accepts
        }

        override fun hide() {
            hides++
            showing = false
        }
    }

    @AfterEach
    fun close() {
        if (::bound.isInitialized) bound.close()
    }

    private fun setUp(
        text: String,
        newServer: () -> FeatureServer = { FeatureServer() },
    ) {
        bound = BoundEditor(folder, text, newServer = newServer)
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
        "replace ending at it, 5, 3, ab, xyz, 6",
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

    /** Opens the popup by typing `(` and answering with [help]. */
    private fun open() {
        setUp("f")
        type("(")
        asked(1).single().answer(help)
        awaitTrue("shown") { bound.fx.onFx { view.shown.size } == 1 }
    }

    @Test
    fun `a server without signature help is not asked and does not take Shortcut+P`() {
        setUp("f", newServer = { FeatureServer(signatureHelp = false) })
        type("(")
        assertFalse(bound.fx.onFx { model.invoke() })
        bound.serverIdle()
        assertEquals(emptyList(), bound.server.signatures)
    }

    @Test
    fun `a server without trigger characters is asked only by Shortcut+P, other typing waits for the debounce`() {
        setUp("f", newServer = { FeatureServer(signatureTriggers = null, signatureRetriggers = null) })
        type("(")
        bound.serverIdle()
        assertEquals(emptyList(), bound.server.signatures, "a typed ( asks nothing without trigger characters")

        assertTrue(bound.fx.onFx { model.invoke() })
        asked(1).single().answer(help)
        awaitTrue("shown") { bound.fx.onFx { view.shown.size } == 1 }
        type(")")
        bound.serverIdle()
        assertEquals(1, bound.server.signatures.size, "not at once: no retrigger character")
        bound.fx.onFx { bound.timers.runDue() }
        assertEquals(SignatureHelpTriggerKind.ContentChange, asked(2)[1].params.context.triggerKind)
    }

    @Test
    fun `while closed, a retrigger character, other changes and caret moves ask nothing and schedule nothing`() {
        setUp("f")
        type(")")
        bound.fx.onFx { bound.editor.edit(0, 0, "x") }
        bound.fx.onFx { model.caretMoved() }
        bound.serverIdle()
        assertEquals(emptyList(), bound.server.signatures)
        assertEquals(emptyList(), bound.timers.due)
    }

    @Test
    fun `a caret move while open asks after the debounce, one to where it was asked does not`() {
        open()
        val askedAt =
            bound.server.signatures
                .single()
                .params.position
        bound.fx.onFx { model.caretMoved() }
        assertEquals(emptyList(), bound.timers.due, "the caret is where the request was made")

        bound.fx.onFx { bound.editor.caret = 0 }
        bound.fx.onFx { model.caretMoved() }
        assertEquals(1, bound.timers.due.size)
        bound.fx.onFx { bound.timers.runDue() }
        val second = asked(2)[1]
        assertEquals(SignatureHelpTriggerKind.ContentChange, second.params.context.triggerKind)
        assertEquals(0, second.params.position.character)
        assertEquals(2, askedAt.character)
    }

    @Test
    fun `typing again while a request waits replaces it, and a trigger character asks at once and drops it`() {
        open()
        type("1")
        type("2")
        assertEquals(1, bound.timers.due.size)
        type(",")
        assertEquals(emptyList(), bound.timers.due, "the request at once took the waiting one's place")
        assertEquals(SignatureHelpTriggerKind.TriggerCharacter, asked(2)[1].params.context.triggerKind)
    }

    @Test
    fun `hiding the popup ends it and drops what waits, closing hides it, and a popup not open is not hidden`() {
        open()
        type("1")
        bound.fx.onFx { model.viewHidden() }
        assertFalse(bound.fx.onFx { model.isOpen })
        assertEquals(emptyList(), bound.timers.due, "the waiting request is dropped")
        assertEquals(0, view.hides, "it hid by itself")

        bound.fx.onFx { model.close() }
        assertEquals(0, view.hides, "nothing was open")

        type("(")
        asked(2)
        bound.fx.onFx { model.viewHidden() }
        bound.server.signatures[1].answer(help)
        awaitTrue("shown") { bound.fx.onFx { view.shown.size } == 2 }
        assertTrue(bound.fx.onFx { model.isOpen }, "a hide of no popup does not drop the question on its way")

        bound.fx.onFx { model.close() }
        assertEquals(1, view.hides)
    }

    @Test
    fun `an answer after the popup was hidden or closed is dropped`() {
        open()
        type("(")
        val late = asked(2)[1]
        bound.fx.onFx { model.viewHidden() }
        late.answer(help)
        bound.serverIdle()
        assertEquals(1, view.shown.size)

        type("(")
        val later = asked(3)[2]
        bound.fx.onFx { model.close() }
        later.answer(help)
        bound.serverIdle()
        assertEquals(1, view.shown.size)
    }

    @Test
    fun `a failed request closes an open popup, a popup that cannot show or a closed binding drops an answer`() {
        open()
        type("1")
        bound.fx.onFx { bound.timers.runDue() }
        asked(2)[1].future.completeExceptionally(IllegalStateException("boom"))
        awaitTrue("closed") { bound.fx.onFx { !model.isOpen } }
        assertEquals(1, view.hides)

        view.accepts = false
        type("(")
        asked(3)[2].answer(help)
        awaitTrue("refused") { bound.fx.onFx { view.shown.size } == 2 }
        assertFalse(bound.fx.onFx { model.isOpen }, "a popup that cannot show is not open")

        view.accepts = true
        type("(")
        val last = asked(4)[3]
        bound.fx.onFx { bound.binding.close() }
        last.answer(help)
        bound.serverIdle()
        assertEquals(2, view.shown.size)
    }

    @Test
    fun `an open popup that can no longer show closes and is hidden`() {
        open()
        view.accepts = false
        type("1")
        bound.fx.onFx { bound.timers.runDue() }
        asked(2)[1].answer(help)
        awaitTrue("closed") { bound.fx.onFx { !model.isOpen } }
        assertEquals(1, view.hides)
    }

    @Test
    fun `typing while the server restarts and has no capabilities yet asks nothing, not the new server either`() {
        setUp("f")
        bound.fx.onFx {
            bound.binding.session.restart()
            model.keys.keyTyped("(")
            bound.editor.typeAtCaret("(")
        }
        assertEquals(emptyList(), bound.timers.due)
        awaitTrue("restarted") { bound.fx.fake.started.size == 2 }
        awaitTrue("running") { bound.fx.onFx { bound.binding.session.state.value } == ServerState.Running }
        bound.serverIdle()
        assertEquals(
            emptyList(),
            (
                bound.fx.fake.started
                    .last() as FeatureServer
            ).signatures,
        )
    }

    /** A server whose trigger and retrigger lists hold a JSON null, which lsp4j parses into a null entry. */
    private fun nullTriggerServer() =
        FeatureServer(
            signatureTriggers = listOf<String?>(null) as List<String>,
            signatureRetriggers = listOf<String?>(null) as List<String>,
        )

    @Test
    fun `a change nobody typed is not a trigger character even when the server lists a null one`() {
        setUp("f", newServer = ::nullTriggerServer)
        bound.fx.onFx { bound.editor.edit(0, 0, "x") }
        bound.serverIdle()
        assertEquals(emptyList(), bound.server.signatures)
    }

    @Test
    fun `while open, a change nobody typed is not a retrigger character even when the server lists a null one`() {
        setUp("f", newServer = ::nullTriggerServer)
        assertTrue(bound.fx.onFx { model.invoke() })
        asked(1).single().answer(help)
        awaitTrue("shown") { bound.fx.onFx { view.shown.size } == 1 }
        bound.fx.onFx { bound.editor.edit(0, 0, "x") }
        bound.serverIdle()
        assertEquals(1, bound.server.signatures.size, "it waits for the debounce")
        assertEquals(1, bound.timers.due.size)
    }
}
