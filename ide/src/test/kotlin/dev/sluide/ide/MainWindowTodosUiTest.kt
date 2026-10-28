package dev.sluide.ide

import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSpec
import dev.sluide.todos.TodoScope
import javafx.scene.control.TabPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.net.URI
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI tests of the main window's TODO panel: its place in the window and its wiring (ADR 0102). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowTodosUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private fun todosItem(robot: FxRobot) = menu(robot, "View").items.single { it.text == "TODOs" }

    /** Runs [block] on the FX thread and returns its result. */
    private fun <T> onFx(
        robot: FxRobot,
        block: () -> T,
    ): T {
        var result: Result<T>? = null
        robot.interact { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun panelWidth(robot: FxRobot) = onFx(robot) { todoPanel.width }

    /** Whether the Project tab is disabled; only while the panel is expanded. */
    private fun projectTabDisabled(robot: FxRobot) =
        onFx(robot) {
            robot
                .lookup(".todo-panel .tab-pane")
                .queryAs(TabPane::class.java)
                .tabs
                .single { it.text.startsWith("Project") }
                .isDisable
        }

    private fun sharedTabText(robot: FxRobot) =
        onFx(robot) {
            robot
                .lookup(".todo-panel .tab-pane")
                .queryAs(TabPane::class.java)
                .tabs
                .first()
                .text
        }

    private fun expand(robot: FxRobot) {
        robot.interact { todoPanel.expanded.set(true) }
    }

    @Test
    fun `the panel is the last item of the main split, collapsed to its stripe`(robot: FxRobot) {
        assertFalse(onFx(robot) { todoPanel.expanded.get() })
        assertEquals(onFx(robot) { todoPanel.prefWidth(-1.0) }, panelWidth(robot), 1.0, "the stripe")
        assertTrue(onFx(robot) { splitPaneOf(fileExplorer).items.last() === todoPanel })
    }

    @Test
    fun `View - TODOs expands the panel to its width and collapses it again`(robot: FxRobot) {
        val item = todosItem(robot)
        val accelerator = item.accelerator as KeyCodeCombination
        assertEquals(KeyCode.DIGIT6, accelerator.code)
        val mac = System.getProperty("os.name").startsWith("Mac")
        assertEquals(
            if (mac) KeyCombination.ModifierValue.DOWN else KeyCombination.ModifierValue.UP,
            accelerator.shortcut,
        )
        assertEquals(if (mac) KeyCombination.ModifierValue.UP else KeyCombination.ModifierValue.DOWN, accelerator.alt)
        assertFalse(item.isDisable)

        robot.interact { item.fire() }
        assertTrue(onFx(robot) { todoPanel.expanded.get() })
        assertEquals(320.0, panelWidth(robot), DIVIDER_SLACK + 4)

        robot.interact { item.fire() }
        assertFalse(onFx(robot) { todoPanel.expanded.get() })
        assertTrue(panelWidth(robot) < 60)
    }

    @Test
    fun `a git repository keeps the panel collapsed and the explorer at its width, then expands to its width`(
        robot: FxRobot,
    ) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")

        robot.interact { projectService.open(project) }

        // Setting the explorer's divider moves the panel's; afterFit sets it again. The order of the split's items is
        // MainWindowGitUiTest's.
        assertEquals(onFx(robot) { todoPanel.prefWidth(-1.0) }, panelWidth(robot), 1.0, "still collapsed")
        assertEquals(onFx(robot) { fileExplorer.startWidth() }, onFx(robot) { fileExplorer.width }, DIVIDER_SLACK)
        expand(robot)
        assertEquals(320.0, panelWidth(robot), DIVIDER_SLACK + 4)
    }

    @Test
    fun `resizing the window keeps the width of the expanded panel`(robot: FxRobot) {
        expand(robot)
        val before = panelWidth(robot)
        robot.interact { stage.width += 200 }
        assertEquals(before, panelWidth(robot), DIVIDER_SLACK)
    }

    @Test
    fun `the width the panel was dragged to is kept across a collapse`(robot: FxRobot) {
        expand(robot)
        robot.interact {
            val split = splitPaneOf(fileExplorer)
            split.setDividerPosition(split.items.size - 2, 1 - (400.0 + 3) / split.width)
        }
        assertEquals(400.0, panelWidth(robot), DIVIDER_SLACK + 4)

        robot.interact { todoPanel.expanded.set(false) }
        robot.interact { stage.width += 100 }
        expand(robot)
        assertEquals(400.0, panelWidth(robot), DIVIDER_SLACK + 4)
    }

    @Test
    fun `the folder reaches the panel, which enables the Project tab`(robot: FxRobot) {
        expand(robot)
        assertTrue(projectTabDisabled(robot), "no folder yet")

        robot.interact { projectService.open(project) }
        assertFalse(projectTabDisabled(robot))
    }

    @Test
    fun `a file opened from the panel opens an editor tab`(robot: FxRobot) {
        val file = project.resolve("note.txt").also { it.writeText("text") }
        robot.interact { todoPanel.openFile(file) }
        assertEquals(listOf(file), onFx(robot) { editorArea.openPaths })
    }

    @Test
    fun `a web page opened from the panel goes through the window's opener`(robot: FxRobot) {
        val opened = mutableListOf<URI>()
        mainWindow.openWebPage = { opened += it }
        robot.interact { todoPanel.openWebPage(URI("https://example.com/")) }
        assertEquals(listOf(URI("https://example.com/")), opened)
    }

    @Test
    fun `the description editor is a Markdown code editor in the editor font`(robot: FxRobot) {
        robot.interact {
            val editor = todoPanel.descriptionEditor("# Title")
            assertEquals("# Title", editor.text)
            assertTrue("code-view" in editor.node.styleClass)
            assertEquals(fontSettings.font(FontArea.EDITOR).value.let(FontSpec::toCss), editor.node.style)
            // The text is highlighted when the editor is made: a heading has a style, plain text would have none.
            val area = editor.node.lookup(".code-area") as CodeArea
            assertTrue(area.getStyleAtPosition(0).isNotEmpty(), "the Markdown highlighter styles the heading")
        }
    }

    @Test
    fun `collapsing the panel with the focus in it gives the focus to the editor`(robot: FxRobot) {
        val file = project.resolve("note.txt").also { it.writeText("text") }
        robot.interact { editorArea.open(file) }
        expand(robot)
        val inPanel = onFx(robot) { generateSequence(stage.scene.focusOwner) { it.parent }.any { it === todoPanel } }
        assertTrue(inPanel, "expanding focuses the panel")

        robot.interact { todosItem(robot).fire() }
        val inEditor =
            onFx(robot) {
                val content = editorArea.selectedTab?.content
                content != null && generateSequence(stage.scene.focusOwner) { it.parent }.any { it === content }
            }
        assertTrue(inEditor, "the editor has the focus")
    }

    @Test
    fun `closing the window stops the panel following the TODOs`(robot: FxRobot) {
        expand(robot)
        robot.interact { todos.add(TodoScope.SHARED, null, "first", "") }
        assertEquals("Shared (1)", sharedTabText(robot))

        robot.interact { mainWindow.dispose() }
        robot.interact { todos.add(TodoScope.SHARED, null, "second", "") }
        assertEquals("Shared (1)", sharedTabText(robot), "no refresh after the window was closed")
    }
}
