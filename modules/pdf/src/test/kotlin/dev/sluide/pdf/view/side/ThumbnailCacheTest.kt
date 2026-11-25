package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.PageSize
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThumbnailCacheTest {
    private val submitted = mutableListOf<ThumbnailCache.Request<String>>()
    private var drops = 0
    private val changed = mutableListOf<Int>()

    private fun cache(capacity: Int = ThumbnailCache.CAPACITY) =
        ThumbnailCache<String>({ submitted += it }, { drops++ }, { changed += it }, capacity)

    private fun pending(page: Int) = submitted.last { it.page == page }

    @Test
    fun `the shown pages are asked for once each, in order`() {
        val cache = cache()
        cache.show(0..2)
        cache.show(0..2)
        assertEquals(listOf(0, 1, 2), submitted.map { it.page })
        assertNull(cache.image(1))
    }

    @Test
    fun `an image that arrives is kept, and its page is told`() {
        val cache = cache()
        cache.show(listOf(3))
        pending(3).arrived("three")
        assertEquals("three", cache.image(3))
        assertEquals(listOf(3), changed)
        cache.show(listOf(3))
        assertEquals(1, submitted.size)
        // Its request is done: nothing is left to cancel.
        cache.show(emptyList())
        assertEquals(0, drops)
    }

    @Test
    fun `pages no longer shown have their renders cancelled and dropped from the queue`() {
        val cache = cache()
        cache.show(0..2)
        cache.show(1..3)
        assertTrue(pending(0).cancelled.get())
        assertFalse(pending(1).cancelled.get())
        assertFalse(pending(2).cancelled.get())
        assertEquals(1, drops)
        assertEquals(listOf(0, 1, 2, 3), submitted.map { it.page })
    }

    @Test
    fun `the queue is left alone when no render was cancelled`() {
        val cache = cache()
        cache.show(0..2)
        cache.show(0..3)
        cache.show(emptyList())
        cache.show(emptyList())
        assertEquals(1, drops)
    }

    @Test
    fun `a cancelled render's late image is ignored, and the page is asked for again when shown again`() {
        val cache = cache()
        cache.show(listOf(0))
        val stale = pending(0)
        cache.show(emptyList())
        stale.arrived("late")
        stale.failed()
        assertNull(cache.image(0))
        assertEquals(emptyList(), changed)
        cache.show(listOf(0))
        assertEquals(2, submitted.size)
        pending(0).arrived("fresh")
        assertEquals("fresh", cache.image(0))
    }

    @Test
    fun `a render that gave no image is asked for again`() {
        val cache = cache()
        cache.show(listOf(2))
        pending(2).arrived(null)
        assertNull(cache.image(2))
        assertEquals(emptyList(), changed)
        cache.show(listOf(2))
        assertEquals(2, submitted.size)
    }

    @Test
    fun `a failed render gives no image and is not asked for again until cleared`() {
        val cache = cache()
        cache.show(listOf(0, 1))
        pending(1).failed()
        assertNull(cache.image(1))
        // Its request is done: nothing is left to cancel when it scrolls away, nothing to ask for when it comes back.
        cache.show(listOf(0))
        assertEquals(0, drops)
        cache.show(listOf(0, 1))
        assertEquals(listOf(0, 1), submitted.map { it.page })
        cache.clear()
        cache.show(listOf(1))
        assertEquals(listOf(0, 1, 1), submitted.map { it.page })
    }

    @Test
    fun `the least recently used image goes first once more than the capacity arrived`() {
        val cache = cache(capacity = 2)
        cache.show(0..2)
        pending(0).arrived("zero")
        pending(1).arrived("one")
        cache.image(0)
        pending(2).arrived("two")
        assertEquals("zero", cache.image(0))
        assertNull(cache.image(1))
        assertEquals("two", cache.image(2))
    }

    @Test
    fun `clear cancels every render on its way, drops them from the queue and forgets the images`() {
        val cache = cache()
        cache.show(0..1)
        pending(0).arrived("zero")
        cache.clear()
        assertTrue(pending(1).cancelled.get())
        assertEquals(1, drops)
        assertNull(cache.image(0))
        cache.show(0..1)
        assertEquals(listOf(0, 1, 0, 1), submitted.map { it.page })
    }

    @ParameterizedTest
    @CsvSource(
        "612, 792, 0.19607843137254902",
        // Long and thin: the height limit, not the width, sets the scale.
        "100, 1000, 0.48",
        "500, 0, 0.24",
        "0, 500, 0.96",
        "0, 0, 1.0",
    )
    fun `a thumbnail is 120 pixels wide at most and 480 high at most`(
        width: Double,
        height: Double,
        scale: Double,
    ) {
        assertEquals(scale, thumbnailScale(PageSize(width, height)), 1e-12)
    }

    @Test
    fun `a thumbnail renders at its scale times the screen's output scale`() {
        assertEquals(0.4, thumbnailRenderScale(PageSize(600.0, 800.0), 2.0), 1e-12)
        assertEquals(0.2, thumbnailRenderScale(PageSize(600.0, 800.0), 1.0), 1e-12)
    }
}
