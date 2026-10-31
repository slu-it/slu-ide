package dev.sluide.converting.model

import dev.sluide.converting.FileConversion
import dev.sluide.converting.OptionValues
import dev.sluide.converting.data.DataFamily
import dev.sluide.converting.data.JsonFormat
import dev.sluide.converting.data.YamlFormat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileConverterTest {
    @TempDir
    lateinit var dir: Path

    private val converter = FileConverter(listOf(DataFamily(), FakeFamily("Requests", listOf(FakeFormat("http")))))

    private fun file(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text) }

    @Test
    fun `targets are the other data formats, none for other families or unknown extensions`() {
        assertEquals(
            listOf("yaml", "toml", "properties", "csv", "tsv"),
            converter.targets(dir.resolve("a.json")).map { it.id },
        )
        assertEquals(JsonFormat.id, converter.targets(dir.resolve("a.YML")).first().id)
        assertEquals(emptyList(), converter.targets(dir.resolve("a.http")))
        assertEquals(emptyList(), converter.targets(dir.resolve("a.txt")))
        assertEquals(emptyList(), converter.targets(dir.resolve("Makefile")))
    }

    @Test
    fun `a file converts to the target with the notes`() {
        val result = converter.convert(file("a.json", "{\"a\": {\"b\": null}}"), YamlFormat, OptionValues())

        val converted = assertIs<FileConversion.Converted>(result)
        assertEquals("a:\n  b: null\n", converted.text)
    }

    @Test
    fun `a file that does not read fails with its line and column`() {
        val result = converter.convert(file("a.json", "{\n  \"a\": ,\n}"), YamlFormat, OptionValues())

        val failed = assertIs<FileConversion.Failed>(result)
        assertTrue(failed.message.startsWith("a.json cannot be read as JSON. Line 2, column "), failed.message)
    }

    @Test
    fun `a missing, large or binary file fails with the reason`() {
        assertEquals(
            FileConversion.Failed("Cannot read gone.json: file not found"),
            converter.convert(dir.resolve("gone.json"), YamlFormat, OptionValues()),
        )
        assertEquals(
            FileConversion.Failed("big.json is larger than 1 MiB"),
            converter.convert(
                file("big.json", " ".repeat(ConvertingModel.MAX_FILE_SIZE + 1)),
                YamlFormat,
                OptionValues(),
            ),
        )
        Files.write(dir.resolve("bin.json"), byteArrayOf(0xC3.toByte(), 0x28))
        assertEquals(
            FileConversion.Failed("Cannot read bin.json: not UTF-8 text"),
            converter.convert(dir.resolve("bin.json"), YamlFormat, OptionValues()),
        )
    }

    @Test
    fun `what the target cannot hold fails`() {
        val result = converter.convert(file("a.json", "[1, 2]"), formatOf("toml"), OptionValues())

        assertTrue(assertIs<FileConversion.Failed>(result).message.startsWith("a.json cannot be written as TOML."))
    }

    private fun formatOf(id: String) = DataFamily().formats.first { it.id == id }
}
