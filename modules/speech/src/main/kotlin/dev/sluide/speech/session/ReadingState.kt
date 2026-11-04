package dev.sluide.speech.session

/**
 * Where a [ReadingSession] stands. [PREPARING] until enough audio is buffered ([StartRule]), then [SPEAKING];
 * [PAUSED] by the user from either. [FINISHED], [STOPPED] and [FAILED] are final.
 */
enum class ReadingState { PREPARING, SPEAKING, PAUSED, FINISHED, STOPPED, FAILED }

/**
 * What a reading is speaking now. All offsets are in `SpokenText.text`, ends exclusive.
 *
 * @property chunkIndex the index of the chunk in the list given to the [ReadingSession] (also for the pieces of a
 *   split first chunk).
 * @property sentenceStart the start of the sentence to highlight.
 * @property wordStart the start of the word being spoken, or null between words.
 */
data class ReadingPosition(
    val chunkIndex: Int,
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val wordStart: Int?,
    val wordEnd: Int?,
)
