package dev.sluide.translation.session

import dev.sluide.translation.engine.Quality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentenceCacheTest {
    private val pairs = listOf("de-en")

    @Test
    fun `beyond its entries the least recently used goes`() {
        val cache = SentenceCache(capacity = 2)
        cache.put(pairs, Quality.MEDIUM, "Eins.", "One.")
        cache.put(pairs, Quality.MEDIUM, "Zwei.", "Two.")
        cache.get(pairs, Quality.MEDIUM, "Eins.")

        cache.put(pairs, Quality.MEDIUM, "Drei.", "Three.")

        assertEquals("One.", cache.get(pairs, Quality.MEDIUM, "Eins."))
        assertNull(cache.get(pairs, Quality.MEDIUM, "Zwei."))
        assertNull(cache.get(pairs, Quality.HIGH, "Drei."), "the quality is part of the key")
        assertEquals(2, cache.size)
    }

    @Test
    fun `beyond its characters the oldest go, counting sentence and translation`() {
        // Each entry is 5 + 4 characters; a replaced translation is not counted twice.
        val cache = SentenceCache(maxChars = 20)
        cache.put(pairs, Quality.MEDIUM, "Eins.", "One.")
        cache.put(pairs, Quality.MEDIUM, "Eins.", "One!")
        cache.put(pairs, Quality.MEDIUM, "Zwei.", "Two.")
        assertEquals(2, cache.size)

        cache.put(pairs, Quality.MEDIUM, "Drei.", "Thr.")

        assertNull(cache.get(pairs, Quality.MEDIUM, "Eins."))
        assertEquals("Two.", cache.get(pairs, Quality.MEDIUM, "Zwei."))
        assertEquals(2, cache.size)
    }
}
