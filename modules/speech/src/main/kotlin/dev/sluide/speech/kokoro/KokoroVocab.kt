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
