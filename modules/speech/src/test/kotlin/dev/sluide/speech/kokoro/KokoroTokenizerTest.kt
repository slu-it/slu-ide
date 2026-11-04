package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KokoroTokenizerTest {
    private val vocab = KokoroVocab.load()
    private val chars: Map<Long, Char> =
        (Char.MIN_VALUE..Char.MAX_VALUE).mapNotNull { c -> vocab.id(c)?.let { it to c } }.toMap()

    /** Letters only, digits read as letters; the vocabulary then drops what it lacks (`g`). */
    private val tokenizer =
        KokoroTokenizer(
            { word, _ ->
                word
                    .lowercase()
                    .map { if (it.isDigit()) 'a' else it }
                    .filter { it.isLetter() }
                    .joinToString("")
            },
            vocab,
        )

    private fun chunk(
        text: String,
        start: Int = 0,
    ) = SpeechChunk(start, start + text.length, start, start + text.length, text)

    private fun decode(ids: LongArray) = ids.map { chars.getValue(it) }.joinToString("")

    private fun tokenize(
        text: String,
        start: Int = 0,
    ) = tokenizer.tokenize(chunk(text, start), "en-US")

    private fun spans(t: TokenizedChunk) = t.words.map { listOf(it.start, it.end, it.firstToken, it.endToken) }

    @Test
    fun `words and one space token between them`() {
        val t = tokenize("hello world")
        assertEquals("hello world", decode(t.ids))
        assertEquals(listOf(listOf(0, 5, 0, 5), listOf(6, 11, 6, 11)), spans(t))
    }

    @Test
    fun `word offsets are shifted by the chunk start and map back to the text`() {
        val text = "one  two\nthree"
        val t = tokenize(text, start = 100)
        assertEquals("one two three", decode(t.ids))
        assertEquals(listOf("one", "two", "three"), t.words.map { text.substring(it.start - 100, it.end - 100) })
    }

    @Test
    fun `no space token at the chunk start or end`() {
        assertEquals("hi", decode(tokenize("  hi \n").ids))
    }

    @Test
    fun `punctuation becomes tokens and gets no span`() {
        val t = tokenize("hi, there!")
        assertEquals("hi, there!", decode(t.ids))
        assertEquals(listOf(listOf(0, 2, 0, 2), listOf(4, 9, 4, 9)), spans(t))
    }

    @Test
    fun `punctuation inside a run splits the word without a space`() {
        val t = tokenize("a,b(c)d")
        assertEquals("a,b(c)d", decode(t.ids))
        assertEquals(listOf("a", "b", "c", "d"), t.words.map { "a,b(c)d".substring(it.start, it.end) })
    }

    @Test
    fun `quotes at word edges become double quotes, apostrophes inside stay`() {
        val text = "\"hi\" 'yo' ‘ok’ don't it’s"
        val t = tokenize(text)
        assertEquals("\"hi\" \"yo\" \"ok\" dont its", decode(t.ids))
        assertEquals(listOf("hi", "yo", "ok", "don't", "it’s"), t.words.map { text.substring(it.start, it.end) })
    }

    @Test
    fun `curly quotes and parentheses are punctuation`() {
        assertEquals("“a” (b)", decode(tokenize("“a” (b)").ids))
    }

    @Test
    fun `dashes`() {
        assertEquals("a — b", decode(tokenize("a – b").ids))
        assertEquals("a—b", decode(tokenize("a—b").ids))
        assertEquals("a—b", decode(tokenize("a–b").ids))
        val text = "a well-known b"
        val t = tokenize(text)
        assertEquals("well-known", text.substring(t.words[1].start, t.words[1].end))
        assertEquals("a wellknown b", decode(t.ids))
    }

    @Test
    fun `three dots are one ellipsis`() {
        assertEquals("wait…", decode(tokenize("wait...").ids))
        assertEquals("wait…", decode(tokenize("wait…").ids))
        assertEquals("wait…", decode(tokenize("wait....").ids))
        assertEquals("wait..", decode(tokenize("wait..").ids))
    }

    @Test
    fun `dots inside numbers and abbreviations stay in the word`() {
        val text = "pi 3.14 in the U.S. now"
        val t = tokenize(text)
        assertEquals(listOf("pi", "3.14", "in", "the", "U.S", "now"), t.words.map { text.substring(it.start, it.end) })
        assertEquals("pi aaa in the us. now", decode(t.ids))
    }

    @Test
    fun `symbols and digit runs are words`() {
        val text = "a # @ & / * 1984"
        val t = tokenize(text)
        assertEquals(listOf("a", "#", "@", "&", "/", "*", "1984"), t.words.map { text.substring(it.start, it.end) })
    }

    @Test
    fun `a word without phonemes keeps a span with an empty token range and no extra space`() {
        val t = tokenize("a gg b")
        assertEquals("a b", decode(t.ids))
        assertEquals(listOf(listOf(0, 1, 0, 1), listOf(2, 4, 1, 1), listOf(5, 6, 2, 3)), spans(t))
    }

    @Test
    fun `a leading word without phonemes gets no leading space token`() {
        val t = tokenize("# b")
        assertEquals("b", decode(t.ids))
        assertEquals(listOf(0, 0), listOf(t.words[0].firstToken, t.words[0].endToken))
    }

    @Test
    fun `the language is passed to the phonemes function`() {
        val seen = mutableListOf<String>()
        KokoroTokenizer(
            { _, language ->
                seen.add(language)
                "a"
            },
            vocab,
        ).tokenize(chunk("x y"), "en-GB")
        assertEquals(listOf("en-GB", "en-GB"), seen)
    }

    @Test
    fun `short chunks are not split`() {
        val t = tokenize("aa bb")
        assertSame(t, tokenizer.split(t, 510).single())
    }

    @Test
    fun `split after the last sentence end that fits`() {
        val text = "aa bb. cc dd"
        val parts = tokenizer.split(tokenize(text, start = 10), max = 8)
        assertEquals(listOf("aa bb.", "cc dd"), parts.map { decode(it.ids) })
        assertEquals(listOf(listOf(10, 12, 0, 2), listOf(13, 15, 3, 5)), spans(parts[0]))
        assertEquals(listOf(listOf(17, 19, 0, 2), listOf(20, 22, 3, 5)), spans(parts[1]))
    }

    @Test
    fun `split prefers a sentence end over a later clause end`() {
        val parts = tokenizer.split(tokenize("aa. bb, cc dd"), max = 10)
        assertEquals(listOf("aa.", "bb, cc dd"), parts.map { decode(it.ids) })
    }

    @Test
    fun `split at a clause end when there is no sentence end`() {
        val parts = tokenizer.split(tokenize("aa bb, cc dd"), max = 8)
        assertEquals(listOf("aa bb,", "cc dd"), parts.map { decode(it.ids) })
    }

    @Test
    fun `split at a space when there is no punctuation`() {
        val parts = tokenizer.split(tokenize("aaa bbb ccc ddd"), max = 9)
        assertEquals(listOf("aaa bbb", "ccc ddd"), parts.map { decode(it.ids) })
        assertEquals(listOf(listOf(8, 11, 0, 3), listOf(12, 15, 4, 7)), spans(parts[1]))
    }

    @Test
    fun `split outside a word when there is no space or punctuation to cut at`() {
        val text = "aa(bb)cc"
        val parts = tokenizer.split(tokenize(text), max = 5)
        assertEquals(listOf("aa(bb", ")cc"), parts.map { decode(it.ids) })
        assertEquals(listOf(listOf(0, 2, 0, 2), listOf(3, 5, 3, 5)), spans(parts[0]))
        assertEquals(listOf(listOf(6, 8, 1, 3)), spans(parts[1]))
    }

    @Test
    fun `one word longer than the maximum is cut hard`() {
        val parts = tokenizer.split(tokenize("aaaaaaaaaaaa"), max = 5)
        assertEquals(listOf(5, 5, 2), parts.map { it.ids.size })
        assertEquals(listOf(listOf(0, 12, 0, 5)), spans(parts[0]))
        assertTrue(parts[1].words.isEmpty() && parts[2].words.isEmpty())
    }

    @Test
    fun `every piece is within the maximum`() {
        val text = (1..200).joinToString(" ") { "word" } + ". " + (1..100).joinToString(", ") { "bit" }
        val whole = tokenize(text)
        val parts = tokenizer.split(whole, max = 50)
        assertTrue(parts.all { it.ids.size <= 50 })
        assertEquals(whole.words.size, parts.sumOf { it.words.size })
        for (p in parts) {
            for (w in p.words) {
                assertEquals(
                    text.substring(w.start, w.end).lowercase(),
                    decode(p.ids.copyOfRange(w.firstToken, w.endToken)),
                )
            }
        }
    }
}
