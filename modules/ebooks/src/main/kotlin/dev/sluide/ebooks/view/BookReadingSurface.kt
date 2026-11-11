package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import dev.sluide.speech.ReadAloudSurface
import dev.sluide.speech.ReadingText

/** What [BookReadingSurface] needs from the reader view; the adapter over the view is in [ReaderReadAloud]. */
internal interface ReaderView {
    val page: BookPage?

    /** The marked start, else the top of the view, else 0 (whole-book). */
    fun startOffset(): Int

    fun markRange(
        name: String,
        start: Int,
        end: Int,
    )

    fun clearMark(name: String)

    fun showPosition(offset: Int)

    /** Whole-book jump; loads the chapter in chapter mode. */
    fun jumpTo(offset: Int)

    /** The local offset on the shown page, null if the page does not show it. */
    fun toLocal(offset: Int): Int?

    fun isVisible(
        localStart: Int,
        localEnd: Int,
    ): Boolean

    fun revealLocal(local: Int)

    /** Whether single clicks mark the start. */
    var startClicks: Boolean

    fun showBackButton(show: Boolean)
}

/**
 * Read Aloud's [ReadAloudSurface] over the reader's page (ADR 0164, ADR 0165): what to read, the highlight, the lock,
 * following the reading and reading on from chapter to chapter. FX-free; [controls] is set by the wiring.
 */
internal class BookReadingSurface(
    private val view: ReaderView,
) : ReadAloudSurface {
    var controls: ReaderControls? = null
        set(value) {
            field = value
            value?.onFinished = ::finished
        }

    /** Whether the page scrolls along with the reading; a user scroll away stops it. */
    var following = true
        private set

    private var pendingStart: Int? = null
    private var lastEnd = 0
    private var leftSight = false
    private var sentence: IntRange? = null
    private var word: IntRange? = null
    private var continuing = false
    private var jumpedChapter: Int? = null

    override fun readingText(): ReadingText? {
        val page = view.page ?: return null
        val start = (pendingStart ?: view.startOffset()).coerceIn(0, page.text.length)
        pendingStart = null
        val end = readingEnd(page, start)
        lastEnd = end
        return ReadingText(page.text, start, end, page.skip)
    }

    private fun readingEnd(
        page: BookPage,
        start: Int,
    ): Int {
        val chapterEnd = page.chapters.getOrNull(chapterAt(page.chapters, start))?.end ?: page.text.length
        val end = chapterEnd.coerceIn(start, page.text.length)
        val limit = start + MAX_CHARS
        if (end <= limit) return end
        val cut = page.text.lastIndexOf("\n\n", limit)
        return if (cut > start) cut else limit
    }

    override fun lock(locked: Boolean) {
        view.startClicks = !locked
        if (locked) {
            // A reading on from the previous chapter keeps a user's scrolling away.
            if (!continuing) following = true
            view.showBackButton(!following)
        } else {
            view.showBackButton(false)
        }
    }

    override fun highlight(
        sentence: IntRange?,
        word: IntRange?,
    ) {
        if (sentence != this.sentence) {
            this.sentence = sentence
            if (sentence == null) view.clearMark(SENTENCE) else view.showPosition(sentence.first)
            if (sentence != null) view.markRange(SENTENCE, sentence.first, sentence.last + 1)
        }
        this.word = word
        if (word == null) view.clearMark(WORD) else view.markRange(WORD, word.first, word.last + 1)
    }

    override fun reveal(
        start: Int,
        end: Int,
    ) {
        if (following) show(start, end)
    }

    private fun show(
        start: Int,
        end: Int,
    ) {
        val local = view.toLocal(start)
        if (local == null) {
            // Another chapter: load it once, not at every word until it is there.
            val chapter = view.page?.let { chapterAt(it.chapters, start) }
            if (jumpedChapter != chapter) view.jumpTo(start)
            jumpedChapter = chapter
            return
        }
        jumpedChapter = null
        if (!view.isVisible(local, local + (end - start).coerceAtLeast(1))) view.revealLocal(local)
    }

    /**
     * A genuine user scroll of the page: while reading it stops following; once the sentence has been out of view
     * and is in view again, it resumes (not within the gesture that started the pause).
     */
    fun userScrolled() {
        if (controls?.reading != true) return
        if (following) {
            pause()
        } else if (!sentenceVisible()) {
            leftSight = true
        } else if (leftSight) {
            resume(reveal = false)
        }
    }

    /** The user moved the page without scrolling (Contents, chapter buttons, links): stop following. */
    fun userNavigated() {
        if (controls?.reading == true && following) pause()
    }

    /** A page was loaded: a chapter jump asked for before may have been replaced. */
    fun pageLoaded() {
        jumpedChapter = null
    }

    private fun pause() {
        following = false
        leftSight = false
        view.showBackButton(true)
    }

    /** The "Back to reading" button. */
    fun backToReading() = resume(reveal = true)

    private fun resume(reveal: Boolean) {
        following = true
        view.showBackButton(false)
        val current = word ?: sentence
        if (reveal && current != null) show(current.first, current.last + 1)
    }

    private fun sentenceVisible(): Boolean {
        val current = sentence ?: return false
        val local = view.toLocal(current.first) ?: return false
        return view.isVisible(local, local + current.count())
    }

    /**
     * A double-click at the whole-book [offset] while reading restarts at its sentence and follows again; returns
     * whether it did. An offset the running reading cannot jump to (outside its text, or more than [MAX_CHARS] before
     * its end) starts a new reading there.
     */
    fun doubleClicked(offset: Int?): Boolean {
        val active = controls ?: return false
        if (!active.reading || offset == null) return false
        resume(reveal = false)
        if (offset in (lastEnd - MAX_CHARS).coerceAtLeast(0) until lastEnd) {
            active.jumpTo(offset)
        } else {
            active.stop()
            pendingStart = offset
            try {
                active.play()
            } finally {
                pendingStart = null
            }
        }
        return true
    }

    private fun finished() {
        val page = view.page ?: return
        val next = nextStart(page) ?: return
        pendingStart = next
        continuing = true
        try {
            controls?.play()
        } finally {
            continuing = false
            pendingStart = null
        }
    }

    private fun nextStart(page: BookPage): Int? {
        val chapters = page.chapters
        val containing = chapters.getOrNull(chapterAt(chapters, (lastEnd - 1).coerceAtLeast(0)))
        // A reading cut at the size limit goes on where it ended.
        if (containing != null && lastEnd < containing.end && lastEnd < page.text.length) return lastEnd
        // A chapter without text (an image) ends where it starts: skip it, or the reading would come back to it.
        return chapters.firstOrNull { it.end > lastEnd }?.start?.coerceAtLeast(lastEnd)
    }

    companion object {
        const val SENTENCE = "sentence"
        const val WORD = "word"
        const val MAX_CHARS = 1_000_000
    }
}
