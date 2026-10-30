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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext

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
    fun `convert reports a read error with its offset`() {
        val anyFamily: FormatFamily<*> = family

        val failed = anyFamily.convert("{\"a\" 1}", JsonFormat, JsonFormat, OptionValues())

        assertEquals(5, (failed as ConversionResult.ReadFailed).offset)
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
