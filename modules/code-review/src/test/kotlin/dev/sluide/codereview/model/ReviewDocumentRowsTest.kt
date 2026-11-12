package dev.sluide.codereview.model

import dev.sluide.codereview.ReviewFileContent
import dev.sluide.fileviewers.LineChange
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The line and gap rows of text files: both layouts, context, gaps, hunk headers and expansion. */
class ReviewDocumentRowsTest {
    private val modifiedBlock = textDiff(20, 21, LineChange(5..6, 5..7))

    @Test
    fun `unified shows a modified block as its original lines, then its current lines, with context around`() {
        val document = document("a.kt" to modifiedBlock, layout = ReviewLayout.UNIFIED)

        assertEquals(
            listOf(
                "H a.kt +3 -2",
                "G 0 2 ↑ @@ -3,8 +3,9 @@",
                "U 2 2",
                "U 3 3",
                "U 4 4",
                "U 5 . M",
                "U 6 . M",
                "U . 5 M",
                "U . 6 M",
                "U . 7 M",
                "U 7 8",
                "U 8 9",
                "U 9 10",
                "G 1 10 ↓",
                "-",
            ),
            document.shortRows(),
        )
    }

    @Test
    fun `split pairs a modified block's lines in order, the shorter side filled`() {
        val document = document("a.kt" to modifiedBlock, layout = ReviewLayout.SPLIT)

        assertEquals(
            listOf(
                "G 0 2 ↑ @@ -3,8 +3,9 @@",
                "S 2 2",
                "S 3 3",
                "S 4 4",
                "S 5 5 M",
                "S 6 6 M",
                "S . 7 M",
                "S 7 8",
                "S 8 9",
                "S 9 10",
                "G 1 10 ↓",
            ),
            document.bodyOf("a.kt"),
        )
    }

    @Test
    fun `removed and added lines have one side only, in both layouts`() {
        val content = textDiff(12, 11, LineChange(2..3, 2 until 2), LineChange(8 until 8, 6..6))

        assertEquals(
            listOf(
                "U 0 0",
                "U 1 1",
                "U 2 . R",
                "U 3 . R",
                "U 4 2",
                "U 5 3",
                "U 6 4",
                "U 7 5",
                "U . 6 A",
                "U 8 7",
                "U 9 8",
                "U 10 9",
                "G 2 1 ↓",
            ),
            document("a.kt" to content, layout = ReviewLayout.UNIFIED).bodyOf("a.kt"),
        )
        assertEquals(
            listOf("S 2 . R", "S 3 . R", "S . 6 A"),
            document("a.kt" to content).bodyOf("a.kt").filter { it.endsWith(" R") || it.endsWith(" A") },
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 5, 6])
    fun `a gap of up to 6 lines between two changes shows whole`(length: Int) {
        val content =
            textDiff(
                length + 2,
                length + 2,
                LineChange(0..0, 0..0),
                LineChange(
                    length + 1..length + 1,
                    length + 1..length + 1,
                ),
            )

        val body = document("a.kt" to content).bodyOf("a.kt")

        assertEquals(listOf("S 0 0 M") + (1..length).map { "S $it $it" } + "S ${length + 1} ${length + 1} M", body)
    }

    @Test
    fun `a gap of 7 lines between two changes hides 1`() {
        val content = textDiff(9, 9, LineChange(0..0, 0..0), LineChange(8..8, 8..8))

        assertEquals(
            listOf(
                "S 0 0 M",
                "S 1 1",
                "S 2 2",
                "S 3 3",
                "G 1 1 ↑↓ @@ -6,4 +6,4 @@",
                "S 5 5",
                "S 6 6",
                "S 7 7",
                "S 8 8 M",
            ),
            document("a.kt" to content).bodyOf("a.kt"),
        )
    }

