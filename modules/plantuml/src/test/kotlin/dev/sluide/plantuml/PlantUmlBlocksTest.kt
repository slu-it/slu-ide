package dev.sluide.plantuml

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class PlantUmlBlocksTest {
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        textBlock = """
        "@startuml"                    | true
        "  \t@startmindmap"            | true
        "Alice -> Bob\n  @startuml\nA" | true
        "x @startuml"                  | false
        "Alice -> Bob"                 | false
        ""                             | false""",
    )
    fun `a block is a line that starts with @start after blanks`(
        text: String,
        expected: Boolean,
    ) {
        assertEquals(expected, plantUmlBlocks(text.replace("\\n", "\n").replace("\\t", "\t")))
    }
}
