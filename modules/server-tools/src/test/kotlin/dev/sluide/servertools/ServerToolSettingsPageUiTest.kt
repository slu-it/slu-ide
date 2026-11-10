package dev.sluide.servertools

import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real page of a server tool: its buttons reach the page model and its lines follow the installer and the update
 * check, which only a created page shows; the model's rules are in [ServerToolPageModelTest]. Needs a display, see
 * the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ServerToolSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { ServerToolFakes(acmeTool(dir)) }
    private val setting by lazy { acmeSetting(dir) }
    private val questions = mutableListOf<String>()
    private val opened = mutableListOf<URI>()
    private val checks = mutableListOf<Runnable>()

    private val installer by lazy { ServerToolInstaller(fakes.tool, setting, fakes.tools()) }

    private val page by lazy {
        val updates = ToolUpdates(fakes.tool, FakeSource { acmeRelease("0.1.0") }, { checks += it }, { it.run() })
        val dialogs =
            object : ServerToolDialogs {
                override fun confirmDelete(question: String): Boolean {
                    questions += question
                    return false
                }

                override fun chooseExecutable(current: String): Path? = null
            }
        val texts = ServerToolPageTexts("Acme", "acme-ls, the language server for Acme", "Downloads only on click.")
        ServerToolSettingsPage(listOf("Languages", "Acme"), texts, setting, installer, updates, dialogs).apply {
            openWebPage = { opened += it }
        }
    }

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(root, 600.0, 400.0)
        stage.show()
    }

    private val root = StackPane()

    private fun shown(node: Node) = node.isVisible && node.isManaged

    @Test
    fun `the buttons act through the model and the lines follow the installer and the check`(robot: FxRobot) {
        assertEquals(listOf("Languages", "Acme"), page.path)
        robot.interact {
            root.children += page.node
            page.load()
        }
        val labels = robot.lookup(".label").queryAllAs(Label::class.java).map { it.text }
        assertTrue("acme-ls, the language server for Acme" in labels, labels.toString())
        assertTrue("acme-ls executable" in labels, labels.toString())
        assertTrue("Downloads only on click." in labels, labels.toString())
        val w = page.widgets
        assertEquals("Absolute path of a acme-ls executable", w.customField.promptText)
        assertEquals("Not installed (14 MB download)", w.status.text)
        assertFalse(shown(w.rollbackButton))
        assertFalse(shown(w.updateButton))
        assertTrue(w.deleteButton.isDisable)

        robot.interact {
            w.installButton.fire()
            fakes.runQueued()
        }
        assertEquals("Installed: acme-ls 0.0.99", w.status.text)
        assertTrue(w.installButton.isDisable)

        robot.interact { w.checkButton.fire() }
        assertEquals("Checking for updates…", w.result.text)
        robot.interact { checks.removeFirst().run() }
        assertEquals("acme-ls 0.1.0 is available, newer than tested with this Thoreau.", w.result.text)
        assertTrue(shown(w.updateButton) && shown(w.releaseNotes))

        robot.interact { w.releaseNotes.fire() }
        assertEquals(listOf(URI("https://example.com/acme/releases/0.1.0")), opened)

        robot.interact {
            w.updateButton.fire()
            fakes.runQueued()
        }
        assertEquals("Installed: acme-ls 0.1.0 (this Thoreau was tested with 0.0.99)", w.status.text)
        assertTrue(shown(w.rollbackButton))
        assertEquals("Roll Back to 0.0.99", w.rollbackButton.text)
        assertFalse(shown(w.result), "the result went with the version it was about")

        robot.interact {
            w.rollbackButton.fire()
            fakes.runQueued()
        }
        assertEquals("Installed: acme-ls 0.0.99", w.status.text)

        robot.interact { w.deleteButton.fire() }
        assertEquals(1, questions.size)

        robot.interact {
            installer.update(fakes.release("0.0.98"), null)
            fakes.runQueued()
        }
        assertTrue(shown(w.pinButton))
        assertEquals("Install 0.0.99 (tested with this Thoreau)", w.pinButton.text)
        robot.interact {
            w.pinButton.fire()
            fakes.runQueued()
        }
        assertEquals("Installed: acme-ls 0.0.99", w.status.text)
        assertFalse(shown(w.pinButton))

        robot.interact {
            w.customRadio.fire()
            w.customField.text = "acme-ls"
        }
        assertFalse(w.customField.isDisable)
        assertEquals("Use an absolute path.", w.customHint.text)
        robot.interact { page.apply() }
        assertEquals("acme-ls", setting.path.get())

        robot.interact {
            w.downloadRadio.fire()
            page.apply()
        }
        assertEquals("", setting.path.get())
        assertTrue(w.customField.isDisable)
    }
}
