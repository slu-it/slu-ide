package dev.sluide.jshell.view

import dev.sluide.jshell.FakeAgent
import javafx.application.ColorScheme
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JDK menu, the banners and the look of the toolbar of [JShellView], with fake agents. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JShellViewJdkUiTest : JShellViewUiFixture() {
    @Start
    fun start(stage: Stage) = startWindow(stage)

    private fun banner(view: JShellView): Node? = view.node.lookup(".jshell-banner")

    @Test
    fun `choosing a JDK in the menu remembers it, ends the session on the other JDK and shows the choice`(
        robot: FxRobot,
    ) {
        projectSetting = jdkHome(25, "25.0.5")
        val first = FakeAgent.of("value")
        agents.put(first)
        val view = open(robot, "int x = 5;")
        waitUntil("the JDK") { view.jdkButton.text == "JDK 25 · project setting" }
        robot.interact { view.runButton.fire() }
        waitUntil("the value") { "x ==> 5" in view.transcript.area.text }

        robot.interact { view.jdkButton.show() }
        waitUntil("the items") { view.jdkButton.items.size == 3 }
        val items = fx { view.jdkButton.items.toList() }
        assertEquals("Project JDK (JDK 25 · project setting)", fx { items[0].text })
        assertTrue(fx { (items[0] as RadioMenuItem).isSelected })
        assertTrue(fx { view.jdkButton.tooltip.text }.startsWith("JDK 25.0.5 in "))
        val twentyOne = fx { items.single { it.text.startsWith("JDK 21") } as RadioMenuItem }
        robot.interact { twentyOne.fire() }
        // RadioMenuItem.fire() does not select (checked in this JavaFX): the group is what makes the choice exclusive
        val groups = fx { items.map { (it as RadioMenuItem).toggleGroup } }
        assertTrue(groups.all { it != null && it === groups[0] }, "one group: $groups")

        waitUntil("the new JDK") { view.jdkButton.text == "JDK 21 · chosen" }
        assertEquals(jdkHome(21, "21.0.13.1"), jdks.remembered(view.file, dir))
        assertTrue(first.waitFor(10_000), "the session on JDK 25 ended")

        robot.interact { view.jdkButton.hide() }
        robot.interact { view.jdkButton.show() }
        waitUntil("the items again") { selectedOf(view.jdkButton.items)?.startsWith("JDK 21") == true }
        robot.interact { view.jdkButton.hide() }
    }

    private fun selectedOf(items: List<MenuItem>): String? =
        items.filterIsInstance<RadioMenuItem>().singleOrNull { it.isSelected }?.text

    @Test
    fun `a view without a window has its controls laid out, disabled, and says it looks for the JDK`(robot: FxRobot) {
        val view = open(robot, "int x = 5;", withHost = false)

        // Restart and Clear work without a window, the menu has nothing to look up
        robot.interact {
            view.restartButton.fire()
            view.clearButton.fire()
            view.jdkButton.show()
        }
        assertNull(fx { view.closeWarning })
        assertEquals(listOf("Looking for JDKs…"), fx { view.jdkButton.items.map { it.text } })
        assertTrue(
            fx {
                view.jdkButton.items
                    .single()
                    .isDisable
            },
        )
        robot.interact { view.jdkButton.hide() }

        fx {
            assertEquals("JDK…", view.jdkButton.text)
            assertEquals("Looking for the JDK", view.jdkButton.tooltip.text)
            assertTrue("jshell-jdk" in view.jdkButton.styleClass)
            assertTrue(!view.jdkButton.isFocusTraversable)
            assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, view.jdkButton.minWidth)
            assertEquals(listOf<Node>(view.jdkButton) + view.split.modeButtons, view.toolbar.trailing.toList())
            assertTrue(view.runButton.isDisable && view.runAllButton.isDisable && view.stopButton.isDisable)
            assertTrue("jshell-view" in view.node.styleClass)
            assertTrue(
                (view.node as javafx.scene.layout.Region)
                    .stylesheets
                    .single()
                    .endsWith("jshell-view.css"),
            )
            assertTrue(view.toolbar.style.startsWith("-fx-font-family"))
            assertEquals(0.0, (editorOf.node as javafx.scene.layout.Region).minWidth)
            val font =
                javafx.scene.text.Font
                    .getDefault()
            assertEquals(
                "-fx-font-family: \"${font.family}\"; -fx-font-size: ${font.size}px;",
                view.toolbar.style,
            )
        }
    }

    @Test
    fun `the toolbar buttons are not focus traversable, keep their size and say what they do`(robot: FxRobot) {
        val view = open(robot, "int x = 5;", withHost = false)

        fx {
            val tips =
                mapOf(
                    view.runButton to "Run the snippet at the caret or the selection (",
                    view.runAllButton to "Run All: restart and run the whole file (",
                    view.stopButton to "Stop the running snippet",
                    view.restartButton to "End the session; the next run starts a new one",
                    view.clearButton to "Clear the transcript",
                )
            for ((button, tip) in tips) {
                assertTrue(button.tooltip.text.startsWith(tip), button.tooltip.text)
                assertTrue(!button.isFocusTraversable)
                assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, button.minWidth)
            }
            val run = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN).displayText
            val all =
                KeyCodeCombination(
                    KeyCode.ENTER,
                    KeyCombination.SHORTCUT_DOWN,
                    KeyCombination.SHIFT_DOWN,
                ).displayText
            assertTrue(
                view.runButton.tooltip.text
                    .endsWith("($run)"),
                view.runButton.tooltip.text,
            )
            assertTrue(
                view.runAllButton.tooltip.text
                    .endsWith("($all)"),
                view.runAllButton.tooltip.text,
            )
            assertEquals(
                listOf("jshell-run", "jshell-run-all", "jshell-stop", "jshell-restart", "jshell-clear"),
                tips.keys.map { b -> b.styleClass.single { it.startsWith("jshell-") } },
            )
        }
    }

    @Test
    fun `Soft Wrap shows with the source only`(robot: FxRobot) {
        val view = open(robot, "int x = 5;", withHost = false)
        val wrap = fx { editorOf.softWrapButton }

        assertTrue(fx { wrap.isVisible && wrap.isManaged })
        robot.interact { view.split.mode.set(dev.sluide.fileviewers.SplitMode.OTHER) }
        assertTrue(fx { !wrap.isVisible && !wrap.isManaged })
    }

    @Test
    fun `the banner's text and its button have their sizes and the description of the problem`(robot: FxRobot) {
        projectSetting = null
        val view = open(robot, "int x = 5;")
        waitUntil("the banner") { banner(view) != null }

        fx {
            val label = view.node.lookup(".jshell-banner .label") as Label
            assertEquals("No JDK with JShell (JDK 9 or newer) was found.", label.text)
            assertTrue(label.isWrapText)
            assertEquals(0.0, label.minWidth)
            assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, label.minHeight)
            val action = view.node.lookup(".jshell-banner-action") as Button
            assertEquals(javafx.scene.layout.Region.USE_PREF_SIZE, action.minWidth)
            assertEquals(javafx.geometry.Pos.CENTER_LEFT, (banner(view) as javafx.scene.layout.HBox).alignment)
            assertEquals(
                javafx.scene.layout.Priority.ALWAYS,
                javafx.scene.layout.HBox
                    .getHgrow((banner(view) as javafx.scene.layout.HBox).children[1]),
            )
            assertTrue("editor-banner" in banner(view)!!.styleClass)
        }
    }

    @Test
    fun `a closed view no longer follows the project's JDK setting`(robot: FxRobot) {
        projectSetting = jdkHome(21, "21.0.13.1")
        val view = open(robot, "int x = 5;")
        waitUntil("JDK 21") { view.jdkButton.text == "JDK 21 · project setting" }
        robot.interact { view.dispose() }
        val reads = settingReads.get()

        robot.interact { window.projectJdkChanged() }

        // no signal for "nothing happened": a lookup would read the setting within a few ms
        Thread.sleep(300)
        assertEquals(reads, settingReads.get(), "no lookup after the view was closed")
    }

    @Test
    fun `the JDK menu is off while a snippet runs`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        // stop.jsonl: the lines of `while (true) {}` and of its stop
        agents.put(FakeAgent.of("stop", only = listOf(1, 6, 7, 12, 13, 14)))
        val view = open(robot, "while (true) {}")
        waitUntil("the JDK") { !view.runButton.isDisable }
        assertTrue(!fx { view.jdkButton.isDisable })

        robot.interact { view.runButton.fire() }
        waitUntil("the snippet to start") { !view.stopButton.isDisable }
        assertTrue(fx { view.jdkButton.isDisable })
        robot.interact { view.stopButton.fire() }
        waitUntil("the end of the run") { !view.runButton.isDisable }

        assertTrue(!fx { view.jdkButton.isDisable })
    }

    @Test
    fun `a window that cannot open the project settings offers no Choose JDK`(robot: FxRobot) {
        projectSetting = null
        canChoose = false
        val view = open(robot, "int x = 5;")
        waitUntil("the banner") { banner(view) != null }

        assertNull(fx { view.node.lookup(".jshell-banner-action") })
    }

    @Test
    fun `without a JDK with JShell a banner offers Choose JDK and nothing runs`(robot: FxRobot) {
        projectSetting = null
        val view = open(robot, "int x = 5;")
        waitUntil("the banner") { banner(view) != null }

        assertEquals("No JDK with JShell", fx { view.jdkButton.text })
        assertTrue(fx { view.runButton.isDisable })
        assertTrue(fx { view.runAllButton.isDisable })
        val choose = fx { view.node.lookup(".jshell-banner-action") } as Button
        assertEquals("Choose JDK…", fx { choose.text })
        robot.interact { choose.fire() }
        assertEquals(1, chooseJdkCalls)
    }

    @Test
    fun `a problem shows as a banner that Dismiss removes for the next lookups, and none comes without a problem`(
        robot: FxRobot,
    ) {
        // the file's own JDK is gone: the project's takes over, and the problem says so
        projectSetting = jdkHome(25, "25.0.5")
        jdks.remember(dir.resolve("deploy.jsh"), dir, Path.of("/gone"))
        val view = open(robot, "int x = 5;")

        waitUntil("the banner") { banner(view) != null }
        val text = fx { view.node.lookup(".jshell-banner .label") } as Label
        assertTrue("is gone or has no JShell" in fx { text.text }, fx { text.text })
        assertEquals("JDK 25 · project setting", fx { view.jdkButton.text })

        robot.interact { (view.node.lookup(".jshell-banner-action") as Button).fire() }

        assertNull(fx { banner(view) })
        // the same problem again after a new lookup stays dismissed
        projectSetting = jdkHome(21, "21.0.13.1")
        robot.interact { window.projectJdkChanged() }
        waitUntil("the new lookup") { view.jdkButton.text == "JDK 21 · project setting" }
        assertNull(fx { banner(view) })

        // a lookup without a problem after a dismissed one
        jdks.remember(dir.resolve("deploy.jsh"), dir, null)
        projectSetting = jdkHome(25, "25.0.5")
        robot.interact { window.projectJdkChanged() }
        waitUntil("the lookup without a problem") { view.jdkButton.text == "JDK 25 · project setting" }
        assertNull(fx { banner(view) })
    }

    @Test
    fun `a host that comes twice, or after the view was closed, or without the service attaches nothing more`(
        robot: FxRobot,
    ) {
        projectSetting = jdkHome(25, "25.0.5")
        val view = open(robot, "int x = 5;")
        waitUntil("the JDK") { jdkText(view) == "JDK 25 · project setting" }
        val reads = settingReads.get()
        assertEquals(1, window.listenerCount)

        robot.interact { view.host = host() }
        robot.interact { view.host = null }
        assertEquals(1, window.listenerCount, "one subscription however often a host comes")
        robot.interact { view.dispose() }
        assertEquals(0, window.listenerCount, "a closed view is gone from the window's listeners")
        robot.interact { view.host = host() }
        assertEquals(0, window.listenerCount, "and a host after that does not bring it back")
        // a view closed before any host came
        val early = open(robot, "int y = 6;", withHost = false)
        robot.interact { early.dispose() }
        robot.interact { early.host = host() }
        assertEquals(0, window.listenerCount, "a view closed before its host does not attach")
        // a host without the JShell service (outside an IDE window)
        val outside = open(robot, "int z = 7;", withHost = false)
        robot.interact { outside.host = host(withService = false) }
        assertEquals(0, window.listenerCount, "no window, nothing to attach to")
        assertEquals("JDK…", jdkText(outside))

        // no signal for "nothing happened": a lookup would read the setting within a few ms
        Thread.sleep(300)
        assertEquals(reads, settingReads.get(), "no further lookup")
    }

    private fun jdkText(view: JShellView) = fx { view.jdkButton.text }

    @Test
    fun `a view closed while its JDK is looked up shows nothing of it`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        val view = open(robot, "int x = 5;", withHost = false)

        settingGate = java.util.concurrent.CountDownLatch(1)
        robot.interact { view.host = host() }
        robot.interact { view.dispose() }

        checkNotNull(settingGate).countDown()
        waitUntil("the lookup") { settingReads.get() >= 1 }
        // no signal for an answer a closed view drops: the rest of the lookup takes a few ms
        Thread.sleep(300)
        robot.interact { }

        assertEquals("JDK…", jdkText(view))
    }

    @Test
    fun `the menu says it looks while the JDKs are read, and shows nothing of it for a closed view`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        val view = open(robot, "int x = 5;")
        waitUntil("the JDK") { jdkText(view) == "JDK 25 · project setting" }
        installedGate = java.util.concurrent.CountDownLatch(1)

        robot.interact { view.jdkButton.show() }
        assertEquals(listOf("Looking for JDKs…"), fx { view.jdkButton.items.map { it.text } })
        assertTrue(
            fx {
                view.jdkButton.items
                    .single()
                    .isDisable
            },
        )
        robot.interact { view.jdkButton.hide() }
        robot.interact { view.dispose() }
        checkNotNull(installedGate).countDown()
        waitUntil("the lookup") { installedCalls.get() >= 1 }
        // no signal for an answer a closed view drops: the rest of the lookup takes a few ms
        Thread.sleep(300)
        robot.interact { }

        assertEquals(listOf("Looking for JDKs…"), fx { view.jdkButton.items.map { it.text } })
    }

    @Test
    fun `a closed view takes its banner away`(robot: FxRobot) {
        projectSetting = null
        val view = open(robot, "int x = 5;")
        waitUntil("the banner") { banner(view) != null }
        val node = fx { banner(view) }

        robot.interact { view.dispose() }

        assertNull(fx { node!!.scene })
    }

    @Test
    fun `a change of the project's JDK setting resolves the JDK again`(robot: FxRobot) {
        projectSetting = jdkHome(21, "21.0.13.1")
        val view = open(robot, "int x = 5;")
        waitUntil("JDK 21") { view.jdkButton.text == "JDK 21 · project setting" }

        projectSetting = jdkHome(25, "25.0.5")
        robot.interact { window.projectJdkChanged() }

        waitUntil("JDK 25") { view.jdkButton.text == "JDK 25 · project setting" }
    }

    @Test
    fun `the toolbar icons follow the theme`(robot: FxRobot) {
        projectSetting = jdkHome(25, "25.0.5")
        val view = open(robot, "int x = 5;")
        val icon = fx { view.runButton.graphic as FontIcon }

        // The dark theme's base colour (settings' theme.css), which the test has no theme stylesheet for.
        val darkBase = "data:text/css,.root { -fx-base: %233c3f41; }"

        fun iconColor(base: Boolean): Color {
            robot.interact {
                val sheets = view.node.scene.stylesheets
                if (base) sheets += darkBase else sheets -= darkBase
                view.node.scene.preferences.colorScheme = if (base) ColorScheme.DARK else ColorScheme.LIGHT
                view.node.applyCss()
            }
            return fx { icon.iconColor as Color }
        }

        val dark = iconColor(true)
        val light = iconColor(false)

        assertTrue(dark.brightness > 0.5, "light icons on the dark toolbar: $dark")
        assertTrue(light.brightness < 0.5, "dark icons on the light toolbar: $light")
    }
}
