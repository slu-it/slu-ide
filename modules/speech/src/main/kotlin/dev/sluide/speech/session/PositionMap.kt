package dev.sluide.speech.session

import dev.sluide.speech.engine.WordTiming

/**
 * A piece of a reading as written to the sink: its audio starts at [startFrame] (frames since the sink opened), and
 * its [words] time samples relative to that start.
 */
internal class PlacedPiece(
    val chunkIndex: Int,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val startFrame: Long,
    val words: List<WordTiming>,
)

/**
 * The position at [playedFrames] among the first [count] of [pieces] (ascending [PlacedPiece.startFrame]; the first
 * starts at 0): the last piece started by then, and its word whose sample range holds the frame, else no word.
 * Null when no piece is placed. Binary searches, cheap enough to poll.
 */
internal fun positionAt(
    pieces: Array<PlacedPiece?>,
    count: Int,
    playedFrames: Long,
): ReadingPosition? {
    if (count <= 0) return null
    val piece =
        requireNotNull(
            pieces[lastAtOrBefore(count, playedFrames) { requireNotNull(pieces[it]).startFrame }.coerceAtLeast(0)],
        )
    val offset = playedFrames - piece.startFrame
    val word =
        lastAtOrBefore(piece.words.size, offset) { piece.words[it].startSample.toLong() }
            .takeIf { it >= 0 }
            ?.let { piece.words[it] }
            ?.takeIf { offset < it.endSample }
    return ReadingPosition(piece.chunkIndex, piece.sentenceStart, piece.sentenceEnd, word?.start, word?.end)
}

/** The last index in 0 until [size] whose [key] (ascending) is at most [value], or -1. */
private inline fun lastAtOrBefore(
    size: Int,
    value: Long,
    key: (Int) -> Long,
): Int {
    var low = 0
    var high = size - 1
    var found = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (key(middle) <= value) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}
