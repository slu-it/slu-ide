package dev.sluide.ide

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import dev.sluide.fileexplorer.ExplorerBar
import dev.sluide.ide.explorer.ExplorerPanel
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI tests of the main window's collapsible explorer column: its place, collapsing and wiring (ADR 0104). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowExplorerUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) {
        convertingFamilies = listOf(ShoutingFamily)
        startView(stage)
    }

    private val panel: ExplorerPanel get() = mainWindow.explorer.panel

    private fun explorerItem(robot: FxRobot) = menu(robot, "View").items.single { it.text == "Explorer" }

    /** Runs [block] on the FX thread and returns its result. */
    private fun <T> onFx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        var result: Result<T>? = null
        robot.interact { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun panelWidth(robot: FxRobot) = onFx(robot) { panel.width }

    private fun stripeWidth(robot: FxRobot) = onFx(robot) { panel.prefWidth(-1.0) }

    private fun expanded(robot: FxRobot) = onFx(robot) { panel.expanded.get() }

    /** Whether the focus is in [node] or one of its descendants. */
    private fun focusIn(
        robot: FxRobot,
        node: () -> Node?,
    ) = onFx(robot) {
        val target = node()
        target != null && generateSequence(stage.scene.focusOwner) { it.parent }.any { it === target }
    }

    private fun press(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        Event.fireEvent(panel.stripe, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    private fun click(robot: FxRobot) =
        robot.interact {
            Event.fireEvent(
                panel.stripe,
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

    @Test
    fun `the Collapse button collapses the column to its stripe, which a click, Enter or Space expands`(
        robot: FxRobot,
    ) {
        val start = panelWidth(robot)
        val bar = onFx(robot) { (panel.content as VBox).children.filterIsInstance<ExplorerBar>().single() }

        robot.interact { bar.collapseButton.fire() }
        assertFalse(expanded(robot))
        robot.interact {
            assertTrue(panel.center === panel.stripe)
            assertTrue("explorer-stripe" in panel.stripe.styleClass)
            assertTrue(panel.stripe.isFocusTraversable)
        }
        assertEquals(stripeWidth(robot), panelWidth(robot), 1.0, "the stripe")
        assertTrue(panelWidth(robot) < 40)

        click(robot)
        assertTrue(expanded(robot))
        assertEquals(start, panelWidth(robot), DIVIDER_SLACK)

        for (key in listOf(KeyCode.ENTER, KeyCode.SPACE)) {
            robot.interact { panel.expanded.set(false) }
            press(robot, key)
            assertTrue(expanded(robot), "$key")
        }
    }

    @Test
    fun `View - Explorer comes first of the panels and collapses and expands the column`(robot: FxRobot) {
        assertEquals(
            listOf("Show Changes", "Select Opened File", "Explorer", "Git Log", "TODOs"),
            menu(robot, "View").items.map { it.text },
        )
        val item = explorerItem(robot)
        val accelerator = item.accelerator as KeyCodeCombination
        assertEquals(KeyCode.DIGIT1, accelerator.code)
        val mac = System.getProperty("os.name").startsWith("Mac")
        assertEquals(
            if (mac) KeyCombination.ModifierValue.DOWN else KeyCombination.ModifierValue.UP,
            accelerator.shortcut,
        )
        assertEquals(if (mac) KeyCombination.ModifierValue.UP else KeyCombination.ModifierValue.DOWN, accelerator.alt)

        robot.interact { item.fire() }
        assertFalse(expanded(robot))
        assertEquals(stripeWidth(robot), panelWidth(robot), 1.0)

        robot.interact { item.fire() }
        assertTrue(expanded(robot))
    }

    @Test
    fun `the dragged width is kept across a collapse and the stripe cannot be widened`(robot: FxRobot) {
        robot.interact {
            val split = splitPaneOf(fileExplorer)
            split.setDividerPosition(0, (300.0 + 3) / split.width)
        }
        assertEquals(300.0, panelWidth(robot), DIVIDER_SLACK + 4)

        robot.interact { panel.expanded.set(false) }
        robot.interact { splitPaneOf(editorArea).setDividerPosition(0, 0.5) }
        assertEquals(stripeWidth(robot), panelWidth(robot), 1.0, "dragging does not widen the stripe")
        robot.interact { stage.width += 100 }
        assertEquals(stripeWidth(robot), panelWidth(robot), 1.0, "the window's width goes to the editor")

        robot.interact { panel.expanded.set(true) }
        assertEquals(300.0, panelWidth(robot), DIVIDER_SLACK + 4)
    }

    @Test
    fun `collapsing with the focus inside focuses the editor, and expanding focuses the tree`(robot: FxRobot) {
        val file = project.resolve("note.txt").also { it.writeText("text") }
        robot.interact { editorArea.open(file) }
        robot.interact { fileExplorer.requestFocus() }
        assertTrue(focusIn(robot) { panel })

        robot.interact { explorerItem(robot).fire() }
        assertTrue(focusIn(robot) { editorArea.selectedTab?.content }, "the editor has the focus")

        robot.interact { explorerItem(robot).fire() }
        robot.interact { assertTrue(fileExplorer.isFocused, "expanding focuses the tree") }
    }

    @Test
    fun `collapsing and expanding the column keeps the width of the expanded TODO panel`(robot: FxRobot) {
        robot.interact { todoPanel.expanded.set(true) }
        // A width the TODO panel was dragged to, not only its first one.
        robot.interact {
            val split = splitPaneOf(fileExplorer)
            split.setDividerPosition(split.items.size - 2, 1 - (400.0 + 3) / split.width)
        }
        val todoWidth = onFx(robot) { todoPanel.width }
        assertEquals(400.0, todoWidth, DIVIDER_SLACK + 4)

        robot.interact { panel.expanded.set(false) }
        assertEquals(todoWidth, onFx(robot) { todoPanel.width }, DIVIDER_SLACK)

        robot.interact { panel.expanded.set(true) }
        assertEquals(todoWidth, onFx(robot) { todoPanel.width }, DIVIDER_SLACK)
    }

    @Test
    fun `Select Opened File and the tab menu expand a collapsed column and select the file`(robot: FxRobot) {
        val file = project.resolve("Opened.kt").also { it.createFile() }
        robot.interact { projectService.open(project) }
        robot.interact { editorArea.open(file) }
        val select = menu(robot, "View").items.single { it.text == "Select Opened File" }
        val triggers =
            mapOf<String, () -> Unit>(
                "View menu" to { select.fire() },
                "tab menu hook" to { checkNotNull(editorArea.tabMenu.selectInExplorer)(file) },
            )
        for ((name, fire) in triggers) {
            robot.interact {
                fileExplorer.selectionModel.clearSelection()
                panel.expanded.set(false)
            }
            assertFalse(expanded(robot), name)

            robot.interact { fire() }

            assertTrue(expanded(robot), name)
            robot.interact {
                assertEquals(file, fileExplorer.selectionModel.selectedItem?.value, name)
                assertTrue(fileExplorer.isFocused, "$name: the explorer has the focus")
            }
        }
    }

    @Test
    fun `the explorer's Convert to hooks offer the formats of the file and write, show and open the converted file`(
        robot: FxRobot,
    ) {
        val source = project.resolve("a.low").apply { writeText("hello") }
        project.resolve("b.txt").createFile()
        robot.interact { projectService.open(project) }
        val offer = checkNotNull(fileExplorer.conversionTargets)

        assertEquals(listOf("LOUD"), offer(source))
        assertEquals(emptyList(), offer(project.resolve("b.txt")))

        robot.interact { checkNotNull(fileExplorer.convertFile)(source, "LOUD") }

        val converted = project.resolve("a.loud")
        eventually(robot) { assertEquals(converted, editorArea.selectedPath) }
        assertEquals("HELLO", converted.readText())
        assertEquals(converted, onFx(robot) { fileExplorer.selectionModel.selectedItem?.value })
    }

    @Test
    fun `Convert to asks first when a tab holds unsaved changes of the file, Cancel converts nothing`(robot: FxRobot) {
        val source = project.resolve("a.low").apply { writeText("hello") }
        robot.interact { projectService.open(project) }
        robot.interact { editorArea.open(source) }
        eventually(robot) { assertEquals(source, editorArea.selectedPath) }
        val area = robot.lookup(".code-area").queryAs(org.fxmisc.richtext.CodeArea::class.java)
        robot.interact { area.appendText(" more") }
        val asked = mutableListOf<Path>()
        mainWindow.askToConvertSaved = { _, file ->
            asked.add(file)
            false
        }

        robot.interact { checkNotNull(fileExplorer.convertFile)(source, "LOUD") }

        assertEquals(listOf(source), asked)
        assertFalse(Files.exists(project.resolve("a.loud")))
    }
}

/** A format with its own extension and text: `low` text converts to `loud` text in capitals. */
private class ShoutFormat(
    override val id: String,
    override val name: String,
) : TextFormat {
    override val language: String? = null
    override val extensions = listOf(id)
}

/** A family converting files, so that the window's Converting offers "Convert to" (the real data family is internal). */
private object ShoutingFamily : FormatFamily<String> {
    private val low = ShoutFormat("low", "LOW")
    private val loud = ShoutFormat("loud", "LOUD")
    override val name = "Shouting"
    override val formats = listOf(low, loud)
    override val convertsFiles = true

    override fun detect(text: String) = emptyList<FormatMatch>()

    override fun readOptions(format: TextFormat) = emptyList<ConversionOption>()

    override fun writeOptions(format: TextFormat) = emptyList<ConversionOption>()

    override fun read(
        text: String,
        format: TextFormat,
        options: OptionValues,
    ): ReadResult<String> = ReadResult.Read(text, emptyList())

    override fun write(
        content: String,
        format: TextFormat,
        options: OptionValues,
    ): WriteResult =
        WriteResult.Written(
            if (format.id ==
                "loud"
            ) {
                content.uppercase()
            } else {
                content.lowercase()
            },
            emptyList(),
        )
}
