package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeTreeTest {
    private fun file(
        path: String,
        change: GitChange = GitChange.MODIFIED,
        oldPath: String? = null,
    ) = GitChangedFile(change, path, oldPath)

    /** The tree as indented lines, folders ending with `/`. */
    private fun lines(
        nodes: List<ChangeNode>,
        indent: String = "",
    ): List<String> =
        nodes.flatMap { node ->
            when (node) {
                is ChangeFolder -> listOf("$indent${node.name}/") + lines(node.children, "$indent  ")
                is ChangeFile -> listOf("$indent${node.name}")
            }
        }

    @Test
    fun `lists folders first, then files, each by name`() {
        val tree = changeTree(listOf(file("b.txt"), file("z/x.txt"), file("A.txt"), file("a/y.txt"), file("a/b/c.txt")))

        assertEquals(
            listOf("a/", "  b/", "    c.txt", "  y.txt", "z/", "  x.txt", "A.txt", "b.txt"),
            lines(tree),
        )
    }

    @Test
    fun `compacts chains of folders that hold only one folder each`() {
        val tree =
            changeTree(
                listOf(
                    file("src/main/kotlin/dev/One.kt"),
                    file("src/main/kotlin/dev/sub/Two.kt"),
                    file("src/test/kotlin/OneTest.kt"),
                    file("docs/adr/0038.md"),
                ),
            )

        assertEquals(
            listOf(
                "docs/adr/",
                "  0038.md",
                "src/",
                "  main/kotlin/dev/",
                "    sub/",
                "      Two.kt",
                "    One.kt",
                "  test/kotlin/",
                "    OneTest.kt",
            ),
            lines(tree),
        )
        val src = tree[1] as ChangeFolder
        assertEquals(listOf("docs/adr", "src"), tree.filterIsInstance<ChangeFolder>().map { it.path })
        assertEquals(
            listOf("src/main/kotlin/dev", "src/test/kotlin"),
            src.children.filterIsInstance<ChangeFolder>().map { it.path },
        )
        assertEquals("src/main/kotlin/dev/sub", ((src.children[0] as ChangeFolder).children[0] as ChangeFolder).path)
    }

    @Test
    fun `a renamed file is placed under its new path and keeps its change`() {
        val renamed = file("new/place/Name.kt", GitChange.RENAMED, "old/Name.kt")

        val tree = changeTree(listOf(renamed, file("old/Other.kt", GitChange.DELETED)))

        assertEquals(listOf("new/place/", "  Name.kt", "old/", "  Other.kt"), lines(tree))
        assertEquals(renamed, ((tree.first() as ChangeFolder).children.single() as ChangeFile).file)
    }

    @Test
    fun `no files, no nodes`() {
        assertEquals(emptyList(), changeTree(emptyList()))
    }
}
