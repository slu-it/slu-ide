package dev.sluide.fileexplorer.tree

import dev.sluide.settings.FontSpec
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The bounds of the start width; measuring text needs the toolkit. Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class StartWidthUiTest {
    @TempDir
    lateinit var dir: Path

    private val spec = FontSpec("Serif", 20.0)

    @Start
    fun start(stage: Stage) {
        // Only the toolkit is needed.
    }

    @Test
    fun `without entries the width is 12 times the font size`() {
        assertEquals(12 * 20.0, startWidth(emptyList(), spec))
    }

    @Test
    fun `short names stay at the lower bound, a very long name at the upper bound`() {
        val short = FileTreeItem(dir.resolve("a").createDirectories(), label = "a")
        val long = FileTreeItem(dir.resolve("b").createDirectories(), label = "x".repeat(300))

        assertEquals(12 * 20.0, startWidth(listOf(short), spec))
        assertEquals(24 * 20.0, startWidth(listOf(short, long), spec))
    }

    @Test
    fun `expanded folders count with their indentation, collapsed ones as one entry`() {
        val deep = dir.resolve("one/two/three").createDirectories()
        deep.resolve("a-name-long-enough-to-need-more-than-the-minimum.txt").createFile()
        val top = FileTreeItem(dir.resolve("one"))
        val collapsed = startWidth(listOf(top), spec)

        top.isExpanded = true
        val two = top.children.single().also { it.isExpanded = true }
        val three = two.children.single().also { it.isExpanded = true }
        val expanded = startWidth(listOf(top), spec)

        assertEquals(12 * 20.0, collapsed)
        assertTrue(expanded > collapsed, "$expanded > $collapsed")
        assertTrue(three.isExpanded)
        assertTrue(expanded <= 24 * 20.0)
    }
}
