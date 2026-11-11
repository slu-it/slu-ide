package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import dev.sluide.previews.TextMarks

/**
 * The named marks of the reader (the start of Read Aloud, the sentence, the word, the search hit): whole-book offsets,
 * end exclusive, shown on the shown page again after every load of a page ([applyAll]).
 */
internal class NamedMarks(
    private val textMarks: TextMarks,
    private val shown: () -> ShownPage?,
    private val book: () -> BookPage?,
    private val pageReady: () -> Boolean,
) {
    private val ranges = linkedMapOf<String, Pair<Int, Int>>()

    /** The offset where the marked start sentence begins, null without a mark. */
    val start: Int? get() = ranges[START]?.first

    /** The marked start as `[first, last + 1)`, null without a mark. */
    val startRange: Pair<Int, Int>? get() = ranges[START]

    /** Marks exactly the spoken [sentence] as the start (Read Aloud's own sentence bounds, not [SentenceBounds]). */
    fun setStart(sentence: IntRange) {
        ranges[START] = sentence.first to sentence.last + 1
        apply(START)
    }

    /** Whether the start mark is kept but not painted. */
    var startHidden = false
        set(value) {
            field = value
            apply(START)
        }

    /** Marks the sentence around the whole-book [offset] as the start, without showing it ([apply] does). */
    fun markSentence(offset: Int) {
        val text = book()?.text ?: return
        val sentence = SentenceBounds.around(text, offset)
        if (sentence.isEmpty()) return
        ranges[START] = sentence.first to sentence.last + 1
    }

    fun set(
        name: String,
        start: Int,
        end: Int,
    ) {
        ranges[name] = start to end
        apply(name)
    }

    fun clear(name: String) {
        ranges.remove(name)
        apply(name)
    }

    fun applyAll() {
        for (name in ranges.keys.toList()) apply(name)
    }

    /** Shows the mark [name] on the shown page: in chapter mode only the part in the shown chapter. */
    fun apply(name: String) {
        val range = if (name == START && startHidden) null else ranges[name]
        val local = shown()
        if (!pageReady() || local == null) return
        val clipped = range?.let { local.localRange(book()?.chapters.orEmpty(), it.first, it.second) }
        if (clipped == null) textMarks.clear(name) else textMarks.mark(name, clipped.first, clipped.second)
    }

    companion object {
        const val START = "start"
    }
}
