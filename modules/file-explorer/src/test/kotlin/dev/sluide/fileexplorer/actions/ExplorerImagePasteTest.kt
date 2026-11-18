package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ClipboardFiles
import dev.sluide.fileexplorer.ClipboardImage
import dev.sluide.fileexplorer.ExplorerClipboard
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pasting an image from the clipboard as a PNG, on a [FakeEditHost] with a clipboard in memory. */
class ExplorerImagePasteTest {
    @TempDir
    lateinit var project: Path

    private lateinit var fake: FakeEditHost

    private val image = ClipboardImage(2, 1, intArrayOf(0xFF112233.toInt(), 0x00000000))

    private fun open(): FakeEditHost = FakeEditHost(project).also { fake = it }

    private fun names() = project.listDirectoryEntries().map { it.name }.sorted()

    @Test
    fun `an image alone asks for a name with the default, writes a PNG, selects and opens it`() {
        open()
        fake.clipboardFiles.image = image

        assertTrue(fake.copies.paste(emptyList()))

        val ask = fake.imageNameAsks.single()
        assertEquals("image-2026-03-04-050607.png", ask.initial)
        assertEquals(listOf("image-2026-03-04-050607.png"), names())
        val file = project.resolve("image-2026-03-04-050607.png")
        val decoded = checkNotNull(ImageIO.read(file.toFile()))
        assertEquals(2, decoded.width)
        assertEquals(0xFF112233.toInt(), decoded.getRGB(0, 0))
        assertEquals(0x00000000, decoded.getRGB(1, 0))
        assertEquals(listOf(file), fake.selectedPaths())
        assertEquals(listOf(file), fake.opened)
        assertEquals(emptyList(), fake.errors)
    }

    @Test
    fun `files win over an image, which asks nothing`() {
        project.resolve("a.txt").writeText("a")
        open()
        fake.clipboardFiles.content = listOf(project.resolve("a.txt"))
        fake.clipboardFiles.image = image

        fake.copies.paste(emptyList())

        assertEquals(emptyList(), fake.imageNameAsks)
        assertEquals(listOf("a-copy.txt", "a.txt"), names())
    }

    @Test
    fun `the typed name gets png, may name folders to create, and is judged as the final name`() {
        project.resolve("taken.png").writeText("x")
        open()
        fake.clipboardFiles.image = image
        fake.answerImageName = { "shots/diagram" }

        fake.copies.paste(emptyList())

        val ask = fake.imageNameAsks.single()
        val file = project.resolve("shots/diagram.png")
        assertTrue(file.exists())
        assertEquals(listOf(file), fake.selectedPaths())
        assertEquals(listOf(file), fake.opened)
        assertEquals("Saved as “diagram.png”.", ask.note("diagram"))
        assertNull(ask.note("diagram.PNG"))
        assertNull(ask.note(""))
        assertNull(ask.validate("fresh"))
        assertTrue(checkNotNull(ask.validate("taken")).contains("already exists"), "taken as taken.png")
        assertTrue(checkNotNull(ask.validate("")).isNotEmpty(), "a blank name is not .png")
        assertTrue(checkNotNull(ask.validate("dir/")).isNotEmpty())
    }

    @Test
    fun `the PNG is written in the background, and the file is selected and opened after it`() {
        open()
        fake.clipboardFiles.image = image
        fake.inline = false

        fake.copies.paste(emptyList())

        assertEquals(emptyList(), names(), "nothing written before the background runs")
        assertEquals(emptyList(), fake.selectedPaths())
        assertEquals(emptyList(), fake.opened)
        fake.pending.single().run()

        val file = project.resolve("image-2026-03-04-050607.png")
        assertTrue(file.exists())
        assertEquals(listOf(file), fake.selectedPaths())
        assertEquals(listOf(file), fake.opened)
    }

    @Test
    fun `an unexpected failure while encoding is shown, not swallowed`() {
        open()
        // Fewer pixels than its size: encoding fails with an unchecked exception, as AWT's natives fail with an Error.
        fake.clipboardFiles.image = ClipboardImage(2, 2, intArrayOf(0))
        fake.inline = false

        fake.copies.paste(emptyList())
        assertFailsWith<RuntimeException> { fake.pending.single().run() }

        assertEquals(listOf("Could not save “image-2026-03-04-050607.png”."), fake.errors)
        assertEquals(emptyList(), names())
        assertEquals(emptyList(), fake.opened)
    }

