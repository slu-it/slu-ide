package dev.sluide.codereview.model

import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.fileviewers.LineChange
import javafx.collections.ListChangeListener
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The document's files and their state: order, notices, collapse, Viewed, Load diff, filter, anchors and updates. */
class ReviewDocumentTest {
    @Test
    fun `files are shown in tree order, loading until their content is there`() {
        val document = ReviewDocument()

        document.setFiles(listOf(modified("b.txt"), modified("a/x.txt")))

        assertEquals(listOf("a/x.txt", "b.txt"), document.files.map { it.path })
        assertEquals(
            listOf("H a/x.txt +0 -0", "N LOADING", "-", "H b.txt +0 -0", "N LOADING", "-"),
            document.shortRows(),
        )
    }

    @Test
    fun `new files keep the state of the paths still there and drop the others`() {
        val document = document("a.kt" to oneModifiedLine(10, 5), "b.kt" to oneModifiedLine(10, 5))
        document.setViewed("a.kt", true)
        document.setViewed("b.kt", true)

        document.setFiles(listOf(ReviewFile(ReviewChange.RENAMED, "a.kt", "old.kt"), modified("c.kt")))
        document.setFiles(listOf(modified("a.kt"), modified("b.kt"), modified("c.kt")))

        assertEquals(
            listOf(
                "H a.kt collapsed viewed +1 -1",
                "-",
                "H b.kt +0 -0",
                "N LOADING",
                "-",
                "H c.kt +0 -0",
                "N LOADING",
                "-",
            ),
            document.shortRows(),
        )
        assertEquals(1, document.viewedCount.get())
    }

    @Test
    fun `a file keeps its new change and old path`() {
        val document = document("a.kt" to oneModifiedLine(10, 5))
        val renamed = ReviewFile(ReviewChange.RENAMED, "a.kt", "old.kt")

        document.setFiles(listOf(renamed))

        assertEquals(renamed, (document.rows[0] as ReviewRow.FileHeader).file)
        assertEquals(listOf(renamed), document.files)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notices")
    fun `files without lines to show have a notice`(
        name: String,
        file: ReviewFile,
        content: ReviewFileContent,
        ignoreWhitespace: Boolean,
        notice: String,
    ) {
        val document = ReviewDocument()
        document.setFiles(listOf(file))

        document.setContent(file.path, prepareFile(file, content, null, ignoreWhitespace))

        assertEquals(listOf(notice), document.bodyOf(file.path))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "package-lock.json", "web/YARN.LOCK", "pnpm-lock.yaml", "gradle.lockfile", "rust/Cargo.lock",
            "poetry.lock", "go.sum", "dist/app.min.js", "site.MIN.css",
        ],
    )
    fun `lock and minified files start behind Load diff`(path: String) {
        val document = document(path to oneModifiedLine(10, 5))

        assertEquals(listOf("N LOAD_DIFF 2"), document.bodyOf(path))
    }

    @ParameterizedTest
    @ValueSource(strings = ["lock.json", "src/go.summary", "app.js", "yarn.lock/readme.md"])
    fun `other files show their lines`(path: String) {
        val document = document(path to oneModifiedLine(10, 5))

        assertEquals("S 5 5 M", document.bodyOf(path)[4])
    }

    @Test
    fun `files with over 500 changed lines start behind Load diff`() {
        val document =
            document(
                "big.kt" to textDiff(300, 300, LineChange(0..250, 0..249)),
                "limit.kt" to textDiff(300, 300, LineChange(0..249, 0..249)),
            )

        assertEquals(listOf("N LOAD_DIFF 501"), document.bodyOf("big.kt"))
        assertEquals("S 0 0 M", document.bodyOf("limit.kt").first())
    }

    @Test
    fun `Load diff shows the lines, also after new content`() {
        val document = document("go.sum" to oneModifiedLine(10, 5))

        document.loadDiff("go.sum")
        document.load("go.sum", oneModifiedLine(10, 6))

        assertEquals("S 6 6 M", document.bodyOf("go.sum")[4])
    }

    @Test
    fun `Viewed collapses the file and counts it, unchecking expands it`() {
        val document = document("a.kt" to oneModifiedLine(10, 5), "b.kt" to oneModifiedLine(10, 5))

        document.setViewed("a.kt", true)

        assertEquals(listOf("H a.kt collapsed viewed +1 -1", "-"), document.shortRows().take(2))
        assertEquals(1, document.viewedCount.get())

        document.setViewed("a.kt", false)

        assertEquals("H a.kt +1 -1", document.shortRows()[0])
        assertEquals("S 5 5 M", document.bodyOf("a.kt")[4])
        assertEquals(0, document.viewedCount.get())
    }

    @Test
    fun `collapsing leaves Viewed alone`() {
        val document = document("a.kt" to oneModifiedLine(10, 5))
        document.setViewed("a.kt", true)

        document.setCollapsed("a.kt", false)

        assertEquals("H a.kt viewed +1 -1", document.shortRows()[0])
        assertEquals("S 5 5 M", document.bodyOf("a.kt")[4])

        document.setCollapsed("a.kt", true)

        assertEquals(listOf("H a.kt collapsed viewed +1 -1", "-"), document.shortRows())
    }

