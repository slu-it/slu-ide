package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.KdfParams
import dev.sluide.encryptedfiles.Sluenc
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettings
import dev.sluide.fileviewers.ContentFileViewer
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.settings.SettingsStore
import javafx.event.ActionEvent
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Labeled
import javafx.scene.control.TextInputControl
import javafx.scene.layout.HBox
import javafx.scene.text.Text
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.GenericStyledArea
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.fail

/**
 * What the UI tests of an encrypted file's view share: the [registry], the [settings], plain text views inside, small
 * Argon2 parameters, and helpers that read the views on the FX thread. Call [close] after each test.
 */
internal class EncryptedViewFixture {
    /** Where the tests put the views; the test's scene shows it. */
    val container = HBox()
    val registry = EncryptedFiles()

    private val fallback = DefaultTextFileViewer()
    private val fileViewers = FileViewers(listOf(fallback), fallback)
    private val settingsFile: Path = Files.createTempFile("sluenc-settings", ".properties")
    val settings = EncryptedFilesSettings(SettingsStore(settingsFile))

    val password = "correct horse battery"

    fun close() {
        settingsFile.deleteIfExists()
    }

    /** [read] on the FX thread, where the views live. */
    fun <T> fx(read: () -> T): T = WaitForAsyncUtils.asyncFx<T>(read).get()

    /** Writes [plain] encrypted under [password] to [file]. */
    fun create(
        file: Path,
        plain: String,
    ): Path {
        val created = Sluenc.create(password.toCharArray(), plain.toByteArray(), KdfParams(64, 1, 1))
        created.key.close()
        file.writeBytes(created.bytes)
        return file
    }

    /** A locked view of [file] in the [container], whose idle time [time] drives. */
    fun open(
        file: Path,
        robot: FxRobot,
        time: FakeIdleTime = FakeIdleTime(),
        viewerFor: (String) -> ContentFileViewer? = fileViewers::contentViewerFor,
    ): EncryptedFileView {
        val view =
            fx {
                EncryptedFileView(file, registry, viewerFor, settings) { minutes, onIdle ->
                    IdleLock(minutes, onIdle, Duration.ofMinutes(1), { time.now }, time.scheduler)
                }
            }
        robot.interact { container.children += view.node }
        return view
    }

    /** Types [typed] into the locked pane, presses Enter and waits for the key derivation. */
    fun unlock(
        view: EncryptedFileView,
        robot: FxRobot,
        typed: String = password,
    ) {
        val field = fx { view.node.lookup(".encrypted-password") as TextInputControl }
        robot.interact {
            field.text = typed
            field.fireEvent(ActionEvent())
        }
        awaitFx { !view.isUnlocking }
    }

    fun awaitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!fx(condition)) {
            if (System.nanoTime() > deadline) fail("condition not met in time")
            // Poll interval while Argon2 runs on its thread.
            Thread.sleep(20)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** The inner view's text area; FX thread. */
    fun area(view: EncryptedFileView) = view.node.lookup(".code-area") as CodeArea

    /** The strip's message, or `null` while it shows none. */
    fun stripMessage(view: EncryptedFileView): String? =
        fx { (view.node.lookup(".encrypted-strip-message") as Labeled).takeIf { it.isVisible }?.text }

    /** The locked pane's message. */
    fun lockedMessage(view: EncryptedFileView): String? =
        fx { (view.node.lookup(".encrypted-message") as Labeled).text }

    fun decrypt(
        file: Path,
        with: String = password,
    ): String =
        Sluenc.unlock(file.readBytes(), with.toCharArray()).use { key ->
            String(Sluenc.decrypt(file.readBytes(), key))
        }

    /** Writes [plain] under the file's own key, as a save in another tab or process does. */
    fun saveElsewhere(
        file: Path,
        plain: String,
    ) {
        val bytes =
            Sluenc.unlock(file.readBytes(), password.toCharArray()).use { key ->
                Sluenc.encrypt(plain.toByteArray(), key)
            }
        file.writeBytes(bytes)
    }

    /** Writes the file as "theirs\n" under [newPassword], as Change Password in another tab does. */
    fun changePasswordElsewhere(
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

    /** Every text in the [container]'s scene graph: labels, inputs, text nodes and RichTextFX areas. */
    fun texts(): List<String> =
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
}
