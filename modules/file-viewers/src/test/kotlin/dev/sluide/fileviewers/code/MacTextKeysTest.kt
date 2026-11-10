package dev.sluide.fileviewers.code

import dev.sluide.textediting.Selection
import dev.sluide.textediting.TextEdit
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacTextKeysTest {
    // Text "ab\ncd\n\nef": paragraphs start at 0, 3, 6, 7 and end at 2, 5, 6, 9.
    @ParameterizedTest(name = "caret {0} {1} goes to {2}")
    @CsvSource(
        "1,up,0",
        "0,up,0",
        "3,up,0",
        "4,up,3",
        "6,up,3",
        "7,up,6",
        "9,up,7",
        "0,down,2",
        "1,down,2",
        "2,down,5",
        "3,down,5",
        "5,down,6",
        "6,down,9",
        "8,down,9",
        "9,down,9",
    )
    fun `paragraph targets`(
        caret: Int,
        direction: String,
        expected: Int,
    ) {
        assertEquals(expected, paragraphTarget("ab\ncd\n\nef", caret, forward = direction == "down"))
    }

    @ParameterizedTest(name = "{0} caret {1} goes to {2}")
    @CsvSource(
        "'hello big world',6,9",
        "'hello big world',5,9",
        "'hello big world',9,15",
        "'hello big world',15,15",
        "'foo.bar',0,3",
        "'foo.bar',3,7",
        "'a_1 b',0,3",
    )
    fun `word right ends at the end of the next word`(
        text: String,
        caret: Int,
        expected: Int,
    ) {
        assertEquals(expected, macWordRight(text, caret))
    }

    @ParameterizedTest(name = "{0} caret {1} goes to {2}")
    @CsvSource(
        "'hello big world',9,6",
        "'hello big world',10,6",
        "'hello big world',6,0",
        "'hello big world',0,0",
        "'foo.bar',7,4",
        "'foo.bar',4,0",
        "'a_1 b',3,0",
    )
    fun `word left starts at the start of the previous word`(
        text: String,
        caret: Int,
        expected: Int,
    ) {
        assertEquals(expected, macWordLeft(text, caret))
    }

    @Test
    fun `no text has nowhere to go`() {
        assertEquals(0, paragraphTarget("", 0, forward = false))
        assertEquals(0, paragraphTarget("", 0, forward = true))
    }

    // Anchor, caret, target, forward, length -> deleted [start, end), or "none".
    @ParameterizedTest(name = "{0}-{1} target {2} forward {3} of {4}: {5}")
    @CsvSource(
        "2,5,0,false,10,2-5",
        "5,2,9,true,10,2-5",
        "5,5,2,false,10,2-5",
        "5,5,8,true,10,5-8",
        "5,5,5,false,10,4-5",
        "5,5,5,true,10,5-6",
        "0,0,0,false,10,none",
        "10,10,10,true,10,none",
        "0,0,0,true,10,0-1",
    )
    fun `delete ranges`(
        anchor: Int,
        caret: Int,
        target: Int,
        forward: Boolean,
        length: Int,
        expected: String,
    ) {
        val edit = macDeleteEdit(Selection(anchor, caret), target, forward, length)
        if (expected == "none") {
            assertNull(edit)
        } else {
            val (start, end) = expected.split("-").map(String::toInt)
            assertEquals(TextEdit(start, end, "", Selection(start)), edit)
        }
    }
}
