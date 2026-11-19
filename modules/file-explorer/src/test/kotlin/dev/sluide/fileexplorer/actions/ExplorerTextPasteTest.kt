package dev.sluide.fileexplorer.actions

import dev.sluide.texttypes.TextTypeDetector
import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.logging.Level
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pasting text from the clipboard as a new file, on a [FakeEditHost]. */
class ExplorerTextPasteTest {
    @TempDir
    lateinit var project: Path

    private lateinit var fake: FakeEditHost

    private val json = checkNotNull(TextTypes.byExtension("json"))

    private fun open(): FakeEditHost = FakeEditHost(project).also { fake = it }

    private fun names() = project.listDirectoryEntries().map { it.name }.sorted()

    private fun paster(create: ((Path) -> OutputStream)? = null) =
        if (create == null) {
            ExplorerTextPaste(fake.host, { fake.textTypes }, fake.clock)
        } else {
            ExplorerTextPaste(fake.host, { fake.textTypes }, fake.clock, create)
        }

    private fun paste(text: String) = paster().paste(fake.projectItem, text)

    @Test
    fun `the dialog gets the default name, type and folder, then the file is written, selected and opened`() {
        open()

        paste("#!/bin/bash\necho hi\n")

        val ask = fake.textNameAsks.single()
        assertEquals("pasted-2026-03-04-050607", ask.initialName)
        assertEquals(TextTypes.byExtension("sh"), ask.detected)
        assertEquals(project, ask.folder)
        val file = project.resolve("pasted-2026-03-04-050607.sh")
        assertEquals("#!/bin/bash\necho hi\n", file.toFile().readText())
        assertEquals(listOf(file), fake.selectedPaths())
        assertEquals(listOf(file), fake.opened)
        assertEquals(emptyList(), fake.errors)
    }

    @Test
    fun `the type is detected in the background, before the dialog, and the file is written in the background`() {
        open()
        var detections = 0
        fake.textTypes =
            TextTypeDetector(
                listOf(
                    TextTypeSource {
                        detections++
                        listOf(TypeScore(json, 90))
                    },
                ),
            )
        fake.inline = false

        paste("anything")

        assertEquals(0, detections, "nothing is detected on the calling thread")
        assertEquals(emptyList(), fake.textNameAsks)
        fake.pending.removeFirst().run()
        assertEquals(1, detections)
        assertEquals(json, fake.textNameAsks.single().detected)
        assertEquals(emptyList(), names(), "nothing is written before the background write runs")
        assertEquals(emptyList(), fake.opened)
        fake.pending.removeFirst().run()

        assertEquals(listOf("pasted-2026-03-04-050607.json"), names())
        assertEquals(1, fake.opened.size)
    }

    @Test
    fun `the detector is read when a paste runs, not when the paster is built`() {
        open()
        val paster = paster()
        fake.textTypes = TextTypeDetector(listOf(TextTypeSource { listOf(TypeScore(json, 90)) }))

        paster.paste(fake.projectItem, "x")

        assertEquals(json, fake.textNameAsks.single().detected)
    }

    @Test
    fun `the text is written as it is, with its line endings, as UTF-8`() {
        open()
        val text = "a\r\nb\rc\n\r\nÄ€日本😀\r\n"
        fake.answerTextName = { "out.txt" }

        paste(text)

        assertEquals(text.toByteArray(Charsets.UTF_8).toList(), project.resolve("out.txt").readBytes().toList())
    }

    @Test
    fun `Cancel writes nothing`() {
        open()
        fake.answerTextName = { null }

        paste("text")

        assertEquals(emptyList(), names())
        assertEquals(emptyList(), fake.errors)
        assertEquals(emptyList(), fake.opened)
    }

    @Test
    fun `a path with missing folders creates them`() {
        open()
        fake.answerTextName = { "notes/2026/today.md" }

        paste("# Today")

        val file = project.resolve("notes/2026/today.md")
        assertTrue(file.exists())
        assertEquals(listOf(file), fake.selectedPaths())
        assertEquals(listOf(file), fake.opened)
    }

    @Test
    fun `a file that cannot be written is removed again and the failure is shown with its reason`() {
        open()

        paster(failingStream { IOException("disk full") }).paste(fake.projectItem, "text")

        assertEquals(listOf("Could not save “pasted-2026-03-04-050607.txt”: disk full"), fake.errors)
        assertEquals(emptyList(), names(), "no partial file stays")
        assertEquals(emptyList(), fake.opened)
    }

    @Test
    fun `a file that cannot even be created shows the failure with its reason`() {
        open()

        paster({ throw IOException("no space left") }).paste(fake.projectItem, "text")

        assertEquals(listOf("Could not save “pasted-2026-03-04-050607.txt”: no space left"), fake.errors)
        assertEquals(emptyList(), names())
    }
}
