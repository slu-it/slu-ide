package dev.sluide.pdf.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.engine.PageText
import javafx.stage.Stage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening, the password flow (with the prompt replaced), the notices, reload, move and dispose of the PDF view. Needs a
 * scene (the notices replace the pages, the toolbar is disabled); the notice texts and the file stamp are unit-tested
 * (`PdfNoticeTest`), the dialog in `PasswordPromptUiTest`.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfOpenUiTest : PdfViewUiFixture() {
    @TempDir
    lateinit var dir: Path

    @Start
    fun start(stage: Stage) = startStage(stage)

    /** A prompt that tries [passwords] in turn and records its answers. */
    private class FakePrompt(
        private vararg val passwords: String,
    ) {
        val answers = mutableListOf<Boolean>()
        var asked = 0
        var closed = 0
        lateinit var request: PasswordRequest

        fun ask(request: PasswordRequest): () -> Unit {
            asked++
            this.request = request
            tryNext(0)
            return { closed++ }
        }

        private fun tryNext(index: Int) {
            if (index >= passwords.size) return
            request.attempt(passwords[index].toCharArray()) { accepted ->
                answers += accepted
                if (!accepted) tryNext(index + 1)
            }
        }
    }

    private fun copy(
        file: Path,
        name: String = "copy.pdf",
    ): Path = Files.copy(file, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)

    /** Writes [content] over [file] and moves its modification time on, so that the stamp sees the change. */
    private fun replace(
        file: Path,
        content: Path,
    ) {
        val before = Files.getLastModifiedTime(file).toMillis()
        Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING)
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + 10_000))
    }

    /** [TestPdfs.text], protected with the user password [password]. */
    private fun protectedText(password: String): Path {
        val file = dir.resolve("protected-$password.pdf")
        Loader.loadPDF(TestPdfs.text.toFile()).use { document ->
            document.protect(StandardProtectionPolicy("owner", password, AccessPermission()))
            document.save(file.toFile())
        }
        return file
    }

    private fun textOf(view: PdfFileView): PageText {
        var text: PageText? = null
        fx { view.texts.textOf(0) { text = it } }
        await { text != null }
        return checkNotNull(text)
    }

    @Test
    fun `shows Loading until the document opened, with the page field and zoom disabled`() {
        val opening = CountDownLatch(1)
        val render = NamedThreads.single("test-pdf-render").also { it.execute { opening.await() } }
        val view = open(TestPdfs.text, render)
        assertTrue(fx { view.pageNotice.shown })
        assertEquals(PdfNotice.LOADING, fx { view.pageNotice.text.text })
        assertTrue(
            fx {
                view.toolbar.pageField.isDisable &&
                    view.toolbar.node.trailing[1]
                        .isDisable
            },
        )
        opening.countDown()
        await { !view.pageNotice.shown }
        assertTrue(fx { view.pages.isVisible })
        assertFalse(
            fx {
                view.toolbar.pageField.isDisable ||
                    view.toolbar.node.trailing[1]
                        .isDisable
            },
        )
    }

    @Test
    fun `asks for the password, tries again after a wrong one, and opens both documents with the right one`() {
        val prompt = FakePrompt("wrong", TestPdfs.USER_PASSWORD)
        val view = open(TestPdfs.userPassword) { it.askPassword = prompt::ask }
        awaitRendered(view, 0)
        assertEquals(listOf(false, true), prompt.answers)
        assertEquals("userPassword.pdf".let { TestPdfs.userPassword.fileName.toString() }, prompt.request.fileName)
        assertTrue(textOf(view).text.startsWith("Secret text: needle"))
        assertEquals(TestPdfs.USER_PASSWORD, String(checkNotNull(fx { view.keptPassword })))
        assertFalse(fx { view.pageNotice.shown })
    }

    @Test
    fun `Cancel leaves a notice whose button asks again`() {
        val prompt = FakePrompt()
        val view = open(TestPdfs.userPassword) { it.askPassword = prompt::ask }
        await { prompt.asked == 1 }
        fx { prompt.request.cancelled() }
        assertEquals(PdfNotice.PROTECTED, fx { view.pageNotice.text.text })
        assertEquals(PdfNotice.ENTER_PASSWORD, fx { view.pageNotice.action.text })
        assertTrue(fx { view.pageNotice.action.isVisible && !view.pages.isVisible })
        assertNull(fx { view.info.value })
        fx { view.pageNotice.action.fire() }
        assertEquals(2, prompt.asked)
    }

    @Test
    fun `a file that is no PDF, is cut off or has no pages gets its notice`() {
        for ((file, text) in listOf(
            TestPdfs.notPdf to "Not a valid PDF: ",
            TestPdfs.truncated to "Not a valid PDF: ",
            TestPdfs.zeroPages to "This PDF has no pages.",
        )) {
            val view = open(file)
            await { view.pageNotice.text.text != PdfNotice.LOADING }
            assertTrue(
                fx {
                    view.pageNotice.text.text
                        .startsWith(text)
                },
                "$file: ${fx { view.pageNotice.text.text }}",
            )
            assertTrue(fx { view.pageNotice.shown && !view.pageNotice.action.isVisible })
            assertTrue(fx { view.toolbar.pageField.isDisable })
            assertNull(fx { view.info.value })
        }
    }

    @Test
    fun `reload opens a changed file again on the page shown and tells the listeners`() {
        val file = copy(TestPdfs.manyPages)
        val view = open(file)
        awaitRendered(view, 0)
        var reloads = 0
        fx { view.onReload += { reloads++ } }
        fx { view.scrollTo(PageTarget(3, 100.0)) }
        fx { view.reload() }
        // Unchanged: nothing to do. No signal tells that nothing happened; the workers would answer within this time.
        Thread.sleep(200)
        assertEquals(0, reloads)
        replace(file, TestPdfs.manyPages)
        fx { view.reload() }
        await { reloads == 1 }
        awaitRendered(view, 3)
        assertEquals(3, fx { view.currentPage.get() })
        assertEquals(
            PageTarget(3, 100.0),
            fx {
                view.pageColumn.topTarget()
            }?.let { it.copy(y = Math.round(it.y!!).toDouble()) },
        )
    }

    @Test
    fun `reload shows a notice when the file can no longer be opened, and the pages once it can`() {
        val file = copy(TestPdfs.text)
        val view = open(file)
        awaitRendered(view, 0)
        replace(file, TestPdfs.notPdf)
        fx { view.reload() }
        await { view.pageNotice.shown }
        assertTrue(
            fx {
                view.pageNotice.text.text
                    .startsWith("Not a valid PDF: ")
            },
        )
        replace(file, TestPdfs.text)
        fx { view.reload() }
        awaitRendered(view, 0)
        assertFalse(fx { view.pageNotice.shown })
    }

    @Test
    fun `reload asks again when the password changed`() {
        val file = copy(protectedText("first"))
        val prompt = FakePrompt("first", "second")
        val view = open(file) { it.askPassword = prompt::ask }
        awaitRendered(view, 0)
        replace(file, protectedText("second"))
        fx { view.reload() }
        await { prompt.asked == 2 }
        awaitRendered(view, 0)
        assertEquals("second", String(checkNotNull(fx { view.keptPassword })))
    }

    @Test
    fun `dispose closes the prompt and zeroes the password`() {
        val opened = FakePrompt(TestPdfs.USER_PASSWORD)
        val view = open(TestPdfs.userPassword) { it.askPassword = opened::ask }
        awaitRendered(view, 0)
        val password = checkNotNull(fx { view.keptPassword })
        fx { view.dispose() }
        assertTrue(password.all { it == '\u0000' })
        val asking = FakePrompt()
        val other = open(TestPdfs.userPassword) { it.askPassword = asking::ask }
        await { asking.asked == 1 }
        fx { other.dispose() }
        assertEquals(1, asking.closed)
    }

    @Test
    fun `follows a move to another PDF only`() {
        val host =
            object : FileViewHost {
                val pages = mutableListOf<URI>()

                override fun openFile(file: Path) = false

                override fun openWebPage(uri: URI) {
                    pages += uri
                }
            }
        val view = open(TestPdfs.text) { it.host = host }
        val moved = dir.resolve("Moved.PDF")
        assertFalse(fx { view.moved(dir.resolve("moved.txt")) })
        assertTrue(fx { view.moved(moved) })
        fx { view.toolbar.openExternally.fire() }
        assertEquals(listOf(moved.toUri()), host.pages)
        assertNotNull(view)
    }
}
