package dev.sluide.speech.engine

import org.springframework.stereotype.Component

/**
 * All [SpeechEngine] beans, in their order (`@Order`). There may be none: Spring passes an empty list to the only
 * constructor's list parameter when no engine bean exists (`SpeechEnginesTest`).
 */
@Component
class SpeechEngines(
    engines: List<SpeechEngine>,
) {
    private val engines = engines.toList()

    /** All engines' voices, in engine order. */
    val voices: List<Voice> = this.engines.flatMap { it.voices }

    /** The engine that speaks [voice], found by [Voice.engineId]; [IllegalArgumentException] when there is none. */
    fun engineFor(voice: Voice): SpeechEngine =
        requireNotNull(engines.firstOrNull { it.id == voice.engineId }) {
            "No speech engine '${voice.engineId}' for voice '${voice.id}'"
        }
}
