package dev.sluide.previews.preview

import kotlin.test.Test
import kotlin.test.assertEquals

class OverwriteQuestionTest {
    @Test
    fun `one file is asked about by its name`() {
        assertEquals(
            "a.svg already exists in this folder. Replace it with the export?",
            overwriteQuestion(listOf("a.svg")),
        )
        assertEquals("Replace a.svg?", overwriteHeader(listOf("a.svg")))
    }

    @Test
    fun `two files are named with and`() {
        assertEquals(
            "a.svg and a-2.svg already exist in this folder. Replace them with the export?",
            overwriteQuestion(listOf("a.svg", "a-2.svg")),
        )
        assertEquals("Replace 2 files?", overwriteHeader(listOf("a.svg", "a-2.svg")))
    }

    @Test
    fun `more files are separated by commas, the last by and`() {
        assertEquals(
            "a.svg, a-2.svg and a-3.svg already exist in this folder. Replace them with the export?",
            overwriteQuestion(listOf("a.svg", "a-2.svg", "a-3.svg")),
        )
        assertEquals("Replace 3 files?", overwriteHeader(listOf("a.svg", "a-2.svg", "a-3.svg")))
    }
}
