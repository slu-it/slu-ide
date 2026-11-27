package dev.sluide.gitlog.actions

import dev.sluide.git.ssh.HostKeyRequest
import dev.sluide.git.ssh.PassphraseNotice
import dev.sluide.git.ssh.PassphraseReply
import dev.sluide.git.ssh.PassphraseRequest
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextArea
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real dialogs of the ssh prompts (ADR 0194): their controls, default button and focus, which need a scene. The
 * thread handling is tested without JavaFX in `FxSshPromptUiTest`. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SshPromptDialogsUiTest {
    private lateinit var stage: Stage
    private val key = Path.of(System.getProperty("user.home"), ".ssh", "id_ed25519")
    private val hostText =
        "The authenticity of host 'example.org (192.0.2.1)' can't be established.\n" +
            "ED25519 key fingerprint is SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG.\n" +
            "This key is not known by any other names."

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 300.0, 200.0)
        stage.show()
    }

    private fun ui(operation: String = "Fetching origin…") = FxSshPromptUi({ stage }, { operation })

    /** Asks on a worker thread, as an askpass connection thread does. */
    private fun <T> inBackground(ask: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(ask)

    private fun passphraseRequest(
        notice: PassphraseNotice? = null,
        saveUnavailable: String? = null,
        agentUnavailable: String? = null,
    ) = PassphraseRequest(key, notice, saveUnavailable, agentUnavailable)

    private fun dialogPane(robot: FxRobot): DialogPane {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val pane =
                WaitForAsyncUtils
                    .asyncFx<DialogPane?> {
                        robot
                            .listWindows()
                            .filter { it.isShowing && it !== stage }
                            .firstNotNullOfOrNull { it.scene?.root as? DialogPane }
                    }.get()
            if (pane != null) return pane
            check(System.nanoTime() < deadline) { "no dialog shown" }
            Thread.sleep(20)
        }
    }

    private fun button(
        pane: DialogPane,
        type: ButtonType,
    ) = pane.lookupButton(type) as Button

    private fun unlockOf(pane: DialogPane) = pane.buttonTypes.first { it.text == "Unlock" }

    @Test
    fun `the passphrase dialog names the key with a tilde, the operation and what ssh agent and saving need`(
        robot: FxRobot,
    ) {
        val reply = inBackground { ui().askPassphrase(passphraseRequest(null, "needs secret-tool", "no ssh agent")) }
        val pane = dialogPane(robot)

        val labels =
            robot
                .from(pane)
                .lookup(".label")
                .queryAllAs(Label::class.java)
                .filter { it.isVisible }
        assertTrue(
            labels.any { it.text == "Enter the passphrase for ~/.ssh/id_ed25519." },
            labels.map { it.text }.toString(),
        )
        assertTrue(labels.any { it.text == "Fetching origin…" })
        assertTrue(labels.any { it.text == "Kept in memory until Thoreau quits; saved encrypted when checked." })
        val save = robot.from(pane).lookup("#ssh-save-passphrase").queryAs(CheckBox::class.java)
        val agent = robot.from(pane).lookup("#ssh-add-to-agent").queryAs(CheckBox::class.java)
        assertEquals("Save passphrase (needs secret-tool)", save.text)
        assertEquals("Add to ssh agent (no ssh agent)", agent.text)
        assertTrue(save.isDisable)
        assertTrue(agent.isDisable)
        assertFalse(save.isSelected)
        assertFalse(agent.isSelected)
        assertNull(
            robot
                .from(pane)
                .lookup("#ssh-notice")
                .tryQuery<Label>()
                .orElse(null),
            "no notice on the first ask",
        )
        assertEquals("SSH Key Passphrase", (pane.scene.window as Stage).title)
        assertLayout(robot, pane)
        assertSame(stage, (pane.scene.window as Stage).owner, "owned by the window")
        assertNull(pane.headerText)

        robot.interact { button(pane, ButtonType.CANCEL).fire() }
        assertNull(reply.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `Unlock is the default button, needs a passphrase and answers it with the boxes checked`(robot: FxRobot) {
        val reply = inBackground { ui().askPassphrase(passphraseRequest()) }
        val pane = dialogPane(robot)
        val unlock = button(pane, unlockOf(pane))
        val field = robot.from(pane).lookup("#ssh-passphrase").queryAs(PasswordField::class.java)

        assertTrue(unlock.isDefaultButton)
        assertTrue(unlock.isDisable, "no passphrase yet")
        val boxes = robot.from(pane).lookup(".check-box").queryAllAs(CheckBox::class.java)
        assertEquals(listOf("Save passphrase", "Add to ssh agent"), boxes.map { it.text })
        assertTrue(boxes.none { it.isDisable }, "both can be checked when nothing is missing")
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { WaitForAsyncUtils.asyncFx<Boolean> { field.isFocused }.get() }
        robot.interact {
            field.text = "secret"
            robot
                .from(pane)
                .lookup("#ssh-save-passphrase")
                .queryAs(CheckBox::class.java)
                .isSelected = true
            robot
                .from(pane)
                .lookup("#ssh-add-to-agent")
                .queryAs(CheckBox::class.java)
                .isSelected = true
        }
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { unlock.isDisable }.get())
        robot.interact { unlock.fire() }

        val answer: PassphraseReply = assertNotNull(reply.get(10, TimeUnit.SECONDS))
        assertContentEquals("secret".toCharArray(), answer.passphrase)
        assertTrue(answer.save)
        assertTrue(answer.addToAgent)
        assertEquals("", WaitForAsyncUtils.asyncFx<String> { field.text }.get(), "the field is cleared")
    }

    @Test
    fun `a retry shows why above the field and the boxes stay unchecked by default`(robot: FxRobot) {
        val reply = inBackground { ui().askPassphrase(passphraseRequest(PassphraseNotice.WRONG)) }
        val pane = dialogPane(robot)

        assertEquals(
            "Wrong passphrase, try again.",
            robot
                .from(pane)
                .lookup("#ssh-notice")
                .queryAs(Label::class.java)
                .text,
        )
        robot.interact {
            robot
                .from(pane)
                .lookup("#ssh-passphrase")
                .queryAs(PasswordField::class.java)
                .text = "x"
        }
        robot.interact { button(pane, unlockOf(pane)).fire() }
        val answer = assertNotNull(reply.get(10, TimeUnit.SECONDS))
        assertFalse(answer.save)
        assertFalse(answer.addToAgent)
    }

    @Test
    fun `the host dialog shows ssh's text, has Cancel as default with the focus, and Enter says no`(robot: FxRobot) {
        val trusted = inBackground { ui().confirmHost(HostKeyRequest(hostText)) }
        val pane = dialogPane(robot)
        val cancel = button(pane, ButtonType.CANCEL)
        val connect = button(pane, pane.buttonTypes.first { it.text == "Connect" })

        val area = robot.from(pane).lookup("#ssh-host-text").queryAs(TextArea::class.java)
        assertEquals(hostText, area.text)
        assertFalse(area.isEditable)
        assertTrue(area.isWrapText)
        val labels =
            robot
                .from(pane)
                .lookup(".label")
                .queryAllAs(Label::class.java)
                .map { it.text }
        assertTrue("Connect adds the host to your known_hosts file." in labels)
        assertTrue("Fetching origin…" in labels)
        assertEquals("Unknown SSH Host", (pane.scene.window as Stage).title)
        assertLayout(robot, pane)
        assertSame(stage, (pane.scene.window as Stage).owner, "owned by the window")
        assertTrue(cancel.isDefaultButton)
        assertFalse(connect.isDefaultButton)
        WaitForAsyncUtils.waitFor(
            10,
            TimeUnit.SECONDS,
        ) { WaitForAsyncUtils.asyncFx<Boolean> { cancel.isFocused }.get() }

        // What Enter does in a dialog: fires the default button.
        val enter =
            WaitForAsyncUtils
                .asyncFx<Button> {
                    pane.lookupAll(".button").filterIsInstance<Button>().first { it.isDefaultButton }
                }.get()
        assertSame(cancel, enter)
        robot.interact { enter.fire() }
        assertFalse(trusted.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `Connect trusts the host`(robot: FxRobot) {
        val trusted = inBackground { ui().confirmHost(HostKeyRequest(hostText)) }
        val pane = dialogPane(robot)

        robot.interact { button(pane, pane.buttonTypes.first { it.text == "Connect" }).fire() }

        assertTrue(trusted.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `interrupting the waiting thread closes the open dialog`(robot: FxRobot) {
        val ended = CompletableFuture<Throwable?>()
        val waiting =
            Thread {
                try {
                    ui().askPassphrase(passphraseRequest())
                    ended.complete(null)
                } catch (e: InterruptedException) {
                    ended.complete(e)
                }
            }
        waiting.start()
        val window = dialogPane(robot).scene.window

        waiting.interrupt()

        assertTrue(ended.get(10, TimeUnit.SECONDS) is InterruptedException)
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            WaitForAsyncUtils.asyncFx<Boolean> { !window.isShowing }.get()
        }
    }

    @Test
    fun `asked on the FX thread it answers Cancel at once instead of waiting for itself`(robot: FxRobot) {
        val answers =
            WaitForAsyncUtils
                .asyncFx<List<Any?>> {
                    listOf(ui().askPassphrase(passphraseRequest()), ui().confirmHost(HostKeyRequest(hostText)))
                }.get(10, TimeUnit.SECONDS)

        assertEquals(listOf<Any?>(null, false), answers)
    }

    @Test
    fun `the info alerts are owned by the window and say what failed`(robot: FxRobot) {
        ui().notSaved("needs secret-tool")
        val saved = dialogPane(robot)
        assertEquals("Passphrase Not Saved", (saved.scene.window as Stage).title)
        assertEquals(
            "The passphrase stays in memory until Thoreau quits, but could not be saved: needs secret-tool",
            saved.contentText,
        )
        assertNull(saved.headerText, "no default header such as Message")
        assertSame(stage, (saved.scene.window as Stage).owner)
        robot.interact { button(saved, ButtonType.OK).fire() }

        ui().notAddedToAgent("Could not open a connection to your authentication agent.")
        val added = dialogPane(robot)
        assertEquals("Key Not Added to SSH Agent", (added.scene.window as Stage).title)
        assertEquals(
            "The key was not added to the ssh agent: Could not open a connection to your authentication agent.",
            added.contentText,
        )
        assertNull(added.headerText, "no default header such as Message")
        robot.interact { button(added, ButtonType.OK).fire() }
    }

    /** The content and its texts ask for their preferred height (Linux sizes the window before it is known). */
    private fun assertLayout(
        robot: FxRobot,
        pane: DialogPane,
    ) {
        val content = pane.content as javafx.scene.layout.Region
        assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, content.minHeight, "content")
        assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, pane.minHeight, "dialog pane")
        val labels =
            robot
                .from(content)
                .lookup(".label")
                .queryAllAs(Label::class.java)
                .toList()
        assertTrue(labels.isNotEmpty())
        labels.forEach {
            assertTrue(it.isWrapText, "wrapped: ${it.text}")
            assertEquals(460.0, it.maxWidth, "width of ${it.text}")
            assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, it.minHeight, "height of ${it.text}")
        }
    }

    @Test
    fun `without a window the dialogs and alerts are shown unowned`(robot: FxRobot) {
        val ui = FxSshPromptUi({ null }, { "Fetching origin…" })
        val reply = inBackground { ui.askPassphrase(passphraseRequest()) }
        val pane = dialogPane(robot)
        assertNull((pane.scene.window as Stage).owner)
        robot.interact { button(pane, ButtonType.CANCEL).fire() }
        assertNull(reply.get(10, TimeUnit.SECONDS))

        ui.notSaved("needs secret-tool")
        val alert = dialogPane(robot)
        assertNull((alert.scene.window as Stage).owner)
        robot.interact { button(alert, ButtonType.OK).fire() }
    }
}
