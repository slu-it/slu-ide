package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.FakeCodeEditor
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The word at an offset: identifier characters by code point, a caret also taking the word that ends at it. */
class WordsTest {
    @ParameterizedTest(name = "\"{0}\" at {1} (caret: {2}) is [{3}, {4})")
    @CsvSource(
        delimiter = '|',
        value = [
            "print(value)|0|false|0|5",
            "print(value)|4|false|0|5",
            "print(value)|5|false|-1|-1",
            "print(value)|5|true|0|5",
            "print(value)|6|true|6|11",
            "print(value)|12|true|-1|-1",
            "a_b2 = 1|3|false|0|4",
            "x = größe|6|false|4|9",
            "x = 𝑥y|5|false|4|7",
            "'  '|1|true|-1|-1",
            "a + b|2|true|-1|-1",
            "a + b|1|true|0|1",
        ],
    )
    fun `the word at a column`(
        line: String,
        column: Int,
        orBefore: Boolean,
        start: Int,
        end: Int,
    ) {
        val expected = if (start < 0) null else start until end
        assertEquals(expected, wordIn(line, column, orBefore))
    }

    @Test
    fun `the word at an editor offset is found on its line`() {
        val editor = FakeCodeEditor(Path.of("a.py"), "import os\nos.path\n")
        assertEquals(10 until 12, editor.wordAt(11))
        assertEquals(13 until 17, editor.wordAt(17, orBefore = true))
        assertNull(editor.wordAt(12))
        assertNull(editor.wordAt(18))
    }
}
