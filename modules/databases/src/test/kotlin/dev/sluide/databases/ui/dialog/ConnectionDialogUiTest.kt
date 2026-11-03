package dev.sluide.databases.ui.dialog

import dev.sluide.databases.connect.CardColor
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.SslMode
import dev.sluide.databases.ui.PasswordPrompt
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.DialogPane
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dialog's content in a scene: its controls feed the form, Test Connection ignores stale results and OK follows
 * the validation. The rules are in [ConnectionFormTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ConnectionDialogUiTest {
    private val pending = mutableListOf<CompletableFuture<String>>()
    private val passwords = mutableListOf<CharArray?>()
    private val tester =
        ConnectionTester { _, password ->
            passwords += password
            CompletableFuture<String>().also { pending += it }
        }
    private val initial = ConnectionConfig("id1", "Local", DatabaseKind.POSTGRESQL)
    private lateinit var content: ConnectionDialogContent
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
    }

    private fun <T> fx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        val result = AtomicReference<T>()
        robot.interact { result.set(block()) }
        return result.get()
    }

    private fun show(
        robot: FxRobot,
        names: Set<String> = setOf("Local"),
        isNew: Boolean = false,
        config: ConnectionConfig = initial,
        saveReason: String? = null,
    ) {
        robot.interact {
            content = ConnectionDialogContent(config, isNew, names, tester, saveReason)
            stage.scene = Scene(content.node)
            stage.show()
        }
    }

    @Test
    fun `the fields become the edit`(robot: FxRobot) {
        show(robot)
        val result =
            assertNotNull(
                fx(robot) {
                    content.nameField.text = "Prod"
                    content.kindBox.value = DatabaseKind.MARIADB
                    content.hostField.text = "db"
                    content.databaseField.text = "app"
                    content.userField.text = "me"
                    content.passwordField.text = "secret"
                    content.readOnlyBox.isSelected = true
                    content.commitBox.value = CommitMode.MANUAL
                    content.sslBox.value = SslMode.PREFER
                    content.colorToggles.getValue(CardColor.GREEN).isSelected = true
                    content.addPropertyButton.fire()
                    content.propertyRows
                        .last()
                        .key
                        .set("k")
                    content.propertyRows
                        .last()
                        .value
                        .set("v")
                    content.edit()
                },
            )
        assertEquals(
            ConnectionConfig(
                "id1",
                "Prod",
                DatabaseKind.MARIADB,
                host = "db",
                port = 3306,
                database = "app",
                user = "me",
                readOnly = true,
                commitMode = CommitMode.MANUAL,
                sslMode = SslMode.PREFER,
                driverProperties = mapOf("k" to "v"),
                color = CardColor.GREEN,
            ),
            result.config,
        )
        assertContentEquals("secret".toCharArray(), result.password)
        assertTrue(fx(robot) { content.sslHint.isVisible })
        assertEquals("jdbc:mariadb://db:3306", fx(robot) { content.urlField.text })
        robot.interact { content.sslBox.value = SslMode.REQUIRE }
        assertFalse(fx(robot) { content.sslHint.isVisible }, "the hint is for Prefer only")
    }

    @Test
    fun `an edit of a full config returns the same config`(robot: FxRobot) {
        val full =
            ConnectionConfig(
                "id7",
                "Local",
                DatabaseKind.POSTGRESQL,
                host = "h",
                port = 6543,
                database = "d",
                user = "u",
                readOnly = true,
                commitMode = CommitMode.MANUAL,
                sslMode = SslMode.VERIFY_CA,
                caFile = "/etc/ca.pem",
                customUrl = "jdbc:postgresql://other:1/x",
                driverProperties = mapOf("a" to "1", "b" to "2"),
                color = CardColor.PURPLE,
                showSystemSchemas = true,
            )
        robot.interact {
            content = ConnectionDialogContent(full, false, setOf("Local"), tester)
            stage.scene = Scene(content.node)
            stage.show()
        }
        assertEquals(full, fx(robot) { content.edit()?.config })
        assertNull(fx(robot) { content.problem.get() })
    }

    @Test
    fun `the Save password box follows the saved password and the reason`(robot: FxRobot) {
        show(robot)
        assertFalse(fx(robot) { content.savePasswordBox.isSelected })
        assertEquals("Asked on connect when empty", fx(robot) { content.passwordField.promptText })
        assertEquals(false, fx(robot) { content.edit() }?.savePassword)

        show(robot, config = initial.copy(savedPassword = "v1:x"))
        assertTrue(fx(robot) { content.savePasswordBox.isSelected })
        assertEquals("Saved; type to replace", fx(robot) { content.passwordField.promptText })
        assertEquals(true, fx(robot) { content.edit() }?.savePassword)
        robot.interact { content.savePasswordBox.isSelected = false }
        assertEquals(false, fx(robot) { content.edit() }?.savePassword)

        show(robot, config = initial.copy(savedPassword = "v1:x"), saveReason = "no key store")
        assertTrue(fx(robot) { content.savePasswordBox.isDisable })
        assertEquals("Save password (no key store)", fx(robot) { content.savePasswordBox.text })
    }

    @Test
    fun `the connect prompt shows Save password and answers with its state`(robot: FxRobot) {
        val prompt = fx(robot) { PasswordPrompt(initial, "no key store", true) }
        assertTrue(fx(robot) { prompt.saveBox.isSelected && prompt.saveBox.isDisable })
        assertEquals("Save password (no key store)", fx(robot) { prompt.saveBox.text })

        val plain = fx(robot) { PasswordPrompt(initial, null, false) }
        assertFalse(fx(robot) { plain.saveBox.isSelected || plain.saveBox.isDisable })
        val unticked =
            fx(robot) {
                plain.field.text = "pw"
                plain.answer()
            }
        assertFalse(unticked.save)
        assertContentEquals("pw".toCharArray(), unticked.password)
        assertEquals("", fx(robot) { plain.field.text })

        val ticked =
            fx(robot) {
                plain.field.text = "pw2"
                plain.saveBox.isSelected = true
                plain.answer()
            }
        assertTrue(ticked.save)
        assertEquals("", fx(robot) { plain.field.text })
    }

    @Test
    fun `an empty password is null and the custom URL is editable from the generated one`(robot: FxRobot) {
        show(robot)
        val edit =
            fx(robot) {
                content.customUrlBox.isSelected = true
                content.urlField.text = content.urlField.text + "/x"
                content.edit()
            }
        assertNull(edit?.password)
        assertEquals("jdbc:postgresql://localhost:5432/x", edit?.config?.customUrl)
        assertNull(
            fx(robot) {
                content.customUrlBox.isSelected = false
                content.edit()?.config?.customUrl
            },
        )
    }

    @Test
    fun `a duplicate name shows the reason and refuses the edit`(robot: FxRobot) {
        show(robot, names = setOf("Local", "Other"))
        robot.interact { content.nameField.text = "other" }
        assertEquals("A connection named 'other' exists.", fx(robot) { content.problem.get() })
        assertNull(fx(robot) { content.edit() })
        assertTrue(fx(robot) { content.testButton.isDisable })
    }

    @Test
    fun `the dialog disables OK for a taken name and enables it for a valid one`(robot: FxRobot) {
        robot.interact {
            Platform.runLater {
                ConnectionDialog.show(null, initial.copy(name = "Local"), true, setOf("Local"), tester)
            }
        }
        val pane = robot.lookup(".dialog-pane").queryAs(DialogPane::class.java)
        val ok = pane.buttonTypes.first { it.buttonData == ButtonData.OK_DONE }
        assertTrue(fx(robot) { pane.lookupButton(ok).isDisable }, "taken name")
        robot.interact { robot.lookup("#nameField").queryAs(TextField::class.java).text = "Fresh" }
        assertFalse(fx(robot) { pane.lookupButton(ok).isDisable }, "valid name")
        robot.interact { pane.scene.window.hide() }
    }

    @Test
    fun `test connection shows the result and ignores a stale one`(robot: FxRobot) {
        show(robot)
        robot.interact { content.passwordField.text = "pw" }
        robot.interact { content.testButton.fire() }
        robot.interact { content.testButton.fire() }
        assertEquals(2, pending.size)
        assertNotNull(fx(robot) { content.testResultLabel.graphic }, "a spinner while it runs")

        robot.interact { pending[1].complete("PostgreSQL 17.2") }
        robot.interact { pending[0].completeExceptionally(IllegalStateException("late")) }
        robot.interact { }
        assertEquals("Connected: PostgreSQL 17.2", fx(robot) { content.testResultLabel.text })
        assertNull(fx(robot) { content.testResultLabel.graphic })
        assertTrue(passwords.all { p -> p != null && p.all { it == '\u0000' } }, "the tester's copy is zeroed")

        robot.interact { content.testButton.fire() }
        robot.interact { pending[2].completeExceptionally(RuntimeException("refused\nsecond line")) }
        robot.interact { }
        assertEquals("refused", fx(robot) { content.testResultLabel.text })
        assertEquals("refused\nsecond line", fx(robot) { content.testResultLabel.tooltip.text })
        assertTrue(fx(robot) { "connection-error" in content.testResultLabel.styleClass })
    }
}