    @Test
    fun `Cancel writes nothing`() {
        open()
        fake.clipboardFiles.image = image
        fake.answerImageName = { null }

        fake.copies.paste(emptyList())

        assertEquals(emptyList(), names())
        assertEquals(emptyList(), fake.errors)
    }

    @Test
    fun `a name taken while the dialog was open is an error and the file stays`() {
        open()
        fake.clipboardFiles.image = image
        fake.answerImageName = {
            project.resolve("mine.png").writeText("original")
            "mine"
        }

        fake.copies.paste(emptyList())

        assertEquals("original", String(project.resolve("mine.png").readBytes()))
        assertTrue(fake.errors.single().contains("already exists"))
        assertEquals(emptyList(), fake.opened)
    }

    @Test
    fun `an unreadable image shows an error without a question`() {
        open()
        fake.clipboardFiles.unreadable = true

        assertTrue(fake.copies.paste(emptyList()))

        assertEquals(listOf("The clipboard image could not be read."), fake.errors)
        assertEquals(emptyList(), fake.imageNameAsks)
        assertEquals(emptyList(), names())
    }

    @Test
    fun `a path through a file, appearing while the dialog was open, is an error and creates nothing`() {
        open()
        fake.clipboardFiles.image = ClipboardImage(1, 1, intArrayOf(0))
        fake.answerImageName = {
            project.resolve("blocked").writeText("a file, not a folder")
            "blocked/x.png"
        }

        fake.copies.paste(emptyList())

        assertEquals(listOf("blocked"), names())
        assertEquals(listOf("“blocked” exists and is not a folder."), fake.errors)
    }

    @Test
    fun `the paste item says image only for an image without files and a target`() {
        project.resolve("src").createDirectories()
        open()
        assertEquals(PasteItem(false, "Paste"), fake.copies.pasteItem(emptyList()))

        fake.clipboardFiles.image = image
        assertEquals(PasteItem(true, "Paste Image"), fake.copies.pasteItem(emptyList()))
        assertEquals(
            PasteItem(false, "Paste"),
            fake.copies.pasteItem(listOf(fake.item("src"), fake.projectItem)),
            "no target",
        )

        fake.clipboardFiles.content = listOf(project.resolve("src"))
        assertEquals(PasteItem(true, "Paste"), fake.copies.pasteItem(emptyList()), "files win")
    }

    @Test
    fun `the paste item reads the clipboard once, the image only without files, also with a pending cut`() {
        project.resolve("a.txt").writeText("a")
        open()
        fake.clipboardFiles.image = image

        fake.copies.pasteItem(emptyList())
        assertEquals(1, fake.clipboardFiles.filesReads)
        assertEquals(1, fake.clipboardFiles.imageChecks)

        fake.copies.cut(listOf(fake.item("a.txt")))
        fake.clipboardFiles.filesReads = 0
        fake.clipboardFiles.imageChecks = 0
        fake.copies.pasteItem(emptyList())
        assertEquals(1, fake.clipboardFiles.filesReads, "the cut is checked against the files read")
        assertEquals(0, fake.clipboardFiles.imageChecks, "files win, so the image is not asked for")
        val cutFile = project.resolve("a.txt").toAbsolutePath().normalize()
        assertTrue(fake.clipboard.isCut(cutFile), "the clipboard still holds the cut file")

        fake.clipboardFiles.filesReads = 0
        fake.copies.paste(emptyList())
        assertEquals(1, fake.clipboardFiles.filesReads, "a paste reads once too")

        fake.copies.cut(listOf(fake.item("a.txt")))
        fake.clipboardFiles.content = null
        fake.copies.pasteItem(emptyList())
        assertFalse(fake.clipboard.isCut(cutFile), "the menu drops the cut when the clipboard changed elsewhere")
    }

    @Test
    fun `an image drops a pending cut mark`() {
        project.resolve("a.txt").writeText("a")
        open()
        fake.copies.cut(listOf(fake.item("a.txt")))
        fake.clipboardFiles.content = null
        fake.clipboardFiles.image = image

        fake.copies.paste(emptyList())

        assertFalse(fake.clipboard.isCut(project.resolve("a.txt")))
    }

    private fun paster(
        create: ((Path) -> OutputStream)? = null,
        clipboard: ExplorerClipboard = fake.clipboard,
    ) = if (create == null) {
        ExplorerImagePaste(fake.host, { clipboard }, fake.clock)
    } else {
        ExplorerImagePaste(fake.host, { clipboard }, fake.clock, create)
    }

