package dev.sluide.gitlog.area

import dev.sluide.gitlog.filter.FilterItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoToCompletionsTest {
    private val items =
        listOf("main", "feature/a", "Feature/B", "origin/main", "v1.0").map { FilterItem("refs/x/$it", it) }

    private fun texts(found: List<FilterItem>) = found.map { it.text }

    @Test
    fun `completes the names that have the text, ignoring case, none for no text`() {
        assertEquals(listOf("feature/a", "Feature/B"), texts(goToCompletions(items, " FEAT ")))
        assertEquals(listOf("main", "origin/main"), texts(goToCompletions(items, "mai")))
        assertEquals(emptyList(), goToCompletions(items, "  "))
        assertEquals(emptyList(), goToCompletions(items, "nothing"))
    }

    @Test
    fun `takes the one completion named exactly`() {
        assertEquals("refs/x/main", exactCompletion(items, "main")?.value)
        assertNull(exactCompletion(items, "Main"), "case matters for refnames")
        assertNull(exactCompletion(items + FilterItem("refs/tags/main", "main"), "main"), "several")
    }
}
