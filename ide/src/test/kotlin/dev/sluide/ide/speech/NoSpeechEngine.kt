package dev.sluide.ide.speech

import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.engine.SpeechEngine
import dev.sluide.speech.engine.SpeechException
import dev.sluide.speech.engine.SynthesizedChunk
import dev.sluide.speech.engine.Voice

/**
 * An engine that is never ready, so that no window test synthesizes or opens a sound line (the machine may have the
 * real model and espeak-ng under `~/.sluide`): reading only shows the setup notice.
 */
internal object NoSpeechEngine : SpeechEngine {
    override val id = "none"
    override val voices = listOf(Voice("none-1", "Nobody", "en-US", Gender.FEMALE, "A", id))

    override fun problems() = listOf(SetupProblem.ToolMissing("espeak-ng", "Not installed in tests."))

    override fun synthesize(
        chunk: SpeechChunk,
        voice: Voice,
        speed: Double,
    ): SynthesizedChunk = throw SpeechException("The test engine does not speak.")
}
