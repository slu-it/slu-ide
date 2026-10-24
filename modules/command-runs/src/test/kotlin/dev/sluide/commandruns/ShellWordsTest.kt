package dev.sluide.commandruns

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShellWordsTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            "-Pname=a b|'-Pname=a b'",
            "it's|'it'\\''s'",
            "a;b|'a;b'",
            "\$HOME|'\$HOME'",
            "-Dx=y:z/1.2,3@%+_-|-Dx=y:z/1.2,3@%+_-",
            "=x|'=x'",
            "%x|'%x'",
            "@x|'@x'",
        ],
    )
    fun `words are quoted only when needed`(
        word: String,
        quoted: String,
    ) {
        assertEquals(quoted, ShellWords.quote(word))
    }

    @Test
    fun `an empty word is quoted`() {
        assertEquals("''", ShellWords.quote(""))
    }

    @Test
    fun `arguments are split at white space and quotes group`() {
        assertEquals(
            listOf("--info", "-Pa=b c", "d e", "x", "", "q\"r"),
            ShellWords.tokenize("  --info  -Pa='b c'  \"d e\" x'' '' \"q\\\"r\"  "),
        )
    }

    @Test
    fun `a backslash outside quotes escapes the next character like in sh`() {
        assertEquals(listOf("-Pa=b c", "x'y", "\\"), ShellWords.tokenize("-Pa=b\\ c x\\'y \\\\"))
    }

    @Test
    fun `an unbalanced quote is an error with a message`() {
        val e = assertFailsWith<IllegalArgumentException> { ShellWords.tokenize("--info \"-Pa=b") }
        assertTrue("quote" in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { ShellWords.tokenize("it's") }
    }
}
