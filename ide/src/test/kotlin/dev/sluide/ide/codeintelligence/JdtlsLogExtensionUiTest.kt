package dev.sluide.ide.codeintelligence

import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [JdtlsLogExtension] in a real code editor. Needs a display: the lock refuses typed keys and the caret scrolls only in
 * a code editor with a scene; what is decided is `JdtlsLogPathTest`'s and `JdtlsLogExtensionTest`'s.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JdtlsLogExtensionUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    private var editor: CodeEditor? = null

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    @AfterEach
    fun dispose() {
        editor?.let { WaitForAsyncUtils.asyncFx { it.dispose() }.get() }
    }

    private fun host() =
        object : FileViewHost {
            private val services =
                WindowServices().apply {
                    register(
                        CodeEditorExtensions::class.java,
                        CodeEditorExtensions().apply {
                            add(
                                JdtlsLogExtension(dir.resolve("workspaces"), ::readOnlyBanner),
                            )
                        },
                    )
                }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    private fun open(
        robot: FxRobot,
        file: Path,
        text: String,
    ): CodeEditor {
        file.parent.createDirectories()
        file.writeText(text)
        val opened = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file, null) }.get()
        editor = opened
        robot.interact {
            container.children.setAll(opened.node)
            opened.host = host()
        }
        return opened
    }

    private fun type(
        robot: FxRobot,
        character: String,
    ) = robot.interact {
        val area = robot.lookup(".styled-text-area").queryAs(Node::class.java)
        Event.fireEvent(
            area,
            KeyEvent(KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, false, false, false, false),
        )
    }

    private val lines = (1..80).joinToString("") { "line $it\n" }

    @Test
    fun `the log opens read-only with its banner and the caret on its last line`(robot: FxRobot) {
        val log = dir.resolve("workspaces/app-1/data/.metadata/.log")
        val opened = open(robot, log, lines)

        type(robot, "x")

        assertEquals(lines, opened.text, "typing changed nothing")
        assertFalse(opened.dirty.get())
        assertTrue(opened.readOnlyMarked.get())
        assertEquals(80, opened.caretLine)
        assertTrue(opened.topLine > 0, "the view scrolled to the last lines")
        assertEquals(
            "jdtls's log: read-only. jdtls writes to it while it runs.",
            robot.lookup(".editor-banner .label").queryAs(Label::class.java).text,
        )
    }
}