    @Test
    fun `the first gap expands only up, the last only down, 20 lines a click, or all at once`() {
        val document = document("a.kt" to oneModifiedLine(100, 50))
        val change = listOf("S 47 47", "S 48 48", "S 49 49", "S 50 50 M", "S 51 51", "S 52 52", "S 53 53")
        assertEquals(listOf("G 0 47 ↑ @@ -48,7 +48,7 @@") + change + "G 1 46 ↓", document.bodyOf("a.kt"))

        document.expand("a.kt", 0, ExpandDirection.UP)
        document.expand("a.kt", 1, ExpandDirection.DOWN)

        assertEquals(
            listOf("G 0 27 ↑ @@ -28,47 +28,47 @@") + (27..46).map { "S $it $it" } + change +
                (54..73).map { "S $it $it" } + "G 1 26 ↓",
            document.bodyOf("a.kt"),
        )

        document.expand("a.kt", 0, ExpandDirection.ALL)

        assertEquals((0..46).map { "S $it $it" }, document.bodyOf("a.kt").take(47))
        assertEquals(1, document.bodyOf("a.kt").count { it.startsWith("G") })
    }

    @Test
    fun `a gap between changes expands from both ends until no line stays hidden`() {
        val document = document("a.kt" to textDiff(100, 100, LineChange(10..10, 10..10), LineChange(60..60, 60..60)))

        fun gaps() = document.bodyOf("a.kt").filter { it.startsWith("G") }
        assertEquals(listOf("G 0 7 ↑ @@ -8,7 +8,7 @@", "G 1 43 ↑↓ @@ -58,7 +58,7 @@", "G 2 36 ↓"), gaps())

        document.expand("a.kt", 1, ExpandDirection.DOWN)
        assertEquals("G 1 23 ↑↓ @@ -58,7 +58,7 @@", gaps()[1])

        document.expand("a.kt", 1, ExpandDirection.UP)
        assertEquals("G 1 3 ↑↓ @@ -38,27 +38,27 @@", gaps()[1])

        document.expand("a.kt", 1, ExpandDirection.UP)
        assertEquals(listOf("G 0 7 ↑ @@ -8,57 +8,57 @@", "G 2 36 ↓"), gaps())
        assertEquals(
            (7..63).map {
                if (it == 10 ||
                    it == 60
                ) {
                    "S $it $it M"
                } else {
                    "S $it $it"
                }
            },
            document.bodyOf("a.kt").drop(1).take(57),
        )
    }

    @Test
    fun `the same content again keeps the revealed lines`() {
        val document = document("a.kt" to oneModifiedLine(100, 50))
        document.expand("a.kt", 0, ExpandDirection.ALL)

        document.load("a.kt", oneModifiedLine(100, 50))

        assertEquals("S 0 0", document.bodyOf("a.kt").first())
    }

    @Test
    fun `changed content forgets the revealed lines`() {
        val document = document("a.kt" to oneModifiedLine(100, 50))
        document.expand("a.kt", 0, ExpandDirection.ALL)

        document.load("a.kt", oneModifiedLine(100, 51))

        assertEquals("G 0 48 ↑ @@ -49,7 +49,7 @@", document.bodyOf("a.kt").first())
    }

    @Test
    fun `revealed lines stay when the layout changes`() {
        val document = document("a.kt" to oneModifiedLine(100, 50))
        document.expand("a.kt", 0, ExpandDirection.UP)

        document.layout.set(ReviewLayout.UNIFIED)

        assertEquals("G 0 27 ↑ @@ -28,27 +28,27 @@", document.bodyOf("a.kt").first())
        assertEquals("U 27 27", document.bodyOf("a.kt")[1])
    }

    @Test
    fun `change ranges beyond the line counts are cut off`() {
        val content = ReviewFileContent.Text("a\nb\n", "a\nc\nd\n", listOf(LineChange(1..5, 1..9)))

        assertEquals(listOf("S 0 0", "S 1 1 M", "S . 2 M"), document("a.kt" to content).bodyOf("a.kt"))
    }
}
