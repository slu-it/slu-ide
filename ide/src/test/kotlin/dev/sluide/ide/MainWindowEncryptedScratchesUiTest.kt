package dev.sluide.ide

import dev.sluide.encryptedfiles.KdfParams
import dev.sluide.encryptedfiles.Sluenc
import dev.sluide.scratches.ENCRYPTED_SCRATCH_TYPES
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI tests of the encrypted scratch files in the main window (ADR 0162): creation from the type picker, deleting the
 * empty ones, and the Lock items of the File menu. Needs a display, see the `uiTest` Gradle task. The password dialog is
 * replaced by a function that answers at once.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowEncryptedScratchesUiTest : MainWindowUiFixture() {
    private val plainText = ENCRYPTED_SCRATCH_TYPES.single { it.extension == "txt.sluenc" }
    private val password = "correct horse battery"

    /** The title of each password dialog that was asked for. */
    private val asked = mutableListOf<String>()

    @Start
    fun start(stage: Stage) = startView(stage)

    /** Picks [plainText] in the type picker and answers the password dialog with [password], or cancels it. */
    private fun answering(confirm: Boolean = true) {
        mainWindow.scratches.askType = { _, onPick -> onPick(plainText) }
        mainWindow.scratches.askNewPassword = { _, title, work ->
            asked += title
            if (!confirm) {
                false
            } else {
                work(password.toCharArray())
                true
            }
        }
    }

    private fun newScratchFile(robot: FxRobot) {
        robot.interact { fileMenuItem(robot, "New Scratch File…").fire() }
    }

    /** [read] on the FX thread, where the window's state lives. */
    private fun <T> fx(read: () -> T): T = WaitForAsyncUtils.asyncFx(read).get()

    private fun disabled(item: MenuItem): Boolean = fx { item.isDisable }

    private fun scratch(name: String): Path = scratchFiles.folder.resolve(name)

    /** An encrypted scratch with [plain], opened unlocked through a handover, as a creation would. */
    private fun openWithContent(
        robot: FxRobot,
        name: String,
        plain: String,
    ): Path {
        val created = Sluenc.create(password.toCharArray(), plain.toByteArray(), KdfParams(64, 1, 1))
        val file = scratch(name)
        Files.createDirectories(file.parent)
        Files.write(file, created.bytes)
        encryptedFiles.handOver(file, created.key)
        robot.interact { editorArea.open(file) }
        return file
    }

    private fun unlocked(file: Path): Boolean =
        fx { editorArea.viewOf(file)?.let(encryptedFiles::isUnlockedEncrypted) == true }

    @Test
    fun `an encrypted type creates a file that decrypts with the password and opens unlocked`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        answering()

        newScratchFile(robot)

        val file = scratch("scratch.txt.sluenc")
        assertEquals(listOf("New Encrypted Scratch File"), asked)
        Sluenc.unlock(Files.readAllBytes(file), password.toCharArray()).use { key ->
            assertEquals(0, Sluenc.decrypt(Files.readAllBytes(file), key).size)
        }
        assertEquals(file, fx { editorArea.selectedPath })
        assertTrue(unlocked(file), "the tab is unlocked through the handover")
        assertEquals(plainText, scratchFiles.lastType)
    }

    @Test
    fun `cancelling the password dialog creates nothing`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        answering(confirm = false)

        newScratchFile(robot)

        assertEquals(1, asked.size)
        assertEquals(emptyList(), Files.list(scratchFiles.folder).use { it.toList() })
        assertNull(fx { editorArea.selectedPath })
        assertNull(scratchFiles.lastType)
    }

    @Test
    fun `a scratch closed unlocked and empty is deleted, one with content or closed locked stays`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        answering()
        newScratchFile(robot)
        val empty = scratch("scratch.txt.sluenc")
        val locked = scratch("scratch_1.txt.sluenc")
        newScratchFile(robot)
        robot.interact { fileMenuItem(robot, "Lock Encrypted File").fire() }
        assertFalse(unlocked(locked))
        val withContent = openWithContent(robot, "notes.txt.sluenc", "text")
        assertTrue(unlocked(withContent))

        for (file in listOf(locked, withContent, empty)) {
            robot.interact { editorArea.open(file) }
            robot.interact { editorArea.closeSelected() }
        }

        assertFalse(Files.exists(empty), "closed unlocked and empty")
        assertTrue(Files.isRegularFile(locked), "closed locked")
        assertTrue(Files.isRegularFile(withContent), "with content")
    }

    @Test
    fun `Lock Encrypted File follows the current tab and the lock state`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        val lock = fileMenuItem(robot, "Lock Encrypted File")
        assertEquals(
            KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
            lock.accelerator,
        )
        assertTrue(disabled(lock), "no tab")
        val first = openWithContent(robot, "a.txt.sluenc", "a")
        assertFalse(disabled(lock), "unlocked tab")
        val second = openWithContent(robot, "b.txt.sluenc", "b")
        assertFalse(disabled(lock), "second unlocked tab")

        robot.interact { lock.fire() }

        assertFalse(unlocked(second))
        assertTrue(unlocked(first), "the other tab stays unlocked")
        assertTrue(disabled(lock), "the current tab is locked, though another one is unlocked")
        robot.interact { editorArea.open(first) }
        assertFalse(disabled(lock), "back on the unlocked tab")
        val plain = scratch("plain.txt").apply { Files.writeString(this, "x") }
        robot.interact { editorArea.open(plain) }
        assertTrue(disabled(lock), "a plain file")
    }

    @Test
    fun `Lock All Encrypted Files is enabled while a tab is unlocked and locks all of them`(robot: FxRobot) {
        robot.interact { projectService.open(project) }
        val lockAll = fileMenuItem(robot, "Lock All Encrypted Files")
        assertEquals(
            KeyCodeCombination(
                KeyCode.L,
                KeyCombination.SHORTCUT_DOWN,
                KeyCombination.ALT_DOWN,
                KeyCombination.SHIFT_DOWN,
            ),
            lockAll.accelerator,
        )
        assertTrue(disabled(lockAll), "nothing unlocked")
        val first = openWithContent(robot, "a.txt.sluenc", "a")
        val second = openWithContent(robot, "b.txt.sluenc", "b")
        assertFalse(disabled(lockAll))

        robot.interact { lockAll.fire() }

        assertFalse(unlocked(first))
        assertFalse(unlocked(second))
        assertTrue(disabled(lockAll), "all locked")
    }
}