    /** A file that is created, then fails when written: the disk is full or goes away. */
    private fun failingStream(failure: () -> Throwable): (Path) -> OutputStream =
        { file ->
            val real = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            object : OutputStream() {
                override fun write(b: Int) = throw failure()

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = throw failure()

                override fun close() = real.close()
            }
        }

    @Test
    fun `no image on the clipboard asks nothing and shows nothing`() {
        open()

        paster().paste(fake.projectItem)

        assertEquals(emptyList(), fake.imageNameAsks)
        assertEquals(emptyList(), fake.errors)
        assertEquals(emptyList(), names())
    }

    @Test
    fun `a clipboard that fails without a message shows an empty message`() {
        open()
        val failing =
            object : ClipboardFiles {
                override fun files(): List<Path> = emptyList()

                override fun hasImage() = true

                override fun image(): ClipboardImage = throw IOException()

                override fun put(files: List<Path>) {}

                override fun clear() {}
            }

        paster(clipboard = ExplorerClipboard(failing)).paste(fake.projectItem)

        assertEquals(listOf(""), fake.errors)
        assertEquals(emptyList(), fake.imageNameAsks)
    }

    @Test
    fun `a name ending with a slash is refused as the name of a folder`() {
        open()
        fake.clipboardFiles.image = image
        fake.answerImageName = { null }

        fake.copies.paste(emptyList())

        val ask = fake.imageNameAsks.single()
        assertEquals("The name must not end with /.", ask.validate("dir/"))
        assertNull(ask.note("dir/"), "no png is added to a folder name")
        assertNull(ask.note(" "), "nor to a blank name")
        // A backslash ends a folder name on Windows only; elsewhere it is no separator but not allowed in a name.
        val expected = if (IS_WINDOWS) "The name must not end with /." else "The name must not contain / or \\."
        assertEquals(expected, ask.validate("dir\\"))
    }

    @Test
    fun `a file that cannot be written is removed again and the failure is shown with its reason`() {
        open()
        fake.clipboardFiles.image = image

        paster(failingStream { IOException("disk full") }).paste(fake.projectItem)

        assertEquals(listOf("Could not save “image-2026-03-04-050607.png”: disk full"), fake.errors)
        assertEquals(emptyList(), names(), "no partial PNG stays")
        assertEquals(emptyList(), fake.opened)
    }

    @Test
    fun `a file that cannot be removed again stays, and the failure of the write is still shown`() {
        assumeTrue(Files.getFileAttributeView(project, PosixFileAttributeView::class.java) != null)
        open()
        fake.clipboardFiles.image = image
        val writable = Files.getPosixFilePermissions(project)
        val readOnly = writable - PosixFilePermission.OWNER_WRITE
        try {
            // The folder turns read-only while writing, so that the partial file cannot be deleted.
            paster(
                failingStream {
                    Files.setPosixFilePermissions(project, readOnly)
                    IOException("disk full")
                },
            ).paste(fake.projectItem)
            // Not writable for a user with all rights (root) either: then the delete works and there is nothing to see.
            assumeTrue(!Files.isWritable(project))

            assertEquals(listOf("Could not save “image-2026-03-04-050607.png”: disk full"), fake.errors)
            assertEquals(listOf("image-2026-03-04-050607.png"), names())
        } finally {
            Files.setPosixFilePermissions(project, writable)
        }
    }

    @Test
    fun `a failure without a message is shown with its type`() {
        open()
        fake.clipboardFiles.image = image

        paster(failingStream { IOException() }).paste(fake.projectItem)

        assertEquals(listOf("Could not save “image-2026-03-04-050607.png”: IOException"), fake.errors)
        assertEquals(emptyList(), names())
    }

    @Test
    fun `an unexpected failure while writing removes the file, too, and is shown`() {
        open()
        fake.clipboardFiles.image = image

        assertFailsWith<IllegalStateException> {
            paster(failingStream { IllegalStateException("boom") }).paste(fake.projectItem)
        }

        assertEquals(listOf("Could not save “image-2026-03-04-050607.png”."), fake.errors)
        assertEquals(emptyList(), names())
    }

    @Test
    fun `a file written for a folder that is not shown any more is neither selected nor opened`() {
        open()
        fake.clipboardFiles.image = image
        fake.inline = false
        val folder = fake.projectItem

        paster().paste(folder)
        fake.reopen()
        fake.pending.single().run()

        assertEquals(listOf("image-2026-03-04-050607.png"), names())
        assertEquals(emptyList(), fake.selectedPaths())
        assertEquals(emptyList(), fake.opened)
        assertEquals(emptyList(), fake.errors)
    }
}