    @Test
    fun `the viewed count includes files the filter leaves out`() {
        val document = document("a.kt" to oneModifiedLine(10, 5), "b.kt" to oneModifiedLine(10, 5))
        document.setViewed("a.kt", true)

        document.filter.set("b")

        assertEquals(1, document.viewedCount.get())
        document.setViewed("a.kt", false)
        assertEquals(0, document.viewedCount.get())
    }

    @Test
    fun `a viewed file stays viewed when its content comes again unchanged`() {
        val document = document("a.kt" to oneModifiedLine(10, 5))
        document.setViewed("a.kt", true)

        document.load("a.kt", oneModifiedLine(10, 5), ignoreWhitespace = true)

        assertEquals("H a.kt collapsed viewed +1 -1", document.shortRows()[0])
    }

    @Test
    fun `a viewed file whose content changed is unchecked and expanded`() {
        val document = document("a.kt" to oneModifiedLine(10, 5))
        document.setViewed("a.kt", true)

        document.load("a.kt", oneModifiedLine(10, 6))

        assertEquals("H a.kt +1 -1", document.shortRows()[0])
        assertEquals("S 6 6 M", document.bodyOf("a.kt")[4])
        assertEquals(0, document.viewedCount.get())
    }

    @Test
    fun `a file marked viewed while loading takes its first content as viewed`() {
        val document = ReviewDocument()
        document.setFiles(listOf(modified("a.kt")))
        document.setViewed("a.kt", true)

        document.load("a.kt", oneModifiedLine(10, 5))
        assertEquals("H a.kt collapsed viewed +1 -1", document.shortRows()[0])

        document.load("a.kt", oneModifiedLine(10, 6))
        assertEquals("H a.kt +1 -1", document.shortRows()[0])
    }

    @Test
    fun `the filter leaves out the files whose path does not contain it, ignoring case`() {
        val document =
            document(
                "src/App.kt" to oneModifiedLine(10, 5),
                "docs/app.md" to oneModifiedLine(10, 5),
                "x.kt" to oneModifiedLine(10, 5),
            )

        document.filter.set("APP")

        assertEquals(
            listOf("H docs/app.md +1 -1", "H src/App.kt +1 -1"),
            document.shortRows().filter { it.startsWith("H") },
        )
        assertEquals(-1, document.headerRow("x.kt"))
        assertEquals(document.rows.indexOfFirst { it.path == "src/App.kt" }, document.headerRow("src/App.kt"))

        document.filter.set("")

        assertEquals(3, document.shortRows().count { it.startsWith("H") })
    }

    @Test
    fun `hiding whitespace drops the contents and the revealed lines until new content comes`() {
        val document = document("a.kt" to oneModifiedLine(100, 50))
        document.expand("a.kt", 0, ExpandDirection.ALL)

        document.ignoreWhitespace.set(true)

        assertEquals(listOf("H a.kt +0 -0", "N LOADING", "-"), document.shortRows())

        document.load("a.kt", oneModifiedLine(100, 50), ignoreWhitespace = true)

        assertEquals("G 0 47 ↑ @@ -48,7 +48,7 @@", document.bodyOf("a.kt").first())
    }

    @Test
    fun `a line row is anchored at its current line, or its original line when it has none`() {
        val document = document("a.kt" to textDiff(10, 9, LineChange(5..5, 5 until 5)), layout = ReviewLayout.UNIFIED)
        val rows = document.shortRows()

        assertEquals(ReviewAnchor("a.kt", ReviewSide.CURRENT, 4), document.anchorAt(rows.indexOf("U 4 4")))
        assertEquals(ReviewAnchor("a.kt", ReviewSide.ORIGINAL, 5), document.anchorAt(rows.indexOf("U 5 . R")))
        assertEquals(ReviewAnchor("a.kt", null, null), document.anchorAt(0))
        assertEquals(ReviewAnchor("a.kt", null, null), document.anchorAt(rows.indexOf("G 0 2 ↑ @@ -3,7 +3,6 @@")))
        assertEquals(ReviewAnchor("a.kt", null, null), document.anchorAt(rows.lastIndex))
    }

    @Test
    fun `an anchor finds its line row in either layout, else its file's header`() {
        val document =
            document(
                "a.kt" to oneModifiedLine(10, 1),
                "b.kt" to textDiff(100, 100, LineChange(50..50, 50..50)),
            )
        val line = ReviewAnchor("b.kt", ReviewSide.ORIGINAL, 50)

        assertEquals("S 50 50 M", document.rows[document.rowOf(line)].short())

        document.layout.set(ReviewLayout.UNIFIED)

        assertEquals("U 50 . M", document.rows[document.rowOf(line)].short())
        assertEquals("U . 50 M", document.rows[document.rowOf(line.copy(side = ReviewSide.CURRENT))].short())
        val header = document.headerRow("b.kt")
        assertEquals(header, document.rowOf(ReviewAnchor("b.kt", ReviewSide.CURRENT, 10)))
        assertEquals(header, document.rowOf(ReviewAnchor("b.kt", null, null)))

        document.setCollapsed("b.kt", true)
        assertEquals(header, document.rowOf(line))

        document.filter.set("a.kt")
        assertEquals(-1, document.rowOf(line))
    }

