package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ClipboardImage
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
        assertEquals(1, fake.errors.size)
        assertTrue(fake.errors.single().contains("blocked"), fake.errors.single())
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
}
