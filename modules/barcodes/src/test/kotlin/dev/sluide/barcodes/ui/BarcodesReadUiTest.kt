package dev.sluide.barcodes.ui

import dev.sluide.barcodes.BarcodeOptionsStore
import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.Export
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.Generated
import dev.sluide.barcodes.engine.Generator
import dev.sluide.barcodes.engine.codeImage
import dev.sluide.barcodes.engine.compose
import dev.sluide.barcodes.model.BarcodeModel
import dev.sluide.barcodes.model.Source
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.DragEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.TestDragboards
import javafx.scene.input.TransferMode
import javafx.scene.paint.Color
import javafx.stage.Stage
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading images in the Barcodes view: Shortcut+V, the buttons, a drop, the outlines and the click on one. They need a
 * scene (event filter and drop handlers on nodes, painted pixels) and the system clipboard of the test display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BarcodesReadUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: BarcodeModel
    private lateinit var view: BarcodesView
    private var fileToOpen: Path? = null

    @Start
    fun start(stage: Stage) {
        val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        model = BarcodeModel(BarcodeOptionsStore(data, Duration.ZERO) { it.run() })
        view = BarcodesView(model, chooseSaveFile = { _, _ -> null }, chooseImageFile = { fileToOpen })
        stage.scene = Scene(view.root as Parent, 1100.0, 600.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun area() = fx { view.root.lookup("#barcodes-text") as TextArea }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!fx(condition)) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(20) // Polls for the read thread's result; the deadline is the only bound.
        }
    }

    private fun qr(text: String) = codeImage(text, CodeFormat.QR_CODE, GenerateOptions(sizePx = 300))

    private fun bitmap(image: ArgbImage) =
        WritableImage(image.width, image.height).also {
            it.pixelWriter.setPixels(
                0,
                0,
                image.width,
                image.height,
                PixelFormat.getIntArgbInstance(),
                image.argb,
                0,
                image.width,
            )
        }

    private fun putClipboard(
        image: ArgbImage? = null,
        string: String? = null,
    ) = fx {
        Clipboard.getSystemClipboard().setContent(
            ClipboardContent().apply {
                if (image != null) putImage(bitmap(image))
                if (string != null) putString(string)
            },
        )
    }

    private fun pasteKeys(robot: FxRobot) {
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        robot.interact {
            val target = view.root.lookup("#barcodes-text")
            Event.fireEvent(
                target,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.V, false, !mac, false, mac),
            )
        }
    }

    private fun twoCodes(): ArgbImage =
        compose(
            900,
            500,
            Triple(qr("first code"), 40, 100),
            Triple(codeImage("second code", CodeFormat.DATA_MATRIX, GenerateOptions(sizePx = 200)), 560, 150),
        )

    private fun snapshot() = fx { view.canvas.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null) }

    /** The canvas rectangle of code [index] of the shown read. */
    private fun outline(index: Int): Rect =
        fx {
            val image = checkNotNull(model.image)
            val placed =
                checkNotNull(fit(image.width, image.height, view.canvas.width, view.canvas.height, wholePixels = false))
            imageToCanvas(model.codes[index].bounds, placed)
        }

    /** Clicks the canvas at ([x], [y]) in its own coordinates; a synthetic event's position is a scene position. */
    private fun clickCanvas(
        robot: FxRobot,
        x: Double,
        y: Double,
    ) = robot.interact {
        val at = view.canvas.localToScene(x, y)
        Event.fireEvent(
            view.canvas,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                at.x,
                at.y,
                at.x,
                at.y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null,
            ),
        )
    }

    private fun drop(
        robot: FxRobot,
        type: javafx.event.EventType<DragEvent>,
        content: Map<DataFormat, Any>,
    ): DragEvent {
        lateinit var event: DragEvent
        robot.interact {
            event =
                DragEvent(type, TestDragboards.of(content), 5.0, 5.0, 5.0, 5.0, TransferMode.COPY, null, null, null)
            Event.fireEvent(view.root.lookup(".barcodes-canvas-pane"), event)
        }
        return event
    }

    @Test
    fun `Shortcut+V with only an image reads it from the text area, shows the text and paints the outline`(
        robot: FxRobot,
    ) {
        putClipboard(image = qr("pasted text"))
        pasteKeys(robot)
        waitFor("the read") { model.source == Source.IMAGE }

        assertEquals("pasted text", fx { area().text })
        assertEquals(
            "Read from clipboard · 1 code · QR Code",
            fx {
                (view.root.lookup("#barcodes-image-status") as Label).text
            },
        )
        assertEquals(
            "Edit the text or change an option to make a code",
            fx {
                (view.root.lookup("#barcodes-copy-image") as Button).tooltip?.text
            },
        )
        // One code: a blue outline around the modules that the image itself painted. The two boxes are found from the
        // pixels, so the mapping of the outline is not checked with its own function.
        val shot = snapshot()
        val blue = pixelBox(shot) { it.blue > it.red + 0.3 && it.opacity > 0.5 }
        val modules = pixelBox(shot) { it.opacity > 0.9 && it.red < 0.2 && it.green < 0.2 && it.blue < 0.2 }
        assertTrue(
            blue.map { it.first }.let { (low, high) ->
                low <= modules[0].first + 10 && high >= modules[1].first - 10
            } &&
                blue.map { it.second }.let { (low, high) ->
                    low <= modules[0].second + 10 &&
                        high >= modules[1].second - 10
                },
            "blue $blue, modules $modules",
        )
        // And tight: not a frame around the whole canvas.
        assertTrue(
            modules[0].first - blue[0].first < 20 && blue[1].first - modules[1].first < 20,
            "blue $blue, modules $modules",
        )
        assertTrue(
            modules[0].second - blue[0].second < 20 && blue[1].second - modules[1].second < 20,
            "blue $blue, modules $modules",
        )
    }

    /** The top-left and bottom-right pixel (x to y each) of the pixels of [shot] that satisfy [test]. */
    private fun pixelBox(
        shot: WritableImage,
        test: (Color) -> Boolean,
    ): List<Pair<Int, Int>> {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until shot.height.toInt()) {
            for (x in 0 until shot.width.toInt()) {
                if (test(shot.pixelReader.getColor(x, y))) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        check(maxX >= 0) { "no matching pixel" }
        return listOf(minX to minY, maxX to maxY)
    }

    @Test
    fun `Shortcut+V with text on the clipboard pastes the text and reads nothing`(robot: FxRobot) {
        // Image and text together: only the string check keeps the image from being read.
        putClipboard(image = qr("must not be read"), string = "plain text")
        robot.interact { area().requestFocus() }
        pasteKeys(robot)
        Thread.sleep(300) // Negative check: a read would come back through the read thread within this time.

        assertEquals("plain text", fx { area().text })
        assertEquals(Source.TEXT, fx { model.source })
        assertNull(fx { model.image })
    }

    @Test
    fun `Paste Image without an image says so and changes nothing`(robot: FxRobot) {
        putClipboard(string = "only text")
        robot.interact { (view.root.lookup("#barcodes-paste-image") as Button).fire() }

        assertEquals("The clipboard holds no image.", fx { (view.root.lookup("#barcodes-image-status") as Label).text })
        assertEquals(Source.TEXT, fx { model.source })
    }

    @Test
    fun `Open reads the chosen file and names it as the origin`(robot: FxRobot) {
        fileToOpen =
            dir
                .resolve(
                    "photo.png",
                ).also { Files.write(it, Export.png(code("from the file"), GenerateOptions(sizePx = 300))) }
        robot.interact { (view.root.lookup("#barcodes-open-image") as Button).fire() }
        waitFor("the read") { model.source == Source.IMAGE }

        assertEquals("from the file", fx { area().text })
        assertEquals(
            "Read from photo.png · 1 code · QR Code",
            fx {
                (view.root.lookup("#barcodes-image-status") as Label).text
            },
        )
    }

    private fun code(text: String) = Generator.generate(text, CodeFormat.QR_CODE, GenerateOptions()) as Generated.Code

    @Test
    fun `dropping a PNG file on the image side reads it, several files read the first and say so`(robot: FxRobot) {
        val first =
            dir.resolve("a.png").also {
                Files.write(it, Export.png(code("dropped file"), GenerateOptions(sizePx = 300)))
            }
        val second =
            dir
                .resolve(
                    "b.png",
                ).also { Files.write(it, Export.png(code("other"), GenerateOptions(sizePx = 300))) }

        drop(robot, DragEvent.DRAG_DROPPED, mapOf(DataFormat.FILES to listOf(first.toFile(), second.toFile())))
        waitFor("the read") { model.source == Source.IMAGE }

        assertEquals("dropped file", fx { area().text })
        val status = fx { (view.root.lookup("#barcodes-image-status") as Label).text }
        assertEquals("Read from a.png · 1 code · QR Code · only the first of 2 files was read", status)
    }

    @Test
    fun `a raw image dropped on the image side is read`(robot: FxRobot) {
        drop(robot, DragEvent.DRAG_DROPPED, mapOf(DataFormat.IMAGE to bitmap(qr("raw drop"))))
        waitFor("the read") { model.source == Source.IMAGE }

        assertEquals("raw drop", fx { area().text })
        assertTrue(
            fx { (view.root.lookup("#barcodes-image-status") as Label).text }.startsWith("Read from dropped image"),
        )
    }

    @Test
    fun `clicking the second outline shows its code and format, a click beside any outline does nothing`(
        robot: FxRobot,
    ) {
        putClipboard(image = twoCodes())
        robot.interact { (view.root.lookup("#barcodes-paste-image") as Button).fire() }
        waitFor("the read") { model.source == Source.IMAGE }
        assertEquals("first code", fx { area().text })
        val status = fx { (view.root.lookup("#barcodes-image-status") as Label).text }
        assertTrue(status.endsWith("· click an outline to pick one"), status)

        val second = outline(1)
        clickCanvas(robot, second.x + second.width / 2, second.y + second.height / 2)
        assertEquals("second code", fx { area().text })
        assertEquals(1, fx { model.selectedCode })
        assertEquals(CodeFormat.DATA_MATRIX, fx { (view.root.lookup("#barcodes-format") as ComboBox<*>).value })
        assertEquals(Source.IMAGE, fx { model.source })

        clickCanvas(robot, 2.0, 2.0)
        assertEquals(1, fx { model.selectedCode })
    }

    @Test
    fun `a read that throws an error still ends the Reading status with a failure`(robot: FxRobot) {
        val input = ImageInput(model, reading = {}, noted = {}, reader = { throw OutOfMemoryError("test") })
        val png = dir.resolve("any.png").also { Files.write(it, Export.png(code("x"), GenerateOptions(sizePx = 300))) }

        robot.interact { input.readFile(png) }
        waitFor("the failure") { model.imageStatus == UNREADABLE }

        assertEquals(Source.TEXT, fx { model.source })
    }

    @Test
    fun `the read image is turned into a bitmap once, not on every drawing`(robot: FxRobot) {
        putClipboard(image = twoCodes())
        robot.interact { (view.root.lookup("#barcodes-paste-image") as Button).fire() }
        waitFor("the read") { model.source == Source.IMAGE }
        assertEquals(1, fx { view.picturesBuilt })
        assertTrue(fx { view.holdsPicture })

        robot.interact { model.selectCode(1) }
        robot.interact { view.root.requestLayout() }

        assertEquals(1, fx { view.picturesBuilt })
    }

    @Test
    fun `editing the text after a read makes a generated code again`(robot: FxRobot) {
        putClipboard(image = qr("from image"))
        pasteKeys(robot)
        waitFor("the read") { model.source == Source.IMAGE }

        robot.interact { area().text = "typed" }

        assertEquals(Source.TEXT, fx { model.source })
        assertNotNull(fx { model.generated })
        assertNull(fx { model.image })
        assertFalse(fx { (view.root.lookup("#barcodes-copy-image") as Button).isDisable })
        // The bitmap of the read image is released.
        assertFalse(fx { view.holdsPicture })
    }
}
