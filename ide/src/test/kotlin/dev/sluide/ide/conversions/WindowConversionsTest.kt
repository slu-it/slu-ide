package dev.sluide.ide.conversions

import dev.sluide.converting.FileConversion
import dev.sluide.converting.TextFormat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowConversionsTest {
    @TempDir
    lateinit var dir: Path

    private class Format(
        override val id: String,
        override val name: String,
        override val extensions: List<String>,
    ) : TextFormat {
        override val language: String? = null
    }

    private val yaml = Format("yaml", "YAML", listOf("yaml", "yml"))

    private val events = mutableListOf<String>()
    private var overwrite = true
    private var result: FileConversion = FileConversion.Converted("a: 1\n", emptyList())
    private var failWrite: IOException? = null
    private var unsaved = false
    private var convertSaved = true

    private fun conversions() =
        WindowConversions(
            targets = { if (it.fileName.toString().endsWith(".json")) listOf(yaml) else emptyList() },
            convert = { file, format ->
                events += "convert ${file.fileName} ${format.name}"
                result
            },
            reveal = { events += "reveal ${it.fileName}" },
            open = { events += "open ${it.fileName}" },
            askToOverwrite = {
                events += "ask ${it.fileName}"
                overwrite
            },
            hasUnsavedChanges = { unsaved },
            askToConvertSaved = {
                events += "saved ${it.fileName}"
                convertSaved
            },
            showError = { events += "error $it" },
            showNotes = { events += "notes $it" },
            executor = { it.run() },
            dispatch = { it.run() },
            write = { path, text ->
                failWrite?.let { throw it }
                Files.writeString(path, text)
            },
        )

    private fun source() = dir.resolve("a.json").also { Files.writeString(it, "{}") }

    @Test
    fun `it offers the names of the targets`() {
        assertEquals(listOf("YAML"), conversions().targetNames(dir.resolve("a.json")))
        assertEquals(emptyList(), conversions().targetNames(dir.resolve("a.txt")))
    }

    @Test
    fun `a conversion is written beside the file, shown and opened`() {
        conversions().convert(source(), "YAML")

        assertEquals(listOf("convert a.json YAML", "reveal a.yaml", "open a.yaml"), events)
        assertEquals("a: 1\n", Files.readString(dir.resolve("a.yaml")))
    }

    @Test
    fun `an unknown format name does nothing`() {
        conversions().convert(source(), "XML")

        assertEquals(emptyList(), events)
    }

    @Test
    fun `an existing file is replaced only after the user agrees`() {
        Files.writeString(dir.resolve("a.yaml"), "old")
        overwrite = false
        conversions().convert(source(), "YAML")

        assertEquals(listOf("convert a.json YAML", "ask a.yaml"), events)
        assertEquals("old", Files.readString(dir.resolve("a.yaml")))

        events.clear()
        overwrite = true
        conversions().convert(source(), "YAML")

        assertEquals(listOf("convert a.json YAML", "ask a.yaml", "reveal a.yaml", "open a.yaml"), events)
        assertEquals("a: 1\n", Files.readString(dir.resolve("a.yaml")))
    }

    @Test
    fun `notes are shown after the file is opened`() {
        result = FileConversion.Converted("a: 1\n", listOf("2 comments dropped"))
        conversions().convert(source(), "YAML")

        assertEquals(
            "notes “a.json” was converted to YAML as “a.yaml”, but not everything survived:\n• 2 comments dropped",
            events.last(),
        )
    }

    @Test
    fun `a failed conversion or write shows an error and writes nothing`() {
        result = FileConversion.Failed("a.json cannot be read as JSON. Line 1, column 2: oops")
        conversions().convert(source(), "YAML")
        assertEquals(
            listOf("convert a.json YAML", "error a.json cannot be read as JSON. Line 1, column 2: oops"),
            events,
        )

        events.clear()
        result = FileConversion.Converted("a: 1\n", emptyList())
        failWrite = IOException("disk full")
        conversions().convert(source(), "YAML")
        assertEquals(listOf("convert a.json YAML", "error “a.yaml” cannot be written: disk full"), events)

        events.clear()
        failWrite = AccessDeniedException("a.yaml")
        conversions().convert(source(), "YAML")
        assertEquals(listOf("convert a.json YAML", "error “a.yaml” cannot be written: access denied"), events)

        events.clear()
        failWrite = NoSuchFileException("a.yaml")
        conversions().convert(source(), "YAML")
        assertEquals(listOf("convert a.json YAML", "error “a.yaml” cannot be written: the folder is missing"), events)
    }

    @Test
    fun `a folder in the way is an error without a question`() {
        Files.createDirectory(dir.resolve("a.yaml"))
        conversions().convert(source(), "YAML")

        assertEquals(listOf("convert a.json YAML", "error A folder named a.yaml is in the way"), events)
    }

    @Test
    fun `a dangling symbolic link counts as an existing file and asks`() {
        Files.createSymbolicLink(dir.resolve("a.yaml"), dir.resolve("nowhere"))
        overwrite = false
        conversions().convert(source(), "YAML")

        assertEquals(listOf("convert a.json YAML", "ask a.yaml"), events)
    }

    @Test
    fun `a file with unsaved changes is converted from disk only after the user agrees`() {
        unsaved = true
        convertSaved = false
        conversions().convert(source(), "YAML")

        assertEquals(listOf("saved a.json"), events)

        events.clear()
        convertSaved = true
        conversions().convert(source(), "YAML")

        assertEquals(listOf("saved a.json", "convert a.json YAML", "reveal a.yaml", "open a.yaml"), events)
    }

    @Test
    fun `writing replaces a file or a symbolic link without writing through it and leaves no temporary file`() {
        val target = dir.resolve("target.txt").also { Files.writeString(it, "keep") }
        val link = dir.resolve("link.yaml").also { Files.createSymbolicLink(it, target) }

        writeAtomically(link, "new")

        assertEquals("new", Files.readString(link))
        assertEquals(false, Files.isSymbolicLink(link))
        assertEquals("keep", Files.readString(target))
        assertEquals(
            setOf("target.txt", "link.yaml"),
            Files.list(dir).use { s ->
                s.map { it.fileName.toString() }.toList().toSet()
            },
        )
    }

    @Test
    fun `a result that comes after the window closed is dropped`() {
        val pending = mutableListOf<Runnable>()
        val conversions =
            WindowConversions(
                targets = { listOf(yaml) },
                convert = { _, _ -> result },
                reveal = { events += "reveal" },
                open = { events += "open" },
                askToOverwrite = { true },
                hasUnsavedChanges = { false },
                askToConvertSaved = { true },
                showError = { events += "error" },
                showNotes = { events += "notes" },
                executor = { it.run() },
                dispatch = { pending += it },
            )
        conversions.convert(source(), "YAML")
        conversions.dispose()
        pending.forEach { it.run() }

        assertEquals(emptyList(), events)
        assertEquals(false, Files.exists(dir.resolve("a.yaml")))
    }

    @ParameterizedTest
    @CsvSource(
        "a.json,yaml,'',a.yaml",
        "a.json,yaml,'x.yml',a.yml",
        "a.json,yaml,'x.yml;y.yaml',a.yaml",
        "a.json,yaml,'x.YML',a.yml",
        "a.properties,yaml,'x.yml',a.yml",
        "a.yml,json,'x.yml',a.json",
        "a.b.yaml,json,'',a.b.json",
        "a.yaml,toml,'x.yml',a.toml",
    )
    fun `the target file keeps the base name and takes the first extension, yml in a folder of yml files`(
        source: String,
        format: String,
        siblings: String,
        expected: String,
    ) {
        val extensions = if (format == "yaml") yaml.extensions else listOf(format)

        val actual = targetFile(dir.resolve(source), extensions, siblings.split(';').filter { it.isNotEmpty() })

        assertEquals(dir.resolve(expected), actual)
    }

    @Test
    fun `the overwrite question names the file`() {
        assertEquals(
            "“a.yaml” exists already. Replace it with the converted file?",
            overwriteQuestion(dir.resolve("a.yaml")),
        )
    }

    @Test
    fun `a new file gets the usual mode, a replaced file keeps its own`() {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"))
        val plain = dir.resolve("plain.txt").also { Files.writeString(it, "x") }
        val created = dir.resolve("new.yaml")

        writeAtomically(created, "a: 1")

        assertEquals(Files.getPosixFilePermissions(plain), Files.getPosixFilePermissions(created))

        val old = dir.resolve("old.yaml").also { Files.writeString(it, "x") }
        val mode = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(old, mode)

        writeAtomically(old, "a: 2")

        assertEquals("a: 2", Files.readString(old))
        assertEquals(mode, Files.getPosixFilePermissions(old))
    }

    @Test
    fun `a failed write leaves no temporary file`() {
        val folder = Files.createDirectory(dir.resolve("a.yaml"))
        Files.writeString(folder.resolve("inside.txt"), "x")

        assertFailsWith<IOException> { writeAtomically(folder, "a: 1") }

        assertEquals(setOf("a.yaml"), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSet() })
    }
}
