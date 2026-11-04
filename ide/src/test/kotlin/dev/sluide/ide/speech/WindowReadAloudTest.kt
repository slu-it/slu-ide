package dev.sluide.ide.speech

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowReadAloudTest {
    private val file = Path.of("/p/notes.md")
    private val reads = mutableListOf<Path>()
    private val errors = mutableListOf<String>()
    private val saves = mutableListOf<Path>()
    private val asked = mutableListOf<Path>()

    private fun readAloud(
        dirty: Boolean,
        choice: UnsavedReadChoice = UnsavedReadChoice.CANCEL,
        saved: Boolean = true,
        readError: String? = null,
    ) = WindowReadAloud(
        { f, onError ->
            reads.add(f)
            readError?.let(onError)
        },
        { dirty },
        { f ->
            saves.add(f)
            saved
        },
        { f ->
            asked.add(f)
            choice
        },
        { errors += it },
    )

    @ParameterizedTest
    @CsvSource(
        "a.txt,true",
        "A.TXT,true",
        "notes.md,true",
        "README.MD,true",
        "a.kt,false",
        "txt,false",
        "a.md.bak,false",
    )
    fun `applies to txt and md files by name, ignoring case`(
        name: String,
        applies: Boolean,
    ) {
        assertEquals(applies, readAloud(false).action.appliesTo(Path.of("/p/$name")))
    }

    @Test
    fun `a clean file is read without a question`() {
        readAloud(dirty = false).perform(file)

        assertEquals(listOf(file), reads)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `Save and Read Aloud saves, then reads`() {
        readAloud(dirty = true, UnsavedReadChoice.SAVE_AND_READ).perform(file)

        assertEquals(listOf(file), saves)
        assertEquals(listOf(file), reads)
    }

    @Test
    fun `a failed save reads nothing`() {
        readAloud(dirty = true, UnsavedReadChoice.SAVE_AND_READ, saved = false).perform(file)

        assertEquals(listOf(file), saves)
        assertEquals(emptyList(), reads)
    }

    @Test
    fun `Read Saved Version reads without saving`() {
        readAloud(dirty = true, UnsavedReadChoice.READ_SAVED).perform(file)

        assertEquals(emptyList(), saves)
        assertEquals(listOf(file), reads)
    }

    @Test
    fun `Cancel neither saves nor reads`() {
        readAloud(dirty = true, UnsavedReadChoice.CANCEL).perform(file)

        assertEquals(listOf(file), asked)
        assertEquals(emptyList(), saves)
        assertEquals(emptyList(), reads)
    }

    @Test
    fun `a read error goes to the error dialog`() {
        readAloud(dirty = false, readError = "too big").perform(file)

        assertEquals(listOf("too big"), errors)
    }
}
