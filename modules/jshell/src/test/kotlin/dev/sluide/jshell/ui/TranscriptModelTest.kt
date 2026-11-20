package dev.sluide.jshell.ui

import dev.sluide.jshell.EvalEvent
import dev.sluide.jshell.ui.ParagraphKind.DIAGNOSTIC
import dev.sluide.jshell.ui.ParagraphKind.ERROR_OUTPUT
import dev.sluide.jshell.ui.ParagraphKind.HEADER
import dev.sluide.jshell.ui.ParagraphKind.NOTE
import dev.sluide.jshell.ui.ParagraphKind.OUTPUT
import dev.sluide.jshell.ui.ParagraphKind.THROWN
import dev.sluide.jshell.ui.ParagraphKind.VALUE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transcript's paragraphs, cap and jump targets. Every change the model returns is also applied to a mirror list the
 * way the pane does (append to the last paragraph, add, drop leading ones), which must equal the model's paragraphs.
 */
class TranscriptModelTest {
    private class Fixture(
        capChars: Int = 1_048_576,
    ) {
        val model = TranscriptModel(capChars)
        private val mirror = ArrayDeque<TranscriptParagraph>()

        var last: TranscriptChange = TranscriptChange(null, emptyList(), 0)
            private set

        private fun applied(change: TranscriptChange): TranscriptChange {
            change.append?.let { extra ->
                val old = mirror.removeLast()
                assertEquals(extra.kind, old.kind)
                mirror.addLast(old.copy(text = old.text + extra.text))
            }
            mirror.addAll(change.added)
            repeat(change.dropped) { mirror.removeFirst() }
            assertEquals(model.paragraphs, mirror.toList())
            last = change
            return change
        }

        fun start(text: String) = model.startEvaluation(text)

        fun accept(event: EvalEvent) = applied(model.accept(event))

        fun out(
            text: String,
            error: Boolean = false,
        ) = accept(EvalEvent.Output(text, error))

        fun late(
            text: String,
            error: Boolean = false,
        ) = applied(model.lateOutput(text, error))

        fun note(text: String) = applied(model.note(text))

        fun clear() = applied(model.clear())

        fun snippet(
            start: Int,
            line: Int,
        ) = accept(EvalEvent.SnippetStarted(start, start + 1, line))

        val texts get() = model.paragraphs.map { it.text }
        val kinds get() = model.paragraphs.map { it.kind }
    }

    private fun fixture(
        doc: String = "x",
        cap: Int = 1_048_576,
    ) = Fixture(cap).also { it.start(doc) }

    @Test
    fun `a snippet starting shows its first source line as a header`() {
        val f = fixture("int x = 5\nint y = 6\n")
        f.snippet(0, 0)
        f.snippet(10, 1)

        assertEquals(listOf("jshell> int x = 5", "jshell> int y = 6"), f.texts)
        assertEquals(listOf(HEADER, HEADER), f.kinds)
    }

