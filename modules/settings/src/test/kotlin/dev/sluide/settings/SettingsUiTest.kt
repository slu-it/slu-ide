package dev.sluide.settings

import dev.sluide.settings.pages.AppearancePage
import dev.sluide.settings.pages.FontsPage
import javafx.application.ColorScheme
import javafx.beans.property.SimpleObjectProperty
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shows the settings dialog with the real Fonts page. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SettingsUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var stage: Stage
    private val defaults = TestFonts.defaults
    private val settings by lazy { TestFonts.settings(dir.resolve("settings.properties")) }
    private val fontsPage by lazy { FontsPage(settings) }
    private val themeSettings by lazy {
        ThemeSettings(SettingsStore(dir.resolve("settings.properties")), { SimpleObjectProperty(ColorScheme.LIGHT) })
    }
    private val appearancePage by lazy { AppearancePage(themeSettings) }

    /** A second page in another group, to check the tree. */
    private class OtherPage : SettingsPage {
        var applied = 0
        override val path = listOf("Other", "Things")
        override val node = Label("other page")

        override fun load() {}

        override fun apply() {
            applied++
        }
    }

    /** A page at [path], e.g. one at a group's own name with another below it, like Editor and Editor › Encrypted Files. */
    private class NamedPage(
        override val path: List<String>,
    ) : SettingsPage {
        override val node = Label(path.joinToString(" › "))

        override fun load() {}

        override fun apply() {}
    }

    /** A page whose edits cannot be stored. */
    private class FailingPage : SettingsPage {
        override val path = listOf("Broken", "Page")
        override val node = Label("broken page")

        override fun load() {}

        override fun apply() = throw IOException("Cannot save settings to /nowhere")
    }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = javafx.scene.Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun open(
        robot: FxRobot,
        vararg pages: SettingsPage,
    ): SettingsDialog {
        lateinit var dialog: SettingsDialog
        robot.interact {
            dialog = SettingsDialog(SettingsPages(pages.toList()).pages, stage)
            dialog.show()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return dialog
    }

    private fun close(
        robot: FxRobot,
        dialog: SettingsDialog,
        button: ButtonType,
    ) = robot.interact { (dialog.dialogPane.lookupButton(button) as Button).fire() }

    @Test
    fun `the tree groups the pages, selecting a group shows its first page`(robot: FxRobot) {
        val other = OtherPage()
        val dialog = open(robot, other, fontsPage)

        val groups =
            dialog.tree.root.children
                .map { it.value.name }
        assertEquals(listOf("Display", "Other"), groups)
        assertEquals(
            listOf("Fonts"),
            dialog.tree.root.children[0]
                .children
                .map { it.value.name },
        )
        assertSame(fontsPage, dialog.shownPage, "the first page is shown")

        robot.interact { dialog.tree.selectionModel.select(dialog.tree.root.children[1]) }
        assertSame(other, dialog.shownPage)
        robot.interact { dialog.select(listOf("Display", "Fonts")) }
        assertSame(fontsPage, dialog.shownPage)

        close(robot, dialog, ButtonType.CANCEL)
        assertEquals(0, other.applied)
    }

    @Test
    fun `a page also serves as the parent of the pages below its name`(robot: FxRobot) {
        val editor = NamedPage(listOf("Editor"))
        val child = NamedPage(listOf("Editor", "Encrypted Files"))
        val dialog = open(robot, child, editor)

        val items = dialog.tree.root.children
        assertEquals(listOf("Editor"), items.map { it.value.name })
        assertEquals(listOf("Encrypted Files"), items.single().children.map { it.value.name })
        robot.interact { dialog.tree.selectionModel.select(items.single()) }
        assertSame(editor, dialog.shownPage, "selecting Editor shows the Editor page")
        robot.interact { dialog.select(listOf("Editor", "Encrypted Files")) }
        assertSame(child, dialog.shownPage)

        close(robot, dialog, ButtonType.CANCEL)
    }

    @Test
    fun `pages that cannot be saved are reported, the others are applied, OK keeps the dialog open`(robot: FxRobot) {
        val other = OtherPage()
        val dialog = open(robot, FailingPage(), other)

        close(robot, dialog, ButtonType.OK)
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(dialog.isShowing, "OK keeps the dialog open")
        assertEquals(1, other.applied, "the other pages are applied")
        lateinit var pane: DialogPane
        lateinit var message: String
        lateinit var alertTitle: String
        robot.interact {
            val alert =
                Window
                    .getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.owner === dialog.dialogPane.scene.window }
            pane = alert.scene.root as DialogPane
            message = pane.contentText
            alertTitle = alert.title
        }
        assertEquals("Settings", alertTitle)
        assertTrue("Broken › Page: Cannot save settings to /nowhere" in message, message)

        robot.interact { (pane.lookupButton(ButtonType.OK) as Button).fire() }
        close(robot, dialog, ButtonType.CANCEL)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `the preview follows the edits, the IDE settings change only on apply`(robot: FxRobot) {
        val dialog = open(robot, fontsPage)
        val editor = fontsPage.rows.getValue(FontArea.EDITOR)

        robot.interact {
            editor.size.valueFactory.value = 20.0
            editor.style.value = FontStyle.BOLD_ITALIC
        }
        WaitForAsyncUtils.waitForFxEvents()

        val previewFont = fontsPage.editorPreview.font
        assertEquals(20.0, previewFont.size)
        assertTrue(previewFont.style.contains("Bold") && previewFont.style.contains("Italic"), previewFont.style)
        assertEquals(defaults[FontArea.EDITOR], settings.font(FontArea.EDITOR).get(), "not applied yet")

        close(robot, dialog, dialog.applyButton)
        assertEquals(FontSpec("Monospaced", 20.0, FontStyle.BOLD_ITALIC), settings.font(FontArea.EDITOR).get())
        assertTrue(dialog.isShowing, "Apply keeps the dialog open")
        close(robot, dialog, ButtonType.OK)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `cancel drops the edits, the next dialog shows the current settings`(robot: FxRobot) {
        val first = open(robot, fontsPage)
        val terminal = fontsPage.rows.getValue(FontArea.TERMINAL)
        robot.interact { terminal.size.valueFactory.value = 30.0 }
        assertEquals(
            30.0,
            fontsPage.terminalPreview
                .first()
                .font.size,
        )

        close(robot, first, ButtonType.CANCEL)
        assertEquals(defaults[FontArea.TERMINAL], settings.font(FontArea.TERMINAL).get())

        val second = open(robot, fontsPage)
        assertEquals(14.0, terminal.size.value)
        assertEquals(
            14.0,
            fontsPage.terminalPreview
                .first()
                .font.size,
        )
        close(robot, second, ButtonType.CANCEL)
    }

    @Test
    fun `restore defaults and the explorer preview`(robot: FxRobot) {
        robot.interact { settings.apply(mapOf(FontArea.EXPLORER to FontSpec("Monospaced", 18.0, FontStyle.BOLD))) }
        val dialog = open(robot, fontsPage)
        val explorer = fontsPage.rows.getValue(FontArea.EXPLORER)
        assertEquals("Monospaced", explorer.family.value)
        assertTrue("-fx-font-size: 18px" in fontsPage.explorerPreview.style)

        robot.interact { explorer.restoreDefaults.fire() }
        assertEquals("System", explorer.family.value)
        assertEquals(FontStyle.REGULAR, explorer.style.value)
        assertTrue("-fx-font-size: 13px" in fontsPage.explorerPreview.style)

        close(robot, dialog, ButtonType.OK)
        assertEquals(defaults[FontArea.EXPLORER], settings.font(FontArea.EXPLORER).get())
    }

    @Test
    fun `the terminal offers monospaced families only and typed unknown names are not taken`(robot: FxRobot) {
        val dialog = open(robot, fontsPage)
        val terminal = fontsPage.rows.getValue(FontArea.TERMINAL)

        val editorFamilies =
            fontsPage.rows
                .getValue(FontArea.EDITOR)
                .family.items
        assertTrue("Monospaced" in terminal.family.items)
        assertTrue(editorFamilies.containsAll(terminal.family.items))
        assertTrue(editorFamilies.size > terminal.family.items.size, "proportional fonts are left out")
        assertTrue(FontsPage.isMonospaced("Monospaced"))
        assertFalse(FontsPage.isMonospaced("System"), "a proportional font")
        assertFalse(FontsPage.isMonospaced("No Such Font"))

        robot.interact { terminal.family.value = "No Such Font" }
        assertEquals("Monospaced", terminal.family.value)
        assertEquals(defaults[FontArea.TERMINAL], fontsPage.model.draft(FontArea.TERMINAL).get())
        close(robot, dialog, ButtonType.CANCEL)
    }

    @Test
    fun `enter in a typed font name or size commits it and keeps the dialog open`(robot: FxRobot) {
        val dialog = open(robot, fontsPage)
        val editor = fontsPage.rows.getValue(FontArea.EDITOR)

        robot.interact {
            editor.family.editor.text = "Serif"
            enter(editor.family)
            editor.size.editor.text = "17"
            enter(editor.size)
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(dialog.isShowing)
        assertEquals(FontSpec("Serif", 17.0), fontsPage.model.draft(FontArea.EDITOR).get())
        assertEquals(defaults[FontArea.EDITOR], settings.font(FontArea.EDITOR).get(), "not applied by Enter")
        close(robot, dialog, ButtonType.CANCEL)
    }

    @Test
    fun `the theme changes the dialog on apply, cancel keeps the current theme`(robot: FxRobot) {
        val themed = ThemedWindows(themeSettings)
        robot.interact {
            themed.install()
            themeSettings.apply(Theme.DARK)
        }
        try {
            val dialog = open(robot, appearancePage, fontsPage)
            robot.interact { dialog.select(listOf("Display", "Appearance")) }
            assertSame(appearancePage, dialog.shownPage)
            assertEquals(Theme.DARK, appearancePage.theme.value)
            assertEquals(ColorScheme.DARK, dialog.dialogPane.scene.preferences.colorScheme)
            assertEquals(ColorScheme.DARK, stage.scene.preferences.colorScheme)

            robot.interact { appearancePage.theme.value = Theme.LIGHT }
            assertEquals(ColorScheme.DARK, dialog.dialogPane.scene.preferences.colorScheme, "not applied yet")

            close(robot, dialog, dialog.applyButton)
            WaitForAsyncUtils.waitForFxEvents()
            assertEquals(Theme.LIGHT, themeSettings.theme.get())
            assertEquals(ColorScheme.LIGHT, dialog.dialogPane.scene.preferences.colorScheme)
            assertEquals(ColorScheme.LIGHT, stage.scene.preferences.colorScheme)

            robot.interact { appearancePage.theme.value = Theme.DARK }
            close(robot, dialog, ButtonType.CANCEL)
            assertEquals(Theme.LIGHT, themeSettings.theme.get())
            val second = open(robot, appearancePage)
            assertEquals(Theme.LIGHT, appearancePage.theme.value)
            close(robot, second, ButtonType.CANCEL)
        } finally {
            robot.interact { themed.uninstall() }
        }
    }

    @Test
    fun `OK applies the shown page and closes the dialog`(robot: FxRobot) {
        val other = OtherPage()
        val dialog = open(robot, fontsPage, other)
        robot.interact { dialog.select(listOf("Other", "Things")) }
        assertSame(other, dialog.shownPage)

        close(robot, dialog, ButtonType.OK)
        assertFalse(dialog.isShowing)
        assertEquals(1, other.applied)
    }

    /** A project page that records the folders it is loaded and applied with. */
    private class RecordingProjectPage(
        override val path: List<String>,
    ) : ProjectSettingsPage {
        val loaded = mutableListOf<Path>()
        val applied = mutableListOf<Path>()
        override val node = Label(path.joinToString(" › "))

        override fun load(folder: Path) {
            loaded.add(folder)
        }

        override fun apply(folder: Path) {
            applied.add(folder)
        }
    }

    // UI tests: a Dialog can only be created on the FX thread.
    @Test
    fun `the project dialog is titled Project Settings, the settings dialog Settings`(robot: FxRobot) {
        val project = ProjectSettings(listOf(RecordingProjectPage(listOf("A"))))
        val settingsDialog = open(robot, NamedPage(listOf("A")))
        lateinit var projectDialog: SettingsDialog
        robot.interact { projectDialog = project.dialog(dir, stage) }

        assertEquals("Settings", settingsDialog.title)
        assertEquals("Project Settings", projectDialog.title)
        close(robot, settingsDialog, ButtonType.CANCEL)
    }

    @Test
    fun `project pages are loaded and applied with the folder, the path selects the page`(robot: FxRobot) {
        val first = RecordingProjectPage(listOf("Alpha"))
        val second = RecordingProjectPage(listOf("Java"))
        val project = ProjectSettings(listOf(first, second))
        lateinit var dialog: SettingsDialog
        robot.interact {
            dialog = project.dialog(dir, stage, listOf("Java"))
            dialog.show()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(dir), second.loaded)
        assertEquals(listOf(dir), first.loaded)
        assertEquals(listOf("Java"), dialog.shownPage?.path)
        close(robot, dialog, ButtonType.OK)
        assertEquals(listOf(dir), first.applied)
        assertEquals(listOf(dir), second.applied)
    }

    @Test
    fun `the error of a project page is reported in an alert titled Project Settings`(robot: FxRobot) {
        val failing =
            object : ProjectSettingsPage {
                override val path = listOf("Broken")
                override val node by lazy { Label("broken") }

                override fun load(folder: Path) {}

                override fun apply(folder: Path) = throw IOException("Cannot store in $folder")
            }
        lateinit var dialog: SettingsDialog
        robot.interact {
            dialog = ProjectSettings(listOf(failing)).dialog(dir, stage)
            dialog.show()
        }
        WaitForAsyncUtils.waitForFxEvents()

        close(robot, dialog, ButtonType.OK)
        WaitForAsyncUtils.waitForFxEvents()

        lateinit var alertTitle: String
        lateinit var message: String
        robot.interact {
            val alert =
                Window
                    .getWindows()
                    .filterIsInstance<Stage>()
                    .single { it.owner === dialog.dialogPane.scene.window }
            alertTitle = alert.title
            message = (alert.scene.root as DialogPane).contentText
            alert.close()
        }
        assertEquals("Project Settings", alertTitle)
        assertTrue("Cannot store in $dir" in message, message)
        close(robot, dialog, ButtonType.CANCEL)
    }

    @Test
    fun `an unknown path or none opens the first project page`(robot: FxRobot) {
        val pages = listOf(RecordingProjectPage(listOf("Alpha")), RecordingProjectPage(listOf("Java")))
        val project = ProjectSettings(pages)
        lateinit var unknown: SettingsDialog
        lateinit var none: SettingsDialog
        robot.interact {
            unknown = project.dialog(dir, stage, listOf("Nope"))
            none = project.dialog(dir, stage)
        }
        assertEquals(listOf("Alpha"), unknown.shownPage?.path)
        assertEquals(listOf("Alpha"), none.shownPage?.path)
    }

    private fun enter(node: Node) =
        Event.fireEvent(node, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
}
