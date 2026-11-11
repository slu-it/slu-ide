package dev.sluide.speech.text

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechTextTest {
    @ParameterizedTest
    @ValueSource(strings = ["", " ", " \n\t\r\n  \n"])
    fun `empty and whitespace-only text speaks nothing`(shown: String) {
        for (markdown in listOf(false, true)) {
            val spoken = SpeechText.prepare(shown, markdown)

            assertEquals("", spoken.text)
            assertEquals(0, spoken.sourceOffset(0))
            assertEquals(0, spoken.sourceEnd(0))
        }
    }

    @Test
    fun `plain text collapses whitespace runs and single line breaks to one space`() {
        val shown = "  One  two\tthree\nfour\r\nfive  \n"
        val spoken = SpeechText.prepare(shown, markdown = false)

        assertEquals("One two three four five", spoken.text)
        assertEquals("four", shownOf(shown, spoken, "four"))
        assertEquals("three\nfour", shownOf(shown, spoken, "three four"))
    }

    @Test
    fun `plain text keeps a blank line as a hard break`() {
        val shown = "One.\n\n  \nTwo\r\n\r\nThree"
        val spoken = SpeechText.prepare(shown, markdown = false)

        assertEquals("One.\nTwo\nThree", spoken.text)
        assertEquals("Two", shownOf(shown, spoken, "Two"))
    }

    @Test
    fun `plain text keeps Markdown markup`() {
        assertEquals("# Title **bold**", SpeechText.prepare("# Title **bold**", markdown = false).text)
    }

    @Test
    fun `preparePlain prepares like plain text without skip ranges`() {
        val shown = "  # One  two\n\nthree  "

        assertEquals(SpeechText.prepare(shown, markdown = false).text, SpeechText.preparePlain(shown, emptyList()).text)
    }

    @Test
    fun `preparePlain does not speak skipped chars and maps the rest back`() {
        val shown = "Hello [12] world.\n\nNext."
        val spoken = SpeechText.preparePlain(shown, listOf(shown.indexOf('[')..shown.indexOf(']')))

        assertEquals("Hello world.\nNext.", spoken.text)
        assertEquals("world.", shownOf(shown, spoken, "world."))
        assertEquals("Next.", shownOf(shown, spoken, "Next."))
    }

    @Test
    fun `a skipped range breaks words, also without whitespace around it`() {
        val shown = "foo12bar"

        assertEquals("foo bar", SpeechText.preparePlain(shown, listOf(3..4)).text)
    }

    @Test
    fun `skipped line breaks make no hard break`() {
        val shown = "One\n\nTwo"

        assertEquals("One Two", SpeechText.preparePlain(shown, listOf(3..4)).text)
    }

    @Test
    fun `preparing from the paragraph start gives the sentences of preparing from 0`() {
        // shown, skip, the expected start; each one is read from "Two".
        val cases: List<Triple<String, List<IntRange>, Int>> =
            listOf(
                Triple("One.\n\nTwo. Three.", emptyList(), 4),
                Triple("Mr.\r\n\r\nTwo.", emptyList(), 3),
                Triple("One.\n \nTwo.", emptyList(), 4),
                Triple("Dr.\n[1]\nTwo.", listOf(4..6), 3),
                Triple("Dr.\n[\n]Two.", listOf(4..6), 0),
                Triple("Ch1.\n\nOne. Two.", emptyList(), 4),
                Triple("x\n\nOne.\n\nTwo.", emptyList(), 7),
            )
        for ((shown, skip, expected) in cases) {
            val start = shown.indexOf("Two")
            val cut = SpeechText.paragraphStart(shown, start, skip)
            val whole = SpeechText.preparePlain(shown, skip)
            val fromCut = SpeechText.preparePlain(shown.substring(cut), skip.map { (it.first - cut)..(it.last - cut) })

            assertEquals(expected, cut, shown)
            assertEquals(
                Sentences.chunks(whole).filter { whole.sourceOffset(it.start) >= cut }.map { it.text },
                Sentences.chunks(fromCut).map { it.text },
                shown,
            )
        }
    }

    @Test
    fun `a start inside a paragraph break starts at the break, and the look-back is limited`() {
        val shown = "One.\n\nTwo."

        assertEquals(4, SpeechText.paragraphStart(shown, 5, emptyList()))
        assertEquals(0, SpeechText.paragraphStart(shown, 3, emptyList()))
        assertEquals(90, SpeechText.paragraphStart("a".repeat(100) + " Two.", 100, emptyList(), limit = 10))
    }

    @Test
    fun `skip ranges may be unsorted, overlapping, empty or out of bounds`() {
        val shown = "abcdefghij"
        val skip = listOf(8..20, 2..4, 3..5, -5..0, 7..6)

        assertEquals("b gh", SpeechText.preparePlain(shown, skip).text)
        assertEquals("", SpeechText.preparePlain(shown, listOf(-1..100)).text)
    }

    @Test
    fun `maps every spoken offset to its source char, the end one past the last`() {
        val shown = "a  b"
        val spoken = SpeechText.prepare(shown, markdown = false)

        assertEquals(listOf(0, 1, 3, 4), (0..spoken.text.length).map(spoken::sourceOffset))
        assertEquals(listOf(0, 1, 2, 4), (0..spoken.text.length).map(spoken::sourceEnd))
    }

    @Test
    fun `an empty range at the start ends where the first spoken char comes from`() {
        val spoken = markdown("## Title")

        assertEquals(3, spoken.sourceOffset(0))
        assertEquals(3, spoken.sourceEnd(0))
    }

    @Test
    fun `maps a shown offset to the first spoken char at or after it`() {
        val shown = "# A **bold** word\n\n`x`"
        val spoken = markdown(shown)

        assertEquals("A bold word\nx", spoken.text)
        assertEquals(0, spoken.spokenOffset(0))
        assertEquals(0, spoken.spokenOffset(2))
        assertEquals(2, spoken.spokenOffset(shown.indexOf("**")))
        assertEquals(2, spoken.spokenOffset(shown.indexOf("bold")))
        assertEquals(6, spoken.spokenOffset(shown.indexOf("** word")))
        assertEquals(12, spoken.spokenOffset(shown.indexOf('`')))
        assertEquals(13, spoken.spokenOffset(shown.length - 1))
        assertEquals(13, spoken.spokenOffset(shown.length))
        assertEquals(0, SpeechText.prepare("", markdown = true).spokenOffset(0))
    }

    @Test
    fun `skips front matter`() {
        val shown = "---\ntitle: Notes\n---\nHello world."
        val spoken = markdown(shown)

        assertEquals("Hello world.", spoken.text)
        assertEquals("world", shownOf(shown, spoken, "world"))
    }

    @Test
    fun `a rule that is not at the very start is no front matter`() {
        assertEquals("Intro\nTitle: x", markdown("Intro\n\n---\nTitle: x\n---\n").text)
    }

    @Test
    fun `skips fenced code blocks of either fence`() {
        val shown = "Before.\n```kotlin\nval x = 1\n```\nMiddle.\n~~~~\ncode\n~~~\n~~~~\nAfter."

        assertEquals("Before.\nMiddle.\nAfter.", markdown(shown).text)
    }

    @Test
    fun `skips indented code blocks after a blank line`() {
        val shown = "Text.\n\n    code line\n\tmore code\n\n    still code\nAfter."

        assertEquals("Text.\nAfter.", markdown(shown).text)
    }

    @Test
    fun `an indented line inside a paragraph is no code`() {
        assertEquals("Text goes on", markdown("Text\n    goes on").text)
    }

    @Test
    fun `removes HTML tags but keeps their text, and skips comments`() {
        val shown = "<div>\nA <b>bold</b> word<br/>.\n</div>\n<!-- a\ncomment -->\nEnd <!-- inline --> here."
        val spoken = markdown(shown)

        assertEquals("A bold word.\nEnd here.", spoken.text)
        assertEquals("bold", shownOf(shown, spoken, "bold"))
    }

    @Test
    fun `reads the text beside a comment on its first or last line`() {
        assertEquals("Hello there", markdown("<!-- note --> Hello there").text)

        val shown = "<!--\nx\n--> Visible"
        val spoken = markdown(shown)
        assertEquals("Visible", spoken.text)
        assertEquals("Visible", shownOf(shown, spoken, "Visible"))
    }

    @Test
    fun `an unclosed comment skips the rest`() {
        assertEquals("Before", markdown("Before\n<!-- open\nnever closed").text)
    }

    @Test
    fun `skips a byte order mark, also before front matter`() {
        val shown = "﻿---\ntitle: x\n---\nText"
        val spoken = markdown(shown)

        assertEquals("Text", spoken.text)
        assertEquals("Text", shownOf(shown, spoken, "Text"))
        assertEquals("Text", SpeechText.prepare("﻿Text", markdown = false).text)
    }

    @Test
    fun `removes heading markers and ends a sentence at a heading`() {
        val shown = "# Title #\nText\n## Sub heading\nMore"
        val spoken = markdown(shown)

        assertEquals("Title\nText\nSub heading\nMore", spoken.text)
        assertEquals("Sub heading", shownOf(shown, spoken, "Sub heading"))
    }

    @Test
    fun `a hash without a space is no heading`() {
        assertEquals("#hashtag and C#", markdown("#hashtag and C#").text)
    }

    @Test
    fun `removes setext underlines and rules`() {
        assertEquals("Title\nText\nMore", markdown("Title\n=====\nText\n\n***\nMore").text)
        assertEquals("Sub\nText", markdown("Sub\n---\nText").text)
        assertEquals("A\nB", markdown("A\n\n- - -\n_ _ _\nB").text)
    }

    @Test
    fun `reads a very long rule and delimiter row without overflowing the stack`() {
        assertEquals("A\nB", markdown("A\n\n${"-".repeat(100_000)}\nB").text)

        val cells = 5000
        val table = "${"| a ".repeat(cells)}|\n${"|---".repeat(cells)}|\n"
        assertEquals(List(cells) { "a" }.joinToString(", "), markdown(table).text)
    }

    @Test
    fun `reads deeply nested links without overflowing the stack`() {
        val depth = 5000
        val shown = "[".repeat(depth) + "deep" + "](x)".repeat(depth)

        assertEquals("deep", markdown(shown).text)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Some *emphasis* here|Some emphasis here",
            "Some **strong** and __strong__|Some strong and strong",
            "Some _emphasis_ here|Some emphasis here",
            "A ~~strike~~ word|A strike word",
            "***both*** at once|both at once",
            "in*word*emphasis|inwordemphasis",
            "2 * 3 * 4|2 * 3 * 4",
            "snake_case_name stays|snake_case_name stays",
            "5*3 alone|5*3 alone",
            "an *unclosed marker|an *unclosed marker",
        ],
    )
    fun `removes emphasis markers only when they are markers`(
        shown: String,
        expected: String,
    ) {
        assertEquals(expected, markdown(shown).text)
    }

    @Test
    fun `emphasis may span lines of a paragraph`() {
        assertEquals("one two", markdown("*one\ntwo*").text)
    }

    @Test
    fun `keeps the text of inline code without the backticks`() {
        val shown = "Call `run()` or ``a ` b`` now, `open"
        val spoken = markdown(shown)

        assertEquals("Call run() or a ` b now, `open", spoken.text)
        assertEquals("run()", shownOf(shown, spoken, "run()"))
    }

    @Test
    fun `inline code keeps its markers literally`() {
        assertEquals("Use *args here", markdown("Use `*args` here").text)
    }

    @Test
    fun `speaks only the text of links`() {
        val shown = "See [the docs](https://x.org/a_(b) \"Title\") and [more *here*][ref]."
        val spoken = markdown(shown)

        assertEquals("See the docs and more here.", spoken.text)
        assertEquals("the docs", shownOf(shown, spoken, "the docs"))
        assertEquals("more *here", shownOf(shown, spoken, "more here"))
    }

    @Test
    fun `speaks the alt text of images`() {
        val shown = "Look: ![a red car](car.png) there."
        val spoken = markdown(shown)

        assertEquals("Look: a red car there.", spoken.text)
        assertEquals("red car", shownOf(shown, spoken, "red car"))
    }

    @Test
    fun `skips autolinks and bare URLs`() {
        val shown = "Go to <https://x.org/a>, https://y.org/b?c=d. Or www.z.org (or <me@x.org>)."

        assertEquals("Go to , . Or (or ).", markdown(shown).text)
    }

    @Test
    fun `a word containing http is no URL`() {
        assertEquals("shttp://x and www.", markdown("shttp://x and www.").text)
    }

    @Test
    fun `skips reference definitions`() {
        assertEquals("Text [ref].", markdown("Text [ref].\n\n[ref]: https://x.org \"T\"\n").text)
    }

    @Test
    fun `a definition label does not reach across lines`() {
        assertEquals("[not\nA label]: here", markdown("[not\n\nA label]: here").text)
        assertEquals("[open and", markdown("[open and\n\n[x]: y").text)
    }

    @Test
    fun `reads footnote texts without their labels and skips footnote markers`() {
        val shown = "A claim[^1] here.\n\n[^1]: The source."
        val spoken = markdown(shown)

        assertEquals("A claim here.\nThe source.", spoken.text)
        assertEquals("The source.", shownOf(shown, spoken, "The source."))
    }

    @Test
    fun `removes list bullets and task boxes and ends a sentence at each item`() {
        val shown = "Items:\n- one\n* [ ] two\n+ [x] three\n1. four\n2) five\n   goes on"
        val spoken = markdown(shown)

        assertEquals("Items:\none\ntwo\nthree\nfour\nfive goes on", spoken.text)
        assertEquals("three", shownOf(shown, spoken, "three"))
        assertEquals("five\n   goes", shownOf(shown, spoken, "five goes"))
    }

    @Test
    fun `an indented paragraph in a list is no code`() {
        assertEquals("one\nmore of one", markdown("- one\n\n    more of one").text)
    }

    @Test
    fun `a number without a marker space is no list item`() {
        assertEquals("3.14 is pi, -1 is not", markdown("3.14 is pi,\n-1 is not").text)
    }

    @Test
    fun `removes blockquote markers`() {
        val shown = "> Quoted\n> text.\n>\n> > Nested."
        val spoken = markdown(shown)

        assertEquals("Quoted text.\nNested.", spoken.text)
        assertEquals("Quoted\n> text", shownOf(shown, spoken, "Quoted text"))
    }

    @Test
    fun `reads each table row as a sentence with its cells separated by commas`() {
        val shown = "| Name | Age |\n|:-----|----:|\n| Ann | 3 |\n| *Bob* | a \\| b |\n\nAfter."
        val spoken = markdown(shown)

        assertEquals("Name, Age\nAnn, 3\nBob, a | b\nAfter.", spoken.text)
        assertEquals("Ann", shownOf(shown, spoken, "Ann"))
        assertEquals("Bob* | a", shownOf(shown, spoken, "Bob, a"))
    }

    @Test
    fun `a pipe without a delimiter row is no table`() {
        assertEquals("a | b", markdown("a | b").text)
    }

    @Test
    fun `a backslash at the end of a table row keeps the last cell`() {
        assertEquals("a, b\\", markdown("a | b\\\n-|-").text)
    }

    @Test
    fun `escapes keep the literal char`() {
        val shown = """\*not emphasis\* and \# and \[x]"""
        val spoken = markdown(shown)

        assertEquals("*not emphasis* and # and [x]", spoken.text)
        assertEquals("""not emphasis\*""", shownOf(shown, spoken, "not emphasis*"))
    }

    @Test
    fun `maps a mixed document back to the shown text`() {
        val shown = "# Read **me**\n\nA [link](u) with `code` and *more*.\n\n- item _one_\n"
        val spoken = markdown(shown)

        assertEquals("Read me\nA link with code and more.\nitem one", spoken.text)
        assertEquals("Read **me", shownOf(shown, spoken, "Read me"))
        assertEquals("link", shownOf(shown, spoken, "link"))
        assertEquals("code` and *more*.", shownOf(shown, spoken, "code and more."))
        assertEquals("one", shownOf(shown, spoken, "one"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "# Title\n\nSome *text* with [a link](x) and `code`.\n\n| a | b |\n|---|---|\n| c | d |\n",
            "Unclosed [bracket and (paren and <tag and `tick and \\",
            "![](empty) [](x) [x][] <> <a <!-- open\n> > \n- \n1.\n* [x]\n```",
            "---\nfront: matter\n---\n~~~\ncode\n",
            "Text\r\nwith\r\n\r\nCRLF *and* _more_ ~~of~~ it\r",
            "\\\\ \\` *a **b* c** _a __b_ c__ https://x.org/(a)b) www.y.org.",
        ],
    )
    fun `every spoken char comes from the same shown char, in order`(shown: String) {
        for (markdown in listOf(false, true)) {
            val spoken = SpeechText.prepare(shown, markdown)

            val offsets = spoken.text.indices.map(spoken::sourceOffset)
            assertEquals(offsets.sorted(), offsets, spoken.text)
            for ((index, char) in spoken.text.withIndex()) {
                if (char.isWhitespace()) continue
                val source = shown[spoken.sourceOffset(index)]
                assertTrue(source == char || (char == ',' && source == '|'), "'$char' from '$source' in ${spoken.text}")
            }
        }
    }

    private fun markdown(shown: String) = SpeechText.prepare(shown, markdown = true)

    /** The shown slice that the first occurrence of [spokenPart] in the spoken text came from. */
    private fun shownOf(
        shown: String,
        spoken: SpokenText,
        spokenPart: String,
    ): String {
        val start = spoken.text.indexOf(spokenPart)
        require(start >= 0) { "'$spokenPart' not in '${spoken.text}'" }
        return shown.substring(spoken.sourceOffset(start), spoken.sourceEnd(start + spokenPart.length))
    }
}
