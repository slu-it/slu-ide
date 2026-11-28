package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.SourceCompletion
import dev.sluide.codeintelligence.SourceCompletions
import dev.sluide.codeintelligence.awaitTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The completion flow over a completion source (ADR 0145 amendment) instead of a language server: what asks it, the
 * source's order and anchor, its answers arriving from another thread, the timeout, failures and late answers. The
 * rest of the flow is the server's, `CompletionModelTest`'s.
 */
internal class SourceCompletionModelTest : CompletionModelFixture() {
    private fun answer(
        call: Int,
        anchor: Int,
        vararg items: SourceCompletion,
    ) = sourceCalls[call].second.complete(SourceCompletions(anchor, items.toList()))

    /** The messages [CompletionModel] logs while [action] runs. */
    private fun logged(action: () -> Unit): List<String> {
        val logger = Logger.getLogger(CompletionModel::class.java.name)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    messages += record.message
                }

                override fun flush() {}

                override fun close() {}
            }
        logger.addHandler(handler)
        try {
            action()
        } finally {
            logger.removeHandler(handler)
        }
        return messages.toList()
    }

    /** Runs the one delay of [delay] due, on the FX thread. */
    private fun runDelay(delay: Duration) {
        val due = bound.fx.onFx { bound.timers.due.filter { it.first == delay } }
        assertEquals(1, due.size, "one delay of $delay due")
        bound.fx.onFx {
            bound.timers.due.remove(due.single())
            due.single().second()
        }
    }

    // The anchor lies before the word at the caret (`util.`), and the inserted text is not the label: taking an item
    // without its anchor would give "util.java.util.List" or "util.List".
    private val text = "import x\nvar s = util.Li"
    private val anchor = text.indexOf("util")
    private val list = SourceCompletion("List", "java.util.List", "interface java.util.List")
    private val line = SourceCompletion("Line", "javax.sound.sampled.Line")

    @Test
    fun `Ctrl+Space asks the source at the caret, shows its items in order, taking one replaces from its anchor`() {
        setUpSource(text)
        assertTrue(bound.fx.onFx { model.invoke() })
        assertEquals(listOf(text.length), sourceCalls.map { it.first })
        assertEquals(emptyList(), lastShown(), "Searching… until the answer")

        answer(0, anchor, list, line)
        awaitTrue("shown") { lastShown() == listOf("List", "Line") }
        assertEquals(emptyList(), bound.fx.onFx { bound.timers.due.toList() }, "the answer ended the timeout")
        assertEquals("interface java.util.List", bound.fx.onFx { model.shown.first().detail })
        val first = bound.fx.onFx { model.shown.first() }
        assertSame(first, bound.fx.onFx { model.detailOf(first) }, "nothing to resolve")

        bound.fx.onFx { model.accept(model.shown.first(), replace = false) }
        assertEquals("import x\nvar s = java.util.List", bound.fx.onFx { bound.editor.text })
    }

    @Test
    fun `an item taken after typing on still replaces from the source's anchor`() {
        setUpSource(text)
        bound.fx.onFx { model.invoke() }
        answer(0, anchor, list, line)
        awaitTrue("shown") { lastShown() == listOf("List", "Line") }

        type("s")
        assertEquals(listOf("List"), lastShown())
        bound.fx.onFx { model.accept(model.shown.first(), replace = false) }

        assertEquals("import x\nvar s = java.util.List", bound.fx.onFx { bound.editor.text })
    }

    @Test
    fun `a typed dot asks a source naming no trigger characters at once, typing on filters without asking again`() {
        setUpSource("\"abc\"")
        type(".")
        assertEquals(listOf(6), sourceCalls.map { it.first }, "asked without the debounce")

        answer(0, 6, SourceCompletion("length()"), SourceCompletion("substring("))
        awaitTrue("shown") { lastShown() == listOf("length()", "substring(") }
        type("s")
        assertEquals(listOf("substring("), lastShown())
        assertEquals(emptyList(), bound.fx.onFx { bound.timers.due.toList() }, "a complete answer is not asked again")
        assertEquals(1, sourceCalls.size)
    }

    @Test
    fun `a typed trigger character of the source asks at once, and a dot it does not name asks nothing`() {
        setUpSource("x = ", triggers = listOf("{"))
        type("{")
        assertEquals(listOf(5), sourceCalls.map { it.first }, "asked without the debounce")
        assertTrue(isOpen)

        bound.fx.onFx { model.close() }
        type(".")
        assertEquals(1, sourceCalls.size, "a dot asks nothing")
        assertFalse(isOpen)
    }

    @Test
    fun `an item taken after deleting back past the source's anchor replaces the word up to the caret`() {
        setUpSource("x = abcd")
        bound.fx.onFx { model.invoke() }
        answer(0, 6, SourceCompletion("abcde"))
        awaitTrue("shown") { lastShown() == listOf("abcde") }

        // Backspace three times: "d", "c", "b".
        bound.fx.onFx {
            listOf(
                7,
                6,
                5,
            ).forEach { bound.editor.batch(listOf(Triple(it, it + 1, "")), caretAfter = it) }
        }
        assertEquals(listOf("abcde"), lastShown())
        bound.fx.onFx { model.accept(model.shown.first(), replace = false) }

        assertEquals("x = abcde", bound.fx.onFx { bound.editor.text })
    }

    @Test
    fun `no answer within 2 s ends the completion, and the answer after it shows nothing`() {
        setUpSource("\"abc\".")
        bound.fx.onFx { model.invoke() }

        val logged = logged { runDelay(2.seconds) }
        assertEquals(listOf("completion request failed: no answer within 2s"), logged)
        assertFalse(isOpen)
        assertFalse(view.showing, "Searching… is hidden")

        answer(0, 6, SourceCompletion("length()"))
        bound.fx.idle()
        assertEquals(listOf(emptyList()), bound.fx.onFx { view.shown.toList() }, "only the placeholder showed")
    }

    @Test
    fun `a failed answer ends the completion`() {
        setUpSource("\"abc\".")
        bound.fx.onFx { model.invoke() }
        assertEquals(1, sourceCalls.size)

        val logged =
            logged {
                sourceCalls.single().second.completeExceptionally(IllegalStateException("no JDK"))
                awaitTrue("closed") { !isOpen }
            }
        assertEquals(listOf("completion request failed: java.lang.IllegalStateException: no JDK"), logged)
        assertFalse(view.showing)
        assertEquals(emptyList(), bound.fx.onFx { bound.timers.due.toList() }, "the failure ended the timeout")
    }

    @Test
    fun `the answer to text that changed since is dropped once the text is asked for again`() {
        setUpSource("x = ab")
        bound.fx.onFx { model.invoke() }
        type("c")
        runDelay(100.milliseconds)
        assertEquals(listOf(6, 7), sourceCalls.map { it.first })
        assertEquals(1, bound.fx.onFx { bound.timers.due.count { it.first == 2.seconds } }, "one timeout, the newer's")

        answer(0, 4, SourceCompletion("abcd"))
        answer(1, 4, SourceCompletion("abce"))
        awaitTrue("the latest shown") { lastShown() == listOf("abce") }
        bound.fx.idle()
        assertFalse(bound.fx.onFx { view.shown.flatten() }.contains("abcd"), "the older answer never shows")
    }

    @Test
    fun `an older request that fails after a newer one was sent leaves the completion open`() {
        setUpSource("x = ab")
        bound.fx.onFx { model.invoke() }
        type("c")
        runDelay(100.milliseconds)
        assertEquals(2, sourceCalls.size)

        sourceCalls[0].second.completeExceptionally(IllegalStateException("session ended"))
        bound.fx.idle()
        assertTrue(isOpen, "waits for the newer answer")
        answer(1, 4, SourceCompletion("abce"))
        awaitTrue("shown") { lastShown() == listOf("abce") }
    }

    @Test
    fun `a source without completions for the editor asks nothing and shows nothing`() {
        setUpSource("\"abc\".", answers = false)
        bound.fx.onFx { model.invoke() }

        assertFalse(isOpen)
        assertFalse(view.showing)
        assertEquals(emptyList(), bound.fx.onFx { bound.timers.due.toList() }, "no timeout for no request")
    }
}
