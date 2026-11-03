package dev.sluide.secrets.settings

import dev.sluide.secrets.FakeKeyBackend
import dev.sluide.secrets.KeyRead
import dev.sluide.secrets.SavedSecretEntry
import dev.sluide.secrets.SavedSecretSource
import dev.sluide.secrets.Secrets
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real Secrets page: its buttons are the connection to [SecretsSettingsPageModel] (rules in
 * [SecretsSettingsPageModelTest]). Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SecretsSettingsPageUiTest {
    private val backend = FakeKeyBackend().also { it.stored = ByteArray(32) }
    private val secrets = Secrets(backend, SecureRandom())
    private val removed = mutableListOf<Set<String>>()
    private val source =
        object : SavedSecretSource {
            override val title = "HTTP client passphrases"

            override fun entries() = listOf(SavedSecretEntry("a", "client.pem", "/certs/client.pem"))

            override fun remove(ids: Set<String>): Boolean = removed.add(ids)
        }
    private val page = SecretsSettingsPage(secrets) { listOf(source) }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun <T> FxRobot.onFx(read: () -> T): T {
        var value: T? = null
        interact { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Test
    fun `Remove marks the entry, Undo unmarks it and Apply removes the marked ones`(robot: FxRobot) {
        robot.interact {
            page.node
            page.load()
        }
        assertEquals("Remove", robot.onFx { page.removeButtons.single().text })

        robot.interact { page.removeButtons.single().fire() }
        assertEquals("Undo", robot.onFx { page.removeButtons.single().text })
        assertTrue(robot.onFx { "strikethrough" in page.entryLabels.single().style })
        robot.interact { page.apply() }
        assertEquals(listOf(setOf("a")), removed)

        robot.interact { page.removeButtons.single().fire() }
        robot.interact { page.removeButtons.single().fire() }
        assertEquals("Remove", robot.onFx { page.removeButtons.single().text })
        robot.interact { page.apply() }
        assertEquals(1, removed.size, "an unmarked entry stays")
    }

    @Test
    fun `Forget All deletes the key after the question and shows the result`(robot: FxRobot) {
        robot.interact { page.node }
        page.confirmForgetAll = { false }
        robot.interact { page.forgetButton.fire() }
        assertEquals(0, backend.deletes)

        page.confirmForgetAll = { true }
        robot.interact { page.forgetButton.fire() }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { robot.onFx { page.statusLabel.text.isNotEmpty() } }

        assertEquals(1, backend.deletes)
        assertEquals("Saved secrets forgotten.", robot.onFx { page.statusLabel.text })
        assertFalse(robot.onFx { page.forgetButton.isDisable })
    }

    @Test
    fun `Try Again leaves the denied state and the button goes`(robot: FxRobot) {
        backend.onRead = { KeyRead.Denied("Keychain access denied") }
        secrets.decrypt("v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "x")
        robot.interact { page.node }
        assertEquals("Store: Fake Store, access denied", robot.onFx { page.storeLabel.text })
        val tryAgain =
            robot.onFx {
                page.content.lookupAll(".button").filterIsInstance<Button>().single {
                    it.text ==
                        "Try Again"
                }
            }

        robot.interact { tryAgain.fire() }

        assertEquals("Store: Fake Store", robot.onFx { page.storeLabel.text })
        assertTrue(
            robot.onFx {
                page.content.lookupAll(".button").filterIsInstance<Button>().none {
                    it.text ==
                        "Try Again"
                }
            },
        )
    }

    @Test
    fun `the Forget All question has Cancel as the default button and the focus`(robot: FxRobot) {
        lateinit var alert: Alert
        robot.interact {
            alert = forgetAllAlert(null)
            alert.show()
        }
        WaitForAsyncUtils.waitForFxEvents()
        val forget = robot.onFx { alert.dialogPane.buttonTypes.single { it.text == "Forget All" } }
        val forgetButton = robot.onFx { alert.dialogPane.lookupButton(forget) as Button }
        val cancel = robot.onFx { alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button }

        assertEquals("Forget All Saved Secrets", robot.onFx { alert.title })
        assertFalse(robot.onFx { forgetButton.isDefaultButton })
        assertTrue(robot.onFx { cancel.isDefaultButton })
        assertSame(cancel, robot.onFx { alert.dialogPane.scene.focusOwner })

        robot.interact { alert.close() }
    }
}
