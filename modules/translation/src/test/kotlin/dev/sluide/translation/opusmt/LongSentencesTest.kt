package dev.sluide.translation.opusmt

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongSentencesTest {
    private val tokenizer = fixtureTokenizer()
    private val tokens = { text: String -> tokenizer.encode(text).size }

    private fun parts(
        sentence: String,
        limit: Int,
    ) = LongSentences.parts(sentence, tokens, limit)

    @Test
    fun `a sentence within the limit stays whole`() {
        val sentence = " Hello world. "
        assertEquals(listOf(sentence), parts(sentence, limit = 4))
    }

    @Test
    fun `splits at the clause boundary nearest the middle`() {
        // 11 tokens with </s>: the comma at the end of the second clause is nearer the middle.
        val sentence = "Hello world, Hello world Hello world, Hello world"
        assertEquals(listOf("Hello world, Hello world Hello world,", "Hello world"), parts(sentence, limit = 9))
        assertEquals(
            listOf("Hello world,", "Hello world", "Hello world,", "Hello world"),
            parts(sentence, limit = 5),
        )
    }

    @Test
    fun `without a clause boundary splits at spaces until every part fits`() {
        val sentence = "Hello world Hello world Hello world Hello world Hello"
        val parts = parts(sentence, limit = 4)
        assertTrue(parts.all { tokens(it) <= 4 }, "parts: $parts")
        assertEquals(sentence, parts.joinToString(" "))
    }

    @Test
    fun `without spaces splits in the middle of the characters`() {
        val sentence = "abababababab"
        val parts = parts(sentence, limit = 5)
        assertTrue(parts.all { tokens(it) <= 5 }, "parts: $parts")
        assertEquals(sentence, parts.joinToString(""))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            // After the mark nearest the middle, also the CJK forms.
            "one, two three four; five six seven, eight | 20",
            "你好，世界你好，世界你好 | 8",
            "前文：后文 | 3",
            // No mark: the space nearest the middle; a mark at the very end does not count.
            "one two three four, | 7",
            // Neither: the middle of the characters, never inside a surrogate pair.
            "abcdef | 3",
            "ab😀cd | 4",
        ],
    )
    fun `the split point`(
        text: String,
        at: Int,
    ) {
        assertEquals(at, LongSentences.splitPoint(text))
    }

    @Test
    fun `parts are joined with a space, without one for Chinese and Japanese`() {
        assertEquals("Hallo Welt", LongSentences.join(listOf("Hallo", "Welt"), "de"))
        assertEquals("你好世界", LongSentences.join(listOf("你好", "世界"), "zh"))
        assertEquals("こんにちは世界", LongSentences.join(listOf("こんにちは", "世界"), "ja"))
    }
}