    @Test
    fun `the header is the snippet's first line only, from its start, trimmed at the end`() {
        val f = fixture("// note\nvoid f() {   \n  return;\n}\n")
        f.snippet(8, 1)

        assertEquals(listOf("jshell> void f() {"), f.texts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `the header ends at any line ending`(ending: String) {
        val f = fixture("a$ending" + "b")
        f.snippet(0, 0)

        assertEquals(listOf("jshell> a"), f.texts)
    }

    @Test
    fun `a long first line is cut to 200 characters and an ellipsis`() {
        val f = fixture("a".repeat(500))
        f.snippet(0, 0)

        assertEquals("jshell> " + "a".repeat(200) + "…", f.texts.single())
    }

    @Test
    fun `a first line of exactly 200 characters is not cut`() {
        val f = fixture("a".repeat(200))
        f.snippet(0, 0)

        assertEquals("jshell> " + "a".repeat(200), f.texts.single())
    }

    @Test
    fun `the cut of the header does not split an emoji`() {
        // the emoji straddles the cut: its first half is the 200th character
        val f = fixture("a".repeat(199) + "😀" + "b".repeat(10))
        f.snippet(0, 0)

        assertEquals("jshell> " + "a".repeat(199) + "…", f.texts.single())
    }

    @Test
    fun `a header with an emoji below the cut stays whole`() {
        val f = fixture("String s = \"😀\"")
        f.snippet(0, 0)

        assertEquals("jshell> String s = \"😀\"", f.texts.single())
    }

    @Test
    fun `a snippet start outside the document gives an empty header`() {
        val f = fixture("ab")
        f.snippet(50, 0)

        assertEquals(listOf("jshell> "), f.texts)
    }

    @Test
    fun `printed text becomes output paragraphs split at newlines, stderr as error output`() {
        val f = fixture()
        f.out("a\nb\n")
        f.out("e\n", error = true)

        assertEquals(listOf("a", "b", "e"), f.texts)
        assertEquals(listOf(OUTPUT, OUTPUT, ERROR_OUTPUT), f.kinds)
    }

    @Test
    fun `a print without a newline continues its paragraph with the next output of the same stream`() {
        val f = fixture()
        f.out("ab")
        f.out("cd")
        assertEquals(listOf("abcd"), f.texts)
        assertEquals("cd", f.last.append?.text)
        assertTrue(f.last.added.isEmpty())

        f.out("ef\ngh")
        f.out("\nij\n")
        assertEquals(listOf("abcdef", "gh", "ij"), f.texts)
    }

    @Test
    fun `output of the other stream starts a new paragraph and leaves the open one closed`() {
        val f = fixture()
        f.out("a")
        f.out("e", error = true)
        f.out("b")

        assertEquals(listOf("a", "e", "b"), f.texts)
        assertEquals(listOf(OUTPUT, ERROR_OUTPUT, OUTPUT), f.kinds)
    }

    @Test
    fun `a newline after an open paragraph closes it and an empty line is a paragraph`() {
        val f = fixture()
        f.out("a")
        f.out("\n")
        f.out("\n")
        f.out("b")

        assertEquals(listOf("a", "", "b"), f.texts)
    }

    @Test
    fun `a header closes an open output paragraph`() {
        val f = fixture("a\nb")
        f.snippet(0, 0)
        f.out("x")
        f.snippet(2, 1)
        f.out("y")

        assertEquals(listOf("jshell> a", "x", "jshell> b", "y"), f.texts)
    }

    @Test
    fun `empty output changes nothing`() {
        val f = fixture()
        f.out("")
        assertTrue(f.texts.isEmpty())
        assertEquals(TranscriptChange(null, emptyList(), 0), f.last)

        f.out("a")
        f.out("")
        assertEquals(listOf("a"), f.texts)
    }

    @Test
    fun `carriage return and line feed pairs and lone carriage returns end a line`() {
        val f = fixture()
        f.out("a\r\nb\rc\r\n\rd")

        assertEquals(listOf("a", "b", "c", "", "d"), f.texts)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a carriage return and its line feed in two outputs of one stream make one line end`(error: Boolean) {
        val f = fixture()
        f.out("a\r", error)
        f.out("\nb\n", error)
        f.out("c\r", error)
        f.out("d", error)
        f.out("\ne", error)

        assertEquals(listOf("a", "b", "c", "d", "e"), f.texts)
    }

    @Test
    fun `a value shows its feedback`() {
        val f = fixture()
        f.accept(EvalEvent.Value("x ==> 5"))

        assertEquals(listOf("x ==> 5"), f.texts)
        assertEquals(listOf(VALUE), f.kinds)
    }

    @Test
    fun `a diagnostic shows its one-based line and message, a longer message as several paragraphs`() {
        val f = fixture()
        f.accept(EvalEvent.Diagnostic(0, 1, 2, "cannot find symbol\n  symbol: y\r\n  location: class", true))

        assertEquals(listOf("line 3: cannot find symbol", "  symbol: y", "  location: class"), f.texts)
        assertEquals(listOf(DIAGNOSTIC, DIAGNOSTIC, DIAGNOSTIC), f.kinds)
    }

    @Test
    fun `a thrown text is one paragraph per line, without a trailing empty one`() {
        val f = fixture()
        f.accept(EvalEvent.Thrown("java.lang.IllegalStateException: boom\n\tat REPL.\$JShell\$1.f(#1:1)\n", 0))

        assertEquals(listOf("java.lang.IllegalStateException: boom", "\tat REPL.\$JShell\$1.f(#1:1)"), f.texts)
        assertEquals(listOf(THROWN, THROWN), f.kinds)
    }

    @Test
    fun `an empty thrown text and an empty feedback add nothing`() {
        val f = fixture()
        f.accept(EvalEvent.Thrown("", 1))
        f.accept(EvalEvent.Value(""))

        assertTrue(f.texts.isEmpty())
    }

    @Test
    fun `stopped adds a note, finished nothing`() {
        val f = fixture()
        f.accept(EvalEvent.Finished)
        assertTrue(f.texts.isEmpty())

        f.accept(EvalEvent.Stopped)
        assertEquals(listOf("Stopped"), f.texts)
        assertEquals(listOf(NOTE), f.kinds)
    }

    @Test
    fun `a note is a note paragraph, per line, and closes an open output`() {
        val f = fixture()
        f.out("a")
        f.note("Session ended (exit 3)")
        f.out("b")

        assertEquals(listOf("a", "Session ended (exit 3)", "b"), f.texts)
        assertEquals(listOf(OUTPUT, NOTE, OUTPUT), f.kinds)
    }

    @Test
    fun `late output is output without a header and joins an open paragraph of its stream`() {
        val f = fixture()
        f.late("a")
        f.late("b\n")
        f.late("e\n", error = true)

        assertEquals(listOf("ab", "e"), f.texts)
        assertEquals(listOf(OUTPUT, ERROR_OUTPUT), f.kinds)
    }

    @Test
    fun `clear drops every paragraph and the next output starts fresh`() {
        val f = fixture()
        f.out("a\nb")
        f.clear()
        assertEquals(2, f.last.dropped)
        assertTrue(f.texts.isEmpty())

        f.out("c")
        assertEquals(listOf("c"), f.texts)
        assertTrue(f.last.append == null)
    }

    @Test
    fun `clearing an empty transcript changes nothing`() {
        val f = fixture()
        assertEquals(TranscriptChange(null, emptyList(), 0), f.clear())
    }

    @Test
    fun `jump lines come from the header, the diagnostic and the thrown text`() {
        val f = fixture("a\nb\nc\nd")
        f.snippet(2, 1)
        f.out("o")
        f.accept(EvalEvent.Value("v"))
        f.accept(EvalEvent.Diagnostic(0, 1, 2, "m1\nm2", true))
        f.accept(EvalEvent.Thrown("E\n\tat a\n\tat b", 3))
        f.accept(EvalEvent.Stopped)

        assertEquals(
            listOf(1, null, null, 2, 2, 3, 3, 3, null),
            f.texts.indices.map { f.model.jumpLine(it) },
        )
    }

    @Test
    fun `a thrown text without a document line has no jump target`() {
        val f = fixture()
        f.accept(EvalEvent.Thrown("E\n\tat x", null))

        assertNull(f.model.jumpLine(0))
        assertNull(f.model.jumpLine(1))
    }

    @Test
    fun `jump lines of indexes outside the paragraphs are null`() {
        val f = fixture()
        f.snippet(0, 4)

        assertEquals(4, f.model.jumpLine(0))
        assertNull(f.model.jumpLine(1))
        assertNull(f.model.jumpLine(-1))
    }

    @Test
    fun `a note and late output have no jump target`() {
        val f = fixture()
        f.note("n")
        f.late("l")

        assertNull(f.model.jumpLine(0))
        assertNull(f.model.jumpLine(1))
    }

    @Test
    fun `over the cap the oldest whole paragraphs are dropped`() {
        // a paragraph counts its characters plus one for its line end: 10 per paragraph here, a quarter of the cap each
        val f = fixture(cap = 40)
        f.out("aaaaaaaaa\nbbbbbbbbb\nccccccccc\nddddddddd\n")
        assertEquals(4, f.texts.size)
        assertEquals(0, f.last.dropped)

        f.out("eeeeeeeee\n")
        assertEquals(listOf("bbbbbbbbb", "ccccccccc", "ddddddddd", "eeeeeeeee"), f.texts)
        assertEquals(1, f.last.dropped)
    }

    @Test
    fun `jump indexes stay right after paragraphs were dropped`() {
        val f = fixture("a\nb\nc\nd\ne", cap = 40)
        (0..4).forEach { f.snippet(it * 2, it) }

        assertEquals(listOf("jshell> b", "jshell> c", "jshell> d", "jshell> e"), f.texts)
        assertEquals(listOf(1, 2, 3, 4), f.texts.indices.map { f.model.jumpLine(it) })
    }

    @Test
    fun `a continued paragraph counts towards the cap`() {
        // cap 67: a paragraph of 16 characters counts 17, four of them are 68
        val f = fixture(cap = 67)
        f.out("a".repeat(16) + "\n" + "b".repeat(16) + "\n" + "c".repeat(16) + "\n" + "dd")
        assertEquals(4, f.texts.size)

        f.out("d".repeat(14))

        assertEquals(listOf("b".repeat(16), "c".repeat(16), "d".repeat(16)), f.texts)
        assertEquals(1, f.last.dropped)
    }

    @Test
    fun `one output with many lines over the cap keeps the newest and reports only existing paragraphs dropped`() {
        val f = fixture(cap = 25)
        f.out("old\n")
        f.out((1..50).joinToString("") { "line$it\n" })

        assertEquals(listOf("line49", "line50"), f.texts.takeLast(2))
        assertTrue(f.texts.sumOf { it.length + 1 } <= 25)
        assertTrue(f.last.dropped <= 1)
    }

    @Test
    fun `a paragraph longer than a quarter of the cap is cut and the rest of the snippet's output dropped`() {
        val f = fixture("a\nb", cap = 400)
        f.snippet(0, 0)
        f.out("x".repeat(150) + "\nafter\n")
        f.out("more\n", error = true)
        f.late("late")
        f.accept(EvalEvent.Value("v ==> 1"))

        assertEquals(
            listOf("jshell> a", "x".repeat(100), "… output cut", "v ==> 1"),
            f.texts,
        )
        assertEquals(listOf(HEADER, OUTPUT, NOTE, VALUE), f.kinds)
    }

    @Test
    fun `a paragraph of exactly a quarter of the cap is not cut`() {
        val f = fixture(cap = 400)
        f.out("x".repeat(100) + "\n")

        assertEquals(listOf("x".repeat(100)), f.texts)
    }

    @Test
    fun `an open paragraph growing past the limit is cut`() {
        val f = fixture(cap = 400)
        f.out("x".repeat(60))
        f.out("y".repeat(60))
        assertEquals(listOf("x".repeat(60) + "y".repeat(40), "… output cut"), f.texts)
        assertEquals("y".repeat(40), f.last.append?.text)

        f.out("z")
        assertEquals(2, f.texts.size)
    }

    @Test
    fun `the next snippet's output is shown again after a cut`() {
        val f = fixture("a\nb", cap = 400)
        f.snippet(0, 0)
        f.out("x".repeat(500))
        f.snippet(2, 1)
        f.out("ok\n")

        assertEquals(listOf("jshell> a", "x".repeat(100), "… output cut", "jshell> b", "ok"), f.texts)
    }

    @Test
    fun `starting an evaluation shows output again after a cut`() {
        val f = fixture(cap = 400)
        f.out("x".repeat(500))
        f.start("y")
        f.out("ok")

        assertEquals(listOf("x".repeat(100), "… output cut", "ok"), f.texts)
    }

    @Test
    fun `a cut does not split an emoji`() {
        // the emoji straddles the cut: its first half is the 100th character
        val f = fixture(cap = 400)
        f.out("a".repeat(99) + "😀" + "b".repeat(10))

        assertEquals("a".repeat(99), f.texts.first())
    }

    @Test
    fun `other long paragraphs are cut with an ellipsis`() {
        val f = fixture(cap = 400)
        f.accept(EvalEvent.Value("v".repeat(500)))
        f.accept(EvalEvent.Thrown("t".repeat(500), 0))

        assertEquals(listOf("v".repeat(100) + "…", "t".repeat(100) + "…"), f.texts)
    }

    @Test
    fun `a ten megabyte output without line ends stays small and fast`() {
        val f = fixture()
        val started = System.nanoTime()
        f.accept(EvalEvent.Output("x".repeat(10_000_000), false))
        val ms = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf("x".repeat(262_144), "… output cut"), f.texts)
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `a ten megabyte output of short lines keeps the newest ones under the cap`() {
        val model = TranscriptModel()
        val started = System.nanoTime()
        val change = model.accept(EvalEvent.Output("0123456789\n".repeat(900_000), false))
        val ms = (System.nanoTime() - started) / 1_000_000

        assertTrue(model.paragraphs.sumOf { it.text.length + 1 } <= 1_048_576)
        assertEquals(model.paragraphs.size, change.added.size - change.dropped)
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `a hundred thousand tiny outputs stay fast and under the cap`() {
        val model = TranscriptModel()
        val started = System.nanoTime()
        repeat(100_000) { model.accept(EvalEvent.Output("0123456789abcdef$it\n", false)) }
        val ms = (System.nanoTime() - started) / 1_000_000

        assertEquals("0123456789abcdef99999", model.paragraphs.last().text)
        assertTrue(model.paragraphs.sumOf { it.text.length + 1 } <= 1_048_576)
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `a hundred thousand prints without a newline join into one capped paragraph without quadratic copying`() {
        val model = TranscriptModel()
        val started = System.nanoTime()
        repeat(100_000) { model.accept(EvalEvent.Output("abc", false)) }
        val ms = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf(262_144, 12), model.paragraphs.map { it.text.length })
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `astral characters in output, diagnostics and notes pass unchanged`() {
        val f = fixture()
        f.out("😀\n𝔘")
        f.accept(EvalEvent.Diagnostic(0, 1, 0, "😀 bad", true))
        f.note("🙂")

        assertEquals(listOf("😀", "𝔘", "line 1: 😀 bad", "🙂"), f.texts)
    }

    @Test
    fun `a diagnostic without a message shows its line`() {
        val f = fixture()
        f.accept(EvalEvent.Diagnostic(0, 1, 4, "", true))

        assertEquals(listOf("line 5:"), f.texts)
        assertEquals(4, f.model.jumpLine(0))
    }

    @Test
    fun `a value of exactly the paragraph limit is not cut`() {
        val f = fixture(cap = 400)
        f.accept(EvalEvent.Value("v".repeat(100)))

        assertEquals(listOf("v".repeat(100)), f.texts)
    }

    @Test
    fun `output after a paragraph that is exactly full cuts without adding to it`() {
        val f = fixture(cap = 400)
        f.out("x".repeat(100))
        f.out("y")

        assertEquals(listOf("x".repeat(100), "… output cut"), f.texts)
        assertNull(f.last.append)
    }

    @Test
    fun `empty lines inside one output are paragraphs`() {
        val f = fixture()
        f.out("a\n\nb\n\n\nc")

        assertEquals(listOf("a", "", "b", "", "", "c"), f.texts)
    }

    @Test
    fun `output after a line that ended starts a new paragraph`() {
        val f = fixture()
        f.out("a\n")
        f.out("b")
        f.out("\n")
        f.out("c")

        assertEquals(listOf("a", "b", "c"), f.texts)
    }

    @Test
    fun `the line end of an open paragraph adds nothing to the pane's text`() {
        val f = fixture()
        f.out("a")
        f.out("\n")

        assertNull(f.last.append)
        assertTrue(f.last.added.isEmpty())
    }

    @Test
    fun `a line feed after a header is not the second half of an earlier carriage return`() {
        val f = fixture("x")
        f.out("a\r")
        f.snippet(0, 0)
        f.out("\nb")

        assertEquals(listOf("a", "jshell> x", "", "b"), f.texts)
    }

    @Test
    fun `a cap below four characters is refused`() {
        assertFailsWith<IllegalArgumentException> { TranscriptModel(3) }
    }

    @Test
    fun `a cap of four characters is accepted`() {
        TranscriptModel(4)
    }

    @Test
    fun `a line feed of the other stream is a line end of its own, not the second half of a carriage return`() {
        val f = fixture()
        f.out("a\r")
        f.out("\nb", error = true)

        assertEquals(listOf("a", "", "b"), f.texts)
        assertEquals(listOf(OUTPUT, ERROR_OUTPUT, ERROR_OUTPUT), f.kinds)
    }

    @Test
    fun `a carriage return and its line feed split by the other stream's output make one line end`() {
        val f = fixture()
        f.out("a\r")
        f.out("x", error = true)
        f.out("\nb")

        assertEquals(listOf("a", "x", "b"), f.texts)
        assertEquals(listOf(OUTPUT, ERROR_OUTPUT, OUTPUT), f.kinds)
    }
}
