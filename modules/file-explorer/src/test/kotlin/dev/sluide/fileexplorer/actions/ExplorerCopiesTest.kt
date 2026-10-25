package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Copy, cut and paste of entries on a [FakeEditHost] with a clipboard in memory: no display needed. */
class ExplorerCopiesTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var scratches: Path

    private lateinit var fake: FakeEditHost

    private fun open(): FakeEditHost = FakeEditHost(project, listOf(scratches)).also { fake = it }

    private fun names(folder: Path) = folder.listDirectoryEntries().map { it.name }.sorted()

    @Test
    fun `the paste goes into the single folder, the folder of a single file, or the project folder for none`() {
        project.resolve("src").createDirectories()
        project.resolve("src/a.txt").writeText("a")
        project.resolve("b.txt").writeText("b")
        open()
        fake.item("src").isExpanded = true

        assertSame(fake.item("src"), fake.copies.pasteTarget(listOf(fake.item("src"))))
        assertSame(fake.item("src"), fake.copies.pasteTarget(listOf(fake.item("src/a.txt"))))
        assertSame(fake.projectItem, fake.copies.pasteTarget(emptyList()))
        assertSame(fake.extraItems.single(), fake.copies.pasteTarget(fake.extraItems))
        assertNull(fake.copies.pasteTarget(listOf(fake.item("src"), fake.item("b.txt"))))
    }

    @Test
    fun `copied entries are pasted next to themselves with copy names, then selected in their expanded folder`() {
        project.resolve("notes.txt").writeText("notes")
        project.resolve("src/main").createDirectories()
        project.resolve("src/main/inner.txt").writeText("inner")
        open()
        fake.copies.copy(listOf(fake.item("notes.txt"), fake.item("src")))

        assertEquals(listOf(project.resolve("notes.txt"), project.resolve("src")), fake.clipboardFiles.content)
        assertTrue(fake.copies.paste(listOf(fake.item("notes.txt"))))

        assertEquals(listOf("notes-copy.txt", "notes.txt", "src", "src-copy"), names(project))
        assertEquals("inner", project.resolve("src-copy/main/inner.txt").readText())
        assertEquals(listOf(project.resolve("src-copy"), project.resolve("notes-copy.txt")), fake.selectedPaths())
        assertEquals(emptyList(), fake.copyQuestions, "a paste does not ask")
        assertEquals(emptyList(), fake.errors)
        assertEquals(emptyList(), fake.moved)
        assertTrue(project.resolve("notes.txt").exists(), "the clipboard still holds the files to paste again")
        assertEquals(2, fake.clipboardFiles.content?.size)
    }

    @Test
    fun `entries of the same name from several folders get copy names in the order pasted`() {
        project.resolve("a/x.txt").also { it.parent.createDirectories() }.writeText("from a")
        project.resolve("b/x.txt").also { it.parent.createDirectories() }.writeText("from b")
        project.resolve("target").createDirectories()
        open()
        fake.clipboardFiles.content = listOf(project.resolve("a/x.txt"), project.resolve("b/x.txt"))

        fake.copies.paste(listOf(fake.item("target")))

        assertEquals("from a", project.resolve("target/x.txt").readText())
        assertEquals("from b", project.resolve("target/x-copy.txt").readText())
        assertTrue(fake.item("target").isExpanded)
    }

    @Test
    fun `files from another application are copied into the folder`() {
        scratches.resolve("outside.txt").writeText("outside")
        project.resolve("target").createDirectories()
        open()
        fake.clipboardFiles.content = listOf(scratches.resolve("outside.txt"))

        fake.copies.paste(listOf(fake.item("target")))

        assertEquals("outside", project.resolve("target/outside.txt").readText())
        assertEquals(listOf(project.resolve("target/outside.txt")), fake.selectedPaths())
    }

    @Test
    fun `nothing is pasted for several targets, without files on the clipboard, or into a folder being deleted`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("doomed").createDirectories()
        project.resolve("doomed.txt").writeText("doomed")
        open()

        assertFalse(fake.copies.canPaste(emptyList()))
        assertFalse(fake.copies.paste(emptyList()), "nothing on the clipboard")
        fake.copies.copy(listOf(fake.item("a.txt")))
        assertTrue(fake.copies.canPaste(emptyList()))
        assertFalse(fake.copies.canPaste(listOf(fake.item("a.txt"), fake.item("doomed"))))
        assertFalse(fake.copies.paste(listOf(fake.item("a.txt"), fake.item("doomed"))))
        fake.inline = false
        fake.edits.delete(listOf(fake.item("doomed"), fake.item("doomed.txt")))
        assertNull(fake.copies.pasteTarget(listOf(fake.item("doomed"))))
        assertFalse(fake.copies.canPaste(listOf(fake.item("doomed"))))
        assertFalse(fake.copies.paste(listOf(fake.item("doomed"))))
        // A file being deleted: the paste goes into its folder, which is not.
        assertSame(fake.projectItem, fake.copies.pasteTarget(listOf(fake.item("doomed.txt"))))
        assertTrue(fake.copies.canPaste(listOf(fake.item("doomed.txt"))))

        assertEquals(listOf("a.txt", "doomed", "doomed.txt"), names(project))
    }

    @Test
    fun `files with unsaved changes are asked about once, and Cancel copies nothing`() {
        project.resolve("a.txt").writeText("saved")
        project.resolve("src").createDirectories()
        project.resolve("src/b.txt").writeText("b")
        open()
        fake.unsaved = { path ->
            listOf(project.resolve("a.txt"), project.resolve("src/b.txt")).filter { it.startsWith(path) }
        }
        fake.answerCopy = { false }
        fake.copies.copy(listOf(fake.item("a.txt"), fake.item("src")))

        fake.copies.paste(emptyList())

        assertEquals(listOf(copyUnsavedQuestion(listOf("a.txt", "b.txt"))), fake.copyQuestions)
        assertEquals(listOf("a.txt", "src"), names(project))

        fake.answerCopy = { true }
        fake.copies.paste(emptyList())

        assertEquals(listOf("a-copy.txt", "a.txt", "src", "src-copy"), names(project))
        assertEquals("saved", project.resolve("a-copy.txt").readText())
    }

    @Test
    fun `the unsaved question names the files and says the copies get what is saved`() {
        assertEquals(
            CopyQuestion(
                "“a.txt” has unsaved changes. Copy it anyway?",
                "The copy gets what is saved in the file, without the unsaved changes.",
            ),
            copyUnsavedQuestion(listOf("a.txt")),
        )
        assertEquals(
            CopyQuestion(
                "2 files have unsaved changes. Copy them anyway?",
                "“a.txt”\n“b.txt”\n\nThe copies get what is saved in these files, without the unsaved changes.",
            ),
            copyUnsavedQuestion(listOf("a.txt", "b.txt")),
        )
    }

    @Test
    fun `a folder is not pasted into itself or a folder inside it`() {
        project.resolve("src/main").createDirectories()
        open()
        fake.item("src").isExpanded = true
        fake.copies.copy(listOf(fake.item("src")))

        fake.copies.paste(listOf(fake.item("src/main")))

        assertEquals(listOf("“src” cannot be copied into itself."), fake.errors)
        assertEquals(listOf("main"), names(project.resolve("src")))
    }

    @Test
    fun `a folder is not pasted into a folder reached through a link inside it`() {
        project.resolve("releases/v1").createDirectories()
        project.resolve("current").createSymbolicLinkPointingTo(project.resolve("releases/v1"))
        open()
        fake.copies.copy(listOf(fake.item("releases")))

        fake.copies.paste(listOf(fake.item("current")))

        assertEquals(listOf("“releases” cannot be copied into itself."), fake.errors)
        assertEquals(emptyList(), names(project.resolve("releases/v1")))
    }

    @Test
    fun `a drop into a folder that is gone shows an error and copies nothing`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("target").createDirectories()
        open()
        val target = fake.item("target")
        project.resolve("target").deleteExisting()

        fake.copies.copyDrop(listOf(fake.item("a.txt")), target)

        assertEquals(1, fake.errors.size)
        assertTrue(fake.errors.single().startsWith("Could not copy into “target”: "), fake.errors.single())
        assertEquals(emptyList(), fake.copyQuestions)
        assertEquals(listOf("a.txt"), names(project))
    }

    @Test
    fun `entries that are gone are reported, the others are copied`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("b")
        project.resolve("target").createDirectories()
        open()
        fake.copies.copy(listOf(fake.item("a.txt"), fake.item("b.txt")))
        project.resolve("a.txt").deleteExisting()

        fake.copies.paste(listOf(fake.item("target")))

        assertEquals(listOf("b.txt"), names(project.resolve("target")))
        assertEquals(listOf("“a.txt” no longer exists and was not copied."), fake.errors)
    }

    @Test
    fun `copying runs in the background, stops at the first failure and keeps what was copied`() {
        // One more character than a name may have, once "-copy" is added.
        val long = "l".repeat(251) + ".txt"
        listOf("a.txt", long, "b.txt").forEach { project.resolve(it).writeText(it) }
        open()
        fake.inline = false
        fake.copies.copy(listOf(fake.item("a.txt"), fake.item(long), fake.item("b.txt")))

        fake.copies.paste(emptyList())

        assertEquals(listOf("a.txt", "b.txt", long), names(project), "nothing copied before the background runs")
        fake.pending.single().run()

        assertEquals(listOf("a-copy.txt", "a.txt", "b.txt", long), names(project))
        assertTrue(fake.errors.single().startsWith("Could not copy “$long”: "), fake.errors.single())
        assertEquals(listOf(project.resolve("a-copy.txt")), fake.selectedPaths())
    }

    @Test
    fun `cut entries are moved by the paste without asking, then the cut and the clipboard are cleared`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("src/inner.txt").also { it.parent.createDirectories() }.writeText("inner")
        project.resolve("target").createDirectories()
        open()

        assertTrue(fake.copies.cut(listOf(fake.item("a.txt"), fake.item("src"))))
        assertTrue(fake.clipboard.isCut(project.resolve("src")))
        assertTrue(fake.copies.paste(listOf(fake.item("target"))))

        val target = project.resolve("target")
        assertEquals(emptyList(), fake.moveQuestions)
        assertEquals(listOf("target"), names(project))
        assertEquals(listOf("a.txt", "src"), names(target))
        assertEquals(
            listOf(
                project.resolve("a.txt") to target.resolve("a.txt"),
                project.resolve("src") to target.resolve("src"),
            ),
            fake.moved,
        )
        assertEquals(listOf(target.resolve("src"), target.resolve("a.txt")), fake.selectedPaths())
        assertFalse(fake.clipboard.isCut(project.resolve("src")))
        assertNull(fake.clipboardFiles.content)
    }

    @Test
    fun `cut entries in a collapsed folder or another window move, though they have no row here`() {
        project.resolve("src/inner.txt").also { it.parent.createDirectories() }.writeText("inner")
        scratches.resolve("note.txt").writeText("note")
        open()
        fake.clipboard.cut(listOf(project.resolve("src/inner.txt"), scratches.resolve("note.txt")))

        fake.copies.paste(emptyList())

        assertEquals(listOf("inner.txt", "note.txt", "src"), names(project))
        assertEquals(2, fake.moved.size)
        assertFalse(scratches.resolve("note.txt").exists())
    }

    @Test
    fun `a cut whose names are taken in the folder asks about them, never replaces, and Cancel keeps the cut`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("mine")
        project.resolve("target").createDirectories()
        project.resolve("target/b.txt").writeText("theirs")
        open()
        fake.copies.cut(listOf(fake.item("a.txt"), fake.item("b.txt")))
        fake.answerMove = { false }

        fake.copies.paste(listOf(fake.item("target")))

        val root = project.name
        assertEquals(
            listOf(moveQuestion(listOf("a.txt"), listOf("b.txt"), "target", root, "$root/target")),
            fake.moveQuestions,
        )
        assertEquals(listOf("a.txt", "b.txt", "target"), names(project))
        assertTrue(fake.clipboard.isCut(project.resolve("a.txt")))

        fake.answerMove = { true }
        fake.copies.paste(listOf(fake.item("target")))

        assertEquals(listOf("b.txt", "target"), names(project))
        assertEquals("theirs", project.resolve("target/b.txt").readText())
        assertFalse(fake.clipboard.isCut(project.resolve("b.txt")))
    }

    @Test
    fun `cut entries that are gone are reported, the others are moved and the cut ends`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("b")
        project.resolve("target").createDirectories()
        open()
        fake.copies.cut(listOf(fake.item("a.txt"), fake.item("b.txt")))
        project.resolve("a.txt").deleteExisting()

        fake.copies.paste(listOf(fake.item("target")))

        assertEquals(listOf("b.txt"), names(project.resolve("target")))
        assertEquals(listOf("“a.txt” no longer exists and was not moved."), fake.errors)
        assertNull(fake.clipboardFiles.content)
    }

    @Test
    fun `a cut whose first move fails moves nothing and stays cut`() {
        project.resolve("a.txt").writeText("a")
        val target = project.resolve("target").createDirectories()
        assumeTrue(Files.getFileAttributeView(target, PosixFileAttributeView::class.java) != null)
        target.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        open()
        fake.copies.cut(listOf(fake.item("a.txt")))

        try {
            // Root may write anyway.
            assumeFalse(Files.isWritable(target))
            fake.copies.paste(listOf(fake.item("target")))
        } finally {
            target.setPosixFilePermissions(target.getPosixFilePermissions() + PosixFilePermission.OWNER_WRITE)
        }

        assertTrue(fake.errors.single().startsWith("Could not move “a.txt”: "), fake.errors.single())
        assertTrue(project.resolve("a.txt").exists())
        assertTrue(fake.clipboard.isCut(project.resolve("a.txt")))
        assertEquals(listOf(project.resolve("a.txt")), fake.clipboardFiles.content)
    }

    @Test
    fun `cut entries pasted into their own folder stay, without an error, and stay cut`() {
        project.resolve("a.txt").writeText("a")
        open()
        fake.copies.cut(listOf(fake.item("a.txt")))

        fake.copies.paste(emptyList())

        assertEquals(listOf("a.txt"), names(project))
        assertEquals(emptyList(), fake.errors)
        assertEquals(emptyList(), fake.moveQuestions)
        assertTrue(fake.clipboard.isCut(project.resolve("a.txt")))
    }

    @Test
    fun `when the clipboard holds other files the cut is dropped and the paste copies`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("b")
        project.resolve("target").createDirectories()
        open()
        fake.copies.cut(listOf(fake.item("a.txt")))
        fake.clipboardFiles.content = listOf(project.resolve("b.txt"))

        fake.copies.paste(listOf(fake.item("target")))

        assertEquals(listOf("a.txt", "b.txt", "target"), names(project))
        assertEquals(listOf("b.txt"), names(project.resolve("target")))
        assertFalse(fake.clipboard.isCut(project.resolve("a.txt")))
        assertEquals(listOf(project.resolve("b.txt")), fake.clipboardFiles.content)
    }

    @Test
    fun `Copy takes top-level folders, Cut only entries that can be changed, and Copy ends a cut`() {
        project.resolve("a.txt").writeText("a")
        open()

        assertFalse(fake.copies.cut(listOf(fake.projectItem)))
        assertNull(fake.clipboardFiles.content)
        assertTrue(fake.copies.copy(listOf(fake.projectItem)))
        assertEquals(listOf(project), fake.clipboardFiles.content)

        fake.copies.cut(listOf(fake.item("a.txt")))
        fake.copies.copy(listOf(fake.item("a.txt")))

        assertFalse(fake.clipboard.isCut(project.resolve("a.txt")))
    }

    @Test
    fun `cancelling a cut clears the clipboard only while it holds the cut entries`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("b")
        open()
        val changes = fake.clipboard.cutChanges.get()

        assertFalse(fake.copies.cancelCut())
        fake.copies.cut(listOf(fake.item("a.txt")))
        assertEquals(changes + 1, fake.clipboard.cutChanges.get(), "the rows learn about the cut")
        assertTrue(fake.copies.cancelCut())

        assertNull(fake.clipboardFiles.content)
        assertFalse(fake.clipboard.isCut(project.resolve("a.txt")))
        assertEquals(changes + 2, fake.clipboard.cutChanges.get())

        fake.copies.cut(listOf(fake.item("a.txt")))
        fake.clipboardFiles.content = listOf(project.resolve("b.txt"))
        assertTrue(fake.copies.cancelCut())

        assertEquals(listOf(project.resolve("b.txt")), fake.clipboardFiles.content)
    }

    @Test
    fun `a drop asks once, naming the copies with other names and the unsaved files, then copies`() {
        project.resolve("a.txt").writeText("a")
        project.resolve("b.txt").writeText("b")
        project.resolve("target").createDirectories()
        project.resolve("target/A.txt").writeText("taken")
        open()
        fake.unsaved = { path -> listOf(project.resolve("b.txt")).filter { it.startsWith(path) } }
        fake.answerCopy = { false }

        fake.copies.copyDrop(listOf(fake.item("a.txt"), fake.item("b.txt")), fake.item("target"))

        assertEquals(
            listOf(copyDropQuestion(listOf("a.txt" to "a-copy.txt", "b.txt" to "b.txt"), "target", listOf("b.txt"))),
            fake.copyQuestions,
        )
        assertEquals(listOf("A.txt"), names(project.resolve("target")))

        fake.answerCopy = { true }
        fake.copies.copyDrop(listOf(fake.item("a.txt"), fake.item("b.txt")), fake.item("target"))

        assertEquals(listOf("A.txt", "a-copy.txt", "b.txt"), names(project.resolve("target")))
    }

    @Test
    fun `the drop question lists the entries, renamed ones with their new names, and the unsaved files`() {
        assertEquals(
            CopyQuestion(
                "Copy 3 entries to “target”?",
                "“a.txt” → “a-copy.txt”\n“b.txt”\n“src”\n\n" +
                    "These files have unsaved changes; the copies get what is saved:\n“b.txt”\n“c.txt”",
            ),
            copyDropQuestion(
                listOf("a.txt" to "a-copy.txt", "b.txt" to "b.txt", "src" to "src"),
                "target",
                listOf("b.txt", "c.txt"),
            ),
        )
        assertEquals(
            CopyQuestion("Copy “a.txt” to “target”?", "The copy is named “a-copy.txt”."),
            copyDropQuestion(listOf("a.txt" to "a-copy.txt"), "target", emptyList()),
        )
        assertEquals(
            CopyQuestion("Copy “a.txt” to “target”?", "“a.txt” has unsaved changes; the copy gets what is saved."),
            copyDropQuestion(listOf("a.txt" to "a.txt"), "target", listOf("a.txt")),
        )
    }
}
