package dev.sluide.codeintelligence.completion

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which completion items show for the typed prefix and in which order; the word around the caret. */
internal class CompletionMatchingTest {
    private fun item(
        label: String,
        sortText: String? = null,
        filterText: String? = null,
        detail: String? = null,
        preselect: Boolean = false,
    ) = CompletionItem(label).apply {
        this.sortText = sortText
        this.filterText = filterText
        this.detail = detail
        this.preselect = preselect.takeIf { it }
    }

    private fun ranked(
        prefix: String,
        vararg items: CompletionItem,
        cap: Int = MAX_SHOWN,
    ) = rankCompletions(items.toList(), prefix, cap).map { it.label }

    @ParameterizedTest
    @CsvSource(
        "get, getcwd, PREFIX",
        "GET, getcwd, PREFIX",
        "gc, get_cwd, BOUNDARY",
        "gcw, get_cwd, BOUNDARY",
        "gC, getCwd, BOUNDARY",
        "rt, remove_tree, BOUNDARY",
        "cwd, get_cwd, BOUNDARY",
        "rmt, remove_tree, SUBSEQUENCE",
        "gtw, getcwd, SUBSEQUENCE",
        "xyz, getcwd, ",
        "'', anything, PREFIX",
    )
    fun `how a prefix matches`(
        prefix: String,
        candidate: String,
        kind: MatchKind?,
    ) {
        assertEquals(kind, matchKind(prefix, candidate))
    }

    @Test
    fun `prefix matches first, then part starts, then subsequences, each by sortText or label`() {
        assertEquals(
            listOf("path", "pathsep", "pardir_type", "p_a_t", "spawnat"),
            ranked(
                "pa",
                item("spawnat"),
                item("p_a_t"),
                item("pathsep", sortText = "b"),
                item("path", sortText = "a"),
                item("pardir_type"),
                item("other"),
            ),
        )
        assertEquals(listOf("p_a_t", "path", "spawnat"), ranked("pt", item("spawnat"), item("p_a_t"), item("path")))
    }

    @Test
    fun `filterText is matched instead of the label, preselected items lead their group`() {
        assertEquals(
            listOf("chosen", "alpha", "beta"),
            ranked(
                "",
                item("beta"),
                item("alpha"),
                item("chosen", preselect = true),
            ),
        )
        assertEquals(listOf("@property"), ranked("prop", item("@property", filterText = "property"), item("prepare")))
    }

    @Test
    fun `only items that look and act the same show once, ty's imports of one name from several modules all show`() {
        fun import(
            module: String,
            line: Int = 0,
        ) = CompletionItem("panel").apply {
            labelDetails = CompletionItemLabelDetails().apply { detail = " (import $module)" }
            additionalTextEdits =
                listOf(TextEdit(Range(Position(line, 0), Position(line, 0)), "from $module import panel\n"))
        }
        assertEquals(
            listOf(" (import rich.align)", " (import rich.styled)"),
            rankCompletions(listOf(import("rich.align"), import("rich.styled"), import("rich.align")), "pa")
                .map { it.labelDetails.detail },
        )
        assertEquals(2, rankCompletions(listOf(import("rich.align"), import("rich.align", line = 1)), "").size)
        assertEquals(
            listOf("a", "a", "b"),
            ranked("", item("a", detail = "x"), item("a", detail = "x"), item("a", detail = "y"), item("b")),
        )
        val inserting = CompletionItem("a").apply { insertText = "a()" }
        assertEquals(2, rankCompletions(listOf(item("a"), inserting), "").size, "another inserted text")
    }

    @Test
    fun `at most the cap`() {
        assertEquals(listOf("a", "b"), ranked("", item("c"), item("b"), item("a"), cap = 2))
    }

    @Test
    @Timeout(10) // without the memo the match runs for minutes instead of failing
    fun `a name with many parts costs at most prefix times name steps`() {
        val prefix = "aaaaaaaaab"
        val name = "a_".repeat(40) + "z"
        assertNull(matchKind(prefix, name), "no b in it")
        assertTrue(boundaryMatchSteps(prefix, name) <= (prefix.length + 1) * (name.length + 1))
    }

    @ParameterizedTest
    @CsvSource(
        "'os.pa', 5, 3, 5",
        "'os.pa', 4, 3, 5",
        "'os.', 3, 3, 3",
        "'x = ñame_2', 6, 4, 10",
        "'x = e\u0301t', 7, 4, 7",
        "'x = \uD835\uDC65y', 7, 4, 7",
        "'', 0, 0, 0",
    )
    fun `the word around a column`(
        line: String,
        column: Int,
        start: Int,
        end: Int,
    ) {
        assertEquals(start, wordStart(line, column))
        assertEquals(end, wordEnd(line, column))
    }
}
