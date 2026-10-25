package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.FolderAction
import dev.sluide.fileexplorer.FolderActionResult
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Create, rename, delete and folder actions on a [FakeEditHost]: no display needed. */
class ExplorerEditsTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var scratches: Path

    private fun open(extraRoots: List<Path> = emptyList()): FakeEditHost = FakeEditHost(project, extraRoots)

    private fun layout() {
        project.resolve("dir/inner.txt").createParentDirectories().createFile()
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        project.resolve("c.txt").createFile()
    }

    private fun selectedNames(fake: FakeEditHost) = fake.selected.map { it.value.name }

    // Create

    @Test
    fun `nothing is created when the name is cancelled, an error is shown when creating fails`() {
        val sub = project.resolve("sub").createDirectories()
        val fake = open()

        fake.edits.create(null, folder = false)
        assertEquals(listOf("sub"), project.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), fake.errors)

        // The folder vanishes while the name is asked for.
        fake.answerName = {
            sub.deleteExisting()
            "late.txt"
        }
        fake.edits.create(fake.item("sub"), folder = false)
        assertEquals(listOf("Could not create “late.txt”: ${sub.resolve("late.txt")} does not exist."), fake.errors)
    }

    @Test
    fun `a new file is created in the clicked folder, selected and opened, a new folder is not opened`() {
        project.resolve("sub/taken.txt").createParentDirectories().createFile()
        val fake = open()
        fake.answerName = { "new.txt" }

        fake.edits.create(fake.item("sub"), folder = false)

        val ask = fake.nameAsks.single()
        assertEquals("New File", ask.title)
        assertEquals("", ask.initial)
        assertEquals("“taken.txt” already exists in this folder.", ask.validate("taken.txt"))
        assertNull(ask.validate("other.txt"))
        assertEquals(listOf(project.resolve("sub/new.txt")), fake.selectedPaths())
        assertEquals(listOf(project.resolve("sub/new.txt")), fake.opened)

        fake.answerName = { "nested" }
        fake.edits.create(null, folder = true)
        assertTrue(project.resolve("nested").toFile().isDirectory)
        assertEquals(listOf(project.resolve("nested")), fake.selectedPaths())
        assertEquals(1, fake.opened.size)
    }

    @Test
    fun `a path creates the missing folders, selects the new file and opens it`() {
        project.resolve("a").createDirectories()
        val fake = open()
        fake.answerName = { "a/b/c.txt" }

        fake.edits.create(null, folder = false)

        assertEquals(project.resolve("a/b/c.txt"), fake.selectedPaths().single())
        assertTrue(project.resolve("a/b/c.txt").isRegularFile())
        assertEquals(listOf(project.resolve("a/b/c.txt")), fake.opened)
        assertTrue(fake.item("a").isExpanded)
        assertEquals("“a/b/c.txt” already exists in this folder.", fake.nameAsks.single().validate("a/b/c.txt"))
        assertEquals("The path must not contain an empty part (//).", fake.nameAsks.single().validate("a//b"))
    }

    @Test
    fun `a new folder path selects the deepest folder, a trailing slash in a new file creates folders only`() {
        val fake = open()
        fake.answerName = { "a/b/c" }
        fake.edits.create(null, folder = true)
        assertEquals(listOf(project.resolve("a/b/c")), fake.selectedPaths())

        fake.answerName = { "docs/notes/" }
        fake.edits.create(null, folder = false)

        assertTrue(project.resolve("docs/notes").isDirectory())
        assertEquals(listOf(project.resolve("docs/notes")), fake.selectedPaths())
        assertEquals(emptyList(), fake.opened)
    }

    @Test
    fun `nothing is created in a file`() {
        project.resolve("a.txt").createFile()
        val fake = open()

        fake.edits.create(fake.item("a.txt"), folder = false)

        assertEquals(emptyList(), fake.nameAsks.map { it.title })
    }

    // Rename

    @Test
    fun `renaming to the same name changes nothing, a failed rename shows the error`() {
        val file = project.resolve("a.txt").createFile()
        val fake = open()
        fake.answerName = { it }

        fake.edits.rename(fake.item("a.txt"))
        assertTrue(file.exists())
        assertEquals(emptyList(), fake.errors)
        // Rename takes single names, unlike New File.
        assertEquals("The name must not contain / or \\.", fake.nameAsks.single().validate("a/b"))

        fake.answerName = {
            file.deleteExisting()
            "b.txt"
        }
        fake.edits.rename(fake.item("a.txt"))
        assertEquals(listOf("Could not rename “a.txt”: $file does not exist."), fake.errors)
        assertEquals(emptyList(), fake.moved)
    }

    // Delete

    @Test
    fun `nothing is deleted unless confirmed, a failed delete shows the error`() {
        val file = project.resolve("a.txt").createFile()
        val fake = open()
        fake.answerDelete = { false }

        fake.edits.delete(listOf(fake.item("a.txt")))
        assertTrue(file.exists())

        fake.answerDelete = { true }
        // The file vanishes before it is deleted.
        fake.inline = false
        fake.edits.delete(listOf(fake.item("a.txt")))
        file.deleteExisting()
        fake.pending.single().run()

        assertEquals(listOf("Could not delete “a.txt”: $file does not exist."), fake.errors)
        assertEquals(emptyList(), fake.deleted)
    }

    @Test
    fun `entries that changed their kind while asking are reported, the others are deleted`() {
        for (names in listOf(listOf("a"), listOf("a", "b"))) {
            val dir = project.resolve("case-${names.size}").createDirectories()
            val fake = FakeEditHost(dir)
            (names + "c").forEach { dir.resolve(it).createFile() }
            fake.answerDelete = {
                // The question was about files; now they are folders.
                names.forEach {
                    dir.resolve(it).deleteExisting()
                    dir.resolve("$it/x").createParentDirectories().createFile()
                }
                true
            }

            fake.edits.delete((names + "c").map { fake.item(it) })

            val were = if (names.size == 1) "was" else "were"
            val joined = names.joinToString(" and ") { "“$it”" }
            assertEquals(listOf("$joined changed while asking and $were not deleted."), fake.errors, "$names")
            assertEquals(listOf(dir.resolve("c")), fake.deleted.map { it.first }, "$names")
            assertTrue(dir.resolve("a/x").exists(), "$names")
        }
    }

    @Test
    fun `an entry inside a selected folder is deleted with it, not twice`() {
        layout()
        val fake = open()
        fake.item("dir").isExpanded = true

        fake.edits.delete(listOf(fake.item("dir/inner.txt"), fake.item("dir"), fake.item("a.txt")))

        assertEquals(listOf("dir", "a.txt").map { project.resolve(it) }, fake.deleted.map { it.first })
        assertEquals(1, fake.deleteQuestions.size)
    }

    @Test
    fun `deleting stops at the first failure and the entries after it stay unlocked`() {
        layout()
        val fake = open()
        fake.inline = false
        fake.edits.delete(listOf("a.txt", "b.txt", "c.txt").map { fake.item(it) })
        assertFalse(fake.edits.canChange(fake.item("c.txt")), "locked while deleting")
        // The second file vanishes before its turn, so deleting it fails.
        project.resolve("b.txt").deleteExisting()

        fake.pending.single().run()

        assertEquals(1, fake.errors.size)
        assertEquals(listOf(project.resolve("a.txt")), fake.deleted.map { it.first })
        assertTrue(project.resolve("c.txt").exists(), "not attempted")
        assertTrue(fake.edits.canChange(fake.item("c.txt")), "nothing stays locked")
    }

    @Test
    fun `the deleted entries are reported with the files that would be lost`() {
        layout()
        val fake = open()
        fake.unsaved = { if (it.fileName.toString() == "a.txt") listOf(it) else emptyList() }

        fake.edits.delete(listOf(fake.item("dir"), fake.item("a.txt")))

        assertEquals(
            listOf(
                deleteQuestion(
                    listOf("dir" to EntryKind.FOLDER, "a.txt" to EntryKind.FILE),
                    1,
                ),
            ),
            fake.deleteQuestions,
        )
        assertEquals(
            listOf(
                project.resolve("dir") to emptyList(),
                project.resolve("a.txt") to listOf(project.resolve("a.txt")),
            ),
            fake.deleted,
        )
        assertFalse(project.resolve("dir").exists())
    }

    @Test
    fun `the row after the deleted entries is selected`() {
        layout()
        val fake = open()
        fake.select("dir", "a.txt")

        fake.edits.delete(listOf(fake.item("dir"), fake.item("a.txt")))

        assertEquals(listOf("b.txt"), selectedNames(fake))
    }

    @Test
    fun `without a row after the deleted entries the row before is selected`() {
        layout()
        val fake = open()
        fake.select("b.txt", "c.txt")

        fake.edits.delete(listOf(fake.item("b.txt"), fake.item("c.txt")))

        assertEquals(2, fake.deleted.size)
        assertEquals(listOf("a.txt"), selectedNames(fake))
    }

    @Test
    fun `when everything in the folder is deleted its folder is selected`() {
        project.resolve("a.txt").createFile()
        project.resolve("b.txt").createFile()
        val fake = open()
        fake.select("a.txt", "b.txt")

        fake.edits.delete(listOf(fake.item("a.txt"), fake.item("b.txt")))

        assertEquals(2, fake.deleted.size)
        assertEquals(listOf(project), fake.selectedPaths())
    }

    @Test
    fun `a selection outside the deleted entries stays`() {
        layout()
        val fake = open()
        fake.select("a.txt", "b.txt")

        fake.edits.delete(listOf(fake.item("c.txt")))

        assertEquals(1, fake.deleted.size)
        assertEquals(emptyList(), fake.selectRowCalls)
        assertEquals(listOf("a.txt", "b.txt"), selectedNames(fake))
    }

    @Test
    fun `the replacement selection is applied again unless something else was selected meanwhile`() {
        layout()
        val fake = open()
        fake.inline = false
        fake.select("a.txt")
        fake.edits.delete(listOf(fake.item("a.txt")))
        val replacement = fake.item("b.txt")
        assertSame(replacement, fake.selectRowCalls.single())

        // The selection was lost while deleting, as removing a row above can do.
        fake.selected = emptyList()
        fake.pending.single().run()
        assertEquals(2, fake.selectRowCalls.size)
        assertSame(replacement, fake.selectRowCalls.last())

        fake.pending.clear()
        fake.select("b.txt")
        fake.edits.delete(listOf(fake.item("b.txt")))
        val next = fake.selectRowCalls.last()
        // The user chose another entry while the delete runs.
        fake.select("dir")
        val calls = fake.selectRowCalls.size
        fake.pending.single().run()

        assertEquals(calls, fake.selectRowCalls.size, "$next is not selected again")
        assertEquals(listOf("dir"), selectedNames(fake))
    }

    @Test
    fun `entries held by a delete cannot be changed, nothing is deleted into them`() {
        project.resolve("a/b/c").createDirectories()
        project.resolve("x.txt").createFile()
        val fake = open(listOf(scratches))
        fake.item("a").isExpanded = true
        fake.item("a/b").isExpanded = true
        val top = fake.projectItem
        val extra = fake.extraItems.single()
        assertFalse(fake.edits.canChange(top), "the opened folder")
        assertFalse(fake.edits.canChange(extra), "an extra root")
        assertFalse(fake.edits.canChange(null))
        assertTrue(fake.edits.canChange(fake.item("x.txt")))
        assertFalse(fake.edits.isBeingDeleted(project.resolve("a/b")))

        fake.inline = false
        fake.edits.delete(listOf(fake.item("a/b")))

        assertFalse(fake.edits.canChange(fake.item("a/b")), "being deleted")
        assertFalse(fake.edits.canChange(fake.item("a/b/c")), "in an entry being deleted")
        assertFalse(fake.edits.canChange(fake.item("a")), "holding an entry being deleted")
        assertTrue(fake.edits.canChange(fake.item("x.txt")))
        assertTrue(fake.edits.isBeingDeleted(project.resolve("a/b")))
        assertTrue(fake.edits.isBeingDeleted(project.resolve("a/b/c")))
        assertFalse(fake.edits.isBeingDeleted(project.resolve("a")))
        fake.edits.delete(listOf(fake.item("a")))
        assertEquals(1, fake.pending.size, "no second delete is started")

        fake.pending.single().run()

        assertTrue(fake.edits.canChange(fake.item("a")), "free again")
        assertFalse(fake.edits.isBeingDeleted(project.resolve("a/b")))
    }

    // Folder actions

    private fun action(perform: (Path) -> FolderActionResult) = FolderAction("Do it", perform)

    @Test
    fun `the returned file is selected and opened, and the note is shown`() {
        project.resolve("sub/inner.txt").createParentDirectories().createFile()
        val fake = open()
        val created = project.resolve("sub/new.txt")

        fake.edits.runFolderAction(
            action {
                it.resolve("new.txt").writeText("x")
                FolderActionResult(created, "Created it")
            },
            fake.item("sub"),
        )

        assertEquals(listOf(created), fake.selectedPaths())
        assertEquals(listOf(created), fake.opened)
        assertEquals(listOf("Created it"), fake.infos)
        assertEquals(emptyList(), fake.errors)
    }

    @Test
    fun `an action without a file expands the collapsed folder and shows the new entry at once`() {
        project.resolve("sub/inner.txt").createParentDirectories().createFile()
        val fake = open()
        val sub = fake.item("sub")

        fake.edits.runFolderAction(
            action {
                it.resolve("made.txt").createFile()
                FolderActionResult(null)
            },
            sub,
        )

        assertTrue(sub.isExpanded)
        assertEquals(setOf("inner.txt", "made.txt"), sub.children.map { it.value.name }.toSet())
        assertEquals(emptyList(), fake.opened)
        assertEquals(emptyList(), fake.infos)
    }

    @Test
    fun `a file elsewhere is opened but not selected, the folder is expanded and shows the new entry`() {
        project.resolve("sub/inner.txt").createParentDirectories().createFile()
        val fake = open()
        val sub = fake.item("sub")
        val elsewhere = project.resolve("other.txt").createFile()

        fake.edits.runFolderAction(
            action {
                it.resolve("made.txt").createFile()
                FolderActionResult(elsewhere)
            },
            sub,
        )

        assertEquals(listOf(elsewhere), fake.opened)
        assertTrue(sub.isExpanded)
        assertTrue(sub.children.any { it.value.name == "made.txt" })
        assertEquals(emptyList(), fake.selectedPaths())
    }

    @Test
    fun `the message of an UncheckedIOException's cause is shown as an error`() {
        val fake = open()

        fake.edits.runFolderAction(action { throw UncheckedIOException(IOException("Disk full")) }, null)

        assertEquals(listOf("Disk full"), fake.errors)
        assertEquals(emptyList(), fake.opened)
    }

    @Test
    fun `an IOException is shown as an error and nothing is opened`() {
        val fake = open()

        fake.edits.runFolderAction(action { throw IOException("No space left") }, null)

        assertEquals(listOf("No space left"), fake.errors)
        assertEquals(emptyList(), fake.opened)
        assertEquals(emptyList(), fake.infos)
    }

    @Test
    fun `a folder action runs on the opened folder for no target and not on a file`() {
        project.resolve("a.txt").createFile()
        val fake = open()
        val ranOn = mutableListOf<Path>()
        val action =
            action {
                ranOn.add(it)
                FolderActionResult(null)
            }

        fake.edits.runFolderAction(action, null)
        fake.edits.runFolderAction(action, fake.item("a.txt"))

        assertEquals(listOf(project), ranOn)
    }

    // Compact Middle Folders (ADR 0096)

    @Test
    fun `deleting a compacted row deletes its first folder, asking with the row's name`() {
        val app = project.resolve("src/main/kotlin/App.kt").createParentDirectories().createFile()
        project.resolve("b.txt").createFile()
        val fake = FakeEditHost(project, compact = true)
        fake.unsaved = { if (it == project.resolve("src")) listOf(app) else emptyList() }

        fake.edits.delete(listOf(fake.row("src/main/kotlin"), fake.row("src/main/kotlin")))

        assertEquals(listOf(deleteQuestion("src/main/kotlin", EntryKind.FOLDER, 1)), fake.deleteQuestions)
        assertFalse(project.resolve("src").exists())
        assertEquals(listOf(project.resolve("src") to listOf(app)), fake.deleted)
        assertEquals(listOf("b.txt"), fake.projectItem.children.map { it.value.name })
    }

    @Test
    fun `a compacted row being deleted cannot be changed, nor can a row inside it`() {
        project.resolve("src/main/kotlin/App.kt").createParentDirectories().createFile()
        val fake = FakeEditHost(project, compact = true)
        val row = fake.row("src/main/kotlin").apply { isExpanded = true }
        val app = fake.row("App.kt", row)
        fake.inline = false

        fake.edits.delete(listOf(row))

        assertFalse(fake.edits.canChange(row))
        assertFalse(fake.edits.canChange(app))
        assertTrue(fake.edits.isBeingDeleted(project.resolve("src/main")))
    }
}
