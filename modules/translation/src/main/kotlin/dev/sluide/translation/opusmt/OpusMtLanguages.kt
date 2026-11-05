package dev.sluide.translation.opusmt

import dev.sluide.translation.engine.Language

/** The languages of the Opus-MT catalog by code, with their English names (ADR 0126). */
internal object OpusMtLanguages {
    const val ENGLISH = "en"

    val names: Map<String, String> =
        mapOf(
            "af" to "Afrikaans",
            "ar" to "Arabic",
            "cs" to "Czech",
            "da" to "Danish",
            "de" to "German",
            "en" to "English",
            "es" to "Spanish",
            "et" to "Estonian",
            "fi" to "Finnish",
            "fr" to "French",
            "hi" to "Hindi",
            "hu" to "Hungarian",
            "id" to "Indonesian",
            "it" to "Italian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "nl" to "Dutch",
            "pl" to "Polish",
            "ro" to "Romanian",
            "ru" to "Russian",
            "sv" to "Swedish",
            "th" to "Thai",
            "tr" to "Turkish",
            "uk" to "Ukrainian",
            "vi" to "Vietnamese",
            "xh" to "Xhosa",
            "zh" to "Chinese",
        )

    /** The English name of [code]; the code itself for a language without one (a catalog the map does not know). */
    fun name(code: String): String = names[code] ?: code

    fun language(code: String) = Language(code, name(code))

    /**
     * The multi-target models of the catalog and the token each needs at the start of its input ("a sentence initial
     * language token is required in the form of >>id<<", their model cards): Simplified Chinese, Standard Arabic,
     * Vietnamese.
     */
    private val targetTokens = mapOf("en-zh" to ">>cmn_Hans<<", "en-ar" to ">>ara<<", "en-vi" to ">>vie<<")

    /** The target-language token [pair]'s model needs before the text, or null when it has a single target. */
    fun targetToken(pair: String): String? = targetTokens[pair]

    /** English and every language with a model into English, by name. */
    fun sources(catalog: OpusMtCatalog): List<Language> = offered(catalog, from = false)

    /** English and every language with a model from English, by name. */
    fun targets(catalog: OpusMtCatalog): List<Language> = offered(catalog, from = true)

    private fun offered(
        catalog: OpusMtCatalog,
        from: Boolean,
    ): List<Language> {
        val (english, other) = if (from) 0 to 1 else 1 to 0
        val codes =
            catalog.pairs.keys
                .map { it.split('-') }
                .filter { it[english] == ENGLISH }
                .map { it[other] }
        return (codes + ENGLISH)
            .distinct()
            .map(::language)
            .sortedBy { it.name }
    }
}
