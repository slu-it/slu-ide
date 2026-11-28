package dev.sluide.codeintelligence.completion

import dev.sluide.codeintelligence.SourceCompletion
import dev.sluide.codeintelligence.SourceCompletions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/**
 * How [sourceItems] lets a source's items be filtered by the word at the caret when the source anchors them before it
 * (ADR 0145, amendment 2026-11-28): the label without the fixed part between the anchor and the word.
 */
internal class SourceItemsTest {
    @ParameterizedTest(name = "{0} anchored at {1}, {2}: {3}")
    @CsvSource(
        "'x = {{\$uu', '$', '\$uuid', uuid",
        "'Content-T', 'C', 'Content-Type', Type",
        "'content-t', 'c', 'Content-Type', Type",
        "'Content-Type: application/j', 'a', 'application/json', json",
        "'x = {{\$random.in', '$', '\$random.integer', integer",
    )
    fun `an item anchored before the word at the caret filters by its label without the part before the word`(
        text: String,
        anchorAt: String,
        label: String,
        filterText: String,
    ) {
        assertEquals(filterText, filterTextOf(text, anchorAt, label))
    }

    @ParameterizedTest(name = "{0} anchored at {1}")
    @CsvSource(
        "'x = util.Li', 'L', 'List'",
        "'x = abcd', 'c', 'cde'",
    )
    fun `an item anchored at the word's start or within it filters by its label`(
        text: String,
        anchorAt: String,
        label: String,
    ) {
        assertEquals(null, filterTextOf(text, anchorAt, label))
    }

    @Test
    fun `an item whose label does not start with the part before the word filters by its label`() {
        assertEquals(null, filterTextOf("x = {{\$uu", "$", "uuid"))
    }

    @Test
    fun `an item anchored on an earlier line drops the part before the word from the caret's line start`() {
        assertEquals("Type", filterTextOf("Accept: */*\nContent-T", "A", "Content-Type"))
    }

    private fun filterTextOf(
        text: String,
        anchorAt: String,
        label: String,
    ) = sourceItems(SourceCompletions(text.indexOf(anchorAt), listOf(SourceCompletion(label))), text, text.length)
        .single()
        .filterText
}
