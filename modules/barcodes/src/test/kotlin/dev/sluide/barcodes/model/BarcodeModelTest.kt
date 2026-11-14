package dev.sluide.barcodes.model

import dev.sluide.barcodes.BarcodeOptionsStore
import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.Box
import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.DecodedCode
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.QrEcc
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BarcodeModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var data: ProjectDataStore
    private lateinit var store: BarcodeOptionsStore
    private lateinit var model: BarcodeModel
    private val image = ArgbImage(1, 1, intArrayOf(-1))

    @BeforeEach
    fun setUp() {
        data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        // Writes at once on the calling thread: no timing in these tests.
        store = BarcodeOptionsStore(data, Duration.ZERO) { it.run() }
        model = BarcodeModel(store)
    }

    private fun code(
        text: String,
        format: CodeFormat = CodeFormat.QR_CODE,
    ) = DecodedCode(format, text, Box(0.0, 0.0, 1.0, 1.0))

    @Test
    fun `typing regenerates`() {
        model.editText("hello")
        assertNotNull(model.generated)
        assertNull(model.failure)
        assertEquals(Source.TEXT, model.source)
    }

    @Test
    fun `an empty text gives no code and no failure`() {
        model.editText("hello")
        model.editText("")
        assertNull(model.generated)
        assertNull(model.failure)
    }

    @Test
    fun `a failure keeps the last code`() {
        model.setFormat(CodeFormat.EAN_13)
        model.editText("123456789012")
        val last = model.generated
        assertNotNull(last)
        model.editText("abc")
        assertNotNull(model.failure)
        assertSame(last, model.generated)
    }

    @Test
    fun `a format or option change regenerates and saves`() {
        model.editText("12345")
        val before = model.generated
        model.setFormat(CodeFormat.DATA_MATRIX)
        assertEquals(CodeFormat.DATA_MATRIX, model.generated?.format)
        assertEquals(CodeFormat.DATA_MATRIX, store.load().first)
        model.setFormat(CodeFormat.QR_CODE)
        model.setOptions(GenerateOptions(qrEcc = QrEcc.H, sizePx = 1024))
        assertEquals(QrEcc.H, store.load().second.qrEcc)
        assertEquals(1024, store.load().second.sizePx)
        assertTrue(model.generated !== before)
    }

    @Test
    fun `a model starts with the saved format and options`() {
        store.save(CodeFormat.AZTEC, GenerateOptions(aztecEccPercent = 40))
        val again = BarcodeModel(store)
        assertEquals(CodeFormat.AZTEC, again.format)
        assertEquals(40, again.options.aztecEccPercent)
    }

    @Test
    fun `imageRead sets the text without generating and follows a generatable format`() {
        val seq = model.beginRead()
        model.imageRead(seq, image, listOf(code("12345", CodeFormat.DATA_MATRIX), code("two")), "clipboard")
        assertEquals(Source.IMAGE, model.source)
        assertEquals("12345", model.text)
        assertEquals(CodeFormat.DATA_MATRIX, model.format)
        assertEquals(CodeFormat.DATA_MATRIX, store.load().first)
        assertNull(model.generated)
        assertEquals("Read from clipboard · 2 codes · Data Matrix", model.imageStatus)
    }

    @Test
    fun `imageRead keeps the format for one that cannot be generated`() {
        val seq = model.beginRead()
        model.imageRead(seq, image, listOf(code("12345670", CodeFormat.EAN_8)), "a.png")
        assertEquals(CodeFormat.QR_CODE, model.format)
        assertEquals("12345670", model.text)
        assertEquals("Read from a.png · 1 code · EAN-8", model.imageStatus)
    }

    @Test
    fun `a stale read is ignored, also after an edit`() {
        val first = model.beginRead()
        val second = model.beginRead()
        model.imageRead(first, image, listOf(code("old")), "x")
        assertEquals("", model.text)
        model.editText("typed")
        model.imageRead(second, image, listOf(code("late")), "x")
        model.readFailed(second, "boom")
        assertEquals("typed", model.text)
        assertEquals(Source.TEXT, model.source)
        assertNull(model.imageStatus)
    }

    @Test
    fun `no codes found leaves text, format and source`() {
        model.setFormat(CodeFormat.AZTEC)
        model.editText("keep")
        model.imageRead(model.beginRead(), image, emptyList(), "clipboard")
        assertEquals("No barcode found", model.imageStatus)
        assertEquals("keep", model.text)
        assertEquals(CodeFormat.AZTEC, model.format)
        assertEquals(Source.TEXT, model.source)
    }

    @Test
    fun `readFailed shows its message`() {
        model.readFailed(model.beginRead(), "The image could not be read.")
        assertEquals("The image could not be read.", model.imageStatus)
    }

    @Test
    fun `selectCode sets the text and format and stays an image`() {
        model.imageRead(model.beginRead(), image, listOf(code("one"), code("2", CodeFormat.AZTEC)), "x")
        model.selectCode(1)
        assertEquals("2", model.text)
        assertEquals(CodeFormat.AZTEC, model.format)
        assertEquals(1, model.selectedCode)
        assertEquals(Source.IMAGE, model.source)
        model.selectCode(5)
        assertEquals(1, model.selectedCode)
    }

    @Test
    fun `the image status names the format of the selected code`() {
        model.imageRead(model.beginRead(), image, listOf(code("one"), code("2", CodeFormat.AZTEC)), "two.jpg")
        assertEquals("Read from two.jpg · 2 codes · QR Code", model.imageStatus)

        model.selectCode(1)
        assertEquals("Read from two.jpg · 2 codes · Aztec", model.imageStatus)
        model.selectCode(0)
        assertEquals("Read from two.jpg · 2 codes · QR Code", model.imageStatus)
    }

    @Test
    fun `showsReadStatus is true only while the status is the read text`() {
        assertFalse(model.showsReadStatus)
        model.imageRead(model.beginRead(), image, listOf(code("one"), code("2", CodeFormat.AZTEC)), "two.jpg")
        assertTrue(model.showsReadStatus)

        model.readFailed(model.beginRead(), "The image could not be read.")
        assertFalse(model.showsReadStatus)

        model.imageRead(model.beginRead(), image, listOf(code("one"), code("2", CodeFormat.AZTEC)), "two.jpg")
        model.imageRead(model.beginRead(), image, emptyList(), "two.jpg")
        assertFalse(model.showsReadStatus)
    }

    @Test
    fun `selecting a code keeps a failure message that replaced the read status`() {
        model.imageRead(model.beginRead(), image, listOf(code("one"), code("2", CodeFormat.AZTEC)), "two.jpg")
        model.readFailed(model.beginRead(), "The image could not be read.")
        model.selectCode(1)
        assertEquals("The image could not be read.", model.imageStatus)
    }

    @Test
    fun `an edit after a read makes the text the source and regenerates`() {
        model.imageRead(model.beginRead(), image, listOf(code("one")), "x")
        model.editText("one!")
        assertEquals(Source.TEXT, model.source)
        assertNotNull(model.generated)
        assertNull(model.image)
    }

    @Test
    fun `the colour warning follows the options`() {
        assertNull(model.colorWarning)
        model.setOptions(GenerateOptions(foreground = -1, background = 0xFF000000.toInt()))
        assertNotNull(model.colorWarning)
    }

    @Test
    fun `listeners are told and can be removed`() {
        var calls = 0
        val remove = model.addListener { calls++ }
        model.editText("a")
        remove()
        model.editText("b")
        assertEquals(1, calls)
    }

    @Test
    fun `a format or option change after a read regenerates, makes the text the source and drops the image`() {
        val read = { model.imageRead(model.beginRead(), image, listOf(code("12345"), code("2")), "x") }
        read()
        model.setFormat(CodeFormat.DATA_MATRIX)
        assertEquals(Source.TEXT, model.source)
        assertEquals(CodeFormat.DATA_MATRIX, model.generated?.format)
        assertNull(model.image)
        assertTrue(model.codes.isEmpty())

        read()
        model.setOptions(model.options.copy(sizePx = 1024))
        assertEquals(Source.TEXT, model.source)
        assertNotNull(model.generated)
        assertNull(model.image)
        assertTrue(model.codes.isEmpty())
    }

    @Test
    fun `a format or option change makes a pending read stale`() {
        val first = model.beginRead()
        model.setFormat(CodeFormat.AZTEC)
        model.imageRead(first, image, listOf(code("late")), "x")
        assertEquals("", model.text)

        val second = model.beginRead()
        model.setOptions(GenerateOptions(sizePx = 1024))
        model.imageRead(second, image, listOf(code("late")), "x")
        assertEquals("", model.text)
        assertEquals(Source.TEXT, model.source)
    }

    @Test
    fun `the quiet zone is kept only when it differs from the default of the format`() {
        val options = GenerateOptions()
        assertNull(quietZoneToKeep(options, CodeFormat.QR_CODE, 4))
        assertEquals(3, quietZoneToKeep(options, CodeFormat.QR_CODE, 3))
        assertNull(quietZoneToKeep(options, CodeFormat.EAN_13, 10))
        assertEquals(4, quietZoneToKeep(options, CodeFormat.EAN_13, 4))
        // An explicit value does not change what the default is.
        assertNull(quietZoneToKeep(options.copy(quietZone = 7), CodeFormat.AZTEC, 1))
    }
}
