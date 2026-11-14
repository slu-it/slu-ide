package dev.sluide.barcodes.ui

import dev.sluide.barcodes.BarcodeOptionsStore
import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.Box
import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.DecodedCode
import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.model.Source
import dev.sluide.projectdata.ProjectDataStore
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The extras around the text of the Barcodes view: the structured view, Open in Browser and the templates. Each needs
 * the controls in a scene (visibility and managed state, the clipboard, the TextArea's undo and listener). Needs a
 * display. Controls are read on the FX thread.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BarcodesExtrasUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: BarcodeModel
    private lateinit var view: BarcodesView
    private val opened = mutableListOf<URI>()

    @Start
    fun start(stage: Stage) {
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        model = BarcodeModel(BarcodeOptionsStore(data, Duration.ZERO) { it.run() })
        view = BarcodesView(model, { _: Window?, _: String -> null }, { null }, { opened += it })
        stage.scene = Scene(view.root as Parent, 1100.0, 600.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun <T : Node> node(id: String): T {
        @Suppress("UNCHECKED_CAST")
        return fx { view.root.lookup(id) as T }
    }

    private fun type(
        robot: FxRobot,
        text: String,
    ) = robot.interact { node<TextArea>("#barcodes-text").text = text }

    private fun structuredShown() = fx { view.structuredSlot.children.isNotEmpty() && view.structuredSlot.isManaged }

    private fun fieldText(row: Int) = fx { node<TextField>("#barcodes-field-$row").text }

    private val wifi = "WIFI:T:WPA;S:Home;P:secret pw;;"

    @Test
    fun `a Wi-Fi text shows the structured view with the password masked and plain text hides it`(robot: FxRobot) {
        type(robot, "hello")
        assertFalse(structuredShown())

        type(robot, wifi)
        assertTrue(structuredShown())
        val labels = fx { view.structuredSlot.lookupAll(".barcodes-field-label").map { (it as Label).text } }
        assertEquals(listOf("Network", "Security", "Password"), labels.take(3))
        assertEquals("Home", fieldText(0))
        assertEquals(StructuredView.MASK, fieldText(2))

        type(robot, "hello")
        assertFalse(structuredShown())
    }

    @Test
    fun `the eye toggle shows the password and editing the text masks it again`(robot: FxRobot) {
        type(robot, wifi)
        robot.interact { node<Button>("#barcodes-field-eye-2").fire() }
        assertEquals("secret pw", fieldText(2))
        robot.interact { node<Button>("#barcodes-field-eye-2").fire() }
        assertEquals(StructuredView.MASK, fieldText(2))

        robot.interact { node<Button>("#barcodes-field-eye-2").fire() }
        type(robot, "WIFI:T:WPA;S:Home2;P:secret pw;;")
        assertEquals("Home2", fieldText(0))
        assertEquals(StructuredView.MASK, fieldText(2))
    }

    @Test
    fun `a field's Copy puts the real value on the clipboard`(robot: FxRobot) {
        type(robot, wifi)
        robot.interact { node<Button>("#barcodes-field-copy-2").fire() }
        assertEquals("secret pw", fx { Clipboard.getSystemClipboard().string })
    }

    @Test
    fun `a value with line breaks shows in a wrapping read-only text area with the breaks`(robot: FxRobot) {
        type(robot, "BEGIN:VEVENT\nSUMMARY:Party\nDTSTART:20300102T030400Z\nDESCRIPTION:Bring\\ncake\nEND:VEVENT")
        val areas = fx { view.structuredSlot.lookupAll(".barcodes-field-value").filterIsInstance<TextArea>() }
        assertEquals(1, areas.size)
        assertEquals("Bring\ncake", fx { areas.single().text })
        assertTrue(fx { areas.single().isWrapText && !areas.single().isEditable })
    }

    @Test
    fun `a read Wi-Fi code shows its fields although the source is the image`(robot: FxRobot) {
        val code = DecodedCode(CodeFormat.QR_CODE, wifi, Box(0.0, 0.0, 1.0, 1.0))
        robot.interact {
            model.imageRead(model.beginRead(), ArgbImage(1, 1, intArrayOf(-1)), listOf(code), "clipboard")
        }
        assertEquals(Source.IMAGE, fx { model.source })
        assertTrue(structuredShown())
        assertEquals("Home", fieldText(0))
    }

    private fun browserVisible() = fx { node<Button>("#barcodes-open-browser").isVisible }

    private fun browserManaged() = fx { node<Button>("#barcodes-open-browser").isManaged }

    @Test
    fun `Open in Browser is shown for one http URL only and a click opens that URL`(robot: FxRobot) {
        type(robot, "hello")
        assertFalse(browserVisible())
        assertFalse(browserManaged())

        type(robot, "https://example.com")
        assertTrue(browserVisible())
        assertTrue(browserManaged())
        assertTrue(opened.isEmpty(), "never opens on its own")
        robot.interact { node<Button>("#barcodes-open-browser").fire() }
        assertEquals(listOf(URI("https://example.com")), opened)

        type(robot, "javascript:alert(1)")
        assertFalse(browserVisible())
        assertFalse(browserManaged())
    }
}
