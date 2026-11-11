package dev.sluide.speech

/**
 * What a reading needs from a view of another module that shows the text and draws the highlight itself (an e-book
 * page in a WebView): see [ReadAloud.controls] and ADR 0164. All offsets are in [ReadingText.shown] of the last
 * [readingText]; all calls are made on the FX thread.
 */
interface ReadAloudSurface {
    /** The text to read, asked at every Play; null shows "Nothing to read." and starts nothing. */
    fun readingText(): ReadingText?

    /** Locks the text (true while a reading is preparing, speaking or paused) or unlocks it; called on changes only. */
    fun lock(locked: Boolean)

    /**
     * Highlights the [sentence] being read and the [word] being spoken, each `start until end` (the last offset is
     * `end - 1`); null clears a highlight. Called when the highlight changes only; both null after Stop, the end or an
     * error.
     */
    fun highlight(
        sentence: IntRange?,
        word: IntRange?,
    )

    /** Scrolls the range from [start] to [end] (exclusive) into view: the spoken word, else the sentence. */
    fun reveal(
        start: Int,
        end: Int,
    )
}
