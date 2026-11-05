package dev.sluide.translation.detect

import com.github.pemistahl.lingua.api.IsoCode639_1
import com.github.pemistahl.lingua.api.IsoCode639_3
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import dev.sluide.translation.engine.Language
import dev.sluide.translation.engine.LanguageDetector
import java.util.concurrent.ConcurrentHashMap
import com.github.pemistahl.lingua.api.Language as LinguaLanguage
import com.github.pemistahl.lingua.api.LanguageDetector as Lingua

/**
 * Detects the source language with Lingua in low-accuracy mode (unigram, bigram and trigram models only, so that the
 * fat jar can drop the larger ones, ADR 0126).
 *
 * Only the requested languages Lingua knows take part: matched by the ISO 639-1 code, by an alias (Opus-MT's `no`,
 * `jap`) or by a three-letter ISO 639-3 code; group codes (`mul`, `gem`, `gmw`) are unknown. With fewer than two of
 * them there is nothing to choose between, so the result is null. The first [MAX_CHARS] characters are used.
 *
 * The answer is null when Lingua finds no language, or when the runner-up is too close to the best: Lingua's
 * `computeLanguageConfidenceValues` are relative (the best is always 1.0), so the best is trusted only when the
 * second one's value is at most [maxRunnerUp]. Measured with low accuracy and en/de/sv/fr: "Guten Tag" 0.50,
 * "Das ist gut" 0.31, "Bonjour" 0.71, a long German sentence 0.67, an English one 0.80, "The server" 0.92 and
 * "Hello world" 0.96. This is not a guard for short texts: the ratio settles by how close the languages are, so two
 * close languages that are both offered (Croatian and Bosnian, Indonesian and Malay, Bokmål and Danish near the edge)
 * are not told apart however long the text is. A wrong guess makes a worse translation than asking the user.
 *
 * Building a Lingua detector is cheap: its models load lazily on the first detection into Lingua's JVM-wide static
 * maps, shared by all detectors, and stay loaded (about 27 MB for 40 languages, ADR 0126). The cache per set of
 * candidate languages only saves the build. Lingua detectors are thread-safe.
 */
class LinguaDetector(
    private val maxRunnerUp: Double = MAX_RUNNER_UP,
) : LanguageDetector {
    private val detectors = ConcurrentHashMap<Set<LinguaLanguage>, Lingua>()

    /** The number of distinct candidate sets a detector was built for. */
    internal val cachedDetectors: Int get() = detectors.size

    override fun detect(
        text: String,
        among: Collection<Language>,
    ): Language? {
        val sample = text.take(MAX_CHARS)
        if (sample.isBlank()) return null
        val byLingua = LinkedHashMap<LinguaLanguage, Language>()
        for (language in among) linguaLanguage(language.code)?.let { byLingua.putIfAbsent(it, language) }
        if (byLingua.size < 2) return null
        val detector = detectors.computeIfAbsent(byLingua.keys.toSet()) { build(it) }
        val ranked =
            detector
                .computeLanguageConfidenceValues(
                    sample,
                ).entries
                .filter { it.key != LinguaLanguage.UNKNOWN }
        val best = ranked.firstOrNull()?.takeIf { it.value > 0.0 } ?: return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
        return if (runnerUp / best.value <= maxRunnerUp) byLingua[best.key] else null
    }

    private fun build(languages: Set<LinguaLanguage>): Lingua =
        LanguageDetectorBuilder
            .fromLanguages(*languages.toTypedArray())
            // The fat jar has only the small models (`linguaSmallModels` in ide/build.gradle.kts); a missing model
            // file fails silently, so a model that low-accuracy mode starts to need must be kept there.
            .withLowAccuracyMode()
            .build()

    companion object {
        /** Characters of the text that are looked at. */
        const val MAX_CHARS = 10_000

        /** The highest runner-up value, relative to the best, that still counts as a clear answer. */
        const val MAX_RUNNER_UP = 0.9

        private val isoCodes: Map<String, IsoCode639_1> = IsoCode639_1.entries.associateBy { it.name.lowercase() }
        private val isoCodes3: Map<String, IsoCode639_3> = IsoCode639_3.entries.associateBy { it.name.lowercase() }

        /** Opus-MT codes that are no ISO 639-1 or 639-3 code of the language Lingua has. */
        private val aliases: Map<String, LinguaLanguage> =
            mapOf("no" to LinguaLanguage.BOKMAL, "jap" to LinguaLanguage.JAPANESE)

        /** Lingua's language for an Opus-MT [code], or null when Lingua has none. */
        internal fun linguaLanguage(code: String): LinguaLanguage? {
            val lower = code.lowercase()
            val found =
                aliases[lower]
                    ?: isoCodes[lower]?.let { LinguaLanguage.getByIsoCode639_1(it) }
                    ?: isoCodes3[lower]?.let { LinguaLanguage.getByIsoCode639_3(it) }
            return found?.takeIf { it != LinguaLanguage.UNKNOWN }
        }
    }
}
