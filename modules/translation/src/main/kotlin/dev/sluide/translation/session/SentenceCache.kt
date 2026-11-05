package dev.sluide.translation.session

import dev.sluide.translation.engine.Quality

/**
 * The translations of one window's sentences, least recently used dropped beyond [capacity] entries or [maxChars]
 * characters (sentences and translations): an edit sends only the changed sentences to the engine. Keyed by the route's pair models, the quality and the sentence text (with its
 * placeholders). Thread-safe; the runs use it on the worker thread.
 */
internal class SentenceCache(
    private val capacity: Int = 5000,
    private val maxChars: Long = 4_000_000,
) {
    private data class Key(
        val pairs: List<String>,
        val quality: Quality,
        val sentence: String,
    )

    private val entries = LinkedHashMap<Key, String>(16, 0.75f, true)
    private var chars = 0L

    @Synchronized
    fun get(
        pairs: List<String>,
        quality: Quality,
        sentence: String,
    ): String? = entries[Key(pairs, quality, sentence)]

    @Synchronized
    fun put(
        pairs: List<String>,
        quality: Quality,
        sentence: String,
        translation: String,
    ) {
        val key = Key(pairs, quality, sentence)
        entries.put(key, translation)?.let { chars -= it.length + sentence.length }
        chars += translation.length + sentence.length
        val eldest = entries.entries.iterator()
        while (entries.size > capacity || chars > maxChars) {
            val (old, value) = eldest.next()
            chars -= old.sentence.length + value.length
            eldest.remove()
        }
    }

    @get:Synchronized
    val size: Int get() = entries.size
}
