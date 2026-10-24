package dev.sluide.httpclient.envedit

import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The environment table's actions: variables, types, environments, auth configurations, certificates. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class EnvironmentTableActionsUiTest : EnvironmentFileViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val questions = mutableListOf<String>()

    private fun EnvironmentFileView.answer(yes: Boolean) {
        form.confirm = { question ->
            questions += question
            yes
        }
    }

    private fun EnvironmentFileView.fire(
        robot: FxRobot,
        button: Button,
    ) {
        robot.interact { button.fire() }
    }

    @Test
    fun `adds a variable to every environment, refusing taken names`(robot: FxRobot) {
        val view = open(robot, """{"dev": {"host": "a"}, "prod": {}}""")
        var problem: String? = null
        view.form.askVariable = { title, withName, validate ->
            assertEquals("Add Variable", title)
            assertTrue(withName)
            problem = validate(NewVariable("host", ValueType.TEXT))
            NewVariable("timeout", ValueType.NUMBER)
        }

        view.fire(robot, view.form.addVariableButton)

        assertEquals("\"host\" already exists", problem)
        assertEquals(
            json(
                """
                {
                  "dev": {
                    "host": "a",
                    "timeout": 0
                  },
                  "prod": {
                    "timeout": 0
                  }
                }
                """,
            ),
            view.text,
        )
        assertEquals(path("timeout"), (view.form.tree.selectionModel.selectedItem.value as EnvEntry.Item).row.path)
    }

    @Test
    fun `adds a child to the selected object and an entry to the selected list`(robot: FxRobot) {
        val view = open(robot, """{"dev": {"db": {"user": "u"}, "hosts": ["a"]}}""")
        view.form.askVariable = { title, withName, _ ->
            if (withName) {
                assertEquals("Add Variable to db", title)
                NewVariable("password", ValueType.TEXT)
            } else {
                assertEquals("Add Entry to hosts", title)
                NewVariable("", ValueType.YES_NO)
            }
        }

        view.select(robot, path("db"))
        view.fire(robot, view.form.addVariableButton)
        view.select(robot, path("hosts"))
        view.fire(robot, view.form.addVariableButton)

        assertEquals(
            json(
                """
                {
                  "dev": {
                    "db": {
                      "user": "u",
                      "password": ""
                    },
                    "hosts": [
                      "a",
                      false
                    ]
                  }
                }
                """,
            ),
            view.text,
        )
        assertEquals("[1]", view.row(path("hosts").index(1)).label)
    }

    @Test
    fun `changes a type, asking first when values would be lost`(robot: FxRobot) {
        val view = open(robot, """{"dev": {"port": "8080", "name": "x"}}""")
        val original = view.text

        fun choose(
            path: ValuePath,
            type: ValueType,
        ) {
            view.cell(robot, path, "dev")
            val cell =
                robot
                    .lookup(".tree-table-cell")
                    .queryAllAs(TypeCell::class.java)
                    .filterIsInstance<TypeCell>()
                    .first { (it.item as? EnvEntry.Item)?.row?.path == path }
            assertEquals("Text", cell.text)
            assertNull(cell.choice)
            robot.interact { view.form.tree.edit(view.form.tree.getRow(view.item(path)), cell.tableColumn) }
            robot.interact { assertNotNull(cell.choice).value = type }
            assertNull(cell.choice)
        }

        view.answer(yes = false)
        choose(path("name"), ValueType.NUMBER)
        assertEquals(1, questions.size)
        assertEquals(original, view.text)
        assertEquals(ValueType.TEXT, view.row(path("name")).type)

        choose(path("port"), ValueType.NUMBER)
        assertEquals(1, questions.size)
        assertTrue("\"port\": 8080," in view.text, view.text)

        view.answer(yes = true)
        choose(path("name"), ValueType.NUMBER)
        assertEquals(2, questions.size)
        assertTrue("\"name\": 0" in view.text, view.text)
    }

    @Test
    fun `removes a row after asking`(robot: FxRobot) {
        val view = open(robot, """{"dev": {"a": 1, "b": 2}}""", """{"dev": {"a": 3}}""")
        view.answer(yes = true)

        view.select(robot, path("a"))
        view.fire(robot, view.form.removeButton)

        assertEquals(listOf("Remove \"a\" from all environments?"), questions)
        assertFalse("\"a\"" in view.text)
        assertFalse("\"a\"" in tab(privateFile).orEmpty())
    }

    @Test
    fun `adds, renames and removes environments`(robot: FxRobot) {
        val view = open(robot, """{"dev": {"a": 1}}""", """{"dev": {"b": 2}}""")
        var problem: String? = null
        view.form.askName = { title, initial, validate ->
            if (title == "Add Environment") {
                problem = validate("dev")
                "stage"
            } else {
                assertEquals("dev", initial)
                "local"
            }
        }
        view.answer(yes = true)

        view.fire(robot, view.addEnvironment)
        assertEquals("\"dev\" already exists", problem)
        assertEquals(listOf("dev", "stage"), view.form.table.environments)

        val dev =
            view.form.tree.columns
                .first { it.text == "dev" }
        robot.interact { dev.contextMenu.items[0].fire() }
        assertEquals(listOf("local", "stage"), view.form.table.environments)
        assertTrue("\"local\"" in tab(privateFile).orEmpty())

        val stage =
            view.form.tree.columns
                .first { it.text == "stage" }
        robot.interact { stage.contextMenu.items[1].fire() }
        assertEquals(listOf("Remove the environment \"stage\" from both files?"), questions)
        assertEquals(listOf("local"), view.form.table.environments)
        assertEquals(
            listOf("Name", "Type", "local"),
            view.form.tree.columns
                .map { it.text },
        )
    }

    @Test
    fun `adds an auth configuration with its secret in the private file`(robot: FxRobot) {
        val view = open(robot, """{"dev": {}, "prod": {}}""")
        view.form.askAuth = { environments, validate ->
            assertEquals(listOf("dev", "prod"), environments)
            assertEquals("Choose at least one environment", validate(NewAuth("api", "Client Credentials", emptyList())))
            assertEquals(
                "An auth configuration needs an id",
                validate(NewAuth(" ", "Client Credentials", environments)),
            )
            NewAuth("api", "Client Credentials", listOf("dev"))
        }

        view.fire(robot, view.form.addAuthButton)

        assertTrue("\"Token URL\": \"\"" in view.text, view.text)
        assertFalse("Client Secret" in view.text)
        assertTrue("\"Client Secret\": \"\"" in tab(privateFile).orEmpty(), tab(privateFile))
        val group = (view.item("auth:api").value as EnvEntry.Auth).group
        assertEquals("OAuth2 · Client Credentials", EnvTexts.authSummary(group))
        assertEquals(setOf("dev"), EnvTexts.authEnvironments(group))
    }

    @Test
    fun `adds an auth configuration to another environment from its context menu`(robot: FxRobot) {
        val view =
            open(
                robot,
                """{"dev": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Password"}}}}, "prod": {}}""",
            )
        val menu = view.form.menuItems(view.item("auth:api").value, null)
        assertEquals(listOf("Remove configuration", "Add to prod"), menu.map { it.text })

        robot.interact { menu[1].fire() }

        val group = (view.item("auth:api").value as EnvEntry.Auth).group
        assertEquals(setOf("dev", "prod"), EnvTexts.authEnvironments(group))
        assertTrue("\"Password\": \"\"" in tab(privateFile).orEmpty(), tab(privateFile))
    }

    @Test
    fun `adds a client certificate and edits its format and file`(robot: FxRobot) {
        val view = open(robot, """{"dev": {}}""", "{}\n")
        view.form.chooseFile = { folder ->
            assertEquals(dir.toAbsolutePath().normalize(), folder)
            folder.resolve("certs/client.pem")
        }

        view.fire(robot, view.form.addCertificateButton)
        assertTrue(view.form.addCertificateButton.isDisable)
        assertNotNull(view.form.table.certificate)

        val format = view.edit(robot, EnvSchema.formatPath(CertificatePart.CERTIFICATE), "dev")

        @Suppress("UNCHECKED_CAST")
        val choice = format.graphic as ComboBox<String>
        robot.interact { choice.value = "PEM" }
        assertTrue("\"format\": \"PEM\"" in tab(privateFile).orEmpty(), tab(privateFile))

        val file = view.edit(robot, EnvSchema.certificatePath(CertificatePart.CERTIFICATE), "dev")
        robot.interact { ((file.graphic as HBox).lookup(".env-browse") as Button).fire() }
        assertTrue("\"path\": \"certs/client.pem\"" in tab(privateFile).orEmpty(), tab(privateFile))
        assertEquals("""{"dev": {}}""", view.text)
    }

    @Test
    fun `creates a value an environment lacks when it is edited`(robot: FxRobot) {
        val view = open(robot, """{"dev": {"a": "x"}, "prod": {}}""")

        view.type(robot, path("a"), "prod", "y")

        assertTrue("\"prod\": {\n    \"a\": \"y\"" in view.text, view.text)
        assertNull(view.form.tree.editingCell)
    }

    private fun EnvironmentFileView.texts(
        entry: EnvEntry,
        env: String?,
    ) = form.menuItems(entry, env).map { it.text }

    @Test
    fun `the cell menu removes the value of an environment and moves it to the other file`(robot: FxRobot) {
        val view = open(robot, """{"dev": {"host": "a"}, "prod": {}}""", """{"dev": {"token": "t"}}""")
        val host = view.item(path("host")).value
        val token = view.item(path("token")).value

        assertEquals(listOf("Remove from dev", "Make private", "Remove row"), view.texts(host, "dev"))
        assertEquals(listOf("Remove from dev", "Make public", "Remove row"), view.texts(token, "dev"))
        assertEquals(listOf("Remove row"), view.texts(host, "prod"), "prod has no value to remove or move")
        assertEquals(listOf("Remove row"), view.texts(host, null))

        robot.interact { view.form.menuItems(token, "dev")[1].fire() }

        assertTrue("\"token\": \"t\"" in view.text, view.text)
    }

    @Test
    fun `the certificate menu adds to and removes from each environment`(robot: FxRobot) {
        val view =
            open(
                robot,
                """{"dev": {}, "prod": {}}""",
                """{"dev": {"SSLConfiguration": {"clientCertificate": "c.pem"}}, "prod": {}}""",
            )
        val certificate = view.item("certificate").value

        assertEquals(listOf("Remove from dev", "Add to prod"), view.texts(certificate, null))

        robot.interact { view.form.menuItems(certificate, null)[1].fire() }

        assertEquals(listOf("Remove from dev", "Remove from prod"), view.texts(view.item("certificate").value, null))
    }

    @Test
    fun `removes an auth configuration and the certificate from the buttons after asking`(robot: FxRobot) {
        val view =
            open(
                robot,
                """{"dev": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Password"}}}}}""",
                """{"dev": {"SSLConfiguration": {"clientCertificate": "c.pem"}}}""",
            )
        view.answer(yes = true)

        view.select(robot, "auth:api")
        view.fire(robot, view.form.removeButton)
        view.select(robot, "certificate")
        view.fire(robot, view.form.removeButton)

        assertEquals(
            listOf(
                "Remove the auth configuration \"api\" from all environments?",
                "Remove the client certificate from all environments?",
            ),
            questions,
        )
        assertFalse("Security" in view.text, view.text)
        assertFalse("SSLConfiguration" in tab(privateFile).orEmpty(), tab(privateFile))
    }

    @Test
    fun `chooses a value of an auth setting, also back to another one`(robot: FxRobot) {
        val view =
            open(
                robot,
                """{"dev": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Password"}}}}}""",
            )
        val group = (view.item("auth:api").value as EnvEntry.Auth).group
        val credentials = group.rows.first { it.label == "Client Credentials" }.path

        fun choose(value: String) {
            val cell = view.edit(robot, credentials, "dev")

            @Suppress("UNCHECKED_CAST")
            val choice = cell.graphic as ComboBox<String>
            robot.interact { choice.value = value }
        }

        choose("in body")
        assertTrue("\"Client Credentials\": \"in body\"" in view.text, view.text)
        choose("none")
        assertTrue("\"Client Credentials\": \"none\"" in view.text, view.text)
    }

    @Test
    fun `closing the choice list without a choice cancels the edit and shows an empty choice as none`(robot: FxRobot) {
        val view =
            open(
                robot,
                """{"dev": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Password"}}}}}""",
            )
        val group = (view.item("auth:api").value as EnvEntry.Auth).group
        val credentials = group.rows.first { it.label == "Client Credentials" }.path
        val original = view.text

        val cell = view.edit(robot, credentials, "dev")

        @Suppress("UNCHECKED_CAST")
        val choice = cell.graphic as ComboBox<String>
        assertEquals("", choice.value)
        assertEquals("(none)", choice.converter.toString(choice.value))
        assertEquals("", choice.converter.fromString("(none)"))
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { choice.isShowing }

        robot.interact { choice.hide() }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { view.form.tree.editingCell == null }

        assertEquals(original, view.text)
    }
}
