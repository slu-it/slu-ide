package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import dev.sluide.settings.SettingsStore
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.TreeCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Renders the real view in a stage. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewUiTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var settingsDir: Path

    private lateinit var view: FileExplorerView
    private lateinit var fontSettings: FontSettings
    private val watcher = FileWatcher(Platform::runLater, 50.milliseconds)

    @Start
    fun start(stage: Stage) {
        fontSettings =
            FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
        view = FileExplorerView(watcher, fontSettings)
        stage.scene = Scene(view, 400.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun closeWatcher() = watcher.close()

    private fun renderedNames(robot: FxRobot): List<String> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .sortedBy { it.index } // lookups return cells in no particular order
            .mapNotNull { it.text }

    @Test
    fun `renders hidden files and folders, folders first`(robot: FxRobot) {
        project.resolve(".hidden-dir").createDirectories()
        project.resolve("sub").createDirectories()
        project.resolve(".hidden-file").createFile()
        project.resolve("visible.txt").createFile()

        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            listOf(project.name, ".hidden-dir", "sub", ".hidden-file", "visible.txt"),
            renderedNames(robot),
        )
    }

    @Test
    fun `expanding a folder renders its children`(robot: FxRobot) {
        project.resolve("sub/deeper").createDirectories()
        project.resolve("sub/inner.txt").createFile()
        robot.interact { view.open(project) }

        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "sub", "deeper", "inner.txt"), renderedNames(robot))
    }

    @Test
    fun `opening null clears the view`(robot: FxRobot) {
        project.resolve("a.txt").createFile()
        robot.interact { view.open(project) }

        robot.interact { view.open(null) }
        WaitForAsyncUtils.waitForFxEvents()

        assertNull(view.root)
        assertEquals(emptyList(), renderedNames(robot))
    }

    @Test
    fun `requests opening files but not folders`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val folder = project.resolve("sub").createDirectories()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }

        robot.interact {
            assertTrue(view.requestOpen(file))
            assertFalse(view.requestOpen(folder))
        }

        assertEquals(listOf(file), requested)
    }

    @Test
    fun `Enter on a selected file requests opening it`(robot: FxRobot) {
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }

        robot.interact {
            view.selectionModel.select(view.root.children.single())
            pressKey(KeyCode.ENTER)
            view.selectionModel.select(view.root)
            pressKey(KeyCode.ENTER)
        }

        assertEquals(listOf(file), requested)
    }

    private fun pressKey(code: KeyCode) =
        Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))

    @Test
    fun `double-click on a file requests opening it, on a folder it does not`(robot: FxRobot) {
        val folder = project.resolve("sub").createDirectories()
        val file = project.resolve("a.txt").createFile()
        val requested = mutableListOf<Path>()
        view.onOpenFile = { requested.add(it) }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        val cells = robot.lookup(".tree-cell").queryAllAs(TreeCell::class.java).filter { it.item != null }
        robot.interact {
            doubleClick(cells.single { it.item == file })
            doubleClick(cells.single { it.item == folder })
        }

        assertEquals(listOf(file), requested)
    }

    private fun doubleClick(cell: TreeCell<*>) =
        Event.fireEvent(
            cell,
            MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1.0,
                1.0,
                1.0,
                1.0,
                MouseButton.PRIMARY,
                2,
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

    @Test
    fun `files created and deleted on disk appear and disappear`(robot: FxRobot) {
        project.resolve("existing.txt").createFile()
        robot.interact { view.open(project) }

        val created = project.resolve("created.txt").createFile()
        eventually(robot) { assertEquals(listOf(project.name, "created.txt", "existing.txt"), renderedNames(robot)) }

        created.deleteExisting()
        eventually(robot) { assertEquals(listOf(project.name, "existing.txt"), renderedNames(robot)) }
    }

    @Test
    fun `changes inside an expanded subfolder appear and keep the expansion`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }

        project.resolve("sub/inner.txt").createFile()
        project.resolve("top.txt").createFile()

        eventually(robot) {
            assertEquals(listOf(project.name, "sub", "inner.txt", "top.txt"), renderedNames(robot))
        }
    }

    @Test
    fun `Refresh in the context menu re-reads loaded folders`(robot: FxRobot) {
        // Without working watches, only a manual refresh can show the new file.
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings)
            view.scene.root = manual
            manual.open(project)
        }
        project.resolve("late.txt").createFile()

        val refresh = manual.contextMenu.items.single()
        assertEquals("Refresh", refresh.text)
        robot.interact { refresh.fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))
    }

    @Test
    fun `F5 refreshes`(robot: FxRobot) {
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings)
            view.scene.root = manual
            manual.open(project)
        }
        project.resolve("late.txt").createFile()

        robot.interact {
            Event.fireEvent(manual, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F5, false, false, false, false))
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(project.name, "late.txt"), renderedNames(robot))
    }

    @Test
    fun `a deleted and recreated subfolder keeps being watched`(robot: FxRobot) {
        val sub = project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        robot.interact {
            view.root.children
                .single()
                .isExpanded = true
        }

        sub.toFile().deleteRecursively()
        sub.createDirectories()
        sub.resolve("first.txt").createFile()
        eventually(robot) { assertEquals(listOf(project.name, "sub", "first.txt"), renderedNames(robot)) }

        sub.resolve("second.txt").createFile()
        eventually(robot) {
            assertEquals(listOf(project.name, "sub", "first.txt", "second.txt"), renderedNames(robot))
        }
    }

    /** Repeats [assertion] on the FX thread until it passes; the JDK may poll for file changes on macOS. */
    private fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            try {
                var failure: AssertionError? = null
                robot.interact {
                    try {
                        assertion()
                    } catch (e: AssertionError) {
                        failure = e
                    }
                }
                failure?.let { throw it }
                return
            } catch (e: AssertionError) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun `the tree uses the explorer font and follows its changes`(robot: FxRobot) {
        project.resolve("README.md").createFile()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { fontSettings.apply(mapOf(FontArea.EXPLORER to FontSpec("Serif", 19.0, FontStyle.BOLD))) }
        WaitForAsyncUtils.waitForFxEvents()

        val cell =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .first { it.text == "README.md" }
        assertEquals(19.0, cell.font.size)
        assertEquals("Serif", cell.font.family)
        assertTrue("Bold" in cell.font.style, cell.font.style)
    }

    private fun cellNamed(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> =
        robot
            .lookup(".tree-cell")
            .queryAllAs(TreeCell::class.java)
            .first { it.text == name }

    private fun iconOf(
        robot: FxRobot,
        name: String,
    ): FontIcon = cellNamed(robot, name).graphic.lookup(".ikonli-font-icon") as FontIcon

    /** The style classes of the markers after [name], in the order they are shown. */
    private fun markersOf(
        robot: FxRobot,
        name: String,
    ): List<String> =
        cellNamed(robot, name)
            .graphic
            .lookupAll(".file-marker")
            .filter { it.isVisible && it.parent.isVisible }
            .sortedBy { it.localToScene(0.0, 0.0).x }
            .map { node -> node.styleClass.single { it != "file-marker" } }

    @Test
    fun `shows the markers given for each entry after its name, in order`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        project.resolve("changed.kt").createFile()
        project.resolve("clean.kt").createFile()
        var shown =
            mapOf(
                "sub" to listOf(FileMarker.UNVERSIONED, FileMarker.ADDED, FileMarker.MODIFIED),
                "changed.kt" to listOf(FileMarker.MODIFIED),
            )
        val asked = mutableListOf<Pair<String, Boolean>>()
        view.markers = { path, directory ->
            asked += path.name to directory
            shown[path.name].orEmpty()
        }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            listOf("file-marker-unversioned", "file-marker-added", "file-marker-modified"),
            markersOf(robot, "sub"),
        )
        assertEquals(listOf("file-marker-modified"), markersOf(robot, "changed.kt"))
        assertEquals(emptyList(), markersOf(robot, "clean.kt"))
        assertTrue("sub" to true in asked && "clean.kt" to false in asked, asked.toString())
        // The markers follow the name.
        val cell = cellNamed(robot, "changed.kt")
        val label = cell.graphic.lookup(".label")
        val bubble = cell.graphic.lookup(".file-marker-modified")
        assertTrue(bubble.localToScene(0.0, 0.0).x > label.localToScene(label.layoutBounds.maxX, 0.0).x)

        shown = mapOf("clean.kt" to listOf(FileMarker.IGNORED))
        robot.interact { view.refreshMarkers() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(emptyList(), markersOf(robot, "sub"))
        assertEquals(emptyList(), markersOf(robot, "changed.kt"))
        assertEquals(listOf("file-marker-ignored"), markersOf(robot, "clean.kt"))
    }

    @Test
    fun `reports changed files and refreshes`(robot: FxRobot) {
        var reports = 0
        view.onFilesChanged = { reports++ }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        project.resolve("new.txt").createFile()
        eventually(robot) { assertEquals(1, reports) }

        robot.interact { view.refreshAll() }
        assertEquals(2, reports)
    }

    @Test
    fun `markers have a colour in both themes`(robot: FxRobot) {
        project.resolve("a.kt").createFile()
        view.markers = { _, _ -> FileMarker.entries }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        fun colours() =
            FileMarker.entries.map {
                (cellNamed(robot, "a.kt").graphic.lookup(".${it.styleClass}") as Circle).fill
            }
        val light = colours()
        assertEquals(Color.web("#d32f2f"), light.first())
        robot.interact { view.scene.preferences.colorScheme = ColorScheme.DARK }
        WaitForAsyncUtils.waitForFxEvents()
        val dark = colours()
        assertEquals(Color.web("#ef6461"), dark.first())
        assertEquals(4, light.distinct().size)
        assertTrue(light.zip(dark).all { (l, d) -> l != d })
    }

    @Test
    fun `shows an icon per file type, readable or binary for unknown types`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        project.resolve("Main.kt").createFile()
        project.resolve("notes.adoc").createFile()
        project.resolve("blob.bin").createFile()
        val checked = mutableListOf<String>()
        view.canOpenFile = { file ->
            checked += file.name
            file.name != "blob.bin"
        }

        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            assertEquals(FileIcon.FOLDER_OPEN.ikon, iconOf(robot, project.name).iconCode)
            assertEquals(FileIcon.FOLDER.ikon, iconOf(robot, "sub").iconCode)
            assertEquals(FileIcon.KOTLIN.ikon, iconOf(robot, "Main.kt").iconCode)
            assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "notes.adoc").iconCode)
            assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "blob.bin").iconCode)
            assertTrue("file-icon-kotlin" in iconOf(robot, "Main.kt").styleClass)
            // The colour comes from file-explorer.css.
            assertEquals(Color.web("#7f52ff"), iconOf(robot, "Main.kt").iconColor)
        }
        // Only files without a known extension are checked, each once however often its cell is rendered.
        robot.interact { view.refresh() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("blob.bin", "notes.adoc"), checked.sorted())
    }

    @Test
    fun `the folder icon opens and closes with the folder`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { view.root.isExpanded = false }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals(FileIcon.FOLDER.ikon, iconOf(robot, project.name).iconCode) }

        robot.interact { view.root.isExpanded = true }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals(FileIcon.FOLDER_OPEN.ikon, iconOf(robot, project.name).iconCode) }
    }

    @Test
    fun `a file that changes on disk is checked again`(robot: FxRobot) {
        val file = project.resolve("data")
        file.writeBytes(byteArrayOf(0, 1, 2))
        view.canOpenFile = { 0.toByte() !in Files.readAllBytes(it) }
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "data").iconCode) }

        file.writeText("now text")

        eventually(robot) { assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "data").iconCode) }
    }

    @Test
    fun `icons are sized to the explorer font`(robot: FxRobot) {
        project.resolve("README.md").createFile()
        robot.interact { view.open(project) }
        robot.interact { fontSettings.apply(mapOf(FontArea.EXPLORER to FontSpec("Serif", 20.0))) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact { assertEquals(25, iconOf(robot, "README.md").iconSize) }
    }

    @Test
    fun `icon colours follow the theme`(robot: FxRobot) {
        project.resolve("Main.kt").createFile()
        robot.interact { view.open(project) }
        WaitForAsyncUtils.waitForFxEvents()

        fun kotlinColor(scheme: ColorScheme): Any? {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            var color: Any? = null
            robot.interact { color = iconOf(robot, "Main.kt").iconColor }
            return color
        }
        assertEquals(Color.web("#7f52ff"), kotlinColor(ColorScheme.LIGHT))
        assertEquals(Color.web("#a78bfa"), kotlinColor(ColorScheme.DARK))
        assertEquals(Color.web("#7f52ff"), kotlinColor(ColorScheme.LIGHT))
    }
}
