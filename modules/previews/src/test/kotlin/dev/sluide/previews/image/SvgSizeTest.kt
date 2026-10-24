package dev.sluide.previews.image

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SvgSizeTest {
    @Test
    fun `an image without a size is no SVG image`() {
        for ((width, height) in listOf(0L to 10L, 10L to 0L, -1L to 5L)) {
            val failure = assertFailsWith<IOException>("$width × $height") { checkedSize(width, height, 1) }
            assertEquals("Not an SVG image", failure.message)
        }
    }

    @Test
    fun `the size is scaled`() {
        assertEquals(450 to 300, checkedSize(150, 100, 3))
    }

    @Test
    fun `a side of the largest length passes, one pixel more fails`() {
        assertEquals(8192 to 1, checkedSize(8192, 1, 1))
        val failure = assertFailsWith<IOException> { checkedSize(8193, 1, 1) }
        assertEquals("Too large at 100%: 8193 × 1 px", failure.message)
        assertEquals(
            "Too large at 300%: 9000 × 30 px",
            assertFailsWith<IOException> { checkedSize(3000, 10, 3) }.message,
        )
    }

    @Test
    fun `the most pixels pass, one more fails`() {
        assertEquals(8192 to 4096, checkedSize(8192, 4096, 1))
        val failure = assertFailsWith<IOException> { checkedSize(8192, 4097, 1) }
        assertEquals("Too large at 100%: 8192 × 4097 px", failure.message)
        assertEquals(
            "Too large at 300%: 6000 × 6000 px",
            assertFailsWith<IOException> { checkedSize(2000, 2000, 3) }.message,
        )
    }
}
