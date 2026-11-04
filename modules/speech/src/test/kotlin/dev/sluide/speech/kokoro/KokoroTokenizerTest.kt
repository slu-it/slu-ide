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
        val t = tokenize("x,b(c)d")
        assertEquals("x,b(c)d", decode(t.ids))
        assertEquals(listOf("x", "b", "c", "d"), t.words.map { "x,b(c)d".substring(it.start, it.end) })
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
        assertEquals("“x” (b)", decode(tokenize("“x” (b)").ids))
    }

    @Test
    fun `dashes`() {
        assertEquals("x — b", decode(tokenize("x – b").ids))
        assertEquals("x—b", decode(tokenize("x—b").ids))
        assertEquals("x—b", decode(tokenize("x–b").ids))
        val text = "x well-known b"
        val t = tokenize(text)
        assertEquals("well-known", text.substring(t.words[1].start, t.words[1].end))
        assertEquals("x wellknown b", decode(t.ids))
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
        assertEquals(listOf("pi", "3.14", "in", "the", "U.S.", "now"), t.words.map { text.substring(it.start, it.end) })
        assertEquals("pi aaa in the us now", decode(t.ids))
    }

    @Test
    fun `symbols and digit runs are words`() {
        val text = "x # @ & / * 1984"
        val t = tokenize(text)
        assertEquals(listOf("x", "#", "@", "&", "/", "*", "1984"), t.words.map { text.substring(it.start, it.end) })
    }

    @Test
    fun `a word without phonemes keeps a span with an empty token range and no extra space`() {
        val t = tokenize("x gg b")
        assertEquals("x b", decode(t.ids))
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

    private fun words(
        text: String,
        t: TokenizedChunk = tokenize(text),
    ) = t.words.map { text.substring(it.start, it.end) }

    @Test
    fun `known abbreviations and initials keep their period in the word when text follows`() {
        val text = "Dr. Smith of the U.S. met J. Doe, etc. and left."
        val t = tokenize(text)
        assertEquals(
            listOf("Dr.", "Smith", "of", "the", "U.S.", "met", "J.", "Doe", "etc.", "and", "left"),
            words(text, t),
        )
        assertEquals("dr smith of the us met j doe, etc and left.", decode(t.ids))
    }

    @Test
    fun `a period at the end of the chunk is a token even after an abbreviation or initial`() {
        assertEquals("so etc.", decode(tokenize("so etc.").ids))
        assertEquals("vitamin x.", decode(tokenize("vitamin X.").ids))
        assertEquals("us.", decode(tokenize("U.S.").ids))
    }

    @Test
    fun `thousands separators and times stay in the word`() {
        val text = "\$1,000,000 at 3:30 or 1,5"
        assertEquals(listOf("\$1,000,000", "at", "3:30", "or", "1,5"), words(text))
    }

    @Test
    fun `comma and colon without digits on both sides still split`() {
        assertEquals(listOf("x", "y"), words("x,y"))
        assertEquals(listOf("1", "2"), words("1, 2"))
        assertEquals(listOf("x", "1"), words("x:1"))
        assertEquals(listOf("5"), words("5:"))
        assertEquals(listOf("3:30"), words("3:30"))
        assertEquals(listOf("16", "9"), words("16:9"))
        assertEquals(listOf("3", "300"), words("3:300"))
    }

    @Test
    fun `a plural possessive keeps its apostrophe, a quoted word keeps its quotes`() {
        val possessive = "the Smiths' house"
        val t = tokenize(possessive)
        assertEquals(listOf("the", "Smiths'", "house"), words(possessive, t))
        assertEquals("the smiths house", decode(t.ids))
        assertEquals("the smiths.", decode(tokenize("the Smiths'.").ids))
        assertEquals("the smiths…", decode(tokenize("the Smiths'...").ids))
        assertEquals("\"cats\" ran", decode(tokenize("'cats' ran").ids))
        assertEquals("\"cats\" ran", decode(tokenize("‘cats’ ran").ids))
    }

    @Test
    fun `the article a is a schwa and function words lose their stress`() {
        val stressed = KokoroTokenizer({ w, _ -> "ˈ" + w.lowercase() + "ˌ" }, vocab)

        fun run(text: String) = decode(stressed.tokenize(chunk(text), "en-US").ids)
        assertEquals("ɐ ˈcatˌ", run("a cat"))
        assertEquals("ɐ ˈcatˌ", run("A cat"))
        assertEquals("ˈcatˌ ˈaˌ.", run("cat A."))
        assertEquals("ˈplanˌ ˈaˌ ˈcatˌ", run("plan A cat"))
        assertEquals("\"ɐ ˈcatˌ", run("\"A cat"))
        assertEquals("ˈcatˌ, ɐ ˈcatˌ", run("cat, A cat"))
        assertEquals("the ˈcatˌ of ˈdoˌ", run("The cat of do"))
        assertEquals("ˈitˌ", run("IT"))
        assertEquals("it ˈnˌ", run("it n"))
    }

    @Test
    fun `the article a keeps its span and chunk offsets`() {
        val t = tokenizer.tokenize(chunk("a cat", start = 7), "en-US")
        assertEquals(listOf(listOf(7, 8, 0, 1), listOf(9, 12, 2, 5)), spans(t))
    }

    private val numberTokenizer =
        KokoroTokenizer({ w, _ -> if (w == "2024") "tu θWzənd" else w.lowercase() }, vocab)

    @Test
    fun `a space inside the phonemes of one word is no place to cut`() {
        val text = "aa bb 2024"
        val whole = numberTokenizer.tokenize(chunk(text), "en-US")
        assertEquals("aa bb tu θWzənd", decode(whole.ids))
        val parts = numberTokenizer.split(whole, max = 10)
        assertEquals(listOf("aa bb", "tu θWzənd"), parts.map { decode(it.ids) })
        assertEquals(listOf(listOf(6, 10, 0, 9)), spans(parts[1]))
    }

    @Test
    fun `exactly the maximum is one piece, one more is two`() {
        val exact = tokenize("aa bb cc")
        assertEquals(8, exact.ids.size)
        assertSame(exact, tokenizer.split(exact, 8).single())
        val over = tokenizer.split(tokenize("aa bb cc"), 7)
        assertEquals(listOf("aa bb", "cc"), over.map { decode(it.ids) })
    }
}
