package dev.sluide.converting.data

import dev.sluide.converting.ConversionResult
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import dev.sluide.converting.convert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import tools.jackson.databind.node.JsonNodeFactory

class DataFamilyTest {
    private val family = DataFamily()

    @ParameterizedTest
    @ValueSource(strings = ["", "  \n\t"])
    fun `blank text reads as no documents in every format`(text: String) {
        family.formats.forEach { format ->
            assertEquals(
                ReadResult.Read(DataContent(emptyList()), emptyList()),
                family.read(text, format, OptionValues()),
            )
        }
    }

    @Test
    fun `no documents write as empty text in every format`() {
        family.formats.forEach { format ->
            assertEquals(
                WriteResult.Written("", emptyList()),
                family.write(DataContent(emptyList()), format, OptionValues()),
            )
        }
    }

    @Test
    fun `blank text matches no format`() {
        assertEquals(emptyList<FormatMatch>(), family.detect(" \n"))
    }

    @Test
    fun `convert reads, writes and joins the notes of both`() {
        val anyFamily: FormatFamily<*> = family

        assertEquals(
            ConversionResult.Converted("{\"a\":1}", listOf("1 comment dropped")),
            anyFamily.convert("{\"a\": 1} // x", JsonFormat, JsonFormat, OptionValues().with("compact", true)),
        )
    }

    @Test
    fun `convert reports a write error`() {
        val unwritable =
            object : DataFormat by JsonFormat {
                override val id = "unwritable"

                override fun write(
                    content: DataContent,
                    options: OptionValues,
                ) = WriteResult.Failed("Cannot hold it")
            }
        val anyFamily: FormatFamily<*> = DataFamily(listOf(JsonFormat, unwritable))

        assertEquals(
            ConversionResult.WriteFailed("Cannot hold it"),
            anyFamily.convert("[1]", JsonFormat, unwritable, OptionValues()),
        )
    }

    @Test
    fun `several YAML documents with comments become a JSON array with notes from reading and writing`() {
        assertEquals(
            ConversionResult.Converted(
                "[{\"a\":1},{\"b\":2}]",
                listOf("1 comment dropped", "2 documents written as an array"),
            ),
            family.convert("a: 1 # one\n---\nb: 2\n", YamlFormat, JsonFormat, OptionValues().with("compact", true)),
        )
    }

    @Test
    fun `detection lists every format that reads the text, surest first`() {
        assertEquals(
            listOf(JsonFormat to 100, YamlFormat to 30),
            family.detect("{\"a\": [1, 2]}").map {
                it.format to
                    it.score
            },
        )
        assertEquals(listOf(YamlFormat to 80), family.detect("a:\n  - 1").map { it.format to it.score })
        assertEquals(listOf(JsonFormat to 20, YamlFormat to 10), family.detect("42").map { it.format to it.score })
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["spring-application.yml", "github-workflow.yml", "kubernetes-manifest.yaml", "anchors.yaml"],
    )
    fun `YAML converted to JSON and back reads as the same documents`(resource: String) {
        val yaml = checkNotNull(javaClass.getResource(resource)).readText()
        val original = (family.read(yaml, YamlFormat, OptionValues()) as ReadResult.Read).content

        val json = (family.convert(yaml, YamlFormat, JsonFormat, OptionValues()) as ConversionResult.Converted).text
        val asJson = (family.read(json, JsonFormat, OptionValues()) as ReadResult.Read).content
        val documents = if (original.documents.size > 1) asJson.documents.single().toList() else asJson.documents
        val backToYaml = (family.write(DataContent(documents), YamlFormat, OptionValues()) as WriteResult.Written).text

        assertEquals(original, (family.read(backToYaml, YamlFormat, OptionValues()) as ReadResult.Read).content)
    }

    @Test
    fun `a GitHub workflow keeps its on key and its on-off strings`() {
        val yaml = checkNotNull(javaClass.getResource("github-workflow.yml")).readText()

        val workflow = (family.read(yaml, YamlFormat, OptionValues()) as ReadResult.Read).content.documents.single()
        val written =
            (
                family.write(
                    DataContent(listOf(workflow)),
                    YamlFormat,
                    OptionValues(),
                ) as WriteResult.Written
            ).text

        assertEquals(listOf("push", "pull_request"), workflow["on"].properties().map { it.key })
        assertEquals("off", workflow["env"]["FEATURE"].asString())
        assertTrue(written.startsWith("name: Build\n'on':\n"), written)
        assertTrue("FEATURE: 'off'" in written, written)
    }

    @Test
    fun `convert reports a read error with its offset`() {
        val anyFamily: FormatFamily<*> = family

        val failed = anyFamily.convert("{\"a\" 1}", JsonFormat, JsonFormat, OptionValues())

        assertEquals(5, (failed as ConversionResult.ReadFailed).offset)
    }

    @Test
    fun `an exception of a format becomes a failure and no match`() {
        val throwing =
            object : DataFormat by JsonFormat {
                override val id = "throwing"

                override fun detect(text: String): Int = error("detect broke")

                override fun read(
                    text: String,
                    options: OptionValues,
                ): ReadResult<DataContent> = error("read broke")

                override fun write(
                    content: DataContent,
                    options: OptionValues,
                ): WriteResult = throw IndexOutOfBoundsException("write broke")
            }
        val family = DataFamily(listOf(JsonFormat, throwing))

        assertEquals(listOf<TextFormat>(JsonFormat), family.detect("[1]").map { it.format })
        assertEquals(ReadResult.Failed("Read broke", null), family.read("[1]", throwing, OptionValues()))
        assertEquals(
            WriteResult.Failed("Write broke"),
            family.write(DataContent(listOf(JsonNodeFactory.instance.arrayNode())), throwing, OptionValues()),
        )
    }

    @Test
    fun `a format of another family is refused`() {
        val other =
            object : TextFormat {
                override val id = "curl"
                override val name = "cURL"
                override val language: String? = null
                override val extensions = listOf("sh")
            }

        assertThrows<IllegalArgumentException> { family.read("x", other, OptionValues()) }
    }

    @Test
    fun `Spring creates the family with the built-in formats`() {
        AnnotationConfigApplicationContext().use { context ->
            context.register(DataFamily::class.java)
            context.refresh()

            assertEquals(DataFormats.all, context.getBean(DataFamily::class.java).formats)
        }
    }
}
