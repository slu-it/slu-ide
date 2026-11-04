package dev.sluide.speech.kokoro

/**
 * Turns espeak-ng IPA (`--ipa --tie=^`) into Kokoro's phoneme alphabet; a port of misaki's `EspeakFallback`
 * (`misaki/espeak.py`). Filtering to the vocabulary is the tokenizer's job.
 *
 * misaki applies the table first and the British `e^ə` rule afterwards, so the table's `e → A` has already turned
 * `e^ə` into `A^ə` and that rule never matches. This port keeps misaki's order and so its output.
 */
internal object EspeakToKokoro {
    private const val SYLLABIC = '̩'

    /** misaki's `E2M` in its dict order; sorted by key length, longest first (stable, like Python's sort). */
    private val table: List<Pair<String, String>> =
        listOf(
            "ʔˌn̩" to "ʔn",
            "ʔn̩" to "ʔn",
            "a^ɪ" to "I",
            "a^ʊ" to "W",
            "d^ʒ" to "ʤ",
            "e^ɪ" to "A",
            "e" to "A",
            "t^ʃ" to "ʧ",
            "ɔ^ɪ" to "Y",
            "ə^l" to "ᵊl",
            "ʲo" to "jo",
            "ʲə" to "jə",
            "ʲ" to "",
            "ɚ" to "əɹ",
            "r" to "ɹ",
            "x" to "k",
            "ç" to "k",
            "ɐ" to "ə",
            "ɬ" to "l",
            "̃" to "",
        ).sortedByDescending { it.first.length }

    private val syllabicMark = Regex("(\\S)$SYLLABIC")

    fun convert(
        ipa: String,
        british: Boolean,
    ): String {
        var ps = ipa.trim()
        for ((old, new) in table) ps = ps.replace(old, new)
        ps = syllabicMark.replace(ps, "ᵊ\$1").replace(SYLLABIC.toString(), "")
        ps =
            if (british) {
                ps.replace("e^ə", "ɛː").replace("iə", "ɪə").replace("ə^ʊ", "Q")
            } else {
                ps
                    .replace("o^ʊ", "O")
                    .replace("ɜːɹ", "ɜɹ")
                    .replace("ɜː", "ɜɹ")
                    .replace("ɪə", "iə")
                    .replace("ː", "")
            }
        return ps
            .replace('o', 'ɔ')
            .replace('ɾ', 'T')
            .replace('ʔ', 't')
            .replace("^", "")
    }
}
