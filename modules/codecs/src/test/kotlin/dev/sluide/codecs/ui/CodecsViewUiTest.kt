package dev.sluide.codecs.ui

import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.HashAlgorithm
import dev.sluide.codecs.conversion.ConversionModel
import dev.sluide.codecs.conversion.Side
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Each connection of the Codecs view to its model needs the controls in a scene (selection, paste, focus, clicks).
 * Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CodecsViewUiTest {
    @TempDir
    lateinit var dir: Path

    private val model = ConversionModel()
    private var fileToOpen: Path? = null
    private var fileToSave: Path? = null
    private val suggestedNames = mutableListOf<String>()
    private lateinit var view: CodecsView
    private lateinit var stage: Stage

    private val pngBase64 = "iVBORw0KGgoAAAANSUhEUg=="

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        view = newView()
        stage.scene = Scene(view.node as Parent, 1100.0, 600.0)
        stage.show()
    }

    private fun newView() =
        CodecsView(
            model,
            { _: Window? -> fileToOpen },
            { _: Window?, name: String ->
                suggestedNames += name
                fileToSave
            },
        )

    /** Reads UI state on the FX thread. */
    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun area(
        prompt: String,
        of: CodecsView = view,
    ): TextArea =
        of.node
            .lookupAll(".text-area")
            .filterIsInstance<TextArea>()
            .first { it.promptText == prompt }

    private fun text(
        prompt: String,
        of: CodecsView = view,
    ): String = fx { area(prompt, of).text }

    private fun buttons(text: String): List<Button> =
        view.node
            .lookupAll(".button")
            .filterIsInstance<Button>()
            .filter { it.text == text }

    private fun statuses(): List<Label> = view.node.lookupAll(".codecs-status").filterIsInstance<Label>()

    private fun clipboard(): String? = fx { Clipboard.getSystemClipboard().string }

    private fun choose(
        robot: FxRobot,
        codec: CodecId,
    ) = robot.interact { (view.node.lookup("#codec-${codec.name.lowercase()}") as ToggleButton).fire() }

    private fun click(
        robot: FxRobot,
        node: Node,
    ) = robot.interact {
        Event.fireEvent(
            node,
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
                true,
                null,
            ),
        )
    }

    @Test
    fun `typing on the text side updates the encoded side`(robot: FxRobot) {
        robot.interact { area("Text").text = "hello world" }

        assertEquals("aGVsbG8gd29ybGQ=", text("Encoded"))
    }

    @Test
    fun `setting the encoded side decodes into the text side`(robot: FxRobot) {
        robot.interact { area("Encoded").text = "aGVsbG8gd29ybGQ=" }

        assertEquals("hello world", text("Text"))
    }

    @Test
    fun `Clear on either side empties both`(robot: FxRobot) {
        robot.interact { area("Text").text = "hello" }
        robot.interact { buttons("Clear")[0].fire() }
        assertEquals("", text("Text"))
        assertEquals("", text("Encoded"))
        assertEquals(Side.TEXT, model.source)

        robot.interact { area("Text").text = "hello" }
        robot.interact { buttons("Clear")[1].fire() }
        assertEquals("", text("Text"))
        assertEquals("", text("Encoded"))
        assertEquals(Side.ENCODED, model.source)
    }

    @Test
    fun `the Copy buttons copy their side`(robot: FxRobot) {
        robot.interact { area("Text").text = "hello" }

        robot.interact { buttons("Copy")[0].fire() }
        assertEquals("hello", clipboard())
        robot.interact { buttons("Copy")[1].fire() }
        assertEquals("aGVsbG8=", clipboard())
    }

    @Test
    fun `a pasted token shows the hint whose button switches to JWT`(robot: FxRobot) {
        val jwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ." +
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        robot.interact {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(jwt) })
            area("Encoded").paste()
        }
        assertTrue(fx { view.node.lookup(".codecs-hint").isVisible }, "the pasted token is offered as JWT")

        robot.interact { buttons("Switch").single().fire() }

        assertEquals(CodecId.JWT, model.settings.codec)
        assertFalse(fx { area("Text").isEditable })
        assertTrue(text("Text").startsWith("Header"), text("Text"))
    }

    @Test
    fun `choosing a codec recomputes and the encoded header shows its title`(robot: FxRobot) {
        robot.interact { area("Text").text = "hi" }

        choose(robot, CodecId.HEX)

        assertEquals("6869", text("Encoded"))
        val titles =
            fx {
                view.node
                    .lookupAll(".codecs-side-title")
                    .filterIsInstance<Label>()
                    .map { it.text }
            }
        assertTrue("Hex" in titles, titles.toString())
    }

    @Test
    fun `an option control recomputes`(robot: FxRobot) {
        robot.interact { area("Text").text = "???" }
        assertEquals("Pz8/", text("Encoded"))

        robot.interact {
            view.node
                .lookupAll(".check-box")
                .filterIsInstance<CheckBox>()
                .first { it.text == "URL-safe" }
                .isSelected = true
        }

        assertEquals("Pz8_", text("Encoded"))
    }

    @Test
    fun `base64 binary data shows the binary view that saves and edits as text`(robot: FxRobot) {
        robot.interact { area("Encoded").text = pngBase64 }

        assertTrue(fx { view.node.lookup(".codecs-binary").isVisible })
        assertFalse(fx { area("Text").isVisible })
        val title = fx { (view.node.lookup(".codecs-binary-title") as Label).text }
        assertTrue("PNG image" in title, title)
        assertFalse(fx { statuses().first().isVisible }, "the header says what the status line would")

        fileToSave = dir.resolve("out.png")
        robot.interact { (view.node.lookup("#codecs-binary-save") as Button).fire() }
        assertContentEquals(model.bytesToSave(), Files.readAllBytes(dir.resolve("out.png")))

        robot.interact { (view.node.lookup("#codecs-binary-edit") as Button).fire() }
        assertTrue(fx { area("Text").isVisible })
        assertFalse(fx { view.node.lookup(".codecs-binary").isVisible })
        assertTrue(fx { statuses().first().isVisible })
    }

    @Test
    fun `Save as writes the text and suggests the opened file's name only while it is the content`(robot: FxRobot) {
        fileToOpen = dir.resolve("notes.txt").also { Files.writeString(it, "from notes") }
        robot.interact { buttons("Open File…").single().fire() }
        fileToSave = dir.resolve("copy.txt")

        robot.interact { (view.node.lookup("#codecs-save") as Button).fire() }

        assertEquals(listOf("notes.txt"), suggestedNames)
        assertEquals("from notes", Files.readString(dir.resolve("copy.txt")))

        robot.interact {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(pngBase64) })
            area("Encoded").selectAll()
            area("Encoded").paste()
        }
        robot.interact { (view.node.lookup("#codecs-binary-save") as Button).fire() }

        assertEquals(listOf("notes.txt", "decoded.bin"), suggestedNames)
    }

    @Test
    fun `a Save as that cannot write shows the error, also over binary content`(robot: FxRobot) {
        robot.interact { area("Text").text = "hello" }
        fileToSave = dir.resolve("missing").resolve("x.txt")

        robot.interact { (view.node.lookup("#codecs-save") as Button).fire() }

        assertEquals(listOf("decoded.txt"), suggestedNames)
        assertTrue(fx { "codecs-error" in statuses().first().styleClass })

        // Over binary content the header replaces the status line, but an error must still show.
        robot.interact { area("Encoded").text = pngBase64 }
        robot.interact { (view.node.lookup("#codecs-binary-save") as Button).fire() }

        val shown = fx { statuses().first().let { it.isVisible && it.isManaged && "codecs-error" in it.styleClass } }
        assertTrue(shown, "the error is visible over the binary view")
    }

    @Test
    fun `hash shows a row per algorithm, no editing and HMAC rows with a key`(robot: FxRobot) {
        choose(robot, CodecId.HASH)
        robot.interact { area("Text").text = "abc" }

        assertEquals(HashAlgorithm.entries.size, fx { view.node.lookupAll(".codecs-hash-value").size })
        assertFalse(fx { area("Encoded").isVisible })
        assertFalse(fx { area("Encoded").isEditable })

        robot.interact { buttons("").first { "codecs-copy" in it.styleClass }.fire() }
        val first = fx { (view.node.lookupAll(".codecs-hash-value").first() as TextField).text }
        assertEquals(first, clipboard())

        robot.interact { (view.node.lookup("#codecs-key") as TextField).text = "secret" }

        val labels =
            fx {
                view.node
                    .lookupAll(".codecs-group")
                    .filterIsInstance<Label>()
                    .map { it.text }
            }
        assertTrue("HMAC" in labels, labels.toString())
        assertEquals(
            HashAlgorithm.entries.count { it.hmac != null },
            fx { view.node.lookupAll(".codecs-hash-value").size },
        )
    }

    @Test
    fun `Open File shows the text of the chosen file`(robot: FxRobot) {
        fileToOpen = dir.resolve("in.txt").also { Files.writeString(it, "from a file") }

        robot.interact { buttons("Open File…").single().fire() }

        assertEquals("from a file", text("Text"))
    }

    @Test
    fun `a dropped file that is too large shows the error`(robot: FxRobot) {
        val big = dir.resolve("big.bin").toFile()
        RandomAccessFile(big, "rw").use { it.setLength(ConversionModel.MAX_FILE_SIZE + 1L) }

        robot.interact { view.filesDropped(listOf(big)) }

        val status = fx { statuses().first().let { it.text to it.styleClass.toList() } }
        assertTrue("larger than 1 MiB" in status.first, status.first)
        assertTrue("codecs-error" in status.second)
    }

    @Test
    fun `an error with a position names the character and a click selects it`(robot: FxRobot) {
        robot.interact { area("Encoded").text = "ab!d" }

        val status = fx { statuses().last() }
        val shown = fx { status.text to status.styleClass.toList() }
        assertTrue("(character 3)" in shown.first, shown.first)
        assertTrue("codecs-error" in shown.second)

        click(robot, status)
        assertEquals("!", fx { area("Encoded").selectedText })
    }

    @Test
    fun `a disposed view does not react and a second view takes over the model`(robot: FxRobot) {
        robot.interact { area("Text").text = "hello" }
        robot.interact {
            view.dispose()
            model.editText("changed")
        }
        assertEquals("hello", text("Text"), "the disposed view keeps what it showed")

        var second: CodecsView? = null
        robot.interact {
            second = newView()
            // The split and scroll panes create their children with their skins, in a scene.
            stage.scene = Scene(checkNotNull(second).node as Parent, 1100.0, 600.0)
        }
        robot.interact { }
        val shown = checkNotNull(second)
        assertEquals("changed", text("Text", shown))
        assertEquals("Y2hhbmdlZA==", text("Encoded", shown))

        robot.interact {
            view.dispose()
            model.editText("again")
        }
        assertEquals("again", text("Text", shown), "the first view's dispose leaves the second view's callback")
    }
}
