package dev.sluide.pdf.view

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageTarget
import dev.sluide.pdf.engine.PageText
import javafx.scene.control.Button
import javafx.scene.control.DialogPane
import javafx.scene.control.PasswordField
import javafx.stage.Stage
import javafx.stage.Window
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
import kotlin.test.assertNull
import kotlin.test.assertSame
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

        /** The view's copy of each password tried, and its info when asked. */
        val copies = mutableListOf<CharArray>()
        val infos = mutableListOf<PdfInfo?>()
        var view: PdfFileView? = null

        /** Runs before each attempt. */
        var beforeAttempt: () -> Unit = {}

        fun ask(request: PasswordRequest): () -> Unit {
            asked++
            this.request = request
            infos += view?.info?.value
            tryNext(0)
            return { closed++ }
        }

        private fun tryNext(index: Int) {
            if (index >= passwords.size) return
            beforeAttempt()
            request.attempt(passwords[index].toCharArray()) { accepted ->
                answers += accepted
                if (!accepted) tryNext(index + 1)
            }
            view?.attemptingPassword?.let { copies += it }
        }
    }

    private fun open(
        file: Path,
        prompt: FakePrompt,
    ) = open(file) {
        it.askPassword = prompt::ask
        prompt.view = it
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
        val view = open(TestPdfs.userPassword, prompt)
        awaitRendered(view, 0)
        assertEquals(listOf(false, true), prompt.answers)
        assertEquals(TestPdfs.userPassword.fileName.toString(), prompt.request.fileName)
        assertSame(stage, prompt.request.owner)
        assertTrue(fx { prompt.copies.first().all { it == '\u0000' } }, "the wrong password's copy is zeroed")
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
            assertFalse(fx { view.pageNotice.action.isManaged })
            assertEquals("/ 0", fx { view.toolbar.pageCount.text })
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
        val file = copy(TestPdfs.manyPages)
        var reloads = 0
        val view = open(file) { it.onReload += { reloads++ } }
        awaitRendered(view, 0)
        assertTrue(textOf(view).text.startsWith("Page 1"))
        fx { view.scrollTo(PageTarget(3)) }
        replace(file, TestPdfs.notPdf)
        fx { view.reload() }
        await { view.pageNotice.shown }
        assertTrue(
            fx {
                view.pageNotice.text.text
                    .startsWith("Not a valid PDF: ")
            },
        )
        assertNull(fx { view.info.value })
        // The text document is closed with the notice: texts are empty until the file opens again.
        assertEquals("", textOf(view).text)
        assertEquals(0, reloads)
        replace(file, TestPdfs.manyPages)
        fx { view.reload() }
        awaitRendered(view, 0)
        assertFalse(fx { view.pageNotice.shown })
        await { reloads == 1 }
        // Opened as the first time: at the top.
        assertEquals(0.0, fx { view.pageColumn.viewTop })
    }

    @Test
    fun `reload asks again when the password changed`() {
        val file = copy(protectedText("first"))
        val prompt = FakePrompt("first", "second")
        val view = open(file, prompt)
        awaitRendered(view, 0)
        val first = checkNotNull(fx { view.keptPassword })
        replace(file, protectedText("second"))
        fx { view.reload() }
        await { prompt.asked == 2 }
        awaitRendered(view, 0)
        assertEquals("second", String(checkNotNull(fx { view.keptPassword })))
        assertTrue(first.all { it == '\u0000' }, "the password that no longer opens it is zeroed")
        assertEquals(listOf<PdfInfo?>(null, null), prompt.infos)
    }

    @Test
    fun `a password for a file that turned invalid meanwhile is zeroed and the notice shows`() {
        val file = copy(TestPdfs.userPassword)
        val prompt = FakePrompt(TestPdfs.USER_PASSWORD)
        prompt.beforeAttempt = { Files.copy(TestPdfs.notPdf, file, StandardCopyOption.REPLACE_EXISTING) }
        val view = open(file, prompt)
        await {
            view.pageNotice.text.text
                .startsWith("Not a valid PDF: ")
        }
        assertEquals(listOf(true), prompt.answers)
        assertTrue(fx { prompt.copies.single().all { it == '\u0000' } })
        assertNull(fx { view.keptPassword })
    }

    @Test
    fun `a file that never opened opens on a reload without telling the reload listeners`() {
        val file = copy(TestPdfs.notPdf)
        var reloads = 0
        val view = open(file) { it.onReload += { reloads++ } }
        await {
            view.pageNotice.text.text
                .startsWith("Not a valid PDF: ")
        }
        replace(file, TestPdfs.text)
        fx { view.reload() }
        awaitRendered(view, 0)
        // No signal tells that a listener was not called; the text document opens within this time.
        Thread.sleep(200)
        assertEquals(0, reloads)
    }

    @Test
    fun `a reload while the password prompt shows asks nothing more`() {
        val file = copy(protectedText("first"))
        val prompt = FakePrompt()
        val view = open(file, prompt)
        await { prompt.asked == 1 }
        replace(file, protectedText("second"))
        fx { view.reload() }
        // No signal tells that nothing was opened; the render worker answers within this time.
        Thread.sleep(200)
        assertEquals(1, prompt.asked)
    }

    @Test
    fun `the real password prompt opens the PDF with the password typed`() {
        val view = open(TestPdfs.userPassword)
        await { Window.getWindows().any { it is Stage && it.title == "Open PDF" } }
        val dialog = fx { Window.getWindows().first { it is Stage && it.title == "Open PDF" } }
        fx { (dialog.scene.root.lookup(".pdf-password") as PasswordField).text = TestPdfs.USER_PASSWORD }
        fx { (dialog.scene.root as DialogPane).lookupButton(PasswordPrompt.OPEN).let { (it as Button).fire() } }
        awaitRendered(view, 0)
        assertFalse(fx { dialog.isShowing })
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
    fun `dispose zeroes the password of an attempt whose answer it drops`() {
        val blocked = CountDownLatch(1)
        val render = NamedThreads.single("test-pdf-render")
        var attempt: CharArray? = null
        lateinit var view: PdfFileView
        val prompt = { request: PasswordRequest ->
            // The attempt waits behind this task, so its answer comes after dispose.
            render.execute { blocked.await() }
            request.attempt(TestPdfs.USER_PASSWORD.toCharArray()) {}
            attempt = view.attemptingPassword
            val close = {}
            close
        }
        view = open(TestPdfs.userPassword, render) { it.askPassword = prompt }
        await { attempt != null }
        val pending = checkNotNull(attempt)
        assertEquals(TestPdfs.USER_PASSWORD, String(pending))
        fx { view.dispose() }
        blocked.countDown()
        assertTrue(pending.all { it == '\u0000' })
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
        fx { view.dispose() }
        assertFalse(fx { view.moved(dir.resolve("later.pdf")) })
    }

    @Test
    fun `Open in Default App without a host does nothing`() {
        val view = open(TestPdfs.text)
        fx { view.toolbar.openExternally.fire() }
    }
}
