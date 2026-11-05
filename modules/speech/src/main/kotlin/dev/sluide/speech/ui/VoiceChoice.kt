package dev.sluide.speech.ui

import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.Voice

/** The choice of a voice by accent (language) and gender, independent of the engine. */
internal object VoiceChoice {
    /** The distinct languages of [voices], in their order. */
    fun accents(voices: List<Voice>): List<String> = voices.map { it.language }.distinct()

    /** The genders [voices] have in [language], in their order. */
    fun genders(
        voices: List<Voice>,
        language: String,
    ): List<Gender> = voices.filter { it.language == language }.map { it.gender }.distinct()

    /** The first voice of [language] and [gender], else the first of [language], else null. */
    fun voiceFor(
        voices: List<Voice>,
        language: String,
        gender: Gender,
    ): Voice? {
        val inLanguage = voices.filter { it.language == language }
        return inLanguage.firstOrNull { it.gender == gender } ?: inLanguage.firstOrNull()
    }

    fun accentName(language: String): String =
        when (language) {
            "en-US" -> "American English"
            "en-GB" -> "British English"
            else -> language
        }

    fun genderName(gender: Gender): String = if (gender == Gender.FEMALE) "Female" else "Male"
}
