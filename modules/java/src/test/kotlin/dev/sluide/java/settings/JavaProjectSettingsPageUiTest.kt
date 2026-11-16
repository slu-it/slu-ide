package dev.sluide.java.settings

import dev.sluide.java.JavaHomes
import dev.sluide.java.JavaProjectSettings
import dev.sluide.projectdata.ProjectDataStore
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real Java project page: its combo box shows the model's entries and Other… reaches the model with the chosen
 * folder; the rules are in [JavaProjectSettingsPageModelTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JavaProjectSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val root = StackPane()
    private val fx = ArrayDeque<Runnable>()
    private var shellFails = false
    private var picked: Path? = null
    private var askedFrom: Window? = null

    private val project: Path by lazy { Files.createDirectories(dir.resolve("work/app")) }
    private val thoreau: Path by lazy { jdk(dir.resolve("thoreau-jdk"), "25.0.1") }
    private val settings by lazy {
        JavaProjectSettings(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())) {
            it.run()
        }
    }
    private val page by lazy {
        JavaProjectSettingsPage(
            settings,
            JavaHomes(
                { _, _ ->
                    // Stands for any unchecked failure while loading; the real loginShellPrints returns null.
                    if (shellFails) error("no shell")
                    ""
                },
                emptyMap(),
                thoreau,
            ),
            dir.resolve("home"),
            false,
            { it.run() },
            { fx += it },
            { owner ->
                askedFrom = owner
                picked
            },
        )
    }

    private fun jdk(
        home: Path,
        version: String,
    ): Path {
        home
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("#!/bin/sh\n")
        home.resolve("release").writeText("JAVA_VERSION=\"$version\"\n")
        return home
    }

    /** The text the closed combo box paints, after a layout pass. */
    private fun painted(robot: FxRobot): String? {
        var text: String? = null
        robot.interact {
            page.combo.applyCss()
            page.combo.layout()
            text = page.combo.buttonCell.text
        }
        return text
    }

    @Test
    fun `a failed look for JDKs stops painting the loading text and enables the field`(robot: FxRobot) {
        shellFails = true
        robot.interact {
            root.children.setAll(page.node)
            page.load(project)
        }
        assertEquals("Looking for JDKs…", painted(robot))

        runFx(robot)

        assertFalse(page.combo.isDisabled)
        assertEquals(null, painted(robot), "nothing to show, and not the loading text")
    }

    @Test
    fun `Other opens the chooser after the event that selected it, with the popup hidden`(robot: FxRobot) {
        robot.interact {
            root.children.setAll(page.node)
            page.load(project)
        }
        runFx(robot)
        picked = null
        var askedDuring: Window? = null
        var showingDuring = false
        robot.interact {
            page.combo.show()
            page.combo.value = page.combo.items.last()
            askedDuring = askedFrom
            showingDuring = page.combo.isShowing
        }

        assertEquals(null, askedDuring, "not inside the selecting event")
        assertFalse(showingDuring, "the popup has hidden")
        assertEquals(page.combo.scene.window, askedFrom, "but right after it")
    }

    private fun runFx(robot: FxRobot) = robot.interact { while (fx.isNotEmpty()) fx.removeFirst().run() }

    @Start
    fun start(window: Stage) {
        window.scene = Scene(root, 700.0, 300.0)
        window.show()
    }

    @Test
    fun `the combo box shows the model's entries and its error shows a rejected Other folder`(robot: FxRobot) {
        robot.interact {
            root.children.setAll(page.node)
            page.load(project)
        }

        assertTrue(page.combo.isDisabled, "disabled while looking for JDKs")
        assertEquals("Looking for JDKs…", painted(robot), "what the field shows while loading")
        runFx(robot)
        assertEquals(
            page.model.selected
                .get()
                ?.text,
            painted(robot),
        )
        robot.interact { page.load(project) }
        assertEquals("Looking for JDKs…", painted(robot), "also when it is opened again after a value was shown")
        runFx(robot)

        assertEquals(page.model.choices.map { it.text }, page.combo.items.map { it.text })
        assertEquals(page.model.selected.get(), page.combo.value)
        assertFalse(page.combo.isDisabled)
        val error = root.lookup("#java-project-jdk-error") as Label
        assertFalse(error.isVisible)
        assertFalse(error.isManaged)

        val notJdk = Files.createDirectories(dir.resolve("plain"))
        picked = notJdk
        robot.interact { page.combo.value = page.combo.items.last() }
        runFx(robot)

        assertEquals("$notJdk isn't a JDK: no bin/java or release file", error.text)
        assertTrue(error.isVisible)
        assertEquals(page.model.choices.first(), page.combo.value, "back to the previous entry")
        assertEquals(page.combo.scene.window, askedFrom, "the dialog's window owns the chooser")
    }
}
