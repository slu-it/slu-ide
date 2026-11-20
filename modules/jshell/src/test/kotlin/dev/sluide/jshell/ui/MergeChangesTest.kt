package dev.sluide.jshell.ui

import dev.sluide.jshell.EvalEvent
import dev.sluide.jshell.ui.ParagraphKind.HEADER
import dev.sluide.jshell.ui.ParagraphKind.OUTPUT
import dev.sluide.jshell.ui.ParagraphKind.VALUE
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pane merges the changes of one FX event into one; applied to the pane's paragraphs it must give the model's. */
class MergeChangesTest {
    private fun out(text: String) = TranscriptParagraph(text, OUTPUT)

    private fun change(
        append: TranscriptParagraph? = null,
        added: List<TranscriptParagraph> = emptyList(),
        dropped: Int = 0,
    ) = TranscriptChange(append, added, dropped)

    /** What the pane does with a change. */
    private fun apply(
        shown: List<TranscriptParagraph>,
        change: TranscriptChange,
    ): List<TranscriptParagraph> {
        val paragraphs = shown.toMutableList()
        change.append?.let {
            paragraphs[paragraphs.lastIndex] = paragraphs.last().copy(text = paragraphs.last().text + it.text)
        }
        paragraphs += change.added
        repeat(change.dropped) { paragraphs.removeAt(0) }
        return paragraphs
    }

    @Test
    fun `no changes merge to nothing`() {
        assertEquals(change(), mergeChanges(emptyList(), 3))
    }

    @Test
    fun `one change stays as it is`() {
        val one = change(out("x"), listOf(out("a"), out("b")), 1)

        assertEquals(one, mergeChanges(listOf(one), 2))
    }

    @Test
    fun `appends before anything was added join and go to the last shown paragraph`() {
        val merged = mergeChanges(listOf(change(out("a")), change(out("b"))), 1)

        assertEquals(change(out("ab")), merged)
    }

    @Test
    fun `an append after an added paragraph goes into that paragraph`() {
        val merged =
            mergeChanges(
                listOf(change(out("a")), change(added = listOf(out("x"), out("y"))), change(out("z"))),
                1,
            )

        assertEquals(change(out("a"), listOf(out("x"), out("yz"))), merged)
    }

    @Test
    fun `the kind of an append is that of its paragraph`() {
        val merged = mergeChanges(listOf(change(TranscriptParagraph("a", VALUE))), 1)

        assertEquals(VALUE, merged.append?.kind)
    }

    @Test
    fun `added paragraphs keep their order and kinds`() {
        val header = TranscriptParagraph("h", HEADER)
        val merged = mergeChanges(listOf(change(added = listOf(header)), change(added = listOf(out("o")))), 0)

        assertEquals(listOf(header, out("o")), merged.added)
    }

    @Test
    fun `drops add up while they reach shown paragraphs only`() {
        val merged = mergeChanges(listOf(change(dropped = 1), change(dropped = 2)), 5)

        assertEquals(change(dropped = 3), merged)
    }

    @Test
    fun `drops beyond the shown paragraphs remove the first added ones instead`() {
        val merged =
            mergeChanges(
                listOf(change(added = listOf(out("a"), out("b"), out("c")), dropped = 2), change(dropped = 1)),
                1,
            )

        assertEquals(change(added = listOf(out("c")), dropped = 1), merged)
    }

    @Test
    fun `a clear with paragraphs added before and after it leaves only those after it`() {
        val merged =
            mergeChanges(
                listOf(
                    change(added = listOf(out("before"))),
                    change(dropped = 3),
                    change(added = listOf(out("after"))),
                ),
                2,
            )

        assertEquals(change(added = listOf(out("after")), dropped = 2), merged)
    }

    @Test
    fun `a clear alone drops everything shown`() {
        assertEquals(change(dropped = 4), mergeChanges(listOf(change(dropped = 4)), 4))
    }

    @Test
    fun `batches of random output, headers, notes and clears give the model's paragraphs, with a small cap`() {
        val random = Random(42)
        repeat(60) { round ->
            val model = TranscriptModel(capChars = 40 + random.nextInt(60))
            model.startEvaluation("doc")
            var pane = emptyList<TranscriptParagraph>()
            repeat(25) { batch ->
                val queued = ArrayList<TranscriptChange>()
                repeat(1 + random.nextInt(12)) {
                    queued +=
                        when (random.nextInt(10)) {
                            0 -> model.clear()
                            1 -> model.accept(EvalEvent.SnippetStarted(0, 1, random.nextInt(9)))
                            2 -> model.note("note ${random.nextInt(99)}")
                            3 -> model.accept(EvalEvent.Value("v ==> ${random.nextInt(99)}"))
                            else -> model.accept(EvalEvent.Output(chunk(random), random.nextBoolean()))
                        }
                }
                val merged = mergeChanges(queued, pane.size)
                assertTrue(merged.dropped <= pane.size, "dropped ${merged.dropped} of ${pane.size}")
                pane = apply(pane, merged)
                assertEquals(model.paragraphs, pane, "round $round batch $batch")
            }
        }
    }

    private fun chunk(random: Random): String =
        (0 until random.nextInt(14)).joinToString("") {
            "ab\n\rxyz".random(random).toString()
        }
}
