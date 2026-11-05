package dev.sluide.translation.opusmt

import dev.sluide.localmodels.ModelFile
import dev.sluide.translation.engine.Quality
import java.net.URI

/** The files of one Opus-MT pair: [shared] by both qualities (tokenizer, configs) and the encoder and decoder of each. */
internal data class PairFiles(
    val shared: List<ModelFile>,
    val medium: List<ModelFile>,
    val high: List<ModelFile>,
) {
    fun of(quality: Quality): List<ModelFile> =
        when (quality) {
            Quality.MEDIUM -> medium
            Quality.HIGH -> high
        }
}

/**
 * The pinned Opus-MT models (ADR 0126): per pair (`de-en`) its files with URL, size and sha256, read from the resource
 * `opus-mt-catalog.tsv` that `modules/translation/scripts/generate-opus-mt-catalog.sh` writes. Immutable.
 */
internal class OpusMtCatalog(
    val pairs: Map<String, PairFiles>,
) {
    operator fun contains(pair: String): Boolean = pair in pairs

    companion object {
        const val RESOURCE = "/dev/sluide/translation/opus-mt-catalog.tsv"

        /** The catalog of the bundled resource. */
        fun load(): OpusMtCatalog {
            val stream =
                OpusMtCatalog::class.java.getResourceAsStream(RESOURCE)
                    ?: throw IllegalStateException("$RESOURCE is missing")
            return parse(stream.bufferedReader().use { it.readText() })
        }

        /** Parses the TSV: `pair quality path url bytes sha256`; `#` lines are comments. */
        fun parse(text: String): OpusMtCatalog {
            val rows = LinkedHashMap<String, MutableMap<String, MutableList<ModelFile>>>()
            for ((index, line) in text.lines().withIndex()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val columns = line.split('\t')
                require(columns.size == 6) { "Line ${index + 1} of the Opus-MT catalog has ${columns.size} columns" }
                val (pair, quality, path, url, bytes, sha256) = columns
                rows
                    .getOrPut(pair) { HashMap() }
                    .getOrPut(quality) { ArrayList() }
                    .add(ModelFile(path, URI(url), bytes.toLong(), sha256))
            }
            val pairs =
                rows.mapValues { (pair, byQuality) ->
                    fun files(quality: String) =
                        byQuality[quality] ?: throw IllegalArgumentException("$pair has no $quality files")
                    PairFiles(files("shared"), files("medium"), files("high"))
                }
            return OpusMtCatalog(pairs)
        }

        private operator fun <T> List<T>.component6(): T = get(5)
    }
}
