package dev.sluide.speech.ui

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.Voice

/** What the Play button does now. */
internal enum class PlayMode { PLAY, PAUSE, RESUME }

/** The style classes of the highlight, defined in `speech.css`. */
internal const val SENTENCE_STYLE = "speech-sentence"
internal const val WORD_STYLE = "speech-word"

/**
 * Everything the view shows of a [ReadAloudModel]; the model publishes a new one when it differs.
 *
 * @property textVersion counts the texts the model put in ([ReadAloudModel.read]); the view replaces its text when it
 *   changes. Typing does not change it.
 * @property overlay the sentence and word to highlight, in offsets of the shown text.
 * @property reveal the range to scroll into view: the word, else the sentence.
 * @property notice the setup problems; empty when there are none.
 */
internal data class ReadAloudUi(
    val textVersion: Int,
    val editable: Boolean,
    val playMode: PlayMode,
    val playEnabled: Boolean,
    val stopEnabled: Boolean,
    val status: String,
    val activity: ViewActivity,
    val overlay: List<StyledRange>,
    val reveal: IntRange?,
    val notice: List<SetupProblem>,
    val voice: Voice?,
    val speed: Double,
)