    @Test
    fun `a line has its text, tokens and changed words`() {
        val document =
            document("a.kt" to ReviewFileContent.Text("val x = 1\n", "val x = 2\n", listOf(LineChange(0..0, 0..0))))

        assertEquals(ReviewLine("val x = 1", emptyList(), listOf(8..8)), document.line("a.kt", ReviewSide.ORIGINAL, 0))
        assertEquals(ReviewLine("val x = 2", emptyList(), listOf(8..8)), document.line("a.kt", ReviewSide.CURRENT, 0))
        assertEquals(ReviewLine("", emptyList(), emptyList()), document.line("a.kt", ReviewSide.CURRENT, 1))
        assertEquals(ReviewLine("", emptyList(), emptyList()), document.line("b.kt", ReviewSide.CURRENT, 0))
    }

    @Test
    fun `a context line has no changed words`() {
        val document = document("a.kt" to oneModifiedLine(10, 5))

        assertEquals(ReviewLine("o4", emptyList(), emptyList()), document.line("a.kt", ReviewSide.CURRENT, 4))
    }

    @Test
    fun `updates of one file change only that file's rows, in one change each`() {
        val document =
            document(
                "a.kt" to oneModifiedLine(100, 50),
                "b.kt" to oneModifiedLine(100, 50),
                "c.kt" to oneModifiedLine(100, 50),
            )
        val updates =
            listOf<(ReviewDocument) -> Unit>(
                { it.expand("b.kt", 0, ExpandDirection.UP) },
                { it.expand("b.kt", 1, ExpandDirection.ALL) },
                { it.setViewed("b.kt", true) },
                { it.setViewed("b.kt", false) },
                { it.setCollapsed("b.kt", true) },
                { it.setCollapsed("b.kt", false) },
                { it.loadDiff("b.kt") },
                { it.load("b.kt", oneModifiedLine(100, 20)) },
            )
        for (update in updates) {
            val before = document.rows.toList()
            val bStart = document.headerRow("b.kt")
            val bEnd = document.headerRow("c.kt")
            val changes = mutableListOf<String>()
            val listener =
                ListChangeListener<ReviewRow> { change ->
                    while (change.next()) changes += "${change.from} ${change.removedSize} ${change.addedSize}"
                }
            document.rows.addListener(listener)

            update(document)

            document.rows.removeListener(listener)
            val after = document.rows.toList()
            val newEnd = document.headerRow("c.kt")
            assertEquals(listOf("$bStart ${bEnd - bStart} ${newEnd - bStart}"), changes)
            assertEquals(before.subList(0, bStart), after.subList(0, bStart))
            assertEquals(before.subList(bEnd, before.size), after.subList(newEnd, after.size))
            assertTrue(after.subList(bStart, newEnd).all { it.path == "b.kt" })
        }
    }

    companion object {
        @JvmStatic
        fun notices(): List<Arguments> {
            val modified = modified("a.kt")
            val unchanged = ReviewFileContent.Text("a\n", "a\n", emptyList())
            // What git gives with -w for a file whose only change is whitespace.
            val whitespace = ReviewFileContent.Text("a b\n", "a  b \n", emptyList())
            val renamed = ReviewFile(ReviewChange.RENAMED, "a.kt", "b.kt")
            return listOf(
                Arguments.of("binary", modified, ReviewFileContent.Binary, false, "N BINARY"),
                Arguments.of("too large", modified, ReviewFileContent.TooLarge, false, "N TOO_LARGE"),
                Arguments.of(
                    "submodule",
                    modified,
                    ReviewFileContent.Submodule("abc1234567890", null),
                    false,
                    "N SUBMODULE abc1234 → none",
                ),
                Arguments.of("unchanged", modified, unchanged, false, "N NO_CONTENT_CHANGE No content changes"),
                Arguments.of(
                    "mode change, whitespace hidden",
                    ReviewFile(ReviewChange.TYPE_CHANGED, "a.kt"),
                    unchanged,
                    true,
                    "N NO_CONTENT_CHANGE No content changes",
                ),
                Arguments.of("whitespace", modified, whitespace, true, "N NO_CONTENT_CHANGE Only whitespace changes"),
                Arguments.of("renamed", renamed, unchanged, true, "N NO_CONTENT_CHANGE Renamed without changes"),
                Arguments.of(
                    "renamed, whitespace",
                    renamed,
                    whitespace,
                    true,
                    "N NO_CONTENT_CHANGE Only whitespace changes",
                ),
                Arguments.of(
                    "copied",
                    ReviewFile(ReviewChange.COPIED, "a.kt", "b.kt"),
                    unchanged,
                    false,
                    "N NO_CONTENT_CHANGE Copied without changes",
                ),
            )
        }
    }
}
