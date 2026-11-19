package dev.sluide.ide.clipboard

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore
import kotlin.test.Test
import kotlin.test.assertEquals

class ConvertingTextTypesTest {
    private class Format(
        override val id: String,
        override val extensions: List<String>,
    ) : TextFormat {
        override val name = id
        override val language: String? = null
    }

    /** A family that answers [detect] with [scores] (format, score); the rest is never asked. */
    private class FakeFamily(
        private val scores: List<Pair<Format, Int>>,
        private val failure: RuntimeException? = null,
    ) : FormatFamily<String> {
        override val name = "Fake"
        override val formats: List<TextFormat> = scores.map { it.first }

        override fun detect(text: String): List<FormatMatch> {
            failure?.let { throw it }
            return scores.map { FormatMatch(it.first, it.second) }
        }

        override fun readOptions(format: TextFormat) = emptyList<ConversionOption>()

        override fun writeOptions(format: TextFormat) = emptyList<ConversionOption>()

        override fun read(
            text: String,
            format: TextFormat,
            options: OptionValues,
        ): ReadResult<String> = error("not asked")

        override fun write(
            content: String,
            format: TextFormat,
            options: OptionValues,
        ): WriteResult = error("not asked")
    }

    private val json = Format("json", listOf("json", "jsonc"))
    private val yaml = Format("yaml", listOf("yaml", "yml"))
    private val toml = Format("toml", listOf("toml"))
    private val curl = Format("curl", listOf("sh"))
    private val http = Format("http", listOf("http"))

    private fun type(extension: String): TextType = checkNotNull(TextTypes.byExtension(extension))

    private fun scores(
        format: Format,
        score: Int,
        text: String,
    ): List<TypeScore> = ConvertingTextTypes(listOf(FakeFamily(listOf(format to score)))).scores(text)

    @Test
    fun `a match maps through its format's first extension to the score of that type, as given`() {
        assertEquals(listOf(TypeScore(type("json"), 93)), scores(json, 93, "{\"a\": 1}"))
        assertEquals(listOf(TypeScore(type("yaml"), 80)), scores(yaml, 80, "server:\n  port: 8080\n"))
        assertEquals(listOf(TypeScore(type("toml"), 70)), scores(toml, 70, "[a]\nb = 1\n"))
    }

    @Test
    fun `a cURL command is Shell Script and an HTTP request is HTTP Request`() {
        val curlText = "curl -X POST https://example.com \\\n  -d '{}'\n"
        assertEquals(listOf(TypeScore(type("sh"), 90)), scores(curl, 90, curlText))
        assertEquals(listOf(TypeScore(type("http"), 85)), scores(http, 85, "GET https://example.com\nAccept: */*\n"))
    }

    @Test
    fun `a format whose extension is no known type is dropped`() {
        val unknown = Format("xyz", listOf("xyz"))
        val family = FakeFamily(listOf(unknown to 90, json to 60))

        assertEquals(listOf(TypeScore(type("json"), 60)), ConvertingTextTypes(listOf(family)).scores("{\"a\": 1}"))
    }

    @Test
    fun `a family that throws counts as nothing and the others still score`() {
        val failing = FakeFamily(listOf(yaml to 80), RuntimeException("boom"))
        val working = FakeFamily(listOf(json to 90))

        assertEquals(listOf(TypeScore(type("json"), 90)), ConvertingTextTypes(listOf(failing, working)).scores("{}"))
    }
}
