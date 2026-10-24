package dev.sluide.git.cli

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

class GitArgumentsTest {
    @Test
    fun `characters a charset cannot encode become a dot per UTF-8 byte`() {
        val ascii = Charsets.US_ASCII
        assertEquals("plain [a-z]+", encodableArgument("plain [a-z]+", ascii))
        assertEquals("Gr....e", encodableArgument("Gr\u00f6\u00dfe", ascii))
        assertEquals("a...b", encodableArgument("a\u20acb", ascii))
        assertEquals("....!", encodableArgument("\ud83d\ude00!", ascii), "a surrogate pair is one character")
        assertEquals("Gr\u00f6\u00dfe", encodableArgument("Gr\u00f6\u00dfe", Charsets.UTF_8))
        val latin9 = Charset.forName("ISO-8859-15")
        assertEquals("a..b", encodableArgument("a\u00a4b", latin9), "\u00a4 is not in Latin-9")
        assertEquals("a\u20acb", encodableArgument("a\u20acb", latin9))
    }

    @Test
    fun `escapes regular expressions and matches authors ignoring case`() {
        assertEquals("a\\.b\\[c\\]\\(\\)\\*\\+\\?\\{\\}\\|\\^\\$\\\\ x", escapeRegex("a.b[c]()*+?{}|^$\\ x"))
        assertEquals(listOf("^[Aa][Nn] [Oo]\\. <", "<[Aa][Nn] [Oo]\\.>"), authorPatterns("An o."))
        assertEquals(listOf("^1\u00e4 <", "<1\u00e4>"), authorPatterns("1\u00e4"))
    }
}
