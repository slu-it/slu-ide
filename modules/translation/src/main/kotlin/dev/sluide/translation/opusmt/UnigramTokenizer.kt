package dev.sluide.translation.opusmt

import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/** The word boundary mark of SentencePiece (Metaspace). */
private const val SPACE_MARK = '▁'

/** SentencePiece's penalty of an unknown character below the lowest piece score. */
private const val UNK_PENALTY = 10.0

/**
 * The SentencePiece Unigram tokenizer of an Opus-MT model, read from its Hugging Face `tokenizer.json` (ADR 0126):
 * no normalization (the models' `precompiled_charsmap` is null), words split at whitespace, each word prefixed with
 * "▁", the most likely segmentation by piece scores (Viterbi), `</s>` appended.
 *
 * Quirk of the Opus-MT exports: `model.unk_id` is 2, which is ",", so the unknown id is taken from the `<unk>` token.
 * Characters no piece covers become `<unk>`, consecutive ones fused into one as SentencePiece does. Immutable and
 * thread-safe.
 */
internal class UnigramTokenizer(
    vocab: List<Pair<String, Double>>,
    specialTokens: Map<String, Int>,
) {
    /** The id of `</s>`, appended to every encoded text. */
    val eosId: Int =
        specialTokens["</s>"] ?: vocab.indexOfFirst { it.first == "</s>" }.also { require(it >= 0) { "No </s> token" } }

    /** The id of `<unk>`. */
    val unkId: Int =
        specialTokens["<unk>"]
            ?: vocab.indexOfFirst { it.first == "<unk>" }.also { require(it >= 0) { "No <unk> token" } }

    private val specialIds: Set<Int> = specialTokens.values.toSet() + eosId + unkId
    private val pieces: Array<String> = Array(vocab.size) { vocab[it].first }
    private val scores = DoubleArray(vocab.size) { vocab[it].second }
    private val ids = HashMap<String, Int>(vocab.size * 2)
    private val maxPieceLength: Int
    private val unkScore: Double

    init {
        var longest = 1
        for ((id, piece) in pieces.withIndex()) {
            if (id in specialIds || piece.isEmpty()) continue
            ids.putIfAbsent(piece, id)
            longest = maxOf(longest, piece.length)
        }
        maxPieceLength = longest
        unkScore = (scores.minOrNull() ?: 0.0) - UNK_PENALTY
    }

    /** The id of [piece] in the vocabulary as one token (a target-language token like `>>cmn_Hans<<`), or null. */
    fun pieceId(piece: String): Int? = ids[piece]

    /** The ids of [text] with `</s>` at the end. */
    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        for (word in text.split(WHITESPACE)) {
            if (word.isNotEmpty()) segment(SPACE_MARK + word, out)
        }
        out.add(eosId)
        return out.toIntArray()
    }

    /** The text of [ids]: special tokens skipped, "▁" as spaces, Hugging Face's clean-up of spaces before punctuation. */
    fun decode(ids: IntArray): String {
        val text = StringBuilder()
        for (id in ids) {
            if (id in specialIds || id !in pieces.indices) continue
            text.append(pieces[id])
        }
        return cleanUp(text.toString().replace(SPACE_MARK, ' ').removePrefix(" "))
    }

    /** The most likely pieces of [word] (Viterbi over the piece scores), added to [out]. */
    private fun segment(
        word: String,
        out: MutableList<Int>,
    ) {
        val n = word.length
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val piece = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (start in 0 until n) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            val charLength = Character.charCount(word.codePointAt(start))
            var single = false
            for (length in 1..minOf(maxPieceLength, n - start)) {
                val id = ids[word.substring(start, start + length)] ?: continue
                if (length == charLength) single = true
                val score = best[start] + scores[id]
                if (score > best[start + length]) {
                    best[start + length] = score
                    from[start + length] = start
                    piece[start + length] = id
                }
            }
            if (!single) {
                val score = best[start] + unkScore
                if (score > best[start + charLength]) {
                    best[start + charLength] = score
                    from[start + charLength] = start
                    piece[start + charLength] = unkId
                }
            }
        }
        val reversed = ArrayList<Int>()
        var at = n
        while (at > 0) {
            val id = piece[at]
            if (id != unkId || reversed.lastOrNull() != unkId) reversed.add(id)
            at = from[at]
        }
        for (i in reversed.indices.reversed()) out.add(reversed[i])
    }

    companion object {
        /** Unicode whitespace (no-break and ideographic spaces too), as in `WhitespaceSplit`. */
        private val WHITESPACE = Regex("(?U)\\s+")

        private val mapper = JsonMapper.builder().build()

        fun load(file: Path): UnigramTokenizer = Files.newInputStream(file).use(::load)

        fun load(input: InputStream): UnigramTokenizer {
            val root = mapper.readTree(input)
            val model = root.path("model")
            val type = model.path("type").asString()
            require(type == "Unigram") { "The tokenizer is not a Unigram tokenizer ($type)" }
            val vocab = model.path("vocab")
            require(vocab.isArray && vocab.size() > 0) { "The tokenizer has no vocabulary" }
            val entries = ArrayList<Pair<String, Double>>(vocab.size())
            for (entry in vocab) entries.add(entry[0].asString() to entry[1].asDouble())
            val specials = HashMap<String, Int>()
            for (token in root.path("added_tokens")) {
                if (token.path("special").asBoolean()) {
                    specials[token.path("content").asString()] =
                        token.path("id").asInt()
                }
            }
            return UnigramTokenizer(entries, specials)
        }

        /** Hugging Face's `clean_up_tokenization` (the models' `clean_up_tokenization_spaces` is true). */
        internal fun cleanUp(text: String): String =
            text
                .replace(" .", ".")
                .replace(" ?", "?")
                .replace(" !", "!")
                .replace(" ,", ",")
                .replace(" ' ", "'")
                .replace(" n't", "n't")
                .replace(" 'm", "'m")
                .replace(" 's", "'s")
                .replace(" 've", "'ve")
                .replace(" 're", "'re")
    }
}
