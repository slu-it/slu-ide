package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.KdfParams
import dev.sluide.encryptedfiles.LockChoice
import dev.sluide.encryptedfiles.Sluenc
import dev.sluide.encryptedfiles.SluencException
import dev.sluide.encryptedfiles.SluencFailure
import dev.sluide.encryptedfiles.SluencFormat
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettings
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.settings.SettingsStore
import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Labeled
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.control.TextInputControl
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.GenericStyledArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The tab of an encrypted file: unlock, the inner views, save, lock, close and changes from other windows. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EncryptedFileViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = HBox()

    private val registry = EncryptedFiles()
    private val fallback = DefaultTextFileViewer()
    private lateinit var fileViewers: FileViewers

    // Not in dir: the viewer is built in init, before JUnit injects it.
    private val settingsFile = Files.createTempFile("sluenc-settings", ".properties")
    private val settings = EncryptedFilesSettings(SettingsStore(settingsFile))
    private val viewer = EncryptedFileViewer(registry, settings) { fileViewers }

    @AfterEach
    fun deleteSettings() {
        settingsFile.deleteIfExists()
    }

    private val password = "correct horse battery"

    init {
        fileViewers = FileViewers(listOf(fallback, MarkdownFileViewer(), viewer), fallback)
    }

    /** Another control of the window, where the focus may go while the password field is disabled. */
    private lateinit var elsewhere: TextField

    @Start
    fun start(stage: Stage) {
        elsewhere = TextField()
        stage.scene = Scene(VBox(elsewhere, container), 900.0, 400.0)
        stage.show()
    }

    /** [read] on the FX thread, where the views live. */
    private fun <T> fx(read: () -> T): T = WaitForAsyncUtils.asyncFx<T>(read).get()

    private fun create(
        name: String,
        plain: String,
    ): Path {
        val file = dir.resolve(name)
        val created = Sluenc.create(password.toCharArray(), plain.toByteArray(), KdfParams(64, 1, 1))
        created.key.close()
        file.writeBytes(created.bytes)
        return file
    }

    private fun open(
        file: Path,
        robot: FxRobot,
    ): EncryptedFileView {
        val view = fx { viewer.open(file) as EncryptedFileView }
        robot.interact { container.children += view.node }
        return view
    }

    private fun locked(view: EncryptedFileView) = fx { LockedParts(view.node) }

    /** The locked pane's controls, found in the view's scene graph; read them with [fx]. */
    private class LockedParts(
        node: Node,
    ) {
        val field = node.lookup(".encrypted-password") as TextInputControl
        val unlockButton = node.lookupAll(".button").filterIsInstance<Button>().single { it.text == "Unlock" }
        val message = node.lookup(".encrypted-message") as Labeled
        val title = node.lookup(".encrypted-title") as Labeled
    }

    private fun unlock(
        view: EncryptedFileView,
        robot: FxRobot,
        typed: String = password,
    ) {
        val parts = locked(view)
        robot.interact {
            parts.field.text = typed
            parts.field.fireEvent(ActionEvent())
        }
        assertEquals("", fx { parts.field.text }, "the password leaves the field at once")
        awaitFx { !view.isUnlocking }
    }

    private fun awaitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!fx(condition)) {
            if (System.nanoTime() > deadline) fail("condition not met in time")
            // Poll interval while Argon2 runs on its thread.
            Thread.sleep(20)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** The inner view's (source) text area; FX thread. */
    private fun area(view: EncryptedFileView) = view.node.lookup(".code-area") as CodeArea

    private fun textOf(view: EncryptedFileView): String = fx { area(view).text }

    private fun stripButton(
        view: EncryptedFileView,
        text: String,
    ): Button =
        view.node
            .lookupAll(".encrypted-strip-button")
            .filterIsInstance<Button>()
            .single { it.text.startsWith(text) }

    /** The strip's message, or `null` while it shows none. */
    private fun stripMessage(view: EncryptedFileView): String? =
        fx { (view.node.lookup(".encrypted-strip-message") as Labeled).takeIf { it.isVisible }?.text }

    private fun innerClass(view: EncryptedFileView): String? = fx { view.inner?.javaClass?.name }

    private fun decrypt(
        file: Path,
        with: String = password,
    ): String =
        Sluenc.unlock(file.readBytes(), with.toCharArray()).use { key ->
            String(Sluenc.decrypt(file.readBytes(), key))
        }

    /** Every text in the views' scene graph: labels, inputs, text nodes and RichTextFX areas. */
    private fun texts(): List<String> =
        fx {
            buildList {
                fun visit(n: Node) {
                    when (n) {
                        is Text -> {
                            add(n.text.orEmpty())
                        }

                        is Labeled -> {
                            add(n.text.orEmpty())
                        }

                        is TextInputControl -> {
                            add(n.text.orEmpty())
                        }

                        is GenericStyledArea<*, *, *> -> {
                            add(n.text)
                        }

                        else -> {}
                    }
                    if (n is Parent) n.childrenUnmodifiable.forEach(::visit)
                }
                visit(container)
            }
        }

    /** Writes the file under another password, as Change Password in another tab does. */
    private fun changePasswordElsewhere(
        file: Path,
        newPassword: String,
    ) {
        val changed =
            Sluenc.changePassword(
                file.readBytes(),
                password.toCharArray(),
                newPassword.toCharArray(),
                "theirs\n".toByteArray(),
                KdfParams(64, 1, 1),
            )
        changed.key.close()
        file.writeBytes(changed.bytes)
    }

    @Test
    fun `a wrong password shows why and keeps the field, the right one shows the Markdown view`(robot: FxRobot) {
        val file = create("notes.md.sluenc", "# MARKER-1\n")
        // The unlocks wait until the test lets them run.
        val unlocks = LinkedBlockingQueue<Runnable>()
        val session = EncryptedSession(file, background = unlocks::add)
        val view = fx { EncryptedFileView(file, registry, fileViewers::contentViewerFor, settings, session = session) }
        robot.interact { container.children += view.node }
        val parts = locked(view)

        assertEquals("notes.md.sluenc is encrypted.", fx { parts.title.text })
        assertTrue(fx { view.readOnlyMarked.get() })
        assertEquals("Encrypted, locked", view.lockMarkerText)
        assertTrue(fx { view.tabMenuItems.single { it.text == "Lock" }.isDisable })
        robot.interact { view.focus() }
        assertTrue(fx { parts.field.scene.focusOwner === parts.field })

        robot.interact {
            parts.field.text = "wrong horse battery"
            parts.unlockButton.fire()
        }
        assertTrue(fx { parts.field.isDisable })
        assertEquals("Unlocking…", fx { parts.message.text })
        // Disabling the field may move the focus away (the platform decides); the failure gives it back.
        robot.interact { elsewhere.requestFocus() }
        unlocks.take().run()
        awaitFx { !view.isUnlocking }

        assertEquals("Wrong password.", fx { parts.message.text })
        assertTrue(fx { parts.field.scene.focusOwner === parts.field }, "the field has the focus again")
        assertFalse(fx { parts.field.isDisable })
        assertNull(innerClass(view))

        robot.interact {
            parts.field.text = password
            parts.field.fireEvent(ActionEvent())
        }
        unlocks.take().run()
        awaitFx { !view.isUnlocking }

        assertTrue(fx { view.isUnlocked })
        assertFalse(fx { view.readOnlyMarked.get() })
        assertFalse(fx { view.tabMenuItems.single { it.text == "Lock" }.isDisable })
        assertTrue(fx { view.node.lookup(".encrypted-strip") != null })
        assertEquals("dev.sluide.previews.preview.PreviewFileView", innerClass(view))
        assertEquals("# MARKER-1\n", textOf(view))
        assertTrue(fx { registry.anyUnlocked.get() && registry.isUnlockedEncrypted(view) })
        robot.interact { view.dispose() }
        assertFalse(fx { registry.anyUnlocked.get() })
    }

    @Test
    fun `a handed over key opens the plain text view unlocked, and Save writes only ciphertext`(robot: FxRobot) {
        val file = dir.resolve("notes.txt.sluenc")
        val created = Sluenc.create(password.toCharArray(), "first\n".toByteArray(), KdfParams(64, 1, 1))
        file.writeBytes(created.bytes)
        registry.handOver(file, created.key)

        val view = open(file, robot)

        assertTrue(fx { view.isUnlocked })
        assertIs<DefaultTextFileViewer>(fileViewers.contentViewerFor("notes.txt"))
        robot.interact { area(view).insertText(0, "MARKER-2 ") }
        assertTrue(fx { view.dirty.get() })
        robot.interact { view.save() }

        assertFalse(fx { view.dirty.get() })
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("MARKER-2"))
        assertEquals("MARKER-2 first\n", decrypt(file))
        robot.interact { view.dispose() }
    }

    @Test
    fun `without a viewer for the inner type the locked pane has no field`(robot: FxRobot) {
        val view = open(create("photo.png.sluenc", "x"), robot)
        val parts = locked(view)

        assertEquals("Cannot show .png files.", fx { parts.message.text })
        assertFalse(fx { parts.field.isVisible })
        robot.interact { view.dispose() }
    }

    enum class Answer { SAVE, DISCARD, CANCEL }

    @ParameterizedTest
    @EnumSource(Answer::class)
    fun `Lock with unsaved changes asks, then saves, discards or stays`(
        answer: Answer,
        robot: FxRobot,
    ) {
        val file = create("notes.txt.sluenc", "saved\n")
        val view = open(file, robot)
        unlock(view, robot)
        val key = checkNotNull(fx { view.session.key })
        val questions = mutableListOf<String>()
        view.askUnsaved = { question ->
            questions += question
            LockChoice.valueOf(answer.name)
        }
        robot.interact {
            area(view).insertText(0, "MARKER-3 ")
            view.tabMenuItems.single { it.text == "Lock" }.fire()
        }

        assertEquals(listOf("notes.txt.sluenc has unsaved changes."), questions)
        when (answer) {
            Answer.CANCEL -> {
                assertTrue(fx { view.isUnlocked && view.dirty.get() })
                assertEquals("saved\n", decrypt(file))
            }

            else -> {
                assertFalse(fx { view.isUnlocked })
                assertFalse(fx { view.dirty.get() })
                assertTrue(fx { view.readOnlyMarked.get() })
                assertTrue(key.rawDek.all { it == 0.toByte() }, "key zeroed")
                assertEquals(if (answer == Answer.SAVE) "MARKER-3 saved\n" else "saved\n", decrypt(file))
                assertTrue(texts().none { "MARKER-3" in it || "saved" in it }, "no text of the content")
            }
        }
        robot.interact { view.dispose() }
    }

    @Test
    fun `the strip's Lock leaves no node of the Markdown view holding its text`(robot: FxRobot) {
        val view = open(create("notes.md.sluenc", "# MARKER-4\n\nbody MARKER-4\n"), robot)
        unlock(view, robot)
        assertTrue(texts().any { "MARKER-4" in it })
        val source = fx { area(view) }

        robot.interact { stripButton(view, "Lock").fire() }

        assertNull(innerClass(view))
        assertEquals("", fx { source.text }, "the dropped view is wiped")
        assertTrue(texts().none { "MARKER-4" in it })
        robot.interact { view.dispose() }
    }

    @Test
    fun `closing zeroes the key and records the file only when it closes unlocked and empty`(robot: FxRobot) {
        val empty = create("empty.txt.sluenc", "")
        val view = open(empty, robot)
        unlock(view, robot)
        val key = checkNotNull(fx { view.session.key })

        robot.interact { view.dispose() }

        assertTrue(key.rawDek.all { it == 0.toByte() })
        assertContentEquals(empty.readBytes(), fx { registry.takeClosedEmpty(empty) })
        assertNull(fx { registry.takeClosedEmpty(empty) }, "once")

        val full = create("full.txt.sluenc", "text")
        val fullView = open(full, robot)
        unlock(fullView, robot)
        val text = fx { area(fullView) }
        robot.interact { fullView.dispose() }
        assertNull(fx { registry.takeClosedEmpty(full) })
        assertEquals("", fx { text.text }, "the closed view is wiped")

        val lockedView = open(empty, robot)
        robot.interact { lockedView.dispose() }
        assertNull(fx { registry.takeClosedEmpty(empty) })
    }

    @Test
    fun `a renamed file keeps its view, and the next unlock shows the new type`(robot: FxRobot) {
        val file = create("notes.md.sluenc", "# MARKER-5\n")
        val view = open(file, robot)
        unlock(view, robot)
        val renamed = Files.move(file, dir.resolve("notes.txt.sluenc"))

        assertTrue(fx { view.moved(renamed) })

        assertEquals("dev.sluide.previews.preview.PreviewFileView", innerClass(view))
        robot.interact { view.lock() }
        unlock(view, robot)
        assertEquals("dev.sluide.fileviewers.code.CodeView", innerClass(view))
        robot.interact {
            area(view).insertText(0, "moved ")
            view.save()
        }
        assertEquals("moved # MARKER-5\n", decrypt(renamed))
        robot.interact { view.dispose() }
    }

    @Test
    fun `a change from another tab with the same key reloads`(robot: FxRobot) {
        val file = create("notes.txt.sluenc", "one\n")
        val first = open(file, robot)
        val second = open(file, robot)
        unlock(first, robot)
        unlock(second, robot)

        robot.interact {
            area(second).replaceText("two\n")
            second.save()
            first.reload()
        }

        assertEquals("two\n", textOf(first))
        assertTrue(fx { first.isUnlocked })
        robot.interact {
            first.dispose()
            second.dispose()
        }
    }

    @Test
    fun `a password changed elsewhere locks a clean tab and warns a dirty one, whose Save asks to replace`(
        robot: FxRobot,
    ) {
        val file = create("notes.txt.sluenc", "one\n")
        val clean = open(file, robot)
        val dirty = open(file, robot)
        unlock(clean, robot)
        unlock(dirty, robot)
        robot.interact { area(dirty).insertText(0, "mine ") }
        val other = "another long password"
        changePasswordElsewhere(file, other)

        robot.interact {
            clean.reload()
            dirty.reload()
        }

        assertFalse(fx { clean.isUnlocked })
        assertEquals("Changed elsewhere with another password. Unlock again.", fx { locked(clean).message.text })
        assertTrue(fx { dirty.isUnlocked })
        assertEquals(
            "Changed elsewhere with another password. Saving replaces it with your version.",
            stripMessage(dirty),
        )
        val questions = mutableListOf<String>()
        var replace = false
        dirty.askReplace = { question ->
            questions += question
            replace
        }

        assertTrue(fx { runCatching { dirty.save() }.exceptionOrNull() } is IOException)
        assertEquals("theirs\n", decrypt(file, other))
        assertTrue(fx { dirty.dirty.get() })
        replace = true
        robot.interact { dirty.save() }

        assertEquals(2, questions.size)
        assertTrue(questions.first().startsWith("Replace notes.txt.sluenc? It was changed elsewhere"))
        assertEquals("mine one\n", decrypt(file))
        assertNull(stripMessage(dirty))
        robot.interact {
            clean.dispose()
            dirty.dispose()
        }
    }

    @Test
    fun `a save that fails before a lock keeps the tab unlocked and says why, also in Lock All`(robot: FxRobot) {
        val failing = create("failing.txt.sluenc", "one\n")
        val failingView = open(failing, robot)
        val savingView = open(create("saving.txt.sluenc", "two\n"), robot)
        unlock(failingView, robot)
        unlock(savingView, robot)
        robot.interact {
            area(failingView).insertText(0, "mine ")
            area(savingView).insertText(0, "mine ")
        }
        changePasswordElsewhere(failing, "another long password")
        robot.interact { failingView.reload() }
        failingView.askReplace = { false }
        failingView.askUnsaved = { LockChoice.SAVE }

        robot.interact { stripButton(failingView, "Lock").fire() }

        val reason = "failing.txt.sluenc could not be saved. Replacing the changed file was cancelled."
        assertTrue(fx { failingView.isUnlocked && failingView.dirty.get() })
        assertEquals(reason, stripMessage(failingView))

        registry.askLockAll = { _, _ -> LockChoice.SAVE }
        val stayed = mutableListOf<Int>()
        registry.showStayedUnlocked = { _, count -> stayed += count }
        robot.interact { registry.lockAll(null) }

        assertTrue(fx { failingView.isUnlocked })
        assertEquals(reason, stripMessage(failingView))
        assertEquals(listOf(1), stayed)
        assertFalse(fx { savingView.isUnlocked })
        assertEquals("mine two\n", decrypt(dir.resolve("saving.txt.sluenc")))
        robot.interact {
            failingView.dispose()
            savingView.dispose()
        }
    }

    @Test
    fun `Lock All asks once for the unsaved tabs, and Cancel locks nothing`(robot: FxRobot) {
        val dirtyView = open(create("a.txt.sluenc", "a"), robot)
        val cleanView = open(create("b.txt.sluenc", "b"), robot)
        unlock(dirtyView, robot)
        unlock(cleanView, robot)
        robot.interact { area(dirtyView).insertText(0, "x") }
        val counts = mutableListOf<Int>()
        var choice = LockChoice.CANCEL
        registry.askLockAll = { _, count ->
            counts += count
            choice
        }
        registry.showStayedUnlocked = { _, count -> fail("$count tabs reported as staying unlocked") }

        robot.interact { registry.lockAll(null) }
        assertTrue(fx { dirtyView.isUnlocked && cleanView.isUnlocked })

        choice = LockChoice.DISCARD
        robot.interact { registry.lockAll(null) }

        assertEquals(listOf(1, 1), counts)
        assertFalse(fx { dirtyView.isUnlocked || cleanView.isUnlocked || registry.anyUnlocked.get() })
        robot.interact {
            dirtyView.dispose()
            cleanView.dispose()
        }
    }

    private val newPassword = "another long password"

    /** A view over [file] whose Change Password derives with small parameters, and whose idle time [time] drives. */
    private fun openForChange(
        file: Path,
        robot: FxRobot,
        time: FakeIdleTime = FakeIdleTime(),
    ): EncryptedFileView {
        val session =
            EncryptedSession(
                file,
                rekeyer = { bytes, current, new ->
                    Sluenc.changePassword(bytes, current, new, ByteArray(0), KdfParams(64, 1, 1))
                },
            )
        val view =
            fx {
                EncryptedFileView(file, registry, fileViewers::contentViewerFor, settings, session = session) { m, f ->
                    IdleLock(m, f, Duration.ofMinutes(1), { time.now }, time.scheduler)
                }
            }
        robot.interact { container.children += view.node }
        unlock(view, robot)
        return view
    }

    private fun passwordFieldOf(
        robot: FxRobot,
        id: String,
    ) = robot.lookup(id).queryAs(PasswordField::class.java)

    private fun dialogShown(robot: FxRobot) = fx { robot.lookup("#password-new").tryQuery<Node>().isPresent }

    private fun dialogButton(
        robot: FxRobot,
        text: String,
    ) = fx { robot.lookup(".dialog-pane .button").queryAllAs(Button::class.java).single { it.text == text } }

    private fun statusOf(robot: FxRobot) = fx { robot.lookup("#password-status").queryAs(Labeled::class.java).text }

    /** Runs [open] later (it blocks the FX thread until its dialog closes) and waits for the dialog to show. */
    private fun showDialog(
        robot: FxRobot,
        open: () -> Unit,
    ) {
        Platform.runLater(open)
        awaitFx { dialogShown(robot) }
    }

    private fun type(
        robot: FxRobot,
        current: String?,
        new: String = newPassword,
        repeat: String = new,
    ) = robot.interact {
        if (current != null) passwordFieldOf(robot, "#password-current").text = current
        passwordFieldOf(robot, "#password-new").text = new
        passwordFieldOf(robot, "#password-repeat").text = repeat
    }

    @Test
    fun `Change Password saves the text under the new password only, and the tab stays unlocked and clean`(
        robot: FxRobot,
    ) {
        val file = create("notes.txt.sluenc", "one\n")
        val view = openForChange(file, robot)
        val oldKey = checkNotNull(fx { view.session.key })
        robot.interact { area(view).insertText(0, "edited ") }
        assertTrue(fx { view.dirty.get() })

        showDialog(robot) { view.changePassword() }
        assertTrue(fx { dialogButton(robot, "Change Password").isDisable })
        type(robot, password)
        assertFalse(fx { dialogButton(robot, "Change Password").isDisable })
        robot.interact { dialogButton(robot, "Change Password").fire() }
        awaitFx { !dialogShown(robot) }

        assertEquals("edited one\n", decrypt(file, newPassword))
        assertEquals(1, SluencFormat.parse(file.readBytes()).slotCount, "only the new password slot is written")
        val e = assertThrows(SluencException::class.java) { decrypt(file, password) }
        assertEquals(SluencFailure.WRONG_PASSWORD, e.failure)
        assertTrue(fx { view.isUnlocked })
        assertFalse(fx { view.dirty.get() })
        assertTrue(oldKey.rawDek.all { it == 0.toByte() })
        assertEquals("edited one\n", textOf(view))
        robot.interact { view.dispose() }
    }

    @Test
    fun `a wrong current password is said in the dialog, which stays, and nothing changes`(robot: FxRobot) {
        val file = create("notes.txt.sluenc", "one\n")
        val view = openForChange(file, robot)
        robot.interact { area(view).insertText(0, "edited ") }

        showDialog(robot) { view.changePassword() }
        type(robot, "not the password")
        robot.interact { dialogButton(robot, "Change Password").fire() }
        awaitFx { statusOf(robot) == "The current password is wrong." }

        assertTrue(dialogShown(robot))
        assertEquals("", fx { passwordFieldOf(robot, "#password-current").text })
        assertEquals(newPassword, fx { passwordFieldOf(robot, "#password-new").text })
        assertEquals(newPassword, fx { passwordFieldOf(robot, "#password-repeat").text })
        assertEquals("one\n", decrypt(file))
        assertTrue(fx { view.isUnlocked && view.dirty.get() })
        robot.interact { dialogButton(robot, "Cancel").fire() }
        awaitFx { !dialogShown(robot) }
        robot.interact { view.dispose() }
    }

    @Test
    fun `the strip and the tab menu offer Change Password, the menu item only while unlocked`(robot: FxRobot) {
        val view = open(create("notes.txt.sluenc", "one\n"), robot)
        assertTrue(fx { view.tabMenuItems.single { it.text == "Change Password…" }.isDisable })
        unlock(view, robot)
        assertFalse(fx { view.tabMenuItems.single { it.text == "Change Password…" }.isDisable })
        val labels =
            fx {
                view.node
                    .lookupAll(".encrypted-strip-button")
                    .filterIsInstance<Button>()
                    .map { it.text }
            }
        assertEquals(listOf("Change Password…", "Lock"), labels)
        robot.interact { view.dispose() }
    }

    @Test
    fun `Change Password first asks to replace a file changed elsewhere, and Cancel changes nothing`(robot: FxRobot) {
        val file = create("notes.txt.sluenc", "one\n")
        val view = openForChange(file, robot)
        robot.interact { area(view).insertText(0, "mine ") }
        changePasswordElsewhere(file, "another long password")
        robot.interact { view.reload() }
        val questions = mutableListOf<String>()
        view.askReplace = { question ->
            questions += question
            false
        }

        robot.interact { view.changePassword() }

        assertEquals(1, questions.size)
        assertTrue(questions.single().startsWith("Replace notes.txt.sluenc?"))
        assertFalse(dialogShown(robot), "Cancel opens no dialog")
        assertEquals("theirs\n", decrypt(file, "another long password"))
        robot.interact { view.dispose() }
    }

    @Test
    fun `a tab locked while the Change Password dialog is open closes the dialog on confirm`(robot: FxRobot) {
        val view = openForChange(create("notes.txt.sluenc", "one\n"), robot)
        showDialog(robot) { view.changePassword() }
        type(robot, password)

        robot.interact { view.lockNow(saveFirst = false) }
        robot.interact { dialogButton(robot, "Change Password").fire() }

        awaitFx { !dialogShown(robot) }
        robot.interact { view.dispose() }
    }
}
