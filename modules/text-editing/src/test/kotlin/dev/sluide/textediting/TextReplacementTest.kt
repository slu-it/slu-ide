package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextReplacementTest {
    private fun assertTurns(
        old: String,
        new: String,
        expected: TextReplacement,
    ) {
        val replacement = TextReplacement.between(old, new)
        assertEquals(expected, replacement)
        assertEquals(new, replacement.applyTo(old))
    }

    @Test
    fun `equal texts need no replacement`() {
        val replacement = TextReplacement.between("same\n", "same\n")
        assertTrue(replacement.isEmpty)
        assertEquals(TextReplacement(5, 5, ""), replacement)
        assertTrue(TextReplacement.between("", "").isEmpty)
    }

    @Test
    fun `replaces only what changed in the middle`() {
        assertTurns("val a = 1\nval b = 2\n", "val a = 1\nval b = 3\n", TextReplacement(18, 19, "3"))
        assertFalse(TextReplacement.between("a", "b").isEmpty)
    }

    @Test
    fun `finds insertions and deletions`() {
        assertTurns("ac", "abc", TextReplacement(1, 1, "b"))
        assertTurns("abc", "ac", TextReplacement(1, 2, ""))
        assertTurns("abc", "abcdef", TextReplacement(3, 3, "def"))
        assertTurns("abcdef", "def", TextReplacement(0, 3, ""))
        assertTurns("", "new", TextReplacement(0, 0, "new"))
        assertTurns("old", "", TextReplacement(0, 3, ""))
    }

    @Test
    fun `prefix and suffix never overlap in repeated text`() {
        assertTurns("aaa", "aaaa", TextReplacement(3, 3, "a"))
        assertTurns("aaaa", "aa", TextReplacement(2, 4, ""))
        assertTurns("abab", "ab", TextReplacement(2, 4, ""))
    }

    @Test
    fun `never splits a surrogate pair`() {
        // U+1F600 and U+1F601 share their high surrogate.
        assertTurns("x😀y", "x😁y", TextReplacement(1, 3, "😁"))
        // U+1F600 and U+2F600 (high surrogate D87D) share their low surrogate.
        assertTurns("😀", "𯘀", TextReplacement(0, 2, "𯘀"))
    }
}
