package dev.sluide.codereview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewTreeTest {
    private fun file(path: String) = ReviewFile(ReviewChange.MODIFIED, path)

    /** The tree as indented lines, folders marked with a slash at the end. */
    private fun render(
        nodes: List<ReviewTreeNode>,
        indent: String = "",
    ): List<String> =
        nodes.flatMap { node ->
            listOf(indent + node.name + if (node.file == null) "/" else "") + render(node.children, "$indent  ")
        }

    @Test
    fun `folders come before files on each level, by name ignoring case`() {
        val files = listOf("b.txt", "A.txt", "z/1.txt", "Docs/x.md", "docs2/y.md", "a.txt").map(::file)

        assertEquals(
            listOf("Docs/", "  x.md", "docs2/", "  y.md", "z/", "  1.txt", "A.txt", "a.txt", "b.txt"),
            render(reviewTree(files)),
        )
    }

    @Test
    fun `names that differ only in case keep a stable order`() {
        val one = render(reviewTree(listOf("a.txt", "A.txt").map(::file)))
        val other = render(reviewTree(listOf("A.txt", "a.txt").map(::file)))

        assertEquals(listOf("A.txt", "a.txt"), one)
        assertEquals(one, other)
    }

    @Test
    fun `single-child folder chains are compacted`() {
        val files =
            listOf("src/main/kotlin/App.kt", "src/main/kotlin/Util.kt", "src/test/kotlin/AppTest.kt", "README.md")
                .map(::file)

        val tree = reviewTree(files)

        assertEquals(
            listOf(
                "src/",
                "  main/kotlin/",
                "    App.kt",
                "    Util.kt",
                "  test/kotlin/",
                "    AppTest.kt",
                "README.md",
            ),
            render(tree),
        )
        val main = tree[0].children[0]
        assertEquals("src/main/kotlin", main.path)
        assertNull(main.file)
        assertEquals("src/main/kotlin/App.kt", main.children[0].path)
    }

    @Test
    fun `a folder with a file and a folder is not compacted`() {
        val files = listOf("a/b/c.txt", "a/d.txt").map(::file)

        assertEquals(listOf("a/", "  b/", "    c.txt", "  d.txt"), render(reviewTree(files)))
    }

    @Test
    fun `the filter keeps the matching files and the folders leading to them, compacted anew`() {
        val files = listOf("src/main/App.kt", "src/test/AppTest.kt", "src/main/Other.kt", "build.gradle").map(::file)

        assertEquals(listOf("src/test/", "  AppTest.kt"), render(reviewTree(files, "apptest")))
        assertEquals(
            listOf("src/", "  main/", "    App.kt", "  test/", "    AppTest.kt"),
            render(reviewTree(files, "APP")),
        )
    }

    @Test
    fun `the filter matches anywhere in the path, ignoring case`() {
        assertEquals(true, matchesReviewFilter("src/Main/App.kt", "main/a"))
        assertEquals(false, matchesReviewFilter("src/Main/App.kt", "test"))
        assertEquals(true, matchesReviewFilter("src/Main/App.kt", ""))
    }

    @Test
    fun `tree order lists the files depth first`() {
        val files = listOf("z.txt", "b/2.txt", "a/x/1.txt", "a/0.txt").map(::file)

        assertEquals(listOf("a/x/1.txt", "a/0.txt", "b/2.txt", "z.txt"), treeOrder(files).map { it.path })
    }
}
