package dev.sluide.httpclient.envedit

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.HttpEnvironmentFileViewer
import dev.sluide.httpclient.env.EnvironmentFiles
import javafx.event.ActionEvent
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertNotNull

/**
 * What the UI tests of the environment files' view share: a folder with the files, the view in a window with a
 * [session], and a [host] whose tabs are a map of texts that records the edits.
 */
internal abstract class EnvironmentFileViewUiFixture {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()
    protected val viewer = HttpEnvironmentFileViewer()
    protected val session = HttpClientSession()
    protected val host = FakeHost()

    @AfterEach
    fun closeSession() = session.close()

    protected fun startView(stage: Stage) {
        stage.scene = Scene(container, 1200.0, 700.0)
        stage.show()
    }

    /** The other files' tabs: their texts by file, and every file [editText] changed. */
    inner class FakeHost : FileViewHost {
        val tabs = mutableMapOf<Path, String>()
        val edited = mutableListOf<Path>()
        var canEdit = true

        override fun openFile(file: Path) = false

        override fun openWebPage(uri: URI) {}

        override fun <T : Any> windowService(type: Class<T>): T? = session.takeIf(type::isInstance)?.let(type::cast)

        override fun currentText(file: Path): String? = tabs[file.toAbsolutePath().normalize()]

        override fun editText(
            file: Path,
            edit: (String) -> String,
        ): Boolean {
            val key = file.toAbsolutePath().normalize()
            if (!canEdit || !Files.exists(key)) return false
            tabs[key] = edit(tabs[key] ?: Files.readString(key))
            edited.add(key)
            return true
        }
    }

    protected val publicFile: Path get() = dir.resolve(EnvironmentFiles.PUBLIC)
    protected val privateFile: Path get() = dir.resolve(EnvironmentFiles.PRIVATE)

    /** [text] without its indentation, with a trailing newline, as the form writes files. */
    protected fun json(text: String) = text.trimIndent() + "\n"

    /** Writes [publicText] and [privateText] (`null`: no file) and opens [open] of them in the [host]'s window. */
    protected fun open(
        robot: FxRobot,
        publicText: String?,
        privateText: String? = null,
        open: Path = publicFile,
    ): EnvironmentFileView {
        publicText?.let { publicFile.writeText(it) }
        privateText?.let { privateFile.writeText(it) }
        val view = WaitForAsyncUtils.asyncFx<EnvironmentFileView> { viewer.open(open) as EnvironmentFileView }.get()
        robot.interact {
            container.children.setAll(view.node)
            view.host = host
        }
        WaitForAsyncUtils.waitForFxEvents()
        return view
    }

    /** Two environments with a text, a number and a yes/no. */
    protected val publicText =
        json(
            """
            {
              "dev": {
                "host": "https://dev",
                "port": 8080,
                "debug": true
              },
              "prod": {
                "host": "https://prod"
              }
            }
            """,
        )

    /** A private value of `dev`. */
    protected val privateText =
        json(
            """
            {
              "dev": {
                "token": "s3cret"
              }
            }
            """,
        )

    protected fun path(vararg keys: String) = ValuePath.of(*keys)

    /** The tree item of the line with [key] (a [ValuePath] for rows). */
    protected fun EnvironmentFileView.item(key: Any): TreeItem<EnvEntry> {
        fun find(item: TreeItem<EnvEntry>): TreeItem<EnvEntry>? =
            if (item.value?.key == key) item else item.children.firstNotNullOfOrNull(::find)
        return assertNotNull(find(form.tree.root), "line $key")
    }

    protected fun EnvironmentFileView.row(path: ValuePath): Row = (item(path).value as EnvEntry.Item).row

    /** Selects the line with [key] in the name column. */
    protected fun EnvironmentFileView.select(
        robot: FxRobot,
        key: Any,
    ) {
        robot.interact { form.tree.selectionModel.select(form.tree.getRow(item(key)), form.tree.columns[0]) }
    }

    /** The cell of [path] in the column of [env], as shown. */
    protected fun EnvironmentFileView.cell(
        robot: FxRobot,
        path: ValuePath,
        env: String,
    ): EnvValueCell {
        robot.interact { form.tree.scrollTo(form.tree.getRow(item(path))) }
        WaitForAsyncUtils.waitForFxEvents()
        return robot
            .lookup(".env-value-cell")
            .queryAllAs(EnvValueCell::class.java)
            .first { (it.item as? EnvEntry.Item)?.row?.path == path && it.tableColumn?.userData == env && it.isVisible }
    }

    /** The text the cell of [path] in [env] shows. */
    protected fun EnvironmentFileView.shown(
        robot: FxRobot,
        path: ValuePath,
        env: String,
    ): String? = ((cell(robot, path, env).graphic as? HBox)?.children?.first() as? Label)?.text

    /** Starts editing the cell of [path] in [env], as a double-click would. */
    protected fun EnvironmentFileView.edit(
        robot: FxRobot,
        path: ValuePath,
        env: String,
    ): EnvValueCell {
        val cell = cell(robot, path, env)
        robot.interact { form.tree.edit(form.tree.getRow(item(path)), cell.tableColumn) }
        WaitForAsyncUtils.waitForFxEvents()
        return cell
    }

    /** Edits the text of [path] in [env] to [text] and commits it with Enter. */
    protected fun EnvironmentFileView.type(
        robot: FxRobot,
        path: ValuePath,
        env: String,
        text: String,
    ) {
        val cell = edit(robot, path, env)
        val field = cell.graphic as TextField
        robot.interact {
            field.text = text
            field.fireEvent(ActionEvent())
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Fires a press and release of the primary button with [clicks] at the middle of [node]. */
    protected fun click(
        node: Node,
        clicks: Int,
    ) {
        val point = node.localToScene(node.layoutBounds.width / 2, node.layoutBounds.height / 2)
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            val event =
                MouseEvent(
                    type,
                    point.x,
                    point.y,
                    point.x,
                    point.y,
                    MouseButton.PRIMARY,
                    clicks,
                    false,
                    false,
                    false,
                    false,
                    type == MouseEvent.MOUSE_PRESSED,
                    false,
                    false,
                    false,
                    false,
                    true,
                    null,
                )
            Event.fireEvent(node, event)
        }
    }

    /** Fires a press of [code] at [node]. */
    protected fun press(
        node: Node,
        code: KeyCode,
    ) {
        Event.fireEvent(node, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    /** The text of the [host]'s tab of [file]. */
    protected fun tab(file: Path): String? = host.tabs[file.toAbsolutePath().normalize()]
}
