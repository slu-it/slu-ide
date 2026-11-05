package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.Voice

/**
 * The four Kokoro-82M voices Thoreau offers, one per accent and gender: American before British, female before male.
 * The rule (as in the Python prototype): the best-graded voice of the accent and gender, ties broken in favour of the
 * one the prototype uses. The first voice, `af_heart`, is the default.
 */
internal object KokoroVoices {
    const val ENGINE_ID = "kokoro"

    private fun voice(
        id: String,
        language: String,
        gender: Gender,
        grade: String,
    ) = Voice(
        id = id,
        name = id.substringAfter('_').replaceFirstChar { it.uppercase() },
        language = language,
        gender = gender,
        grade = grade,
        engineId = ENGINE_ID,
    )

    /** The voice for each (language, gender): best grade per accent and gender; ties as in the prototype. */
    internal val BY_ACCENT_AND_GENDER: Map<Pair<String, Gender>, Voice> =
        linkedMapOf(
            ("en-US" to Gender.FEMALE) to voice("af_heart", "en-US", Gender.FEMALE, "A"),
            ("en-US" to Gender.MALE) to voice("am_michael", "en-US", Gender.MALE, "C+"),
            ("en-GB" to Gender.FEMALE) to voice("bf_emma", "en-GB", Gender.FEMALE, "B-"),
            ("en-GB" to Gender.MALE) to voice("bm_george", "en-GB", Gender.MALE, "C"),
        )

    val all: List<Voice> = BY_ACCENT_AND_GENDER.values.toList()
}
