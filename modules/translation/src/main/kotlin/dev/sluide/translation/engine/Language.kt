package dev.sluide.translation.engine

/** A language the engine offers: its Opus-MT [code] (`de`, `en`, `sv`) and its English [name] ("German"). */
data class Language(
    val code: String,
    val name: String,
)

/** The model precision a translation runs with: Medium is the int8 model (the default), High the fp32 one. */
enum class Quality { MEDIUM, HIGH }

/** Detects the language of a text; FX-free and thread-safe, called off the FX thread. */
fun interface LanguageDetector {
    /** The language of [text] among [among], or null when it cannot be told (too short, none of them). */
    fun detect(
        text: String,
        among: Collection<Language>,
    ): Language?
}
