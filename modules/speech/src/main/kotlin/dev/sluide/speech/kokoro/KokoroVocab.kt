package dev.sluide.speech.kokoro

import tools.jackson.databind.json.JsonMapper

/**
 * The token id of each phoneme character the Kokoro model knows (the `vocab` of kokoro-onnx's `config.json`, MIT, see
 * `NOTICE-kokoro-onnx.txt`). Characters outside it are dropped by the tokenizer.
 */
internal class KokoroVocab(
    private val ids: Map<Char, Long>,
) {
    fun id(c: Char): Long? = ids[c]

    /** Whether [other] (the model's own vocab, by single-character key) has exactly these entries. */
    fun matches(other: Map<String, Long>): Boolean {
        val single = other.filterKeys { it.length == 1 }
        return single.size == ids.size && ids.all { (c, id) -> single[c.toString()] == id }
    }

    companion object {
        private const val RESOURCE = "kokoro-vocab.json"

        fun load(): KokoroVocab {
            val stream =
                KokoroVocab::class.java.getResourceAsStream(RESOURCE)
                    ?: error("Resource $RESOURCE is missing")
            val root = stream.use { JsonMapper.builder().build().readTree(it) }
            val ids = HashMap<Char, Long>()
            for (entry in root.properties()) {
                val key = entry.key
                if (key.length == 1) ids[key[0]] = entry.value.asLong()
            }
            return KokoroVocab(ids)
        }
    }
}
