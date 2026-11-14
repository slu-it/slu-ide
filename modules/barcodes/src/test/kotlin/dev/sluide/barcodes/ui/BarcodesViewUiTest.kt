package dev.sluide.barcodes.ui

import dev.sluide.barcodes.BarcodeOptionsStore
import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.Box
import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.DecodedCode
import dev.sluide.barcodes.engine.Layout
import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.model.Source
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.TextArea
import javafx.scene.image.WritableImage
import javafx.scene.input.Clipboard
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each connection of the Barcodes view to its model needs the controls in a scene: painting on the canvas, the
 * clipboard, the controls shown per format, the spinners' own commit, a click on the status. Needs a display.
 * Controls are read on the FX thread.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BarcodesViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: BarcodeModel
    private lateinit var view: BarcodesView
    private var fileToSave: Path? = null
    private val suggestedNames = mutableListOf<String>()

    @Start
    fun start(stage: Stage) {
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        // Writes at once on the calling thread: no timing in these tests.
        model = BarcodeModel(BarcodeOptionsStore(data, Duration.ZERO) { it.run() })
        view =
            BarcodesView(
                model,
                chooseSaveFile = { _: Window?, name: String ->
                    suggestedNames += name
                    fileToSave
                },
                chooseImageFile = { null },
            )
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

    private fun snapshot() = fx { view.canvas.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null) }

    private fun WritableImage.colorAt(
        x: Int,
        y: Int,
    ): Color = pixelReader.getColor(x, y)

    private fun WritableImage.darkAt(
        x: Int,
        y: Int,
    ): Boolean {
        val color = colorAt(x, y)
        return color.opacity > 0.5 && color.red < 0.3
    }

    /** Where the canvas puts the centre of module [column], [row] of the shown code, as pixel coordinates. */
    private fun moduleCenter(
        column: Int,
        row: Int,
    ): Pair<Int, Int> {
        val (code, options) = fx { checkNotNull(model.generated) to model.options }
        val layout = Layout.of(code, options)
        val placed =
            checkNotNull(
                fx { fit(layout.widthModules, layout.heightModules, view.canvas.width, view.canvas.height, true) },
            )
        val x = placed.x + floor((column + layout.quietX) * placed.scale + placed.scale / 2)
        val y = placed.y + floor((row + layout.quietY) * placed.scale + placed.scale / 2)
        return x.toInt() to y.toInt()
    }

    @Test
    fun `typing paints the modules of the code on the canvas and nothing in the quiet zone`(robot: FxRobot) {
        type(robot, "hello")
        val code = fx { checkNotNull(model.generated) }
        val shot = snapshot()
        for (row in 0 until code.grid.height) {
            for (column in 0 until code.grid.width) {
                val (x, y) = moduleCenter(column, row)
                assertEquals(code.grid[column, row], shot.darkAt(x, y), "module $column,$row")
            }
        }
        // The finder pattern's corner module is always dark, the quiet zone beside it is not.
        val (finderX, finderY) = moduleCenter(0, 0)
        assertTrue(shot.darkAt(finderX, finderY))
        val (quietX, quietY) = moduleCenter(-1, 0)
        assertFalse(shot.darkAt(quietX, quietY))
    }

    @Test
    fun `while an error shows, the last code is drawn at 35 percent`(robot: FxRobot) {
        type(robot, "hello")
        val (x, y) = moduleCenter(0, 0)
        val full = snapshot().colorAt(x, y)
        assertEquals(1.0, full.opacity, 0.01)

        type(robot, "a".repeat(3000))

        assertTrue(fx { model.failure } != null)
        val dimmed = snapshot().colorAt(x, y)
        // The background at 35 % and the module at 35 % over it: well below the opaque module, and not black.
        assertTrue(dimmed.opacity < 0.7, "opacity ${dimmed.opacity}")
        assertTrue(dimmed.red > 0.3, "red ${dimmed.red}")
    }

    @Test
    fun `the hint shows only while there is nothing to draw`(robot: FxRobot) {
        val hint = node<Label>(".barcodes-hint")
        assertTrue(fx { hint.isVisible })
        type(robot, "hello")
        assertFalse(fx { hint.isVisible })
        robot.interact { (view.root.lookup("#barcodes-clear") as Button).fire() }
        assertTrue(fx { hint.isVisible })
    }

    @Test
    fun `Copy and Save as are disabled without a code and while an error shows the last one`(robot: FxRobot) {
        val copy = node<Button>("#barcodes-copy-image")
        val save = node<Button>("#barcodes-save-image")
        assertTrue(fx { copy.isDisable && save.isDisable })

        robot.interact { model.setFormat(CodeFormat.EAN_13) }
        type(robot, "123456789012")
        assertFalse(fx { copy.isDisable || save.isDisable })

        // Wrong check digit: the old number's code is still drawn, but it is not this text's.
        type(robot, "1234567890123")
        assertTrue(fx { model.failure } != null)
        assertTrue(fx { copy.isDisable && save.isDisable })
    }

    @Test
    fun `the format shows only the option controls of that format`(robot: FxRobot) {
        val combo = node<ComboBox<CodeFormat>>("#barcodes-format")

        fun shown(id: String) =
            fx {
                view.root
                    .lookup(id)
                    .parent
                    .let { it.isVisible && it.isManaged }
            }

        assertTrue(shown("#barcodes-qr-ecc"))
        assertFalse(shown("#barcodes-aztec"))
        assertFalse(shown("#barcodes-pdf417"))
        robot.interact { combo.value = CodeFormat.AZTEC }
        assertEquals(CodeFormat.AZTEC, fx { model.format })
        assertFalse(shown("#barcodes-qr-ecc"))
        assertTrue(shown("#barcodes-aztec"))
        robot.interact { combo.value = CodeFormat.PDF_417 }
        assertTrue(shown("#barcodes-pdf417"))
        assertFalse(shown("#barcodes-aztec"))
        robot.interact { combo.value = CodeFormat.EAN_13 }
        assertFalse(shown("#barcodes-qr-ecc") || shown("#barcodes-aztec") || shown("#barcodes-pdf417"))
        assertTrue(shown("#barcodes-size"))
    }

    @Test
    fun `Copy puts an image of the export size on the clipboard`(robot: FxRobot) {
        type(robot, "hello")
        val expected = fx { Layout.of(checkNotNull(model.generated), model.options).widthPx }
        robot.interact { node<Button>("#barcodes-copy-image").fire() }
        val image = fx { Clipboard.getSystemClipboard().image }
        assertEquals(expected.toDouble(), checkNotNull(image).width)
    }

    @Test
    fun `Save as writes PNG or SVG by the extension and suggests a name`(robot: FxRobot) {
        type(robot, "hello")
        fileToSave = dir.resolve("out.svg")
        robot.interact { node<Button>("#barcodes-save-image").fire() }
        assertEquals(listOf("qr-code.png"), suggestedNames)
        assertTrue(Files.readString(dir.resolve("out.svg")).startsWith("<svg"))
        fileToSave = dir.resolve("out.png")
        robot.interact { node<Button>("#barcodes-save-image").fire() }
        assertEquals(0x89, Files.readAllBytes(dir.resolve("out.png"))[0].toInt() and 0xFF)
        // The temporary file the write goes through is gone.
        Files.list(dir).use { files -> assertTrue(files.noneMatch { it.fileName.toString().startsWith(".") }) }
    }

    @Test
    fun `an error shows in the status and a click on it selects the character`(robot: FxRobot) {
        robot.interact { model.setFormat(CodeFormat.EAN_13) }
        type(robot, "12a")
        val status = node<Label>("#barcodes-text-status")
        assertTrue(fx { status.text }.contains("digits only"), fx { status.text })
        robot.interact { Event.fireEvent(status, click()) }
        assertEquals("a", fx { node<TextArea>("#barcodes-text").selectedText })
    }

    @Test
    fun `a change of the app does not flip the source back to text`(robot: FxRobot) {
        val code = DecodedCode(CodeFormat.DATA_MATRIX, "from image", Box(0.0, 0.0, 1.0, 1.0))
        robot.interact {
            model.imageRead(model.beginRead(), ArgbImage(1, 1, intArrayOf(-1)), listOf(code), "clipboard")
        }
        assertEquals("from image", fx { node<TextArea>("#barcodes-text").text })
        assertEquals(Source.IMAGE, fx { model.source })
        assertEquals(CodeFormat.DATA_MATRIX, fx { node<ComboBox<CodeFormat>>("#barcodes-format").value })
        assertEquals("Read from clipboard · 1 code · Data Matrix", fx { node<Label>("#barcodes-image-status").text })
    }

    @Test
    fun `an empty spinner field keeps the old value when the focus leaves it`(robot: FxRobot) {
        val size = node<Spinner<Int>>("#barcodes-size")
        robot.interact { size.editor.requestFocus() }
        robot.interact { size.editor.text = "" }
        robot.interact { node<TextArea>("#barcodes-text").requestFocus() }

        assertEquals(512, fx { size.value })
        assertEquals("512", fx { size.editor.text })
        assertEquals(512, fx { model.options.sizePx })
    }

    @Test
    fun `text that is no number and an arrow click give the old value plus the step`(robot: FxRobot) {
        val size = node<Spinner<Int>>("#barcodes-size")
        robot.interact { size.editor.text = "abc" }
        // The spinner's own increment() commits the field's text before it steps, as the arrow button does.
        robot.interact { size.increment() }

        assertEquals(512 + 64, fx { size.value })
        assertEquals("576", fx { size.editor.text })
        assertEquals(576, fx { model.options.sizePx })
    }

    @Test
    fun `a number beyond the range is clamped before the model sees it`(robot: FxRobot) {
        val size = node<Spinner<Int>>("#barcodes-size")
        val seen = mutableListOf<Int>()
        robot.interact { model.addListener { seen += model.options.sizePx } }

        robot.interact {
            size.editor.text = "9999"
            Event.fireEvent(size.editor, ActionEvent())
        }

        assertEquals(4096, fx { size.value })
        assertEquals("4096", fx { size.editor.text })
        assertEquals(4096, fx { model.options.sizePx })
        val updates = fx { seen.toList() }
        assertFalse(updates.any { it > 4096 }, "$updates")
    }

    private fun click() =
        MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            1.0,
            1.0,
            1.0,
            1.0,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            null,
        )
}
