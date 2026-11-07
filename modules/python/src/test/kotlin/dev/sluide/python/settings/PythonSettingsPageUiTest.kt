package dev.sluide.python.settings

import dev.sluide.python.FAKE_ASSET
import dev.sluide.python.HttpAnswer
import dev.sluide.python.TyFakes
import dev.sluide.python.TyUpdates
import javafx.scene.Node
import javafx.scene.Scene
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
 * The real Python page: its buttons reach the page model and its lines follow the installer and the update check,
 * which only a created page shows; the model's rules are in [PythonSettingsPageModelTest]. Needs a display, see the
 * `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PythonSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { TyFakes(dir) }
    private val questions = mutableListOf<String>()
    private val opened = mutableListOf<URI>()
    private val checks = mutableListOf<Runnable>()
    private val latest =
        """
        {"tag_name": "0.1.0", "html_url": "https://github.com/astral-sh/ty/releases/tag/0.1.0", "prerelease": false,
         "assets": [{"name": "$FAKE_ASSET", "size": 1000, "digest": "sha256:${"c".repeat(64)}",
         "browser_download_url": "https://github.com/astral-sh/ty/releases/download/0.1.0/$FAKE_ASSET"}]}
        """.trimIndent()

    private val installer by lazy { fakes.installer() }

    private val page by lazy {
        val updates =
            TyUpdates(
                fakes.pin,
                "linux-aarch64",
                { HttpAnswer(200, emptyMap(), latest) },
                { checks += it },
                { it.run() },
            )
        val dialogs =
            object : PythonSettingsDialogs {
                override fun confirmDelete(question: String): Boolean {
                    questions += question
                    return false
                }

                override fun chooseExecutable(current: String): Path? = null
            }
        PythonSettingsPage(fakes.settings, installer, updates, dialogs).apply { openWebPage = { opened += it } }
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
        assertEquals(listOf("Languages", "Python"), page.path)
        robot.interact {
            root.children += page.node
            page.load()
        }
        val w = page.widgets
        assertEquals("Not installed (14 MB download)", w.status.text)
        assertFalse(shown(w.rollbackButton))
        assertFalse(shown(w.updateButton))
        assertTrue(w.deleteButton.isDisable)

        robot.interact {
            w.installButton.fire()
            fakes.runQueued()
        }
        assertEquals("Installed: ty 0.0.99", w.status.text)
        assertTrue(w.installButton.isDisable)

        robot.interact { w.checkButton.fire() }
        assertEquals("Checking for updates…", w.result.text)
        robot.interact { checks.removeFirst().run() }
        assertEquals("ty 0.1.0 is available, newer than tested with this Thoreau.", w.result.text)
        assertTrue(shown(w.updateButton) && shown(w.releaseNotes))

        robot.interact { w.releaseNotes.fire() }
        assertEquals(listOf(URI("https://github.com/astral-sh/ty/releases/tag/0.1.0")), opened)

        robot.interact {
            w.updateButton.fire()
            fakes.runQueued()
        }
        assertEquals("Installed: ty 0.1.0 (this Thoreau was tested with 0.0.99)", w.status.text)
        assertTrue(shown(w.rollbackButton))
        assertEquals("Roll Back to 0.0.99", w.rollbackButton.text)
        assertFalse(shown(w.result), "the result went with the version it was about")

        robot.interact {
            w.rollbackButton.fire()
            fakes.runQueued()
        }
        assertEquals("Installed: ty 0.0.99", w.status.text)

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
        assertEquals("Installed: ty 0.0.99", w.status.text)
        assertFalse(shown(w.pinButton))

        robot.interact {
            w.customRadio.fire()
            w.customField.text = "ty"
        }
        assertFalse(w.customField.isDisable)
        assertEquals("Use an absolute path.", w.customHint.text)
        robot.interact { page.apply() }
        assertEquals("ty", fakes.settings.tyPath.get())

        robot.interact {
            w.downloadRadio.fire()
            page.apply()
        }
        assertEquals("", fakes.settings.tyPath.get())
        assertTrue(w.customField.isDisable)
    }
}
