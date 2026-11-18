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
        // Part starts: after any non-word character, not inside a run of capitals, never at an underscore.
        "m, pkg.mod, BOUNDARY",
        "pm, pkg.mod, BOUNDARY",
        "b, a\$b, BOUNDARY",
        "B, ABc, SUBSEQUENCE",
        "bc, aBc, BOUNDARY",
        "'.', a..b, SUBSEQUENCE",
        "b, a..b, BOUNDARY",
        "_b, a__b, SUBSEQUENCE",
        "_b, a_b, SUBSEQUENCE",
        "ace, a_c_e, BOUNDARY",
        "ace, ab_cd_ef, BOUNDARY",
        "abcd, abc, ",
        "cwdx, get_cwd, ",
        "ca, xaxc, ",
        "ba, ab, ",
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
        "'os.pa(x)', 4, 3, 5",
        "'abc def', 1, 0, 3",
        "'abc def', 4, 4, 7",
        "'abc def', 3, 0, 3",
        "'abc def', 99, 4, 7",
        "'abc def', -5, 0, 3",
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

    @ParameterizedTest
    @CsvSource(
        "a, true",
        "Z, true",
        "ñ, true",
        "_, true",
        "7, true",
        "\u0663, true", // an Arabic-Indic digit: Nd
        "\u2163, true", // a Roman numeral: Nl, not a letter
        "\u00B2, true", // superscript two: No
        "\u0301, true", // a combining acute accent: Mn
        "\u093E, true", // a Devanagari vowel sign: Mc
        "\u4E2D, true", // a CJK letter
        "' ', false",
        ". , false",
        "-, false",
        "+, false", // Sm
        "\$, false", // Sc
        "(, false",
        "\u20DD, false", // an enclosing mark: Me
        "\u200D, false", // a zero width joiner: Cf
        "\u00A0, false", // a no-break space: Zs
    )
    fun `which characters are word characters`(
        char: String,
        word: Boolean,
    ) {
        assertEquals(word, isWordCodePoint(char.trim().ifEmpty { " " }.codePointAt(0)))
    }

    @Test
    fun `a letter beyond the BMP is a word code point, and each half of its pair counts as a word char`() {
        val x = "\uD835\uDC65" // MATHEMATICAL ITALIC SMALL X
        assertTrue(isWordCodePoint(x.codePointAt(0)))
        assertTrue(isWordChar(x[0]))
        assertTrue(isWordChar(x[1]))
        assertTrue(isWordChar('a'))
        assertTrue(!isWordChar('.'))
        // A part starts at the pair after a dot, but not between its halves.
        assertEquals(MatchKind.BOUNDARY, matchKind(x, "a.$x"))
        assertEquals(MatchKind.SUBSEQUENCE, matchKind(x[1].toString(), "a.$x"))
    }

    @Test
    fun `items whose preselect, sortText or label tie sort the same whatever order they come in`() {
        val items =
            listOf(
                item("zeta", preselect = true),
                item("beta"),
                item("alpha", preselect = true),
                item("gamma"),
                item("delta", sortText = "b"),
                item("eps", sortText = "b"),
            )
        for (order in permutations(items)) {
            // "delta" and "eps" have the sortText "b": before "beta" ("b" < "beta") in the sort key, and tie on it.
            assertEquals(listOf("alpha", "zeta", "delta", "eps", "beta", "gamma"), ranked("", *order.toTypedArray()))
        }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.indices.flatMap { i -> permutations(items - items[i]).map { listOf(items[i]) + it } }
        }

    @Test
    fun `an item without a label is left out`() {
        assertEquals(listOf("a"), rankCompletions(listOf(CompletionItem(), item("a")), "").map { it.label })
    }

    @Test
    fun `the filterText decides the match, not the label, and the label when there is none`() {
        assertEquals(listOf("x"), ranked("pri", item("print", filterText = "zzz"), item("x", filterText = "priv")))
        assertEquals(listOf("print"), ranked("pri", item("print"), item("x")))
    }

    private fun copies(
        base: () -> CompletionItem,
        change: (CompletionItem) -> Unit,
    ): List<CompletionItem> = listOf(base(), base().also(change))

    @ParameterizedTest
    @CsvSource(
        "label",
        "detail",
        "labelDetail",
        "labelDescription",
        "insertText",
        "textEditLeft",
        "textEditRight",
        "additionalEdit",
    )
    fun `an item that differs in what it shows or inserts is not left out`(difference: String) {
        fun base() =
            CompletionItem("a").apply {
                labelDetails =
                    CompletionItemLabelDetails().apply {
                        detail = " d"
                        description = "desc"
                    }
                detail = "det"
                additionalTextEdits = listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "import a\n"))
            }
        val edited = { item: CompletionItem ->
            when (difference) {
                "label" -> {
                    item.label = "b"
                }

                "detail" -> {
                    item.detail = "other"
                }

                "labelDetail" -> {
                    item.labelDetails.detail = " e"
                }

                "labelDescription" -> {
                    item.labelDetails.description = "other"
                }

                "insertText" -> {
                    item.insertText = "a()"
                }

                "textEditLeft" -> {
                    item.textEdit =
                        org.eclipse.lsp4j.jsonrpc.messages.Either
                            .forLeft(TextEdit(Range(), "a()"))
                }

                "textEditRight" -> {
                    item.textEdit =
                        org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                            org.eclipse.lsp4j.InsertReplaceEdit("a()", Range(), Range()),
                        )
                }

                else -> {
                    item.additionalTextEdits = listOf(TextEdit(Range(Position(1, 0), Position(1, 0)), "import b\n"))
                }
            }
        }
        // Equal in all else: the two texts of a textEdit differ from `base` having none, so both get the same first.
        val first = base().also { if (difference.startsWith("textEdit")) it.textEdit = null }
        val second = base().also(edited)
        assertEquals(2, rankCompletions(listOf(first, second), "").size, difference)
        assertEquals(1, rankCompletions(listOf(base(), base()), "").size, "equal in all")
    }

    @Test
    fun `items inserting the same text through insertText, the label or a textEdit are one`() {
        val viaLabel = CompletionItem("a")
        val viaInsertText = CompletionItem("a").apply { insertText = "a" }
        val viaLeft =
            CompletionItem("a").apply {
                textEdit =
                    org.eclipse.lsp4j.jsonrpc.messages.Either
                        .forLeft(TextEdit(Range(), "a"))
            }
        val viaRight =
            CompletionItem("a").apply {
                textEdit =
                    org.eclipse.lsp4j.jsonrpc.messages.Either
                        .forRight(org.eclipse.lsp4j.InsertReplaceEdit("a", Range(), Range()))
            }

        assertEquals(1, rankCompletions(listOf(viaLabel, viaInsertText), "").size)
        assertEquals(1, rankCompletions(listOf(viaLeft, viaLabel), "").size)
        assertEquals(1, rankCompletions(listOf(viaRight, viaLabel), "").size)
    }

    @Test
    fun `how many steps the part start match decides`() {
        // One part start tried and rejected at once; the other positions are not part starts.
        assertEquals(1, boundaryMatchSteps("b", "ab"))
        assertEquals(0, boundaryMatchSteps("", "abc"))
        assertEquals(2, boundaryMatchSteps("ac", "ab_cd"), "the match is found at the second part start")
    }

    @Test
    fun `the memoized part start match agrees with the plain one on many names`() {
        val random = java.util.Random(7)
        val alphabet = "aAb_.c"

        fun word(max: Int) = String(CharArray(random.nextInt(max) + 1) { alphabet[random.nextInt(alphabet.length)] })
        repeat(4000) {
            val prefix = word(5)
            val name = word(12)
            val expected =
                when {
                    name.startsWith(prefix, ignoreCase = true) -> MatchKind.PREFIX
                    plainBoundaryMatch(prefix, name) -> MatchKind.BOUNDARY
                    else -> null
                }
            val actual = matchKind(prefix, name)
            assertEquals(expected ?: actual.takeIf { it == MatchKind.SUBSEQUENCE }, actual, "$prefix in $name")
        }
    }

    /** The part start match without the memo: [prefix] as runs of part prefixes, each starting at a part start. */
    private fun plainBoundaryMatch(
        prefix: String,
        name: String,
    ): Boolean {
        fun start(i: Int): Boolean {
            if (i == 0) return true
            val before = name[i - 1]
            val at = name[i]
            if (at == '_') return false
            val word = { c: Char -> c == '_' || c.isLetterOrDigit() }
            return before == '_' || (at.isUpperCase() && !before.isUpperCase()) || (!word(before) && word(at))
        }

        fun from(
            p: Int,
            c: Int,
        ): Boolean {
            if (!start(c) || !prefix[p].equals(name[c], ignoreCase = true)) return false
            var run = 1
            while (p + run < prefix.length && c + run < name.length && !start(c + run) &&
                prefix[p + run].equals(name[c + run], ignoreCase = true)
            ) {
                run++
            }
            for (taken in run downTo 1) {
                if (p + taken == prefix.length) return true
                for (next in c + taken until name.length) if (start(next) && from(p + taken, next)) return true
            }
            return false
        }
        return name.indices.any { from(0, it) }
    }
}
