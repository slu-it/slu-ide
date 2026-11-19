package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.ManualDelays
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.fileviewers.StyledRange
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The occurrences of the name at the caret over a bound editor and a fake server: what is asked where, what the
 * answers show (a class per kind, what cannot be placed left out), and what closing and leaving the word undo.
 */
class OccurrencesFeatureTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var harness: BoundEditorHarness<NavigationServer>
    private val delays = ManualDelays()

    @AfterEach
    fun close() = harness.close()

    // Words `foo` at 0 until 3, `bar` at 4 until 7, `foo` at 8 until 11; the second line holds a pair of surrogates.
    private val emoji = String(Character.toChars(0x1F600))
    private val text = "foo bar foo\n$emoji foo\n"

    private fun bind(supported: Set<String> = setOf(DOCUMENT_HIGHLIGHT)) {
        harness =
            BoundEditorHarness(folder, text, { NavigationServer(supported) }, { binding ->
                listOfNotNull(OccurrencesFeature(300.milliseconds, delays::schedule).attach(binding))
            })
    }

    private val layer get() = harness.onFx { harness.editor.layers[OCCURRENCE_LAYER] }

    private fun caretTo(offset: Int) = harness.onFx { harness.editor.moveCaret(offset) }

    private fun highlight(
        kind: DocumentHighlightKind?,
        line: Int,
        start: Int,
        end: Int,
    ) = DocumentHighlight(Range(Position(line, start), Position(line, end)), kind)

    @Test
    fun `the caret resting on a word asks there and shows each occurrence with the class of its kind`() {
        bind()
        harness.server.highlightAnswer = {
            // The shapes a server's JSON can have: a null entry, an entry without range, an empty range.
            @Suppress("UNCHECKED_CAST")
            listOf(
                highlight(DocumentHighlightKind.Read, 0, 0, 3),
                null,
                DocumentHighlight(),
                highlight(DocumentHighlightKind.Write, 0, 8, 11),
                highlight(DocumentHighlightKind.Text, 0, 4, 7),
                highlight(null, 1, 3, 6),
                highlight(DocumentHighlightKind.Read, 0, 5, 5),
            ) as List<DocumentHighlight>
        }
        caretTo(9)
        assertEquals(1, delays.count)
        assertEquals(emptyList(), harness.server.highlightRequests, "not before the delay")
        harness.run(delays)
        awaitTrue("shown") { layer != null }
        assertEquals(
            Position(0, 9),
            harness.server.highlightRequests
                .single()
                .position,
        )
        assertEquals(
            listOf(
                StyledRange(0, 3, "ci-occurrence-read"),
                StyledRange(8, 11, "ci-occurrence-write"),
                StyledRange(4, 7, "ci-occurrence-text"),
                StyledRange(15, 18, "ci-occurrence-text"),
            ),
            layer,
        )
    }

    @Test
    fun `the occurrences ask again after an edit, for the word at the caret then`() {
        bind()
        caretTo(1)
        harness.run(delays)
        awaitTrue("asked") { harness.server.highlightRequests.size == 1 }
        harness.onFx { harness.editor.typeAtCaret("x") }
        assertEquals(1, delays.count)
        harness.run(delays)
        awaitTrue("asked again") { harness.server.highlightRequests.size == 2 }
        assertEquals(Position(0, 2), harness.server.highlightRequests[1].position, "the caret moved with the text")
    }

    @Test
    fun `a server that cannot highlight is not asked`() {
        bind(supported = emptySet())
        caretTo(1)
        harness.run(delays)
        harness.serverIdle()
        assertEquals(emptyList(), harness.server.highlightRequests)
        assertNull(layer)
    }

    @Test
    fun `a failed request clears what showed`() {
        bind()
        harness.server.highlightAnswer = { listOf(highlight(DocumentHighlightKind.Read, 0, 0, 3)) }
        caretTo(1)
        harness.run(delays)
        awaitTrue("shown") { layer?.size == 1 }
        harness.server.highlightAnswer = { error("the server failed") }
        harness.onFx { harness.editor.edit(0, 0, "x") }
        harness.run(delays)
        awaitTrue("cleared") { layer == emptyList<StyledRange>() }
    }

    @Test
    fun `an answer of null is no occurrences`() {
        bind()
        harness.server.highlightAnswer = { null }
        caretTo(1)
        harness.run(delays)
        awaitTrue("cleared") { layer == emptyList<StyledRange>() }
    }

    @Test
    fun `leaving the word while the question is on its way drops the answer`() {
        bind()
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        harness.server.highlightAnswer = {
            asked.countDown()
            assertTrue(answer.await(5, TimeUnit.SECONDS))
            listOf(highlight(DocumentHighlightKind.Read, 0, 0, 3))
        }
        caretTo(1)
        harness.run(delays)
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        caretTo(14)
        answer.countDown()
        harness.server.client
            .refreshDiagnostics()
            .get()
        harness.onFx {}
        assertNull(layer, "the answer for the word left is not shown")
    }

    @Test
    fun `closing adds nothing more, removes the stylesheet, clears the layer and ignores the caret`() {
        bind()
        harness.server.highlightAnswer = { listOf(highlight(DocumentHighlightKind.Read, 0, 0, 3)) }
        assertEquals(1, harness.onFx { harness.editor.stylesheets.size })
        caretTo(1)
        harness.run(delays)
        awaitTrue("shown") { layer?.size == 1 }

        harness.onFx { harness.handles.asReversed().forEach { it.close() } }
        assertEquals(0, harness.onFx { harness.editor.stylesheets.size })
        assertEquals(emptyList(), layer)
        caretTo(5)
        harness.onFx { harness.editor.edit(0, 0, "x") }
        assertEquals(0, delays.count, "nothing is asked any more")
    }
}
