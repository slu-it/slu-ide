package dev.sluide.findinfiles

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.textediting.SearchOptions
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Bounds
import javafx.scene.Scene
import javafx.scene.control.TextArea
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FindInFiles] in a real scene: what needs one are the focus lookup of the editor whose selection becomes the query,
 * the screen anchor, and the editor menu item opening the popup over the editor's window (the rest, also the menu
 * item's states, is `FindInFilesTest`). Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FindInFilesUiTest {
    private lateinit var area: TextArea
    private lateinit var other: TextArea
    private lateinit var areaEditor: FakeEditor
    private lateinit var otherEditor: FakeEditor
    private val view = FakeFindView()
    private lateinit var stage: Stage
    private val find =
        FindInFiles(
            DefaultTextFileViewer().let { FileViewers(listOf(it), it) },
            { view },
            SimpleStringProperty(""),
            { _, _ -> AutoCloseable {} },
            { },
            { },
            { },
        ).apply { roots = { listOf(Path.of("/proj")) } }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        area = TextArea("alpha.beta gamma\ndelta")
        other = TextArea("elsewhere")
        areaEditor = FakeEditor(area, { area.selection }, { area.text })
        otherEditor = FakeEditor(other, { other.selection }, { other.text })
        stage.scene = Scene(VBox(area, other), 400.0, 300.0)
        stage.show()
    }

    /** Remembers `.*` the way the user does: the view reports the toggle, the popup then hides. */
    private fun rememberRegex(robot: FxRobot) {
        robot.interact { find.open(stage) }
        robot.interact {
            view.options = SearchOptions(regex = true)
            view.onChanged()
        }
        view.isShowing = false
    }

    @Test
    fun `the menu item opens the popup over the editor's window with the selection as query`(robot: FxRobot) {
        robot.interact { find.extend(areaEditor) }

        val item = areaEditor.menu("alpha.beta").single()
        robot.interact { item.fire() }

        assertEquals("alpha.beta", view.query)
        assertEquals(1, view.anchors)
        assertTrue(view.isShowing)
    }

    @Test
    fun `the menu item escapes the selection when the remembered regex toggle is on`(robot: FxRobot) {
        robot.interact { find.extend(areaEditor) }
        rememberRegex(robot)

        robot.interact { areaEditor.menu("a.b").single().fire() }

        assertEquals("a\\.b", view.query)
    }

    @Test
    fun `opening uses the selection of the focused editor and anchors over the window with the focus to return to`(
        robot: FxRobot,
    ) {
        robot.interact {
            find.extend(areaEditor)
            find.extend(otherEditor)
            area.selectRange(0, 5)
            other.selectRange(0, 4)
            other.requestFocus()
        }
        robot.interact { find.open(stage) }
        assertEquals("else", view.query)
        assertSame(other, view.lastAnchor?.returnFocus)
        assertSame(stage, view.lastAnchor?.owner)
        val root = stage.scene.root
        assertEquals(
            WaitForAsyncUtils.asyncFx<Bounds?> { root.localToScreen(root.boundsInLocal) }.get(),
            view.lastAnchor?.area,
        )

        view.isShowing = false
        robot.interact { area.requestFocus() }
        robot.interact { find.open(stage) }
        assertEquals("alpha", view.query)
        assertSame(area, view.lastAnchor?.returnFocus)
        assertEquals(2, view.anchors)
    }

    @Test
    fun `opening escapes the focused editor's selection when the remembered regex toggle is on`(robot: FxRobot) {
        robot.interact { find.extend(areaEditor) }
        rememberRegex(robot)
        robot.interact {
            area.selectRange(4, 7)
            area.requestFocus()
        }

        robot.interact { find.open(stage) }

        assertNotNull(view.lastAnchor)
        assertEquals("a\\.b", view.query)
    }

    @Test
    fun `the public constructor shows the real popup and closing hides it`(
        robot: FxRobot,
        @TempDir dir: Path,
    ) {
        val fonts = FontSettings(SettingsStore(dir.resolve("settings.properties")), FontSettings.systemDefaults())
        val real =
            FindInFiles(DefaultTextFileViewer().let { FileViewers(listOf(it), it) }, fonts).apply {
                roots = { listOf(dir) }
            }

        fun popupShows() = Window.getWindows().any { it is Popup && it.isShowing }

        robot.interact { assertTrue(real.open(stage)) }
        assertTrue(popupShows())

        robot.interact { real.close() }
        assertFalse(popupShows())
    }
}
