package dev.sluide.httpclient.ui

import dev.sluide.httpclient.env.SslConfig
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.control.PasswordField
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The passphrase dialog for client certificates. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PassphrasePromptUiTest {
    private val config =
        SslConfig(
            SslConfig.CertificateFile(Path.of("certs", "client.pem"), null),
            SslConfig.CertificateFile(Path.of("certs", "client.key"), null),
            hasCertificatePassphrase = true,
            verifyHostCertificate = true,
        )

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 300.0, 200.0)
        stage.show()
    }

    /** Asks on a worker thread, as the request runner does. */
    private fun askInBackground(prompt: PassphrasePrompt = PassphrasePrompt()) =
        CompletableFuture.supplyAsync { prompt.ask(config) }

    private fun FxRobot.dialog(): DialogPane {
        WaitForAsyncUtils.waitFor(
            10,
            TimeUnit.SECONDS,
        ) { lookup(".http-passphrase").tryQuery<PasswordField>().isPresent }
        return lookup(".dialog-pane").queryAs(DialogPane::class.java)
    }

    @Test
    fun `the dialog answers the waiting worker`(robot: FxRobot) {
        val answer = askInBackground()

        val dialog = robot.dialog()
        assertEquals("Passphrase for client.key", dialog.headerText)
        assertFalse(answer.isDone)
        val field = robot.lookup(".http-passphrase").queryAs(PasswordField::class.java)
        robot.interact {
            field.text = "secret"
            (dialog.lookupButton(ButtonType.OK) as Button).fire()
        }

        assertContentEquals("secret".toCharArray(), answer.get(10, TimeUnit.SECONDS))
        assertEquals("", field.text, "the field forgets the passphrase")
    }

    @Test
    fun `cancelling answers null`(robot: FxRobot) {
        val answer = askInBackground()

        val dialog = robot.dialog()
        robot.interact { (dialog.lookupButton(ButtonType.CANCEL) as Button).fire() }

        assertNull(answer.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `never waits on the FX thread`() {
        val asked = CountDownLatch(1)
        val prompt =
            PassphrasePrompt(dialog = {
                asked.countDown()
                error("not shown")
            })

        assertNull(WaitForAsyncUtils.asyncFx<CharArray?> { prompt.ask(config) }.get(10, TimeUnit.SECONDS))
        assertEquals(1, asked.count)
    }

    @Test
    fun `an interrupted worker stops waiting and the dialog closes`(robot: FxRobot) {
        val worker =
            Thread.ofVirtual().start {
                try {
                    PassphrasePrompt().ask(config)
                } catch (_: InterruptedException) {
                }
            }
        robot.dialog()

        worker.interrupt()

        assertTrue(worker.join(Duration.ofSeconds(10)))
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) {
            robot.lookup(".http-passphrase").tryQuery<PasswordField>().isEmpty
        }
    }
}
