package dev.sluide.fileexplorer.actions

import dev.sluide.texttypes.TextTypes
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The final name, its problem, its note and the labels of the Paste Text dialog, without the dialog. */
class PasteTextNameTest {
    @TempDir
    lateinit var folder: Path

    private val json = TextTypes.byExtension("json")!!
    private val yaml = TextTypes.byExtension("yaml")!!

    @ParameterizedTest
    @CsvSource(
        "pasted,pasted.json",
        "config.yaml,config.yaml.json",
        "a.json,a.json.json",
        "dir/x,dir/x.json",
        "'',.json",
    )
    fun `the extension is always appended`(
        typed: String,
        expected: String,
    ) {
        assertEquals(expected, finalTextName(typed, json))
    }

    @Test
    fun `No extension saves the name as typed`() {
        assertEquals("Dockerfile", finalTextName("Dockerfile", TextTypes.NO_EXTENSION))
    }

    @Test
    fun `a name is judged with its extension, so the type decides whether it is taken`() {
        folder.resolve("taken.json").writeText("x")

        assertNull(textNameProblem("fresh", json, folder))
        assertTrue(checkNotNull(textNameProblem("taken", json, folder)).contains("already exists"))
        assertNull(textNameProblem("taken", yaml, folder))
        assertTrue(
            checkNotNull(textNameProblem("taken.json", TextTypes.NO_EXTENSION, folder)).contains("already exists"),
        )
    }

    @Test
    fun `a blank name is judged as typed, a name ending with a slash is refused, folders to create are fine`() {
        assertTrue(checkNotNull(textNameProblem("", json, folder)).isNotEmpty())
        assertTrue(checkNotNull(textNameProblem("  ", json, folder)).isNotEmpty())
        assertEquals("The name must not end with /.", textNameProblem("dir/", json, folder))
        assertEquals("The name must not end with /.", textNameProblem("dir/", TextTypes.NO_EXTENSION, folder))
        assertNull(textNameProblem("new/sub/x", json, folder))
    }

    @Test
    fun `the note says what the name is saved as, also without a change, not for blank or folder names`() {
        assertEquals("Saved as “config.yaml.json”.", textNameNote("config.yaml", json))
        assertEquals("Saved as “Makefile”.", textNameNote("Makefile", TextTypes.NO_EXTENSION))
        assertNull(textNameNote("", json))
        assertNull(textNameNote("   ", json))
        assertNull(textNameNote("dir/", json))
    }

    @Test
    fun `the type label shows the extension, none for No extension, and marks the detected one`() {
        assertEquals("JSON (.json)", textTypeLabel(json, yaml))
        assertEquals("JSON (.json) · detected", textTypeLabel(json, json))
        assertEquals("No extension", textTypeLabel(TextTypes.NO_EXTENSION, json))
        assertEquals("No extension · detected", textTypeLabel(TextTypes.NO_EXTENSION, TextTypes.NO_EXTENSION))
    }
}
