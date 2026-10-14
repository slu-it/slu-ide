package dev.sluide.settings

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
    fun `pages that cannot be saved are reported, the others are applied, OK keeps the dialog open`(robot: FxRobot) {
        val other = OtherPage()
        val dialog = open(robot, FailingPage(), other)

        close(robot, dialog, ButtonType.OK)
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(dialog.isShowing, "OK keeps the dialog open")
        assertEquals(1, other.applied, "the other pages are applied")
        val alert =
            Window
                .getWindows()
                .filterIsInstance<Stage>()
                .single { it.owner === dialog.dialogPane.scene.window }
        val pane = alert.scene.root as DialogPane
        assertTrue("Broken › Page: Cannot save settings to /nowhere" in pane.contentText, pane.contentText)

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

        close(robot, dialog, dialog.apply)
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
        WaitForAsyncUtils.waitForFxEvents()
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

        assertTrue("Monospaced" in terminal.family.items)
        assertTrue(terminal.family.items.all { it == "Monospaced" || FontsPage.isMonospaced(it) })
        assertTrue(
            fontsPage.rows
                .getValue(FontArea.EDITOR)
                .family.items.size >= terminal.family.items.size,
        )

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

            robot.interact { appearancePage.theme.value = Theme.LIGHT }
            assertEquals(ColorScheme.DARK, dialog.dialogPane.scene.preferences.colorScheme, "not applied yet")

            close(robot, dialog, dialog.apply)
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

    private fun enter(node: Node) =
        Event.fireEvent(node, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))

    @Test
    fun `fonts have the chosen weight and posture`(robot: FxRobot) {
        robot.interact {
            val bold = FontSpec("Monospaced", 15.0, FontStyle.BOLD).toFont()
            assertEquals(15.0, bold.size)
            assertTrue("Bold" in bold.style && "Italic" !in bold.style, bold.style)
            val italic = FontSpec("Monospaced", 15.0, FontStyle.ITALIC).toFont()
            assertTrue("Italic" in italic.style && "Bold" !in italic.style, italic.style)
        }
    }
}
