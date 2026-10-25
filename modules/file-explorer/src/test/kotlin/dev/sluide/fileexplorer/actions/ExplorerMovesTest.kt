package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.tree.FileTreeItem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Moving entries into another folder, as a drop does, on a [FakeEditHost]: no display needed. */
class ExplorerMovesTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var scratches: Path

    private lateinit var fake: FakeEditHost

    private fun open(): FakeEditHost = FakeEditHost(project, listOf(scratches)).also { fake = it }

    private fun move(
        items: List<FileTreeItem>,
        folder: FileTreeItem,
    ) = fake.moves.move(items, folder)

    @Test
    fun `moves the entries after one question, reports each move and selects them in the folder`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("src/main").createDirectories()
        project.resolve("src/main/inner.txt").writeText("inner")
        project.resolve("target").createDirectories()
        open()
        fake.item("src").isExpanded = true

        move(listOf(fake.item("a.txt"), fake.item("src")), fake.item("target"))

        val target = project.resolve("target")
        assertEquals(listOf(moveQuestion(listOf("a.txt", "src"), emptyList(), "target")), fake.moveQuestions)
        assertEquals(
            listOf(
                project.resolve("a.txt") to target.resolve("a.txt"),
                project.resolve("src") to target.resolve("src"),
            ),
            fake.moved,
        )
        assertEquals("a", target.resolve("a.txt").readText())
        assertEquals("inner", target.resolve("src/main/inner.txt").readText())
        assertFalse(project.resolve("src").exists())
        assertTrue(fake.item("target").isExpanded)
        assertTrue(fake.item("target/src").isExpanded, "a moved folder stays expanded")
        assertEquals(listOf("target"), fake.projectItem.children.map { it.value.fileName.toString() })
        // Folders first, as listed.
        assertEquals(listOf(target.resolve("src"), target.resolve("a.txt")), fake.selectedPaths())
        assertEquals(emptyList(), fake.errors)
        assertEquals(listOf(project.resolve("a.txt"), project.resolve("src")), fake.closedUnder, "watches end")
    }

    @Test
    fun `entries whose names exist in the folder are skipped after saying so, never replaced`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("mine")
        project.resolve("target").createDirectories()
        project.resolve("target/b.txt").writeText("theirs")
        open()

        move(listOf(fake.item("a.txt"), fake.item("b.txt")), fake.item("target"))

        val question = fake.moveQuestions.single()
        val root = project.fileName.toString()
        assertEquals(moveQuestion(listOf("a.txt"), listOf("b.txt"), "target", root, "$root/target"), question)
        assertTrue(question.skips)
        assertEquals(listOf(project.resolve("a.txt") to project.resolve("target/a.txt")), fake.moved)
        assertEquals("mine", project.resolve("b.txt").readText())
        assertEquals("theirs", project.resolve("target/b.txt").readText())
        assertEquals(listOf(project.resolve("target/a.txt")), fake.selectedPaths())
    }

    @Test
    fun `when every name exists in the folder an error is shown and nothing moves`() {
        project.resolve("a.txt").writeText("mine")
        project.resolve("b.txt").createFile()
        project.resolve("target").createDirectories()
        project.resolve("target/a.txt").writeText("theirs")
        project.resolve("target/b.txt").createFile()
        open()

        move(listOf(fake.item("a.txt")), fake.item("target"))
        fake.selected = emptyList()
        move(listOf(fake.item("a.txt"), fake.item("b.txt")), fake.item("target"))

        assertEquals(
            listOf(
                "“a.txt” already exists in “target”.",
                "None of the 2 entries can be moved: their names already exist in “target”.",
            ),
            fake.errors,
        )
        assertEquals(emptyList(), fake.moveQuestions)
        assertEquals(emptyList(), fake.moved)
        assertEquals("mine", project.resolve("a.txt").readText())
        assertEquals("theirs", project.resolve("target/a.txt").readText())
        // A drag may have lost the selection; the entries are selected again.
        assertEquals(listOf(project.resolve("a.txt"), project.resolve("b.txt")), fake.selectedPaths())
    }

    @Test
    fun `Cancel moves nothing and selects the entries again, nested ones too`() {
        project.resolve("a.txt").createFile()
        project.resolve("src").createDirectories()
        project.resolve("src/b.txt").createFile()
        project.resolve("target").createDirectories()
        open()
        fake.answerMove = { false }
        fake.item("src").isExpanded = true

        move(listOf(fake.item("src"), fake.item("src/b.txt"), fake.item("a.txt")), fake.item("target"))

        assertEquals(1, fake.moveQuestions.size)
        assertEquals(emptyList(), fake.moved)
        assertTrue(project.resolve("a.txt").exists())
        assertFalse(project.resolve("target/a.txt").exists())
        assertEquals(
            listOf(project.resolve("src"), project.resolve("src/b.txt"), project.resolve("a.txt")),
            fake.selectedPaths(),
        )
    }

    @Test
    fun `entries move into the extra root and out of it`() {
        project.resolve("a.txt").createFile()
        scratches.resolve("notes").createDirectories()
        scratches.resolve("notes/s.md").createFile()
        open()
        val extra = fake.extraItems.single()

        move(listOf(fake.item("a.txt")), extra)

        assertEquals("Move “a.txt” to “${scratches.fileName}”?", fake.moveQuestions.single().header)
        assertEquals(
            "From “${project.fileName}” to “${scratches.fileName}”.",
            fake.moveQuestions.single().details,
        )
        assertEquals(listOf(project.resolve("a.txt") to scratches.resolve("a.txt")), fake.moved)
        assertTrue(extra.isExpanded)
        assertEquals(listOf(scratches.resolve("a.txt")), fake.selectedPaths())

        move(listOf(fake.item("notes/s.md", extra)), fake.projectItem)

        assertEquals(scratches.resolve("notes/s.md") to project.resolve("s.md"), fake.moved.last())
        assertEquals(
            "From “${scratches.fileName}/notes” to “${project.fileName}”.",
            fake.moveQuestions.last().details,
        )
        assertTrue(project.resolve("s.md").exists())
        assertEquals(listOf(project.resolve("s.md")), fake.selectedPaths())
    }

    @Test
    fun `a failure stops the moves after it and is shown, the earlier ones stay`() {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        project.resolve("c.txt").createFile()
        project.resolve("target").createDirectories()
        open()
        fake.answerMove = {
            project.resolve("b.txt").deleteExisting()
            true
        }

        move(listOf(fake.item("a.txt"), fake.item("b.txt"), fake.item("c.txt")), fake.item("target"))

        assertEquals(listOf(project.resolve("a.txt") to project.resolve("target/a.txt")), fake.moved)
        assertEquals(listOf("Could not move “b.txt”: ${project.resolve("b.txt")} does not exist."), fake.errors)
        assertTrue(project.resolve("c.txt").exists())
        assertEquals(
            listOf("c.txt", "target"),
            fake.projectItem.children
                .map { it.value.fileName.toString() }
                .sorted(),
        )
        assertEquals(listOf(project.resolve("target/a.txt")), fake.selectedPaths())
    }

    @Test
    fun `a name taken while asking stops with an error and replaces nothing`() {
        project.resolve("a.txt").writeText("mine")
        project.resolve("target").createDirectories()
        open()
        fake.answerMove = {
            project.resolve("target/a.txt").writeText("theirs")
            true
        }

        move(listOf(fake.item("a.txt")), fake.item("target"))

        assertEquals(emptyList(), fake.moved)
        assertEquals(listOf("“a.txt” already exists in “target”."), fake.errors)
        assertEquals("mine", project.resolve("a.txt").readText())
        assertEquals("theirs", project.resolve("target/a.txt").readText())
    }

    @Test
    fun `an entry inside a moved folder goes along with it, not on its own`() {
        project.resolve("src").createDirectories()
        project.resolve("src/inner.txt").createFile()
        project.resolve("target").createDirectories()
        open()

        move(listOf(fake.item("src/inner.txt"), fake.item("src")), fake.item("target"))

        assertEquals("Move “src” to “target”?", fake.moveQuestions.single().header)
        assertEquals(listOf(project.resolve("src") to project.resolve("target/src")), fake.moved)
        assertTrue(project.resolve("target/src/inner.txt").exists())
    }

    @Test
    fun `nothing happens for a folder inside a moved one, the folder the entries are in, or a top-level entry`() {
        project.resolve("a.txt").createFile()
        project.resolve("src/deep").createDirectories()
        open()

        move(listOf(fake.item("src")), fake.item("src"))
        move(listOf(fake.item("src")), fake.item("src/deep"))
        move(listOf(fake.item("a.txt")), fake.projectItem)
        move(listOf(fake.extraItems.single()), fake.projectItem)
        move(listOf(fake.item("src")), fake.item("a.txt"))

        assertEquals(emptyList(), fake.moveQuestions)
        assertEquals(emptyList(), fake.moved)
        assertEquals(emptyList(), fake.errors)
        assertTrue(Files.isDirectory(project.resolve("src/deep")))
    }

    @Test
    fun `while an entry is deleted nothing moves into it or its folders, and its folders do not move`() {
        project.resolve("a/b/c").createDirectories()
        project.resolve("x.txt").createFile()
        project.resolve("target").createDirectories()
        open()
        fake.item("a").isExpanded = true
        fake.item("a/b").isExpanded = true
        fake.inline = false
        fake.edits.delete(listOf(fake.item("a/b")))
        fake.deleteQuestions.clear()

        move(listOf(fake.item("x.txt")), fake.item("a/b"))
        move(listOf(fake.item("x.txt")), fake.item("a/b/c"))
        move(listOf(fake.item("a")), fake.item("target"))

        assertEquals(emptyList(), fake.moveQuestions)
        assertEquals(emptyList(), fake.moved)
        assertEquals(emptyList(), fake.errors)
    }

    @Test
    fun `a delete started while asking stops the move with an error`() {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open()
        fake.answerMove = {
            fake.inline = false
            fake.edits.delete(listOf(fake.item("target")))
            true
        }

        move(listOf(fake.item("a.txt")), fake.item("target"))

        assertEquals(1, fake.moveQuestions.size)
        assertEquals(emptyList(), fake.moved)
        assertEquals(
            listOf("Nothing was moved: “target” or one of the entries is being deleted or gone."),
            fake.errors,
        )
        assertTrue(project.resolve("a.txt").exists())
    }

    @Test
    fun `entries no longer shown are not moved`() {
        project.resolve("a.txt").createFile()
        project.resolve("target").createDirectories()
        open()
        val stale = fake.item("a.txt")
        // Opened again: new items, the old ones gone from the tree.
        fake.reopen()

        move(listOf(stale), fake.item("target"))

        assertEquals(emptyList(), fake.moveQuestions)
        assertEquals(emptyList(), fake.moved)
        assertTrue(project.resolve("a.txt").exists())
    }

    @Test
    fun `entries moved by path, as cut ones, are refused into themselves and while being deleted`() {
        project.resolve("src/main").createDirectories()
        project.resolve("a.txt").writeText("a")
        project.resolve("doomed").createDirectories()
        open()
        fake.item("src").isExpanded = true

        assertFalse(fake.moves.move(listOf(project.resolve("src")), fake.item("src/main")))
        fake.inline = false
        fake.edits.delete(listOf(fake.item("a.txt")))
        assertFalse(fake.moves.move(listOf(project.resolve("a.txt")), fake.item("src")))
        assertFalse(fake.moves.move(listOf(project), fake.item("src")), "not a top-level folder")

        val root = project.fileName
        assertEquals(
            listOf(
                "“src” cannot be moved into itself.",
                "Nothing was moved: “src” or one of the entries is being deleted or gone.",
                "“$root” cannot be moved into itself.",
            ),
            fake.errors,
        )
        assertEquals(emptyList(), fake.moved)
        assertTrue(project.resolve("src/main").exists())
    }

    // Compact Middle Folders (ADR 0096)

    @Test
    fun `a compacted row as a target receives the entries in its deepest folder`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("src/main/kotlin").createDirectories()
        fake = FakeEditHost(project, listOf(scratches), compact = true)
        val row = fake.row("src/main/kotlin")

        move(listOf(fake.item("a.txt")), row)

        val root = project.fileName.toString()
        assertEquals(
            listOf(moveQuestion(listOf("a.txt"), emptyList(), "src/main/kotlin", root, "$root/src/main/kotlin")),
            fake.moveQuestions,
        )
        assertEquals(listOf(project.resolve("a.txt") to project.resolve("src/main/kotlin/a.txt")), fake.moved)
        assertEquals(listOf(project.resolve("src/main/kotlin/a.txt")), fake.selectedPaths())
    }

    @Test
    fun `a compacted row moves as its first folder, named as shown, and is selected as a row again`() {
        project.resolve("src/main/kotlin/App.kt").also { it.parent.createDirectories() }.writeText("app")
        project.resolve("target/x.txt").also { it.parent.createDirectories() }.writeText("x")
        fake = FakeEditHost(project, listOf(scratches), compact = true)
        val row = fake.row("src/main/kotlin").apply { isExpanded = true }

        move(listOf(row, fake.row("App.kt", row)), fake.row("target"))

        val root = project.fileName.toString()
        assertEquals(
            listOf(moveQuestion(listOf("src/main/kotlin"), emptyList(), "target", root, "$root/target")),
            fake.moveQuestions,
        )
        assertEquals(listOf(project.resolve("src") to project.resolve("target/src")), fake.moved)
        assertEquals("app", project.resolve("target/src/main/kotlin/App.kt").readText())
        val moved = fake.selected.single() as FileTreeItem
        assertEquals("src/main/kotlin", moved.displayName)
        assertEquals(project.resolve("target/src"), moved.sourcePath)
        assertTrue(moved.isExpanded, "a moved row stays expanded")
    }

    @Test
    fun `a compacted row cannot be moved into its own deepest folder`() {
        project.resolve("src/main/kotlin").createDirectories()
        fake = FakeEditHost(project, listOf(scratches), compact = true)
        val row = fake.row("src/main/kotlin")

        move(listOf(row), row)

        assertEquals(emptyList(), fake.moveQuestions)
        assertTrue(project.resolve("src/main/kotlin").exists())
    }
}
