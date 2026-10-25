package dev.sluide.fileexplorer

import dev.sluide.fileicons.FileIcon
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.scene.control.Label
import javafx.scene.control.TreeCell
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The font, icons, bubbles and colours of the rows in the real view. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewMarkersUiTest : FileExplorerViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `the tree uses the explorer font and follows its changes`(robot: FxRobot) {
        project.resolve("README.md").createFile()
        robot.interact { view.open(project) }

        robot.interact { fontSettings.apply(mapOf(FontArea.EXPLORER to FontSpec("Serif", 20.0, FontStyle.BOLD))) }

        val cell =
            robot
                .lookup(".tree-cell")
                .queryAllAs(TreeCell::class.java)
                .first { it.text == "README.md" }
        assertEquals(20.0, cell.font.size)
        // The icons are larger than the text, Material Design icons leave a margin.
        robot.interact { assertEquals(25, iconOf(robot, "README.md").iconSize) }
        assertEquals("Serif", cell.font.family)
        assertTrue("Bold" in cell.font.style, cell.font.style)
    }

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

    /** The label showing [name]. */
    private fun nameLabelOf(
        robot: FxRobot,
        name: String,
    ): Label = cellNamed(robot, name).graphic.lookup(".label") as Label

    /** The style class colouring [name], if any. */
    private fun nameColourOf(
        robot: FxRobot,
        name: String,
    ): String? = nameLabelOf(robot, name).styleClass.singleOrNull { it.startsWith("file-name-") }

    @Test
    fun `shows the bubbles given for each entry after its name, in order`(robot: FxRobot) {
        project.resolve("sub").createDirectories()
        project.resolve("changed.kt").createFile()
        project.resolve("clean.kt").createFile()
        var shown =
            mapOf(
                "sub" to FileMarkers(bubbles = listOf(FileMarker.UNVERSIONED, FileMarker.ADDED, FileMarker.MODIFIED)),
                "changed.kt" to FileMarkers(bubbles = listOf(FileMarker.MODIFIED)),
            )
        val asked = mutableListOf<Pair<String, Boolean>>()
        view.markers = { path, directory ->
            asked += path.name to directory
            shown[path.name] ?: FileMarkers.NONE
        }
        robot.interact { view.open(project) }

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

        shown = mapOf("clean.kt" to FileMarkers(bubbles = listOf(FileMarker.IGNORED)))
        robot.interact { view.refreshMarkers() }

        assertEquals(emptyList(), markersOf(robot, "sub"))
        assertEquals(emptyList(), markersOf(robot, "changed.kt"))
        assertEquals(listOf("file-marker-ignored"), markersOf(robot, "clean.kt"))
    }

    @Test
    fun `colours the name as given and back after a refresh`(robot: FxRobot) {
        project.resolve("changed.kt").createFile()
        project.resolve("clean.kt").createFile()
        var shown = mapOf("changed.kt" to FileMarkers(name = FileMarker.MODIFIED))
        view.markers = { path, _ -> shown[path.name] ?: FileMarkers.NONE }
        robot.interact { view.open(project) }

        assertEquals("file-name-modified", nameColourOf(robot, "changed.kt"))
        assertEquals(Color.web("#1e63c8"), nameLabelOf(robot, "changed.kt").textFill)
        assertEquals(emptyList(), markersOf(robot, "changed.kt"))
        assertNull(nameColourOf(robot, "clean.kt"))
        val normal = cellNamed(robot, "clean.kt").textFill
        assertEquals(normal, nameLabelOf(robot, "clean.kt").textFill)

        // On the selection bar the name has the selection's text colour, so it stays readable.
        robot.interact { view.selectOnly(view.projectNode.children.first { it.value.name == "changed.kt" }) }
        assertNull(nameColourOf(robot, "changed.kt"))
        assertEquals(cellNamed(robot, "changed.kt").textFill, nameLabelOf(robot, "changed.kt").textFill)
        robot.interact { view.selectionModel.clearSelection() }
        assertEquals("file-name-modified", nameColourOf(robot, "changed.kt"))

        shown = emptyMap()
        robot.interact { view.refreshMarkers() }

        assertNull(nameColourOf(robot, "changed.kt"))
        assertEquals(normal, nameLabelOf(robot, "changed.kt").textFill)
    }

    @Test
    fun `reports changed files and refreshes`(robot: FxRobot) {
        val reports = mutableListOf<Set<Path>>()
        view.onFilesChanged = { reports += it }
        robot.interact { view.open(project) }

        val created = project.resolve("new.txt").createFile()
        // The watch may report a change in more than one batch.
        eventually(robot) { assertTrue(reports.any { created in it }, "reported $created, got $reports") }

        robot.interact { view.refreshAll() }
        // Unknown which files changed: refresh anyway.
        assertEquals(emptySet(), reports.last())
    }

    @Test
    fun `markers have a colour in both themes`(robot: FxRobot) {
        project.resolve("a.kt").createFile()
        var nameMarker = FileMarker.UNVERSIONED
        view.markers = { _, _ -> FileMarkers(name = nameMarker, bubbles = FileMarker.entries) }
        robot.interact { view.open(project) }

        fun bubbleColours() =
            FileMarker.entries.map {
                (cellNamed(robot, "a.kt").graphic.lookup(".${it.bubbleStyleClass}") as Circle).fill
            }

        // Name colours equal the bubble colours.
        fun nameColours() =
            FileMarker.entries.map {
                nameMarker = it
                robot.interact { view.refreshMarkers() }
                nameLabelOf(robot, "a.kt").textFill
            }
        val light = bubbleColours()
        assertEquals(Color.web("#d32f2f"), light.first())
        assertEquals(light, nameColours())
        robot.interact { view.scene.preferences.colorScheme = ColorScheme.DARK }
        val dark = bubbleColours()
        assertEquals(Color.web("#ef6461"), dark.first())
        assertEquals(dark, nameColours())
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

        robot.interact {
            assertEquals(FileIcon.FOLDER_OPEN.ikon, iconOf(robot, project.name).iconCode)
            assertEquals(FileIcon.FOLDER.ikon, iconOf(robot, "sub").iconCode)
            assertEquals(FileIcon.KOTLIN.ikon, iconOf(robot, "Main.kt").iconCode)
            assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "notes.adoc").iconCode)
            assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "blob.bin").iconCode)
            assertTrue("file-icon-kotlin" in iconOf(robot, "Main.kt").styleClass)
            // The colour comes from file-icons.css (FILE_ICONS_CSS).
            assertEquals(Color.web("#7f52ff"), iconOf(robot, "Main.kt").iconColor)
        }
        // Only files without a known extension are checked, each once however often its cell is rendered.
        robot.interact { view.refresh() }
        assertEquals(listOf("blob.bin", "notes.adoc"), checked.sorted())
    }

    @Test
    fun `a file that changes on disk is checked again`(robot: FxRobot) {
        val file = project.resolve("data")
        file.writeBytes(byteArrayOf(0, 1, 2))
        view.canOpenFile = { 0.toByte() !in Files.readAllBytes(it) }
        robot.interact { view.open(project) }
        robot.interact { assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "data").iconCode) }

        file.writeText("now text")

        eventually(robot) { assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "data").iconCode) }
    }

    @Test
    fun `a manual refresh checks files again`(robot: FxRobot) {
        // Without working watches, only the refresh can notice that the file became text.
        val unwatched = FileWatcher(Platform::runLater, 50.milliseconds).apply { close() }
        val file = project.resolve("data")
        file.writeBytes(byteArrayOf(0, 1, 2))
        lateinit var manual: FileExplorerView
        robot.interact {
            manual = FileExplorerView(unwatched, fontSettings, ExplorerClipboard())
            manual.canOpenFile = { 0.toByte() !in Files.readAllBytes(it) }
            view.scene.root = manual
            manual.open(project)
        }
        robot.interact { assertEquals(FileIcon.BINARY.ikon, iconOf(robot, "data").iconCode) }

        file.writeText("now text")
        robot.interact { manual.refreshAll() }

        robot.interact { assertEquals(FileIcon.TEXT.ikon, iconOf(robot, "data").iconCode) }
    }

    @Test
    fun `icon colours follow the theme`(robot: FxRobot) {
        project.resolve("Main.kt").createFile()
        robot.interact { view.open(project) }

        fun kotlinColor(scheme: ColorScheme): Any? {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            var color: Any? = null
            robot.interact { color = iconOf(robot, "Main.kt").iconColor }
            return color
        }
        assertEquals(Color.web("#7f52ff"), kotlinColor(ColorScheme.LIGHT))
        assertEquals(Color.web("#a78bfa"), kotlinColor(ColorScheme.DARK))
        assertEquals(Color.web("#7f52ff"), kotlinColor(ColorScheme.LIGHT))
    }
}
