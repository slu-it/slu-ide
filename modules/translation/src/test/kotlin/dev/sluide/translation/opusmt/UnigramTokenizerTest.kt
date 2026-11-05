package dev.sluide.translation.opusmt

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The fixture `tokenizer.json` is shaped like an Opus-MT export, with a vocabulary of 27 pieces, the last one a
 * target-language token.
 */
internal fun fixtureTokenizer(): UnigramTokenizer =
    UnigramTokenizer::class.java.getResourceAsStream("/dev/sluide/translation/opusmt/tokenizer.json").use {
        UnigramTokenizer.load(requireNotNull(it))
    }

class UnigramTokenizerTest {
    private val tokenizer = fixtureTokenizer()

    @Test
    fun `encodes words with their space mark and appends the end token`() {
        assertContentEquals(intArrayOf(4, 5, 6, 0), tokenizer.encode("Hello world."))
    }

    @Test
    fun `splits at any whitespace and ignores leading and trailing whitespace`() {
        assertContentEquals(tokenizer.encode("Hello world."), tokenizer.encode("  Hello\t\n world.  "))
        // No-break space and ideographic space are whitespace too.
        assertContentEquals(tokenizer.encode("Hello world."), tokenizer.encode("Hello world.　"))
    }

    @Test
    fun `finds a piece of the vocabulary as one token`() {
        assertEquals(26, tokenizer.pieceId(">>xx<<"))
        assertEquals(null, tokenizer.pieceId(">>yy<<"))
    }

    @Test
    fun `an empty text is just the end token`() {
        assertContentEquals(intArrayOf(0), tokenizer.encode(""))
        assertContentEquals(intArrayOf(0), tokenizer.encode("   "))
    }

    @Test
    fun `takes the most likely segmentation, not the longest first piece`() {
        // "▁hel" scores best alone, but "▁he" + "llo" (-5) beats "▁hel" + "l" + "o" (-9).
        assertContentEquals(intArrayOf(9, 10, 0), tokenizer.encode("hello"))
    }

    @Test
    fun `unknown characters become one unk token with the id of the unk token, not model unk_id`() {
        // The fixture's model.unk_id is 2 (","), as in every Opus-MT export; the <unk> token has id 1.
        assertEquals(1, tokenizer.unkId)
        assertContentEquals(intArrayOf(4, 3, 1, 5, 0), tokenizer.encode("Hello ☃☃😀 world"))
    }

    @Test
    fun `an unknown character between known ones stays its own token`() {
        assertContentEquals(intArrayOf(22, 1, 20, 0), tokenizer.encode("ab☃a"))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "4 5 6 0          | Hello world.",
            "25 4 1 5 0       | Hello world",
            "16 17            | don't",
            "4 3 19           | Hello!",
            "4 3 2 3 23       | Hello,?",
            "4 3 18 5         | Hello'world",
        ],
    )
    fun `decodes without special tokens, spaces from marks, cleaned up before punctuation`(
        ids: String,
        text: String,
    ) {
        assertEquals(
            text,
            tokenizer.decode(
                ids
                    .trim()
                    .split(Regex(" +"))
                    .map(String::toInt)
                    .toIntArray(),
            ),
        )
    }

    @Test
    fun `round trip of known words`() {
        assertEquals("Hello world.", tokenizer.decode(tokenizer.encode("Hello world.")))
    }
}
