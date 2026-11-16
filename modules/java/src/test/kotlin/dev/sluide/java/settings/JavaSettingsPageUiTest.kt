package dev.sluide.java.settings

import dev.sluide.java.JavaWorkspaceUse
import dev.sluide.java.JavaWorkspaces
import dev.sluide.java.JdtlsFakes
import dev.sluide.java.JdtlsUpdates
import dev.sluide.servertools.HttpAnswer
import dev.sluide.servertools.ServerToolDialogs
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
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
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The real Java page: its extra parts reach [JavaSettingsPageModel] (the combo box, the table, the Delete button) and
 * follow the model and [JavaWorkspaceUse], which only a created page shows; the rules are in
 * [JavaSettingsPageModelTest]. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class JavaSettingsPageUiTest {
    @TempDir
    lateinit var dir: Path

    private val fakes by lazy { JdtlsFakes(dir) }
    private val root by lazy { dir.resolve("workspaces").createDirectories() }
    private val use = JavaWorkspaceUse()
    private val questions = mutableListOf<String>()
    private val stage = StackPane()
    private var dispatch: (Runnable) -> Unit = Platform::runLater

    private val titles = mutableListOf<String>()
    private val installer by lazy { fakes.installer() }

    private val page by lazy {
        val updates =
            JdtlsUpdates(fakes.tool, { _, _ ->
                HttpAnswer(
                    404,
                    emptyMap(),
                    "",
                )
            }, { _, _ -> HttpAnswer(404, emptyMap(), "") }, Runnable::run, {
                it.run()
            })
        val dialogs =
            object : ServerToolDialogs {
                override fun confirmDelete(question: String): Boolean {
                    questions += question
                    return true
                }

                override fun confirmDelete(
                    title: String,
                    question: String,
                ): Boolean {
                    titles += title
                    return confirmDelete(question)
                }

                override fun chooseExecutable(current: String): Path? = null
            }
        // The listing runs inline on the FX thread of the test; its rows arrive through Platform.runLater.
        JavaSettingsPage(
            fakes.settings,
            installer,
            updates,
            JavaWorkspaces(root),
            use,
            dialogs,
            { it.run() },
            { dispatch(it) },
        )
    }

    @Start
    fun start(window: Stage) {
        window.scene = Scene(stage, 700.0, 700.0)
        window.show()
    }

    @Test
    fun `the page shows the memory, the workspaces and a Delete that follows the selection and the use`(
        robot: FxRobot,
    ) {
        val folder = root.resolve("app-0123456789ab").createDirectories()
        folder.resolve("project-path.txt").writeText("/work/app")
        folder.resolve("last-used").writeText("2026-11-15T10:30:00Z")
        folder.resolve("index.bin").writeBytes(ByteArray(2_000_000))
        robot.interact {
            stage.children += page.node
            page.load()
        }
        WaitForAsyncUtils.waitForFxEvents()

        val labels = robot.lookup(".label").queryAllAs(Label::class.java).map { it.text }
        assertTrue("jdtls (Eclipse JDT Language Server), the language server for Java" in labels, labels.toString())
        assertTrue("Maximum memory" in labels && "Java workspaces" in labels, labels.toString())
        val extra = checkNotNull(page.extra)
        assertEquals(
            listOf("1 GB", "2 GB", "3 GB", "4 GB", "6 GB", "8 GB"),
            extra.memory.items.map { label(extra.memory, it) },
        )
        assertEquals("2 GB", label(extra.memory, extra.memory.value))

        val table = robot.lookup("#java-workspaces").queryAs(TableView::class.java)
        assertEquals(1, table.items.size)
        val cells = robot.lookup(".table-cell").queryAllAs(javafx.scene.control.TableCell::class.java).map { it.text }
        assertTrue(
            "/work/app" in cells && "2.0 MB" in cells && "2026-11-15" in (cells.joinToString()),
            cells.toString(),
        )
        val delete = robot.lookup("#java-workspace-delete").queryAs(Button::class.java)
        assertTrue(delete.isDisable, "nothing selected")

        robot.interact { table.selectionModel.select(0) }
        assertFalse(delete.isDisable)
        val held = use.acquire(folder)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("Delete… (in use)", delete.text)
        assertTrue(delete.isDisable)
        held.close()
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals("Delete…", delete.text)

        robot.interact { delete.fire() }
        assertEquals(1, questions.size)
        assertEquals(listOf("Delete Java Workspace"), titles)
        assertFalse(Files.exists(folder))
        assertEquals(0, table.items.size)

        robot.interact {
            extra.memory.value = 4096
            page.apply()
        }
        assertEquals(4096, fakes.settings.maxMemoryMb.get())

        robot.interact {
            extra.memory.value = 1024
            page.load()
        }
        assertEquals(4096, extra.memory.value, "loading drops the unapplied choice")
        var calls = 0
        installer.onCommandChanged { calls++ }
        robot.interact {
            robot
                .lookup(".radio-button")
                .queryAllAs(RadioButton::class.java)
                .single { it.text == "Custom path" }
                .fire()
            robot
                .lookup(".text-field")
                .queryAllAs(TextField::class.java)
                .single()
                .text = "/opt/jdtls/bin/jdtls"
            extra.memory.value = 8192
            page.apply()
        }
        assertEquals(1, calls, "one Apply that changes the path and the memory restarts the server once")
        assertTrue(table.columns.none { it.isSortable })
        assertEquals(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN, table.columnResizePolicy)
    }

    @Test
    fun `a failed delete shows its message and a window's use is seen at once on the FX thread`(robot: FxRobot) {
        robot.interact {
            stage.children += page.node
            page.load()
        }
        val extra = checkNotNull(page.extra)

        robot.interact {
            page.extraModel.delete(WorkspaceRow(dir.resolve("elsewhere"), "/work/x", "1 B", "unknown"))
        }
        robot.interact { }

        assertTrue(extra.message.text.startsWith("Cannot delete /work/x: "), extra.message.text)

        val folder = root.resolve("app-0123456789ab")
        var seen = false
        robot.interact {
            use.acquire(folder).close()
            use.acquire(folder).also { seen = folder in use.inUse }.close()
        }
        assertTrue(seen)
    }

    @Test
    fun `a page loaded before its node exists shows the stored memory once the node is there`(robot: FxRobot) {
        fakes.settings.applyMaxMemory(6144)
        robot.interact { page.load() }

        robot.interact { stage.children += page.node }

        val extra = checkNotNull(page.extra)
        assertEquals("6 GB", label(extra.memory, extra.memory.value))
        assertEquals(6, extra.memory.items.indexOf(8192) + 1)
    }

    @Test
    fun `an empty table says what it waits for`(robot: FxRobot) {
        val pending = mutableListOf<Runnable>()
        dispatch = { pending += it }
        robot.interact {
            stage.children += page.node
            page.load()
        }
        val placeholder = checkNotNull(page.extra).table.placeholder as Label

        assertEquals("Looking for Java workspaces…", placeholder.text)

        robot.interact { pending.forEach(Runnable::run) }

        assertEquals("No Java workspaces yet.", placeholder.text)
    }

    private fun label(
        box: ComboBox<Int>,
        value: Int,
    ) = box.converter.toString(value)
}
