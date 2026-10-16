package dev.sluide.gitlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilterPathTest {
    @Test
    fun `a typed path loses leading dot-slashes and slashes and trailing slashes`() {
        assertEquals("src/main", normalizeFilterPath(" ./src/main/ "))
        assertEquals("src", normalizeFilterPath("/././/src//"))
        assertEquals("a b/.hidden", normalizeFilterPath("a b/.hidden"))
        assertEquals("a..b/..c", normalizeFilterPath("a..b/..c"))
        assertNull(normalizeFilterPath("../"), "outside the repository")
        assertNull(normalizeFilterPath("src/../../x"))
        assertNull(normalizeFilterPath("./"))
        assertNull(normalizeFilterPath(" / "))
        assertNull(normalizeFilterPath(""))
    }
}
