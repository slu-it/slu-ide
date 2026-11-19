package dev.sluide.texttypes

import dev.sluide.texttypes.TextTypeDetector.Companion.RULES_LIMIT
import dev.sluide.texttypes.TextTypeDetector.Companion.SOURCE_LIMIT
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The detector's mechanics with fake sources and rules: threshold, ties, failures and limits. */
class TextTypeDetectorTest {
    private val markdown = TextType("Markdown", "md")
    private val json = TextType("JSON", "json")
    private val yaml = TextType("YAML", "yaml")
    private val css = TextType("CSS", "css")

    private fun source(vararg scores: Pair<TextType, Int>) =
        TextTypeSource { scores.map { (type, score) -> TypeScore(type, score) } }

    private fun detector(
        sources: List<TextTypeSource>,
        rules: List<TextTypeSource> = emptyList(),
    ) = TextTypeDetector(sources, rules)

    /** Records the texts it is asked about. */
    private class Recording(
        private vararg val scores: TypeScore,
    ) : TextTypeSource {
        val texts = mutableListOf<String>()

        override fun scores(text: String): List<TypeScore> {
            texts += text
            return scores.toList()
        }
    }

    @Test
    fun `the highest score of a source names the type`() {
        assertEquals(json, detector(listOf(source(yaml to 60, json to 70))).detect("a: 1"))
    }

    @ParameterizedTest
    @CsvSource("49, Plain Text", "50, YAML")
    fun `a best score below 50 means Plain Text`(
        score: Int,
        expected: String,
    ) {
        assertEquals(expected, detector(listOf(source(yaml to score))).detect("a: 1").name)
    }

    @Test
    fun `a tie between two sources goes to the type earlier in the list`() {
        assertEquals(json, detector(listOf(source(yaml to 70), source(json to 70))).detect("x"))
        assertEquals(json, detector(listOf(source(yaml to 70, json to 70))).detect("x"))
    }

    @Test
    fun `a tie between a rule and a source goes to the type earlier in the list, whichever scored it`() {
        assertEquals(markdown, detector(listOf(source(markdown to 70)), listOf(source(json to 70))).detect("x"))
        assertEquals(markdown, detector(listOf(source(json to 70)), listOf(source(markdown to 70))).detect("x"))
    }

    @Test
    fun `a rule's higher score beats a source's`() {
        assertEquals(css, detector(listOf(source(yaml to 60)), listOf(source(css to 80))).detect("x"))
    }

    @Test
    fun `a throwing source counts as no scores, the others still count`() {
        val throwing = TextTypeSource { throw IllegalStateException("broken") }
        assertEquals(yaml, detector(listOf(throwing, source(yaml to 60))).detect("x"))
        assertEquals(TextTypes.PLAIN_TEXT, detector(listOf(throwing)).detect("x"))
    }

    @Test
    fun `a throwing rule counts as no scores`() {
        val throwing = TextTypeSource { throw IllegalArgumentException("broken") }
        assertEquals(yaml, detector(listOf(source(yaml to 60)), listOf(throwing)).detect("x"))
    }

    @Test
    fun `a source or rule that fails with an error such as a stack overflow counts as no scores too`() {
        val failing = TextTypeSource { throw StackOverflowError() }
        assertEquals(yaml, detector(listOf(failing, source(yaml to 60))).detect("x"))
        assertEquals(yaml, detector(listOf(source(yaml to 60)), listOf(failing)).detect("x"))
    }

    @Test
    fun `a score of 100 counts, one above 100 is ignored rather than clamped`() {
        assertEquals(json, detector(listOf(source(yaml to 60, json to 100))).detect("x"))
        assertEquals(yaml, detector(listOf(source(yaml to 60, json to 101))).detect("x"))
    }

    @Test
    fun `No extension and a type outside the list are never the answer`() {
        val go = TextType("Go", "go")
        assertEquals(TextTypes.PLAIN_TEXT, detector(listOf(source(TextTypes.NO_EXTENSION to 100))).detect("x"))
        assertEquals(yaml, detector(listOf(source(TextTypes.NO_EXTENSION to 100, go to 90, yaml to 60))).detect("x"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\n\t \r\n", "\uFEFF", "\uFEFF  \n"])
    fun `a blank text is Plain Text whatever the sources say`(text: String) {
        assertEquals(
            TextTypes.PLAIN_TEXT,
            detector(listOf(source(json to 100)), listOf(source(css to 100))).detect(text),
        )
    }

    @Test
    fun `sources and rules get the text without its BOM`() {
        val source = Recording(TypeScore(json, 100))
        val rule = Recording()
        assertEquals(json, detector(listOf(source), listOf(rule)).detect("\uFEFF{\"a\": 1}"))
        assertEquals(listOf("{\"a\": 1}"), source.texts)
        assertEquals(listOf("{\"a\": 1}"), rule.texts)
    }

    @Test
    fun `a source is asked about a text of up to SOURCE_LIMIT chars, a BOM not counted`() {
        val source = Recording(TypeScore(json, 60))
        val atLimit = "a".repeat(SOURCE_LIMIT)
        assertEquals(json, detector(listOf(source)).detect(atLimit))
        assertEquals(json, detector(listOf(source)).detect("\uFEFF" + atLimit))
        assertEquals(listOf(atLimit, atLimit), source.texts)
    }

    @Test
    fun `a source is not asked about a longer text, the rules still are`() {
        val source = Recording(TypeScore(json, 60))
        val rule = Recording(TypeScore(css, 55))
        assertEquals(css, detector(listOf(source), listOf(rule)).detect("a".repeat(SOURCE_LIMIT + 1)))
        assertEquals(emptyList(), source.texts)
        assertEquals(1, rule.texts.size)
    }

    @Test
    fun `the rules see the first RULES_LIMIT chars, the sources the whole text`() {
        val source = Recording()
        val rule = Recording()
        val text = "a".repeat(RULES_LIMIT) + "bc"
        detector(listOf(source), listOf(rule)).detect(text)
        assertEquals(listOf("a".repeat(RULES_LIMIT)), rule.texts)
        assertEquals(listOf(text), source.texts)
    }
}
