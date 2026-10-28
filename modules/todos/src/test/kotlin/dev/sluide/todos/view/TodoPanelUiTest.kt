package dev.sluide.todos.view

import dev.sluide.todos.TodoPanel
import dev.sluide.todos.TodoScope
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** UI tests of the TODO panel over a real [dev.sluide.todos.Todos]; the dialogs are replaced by test seams. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TodoPanelUiTest {
    private val fixture = PanelFixture()
    private val todos = fixture.todos
    private val folder = fixture.folder

    private lateinit var scene: Scene
    private lateinit var root: HBox
    private lateinit var panel: TodoPanel

    @Start
    fun start(stage: Stage) {
        panel = fixture.newPanel()
        root = HBox(BorderPane(), panel)
        scene = Scene(root, 500.0, 600.0)
        stage.scene = scene
        stage.show()
    }

    @AfterEach
    fun clean() = fixture.cleanup()

    private fun list(scope: TodoScope = TodoScope.SHARED) = panel.lists.getValue(scope)

    private fun tabText(
        robot: FxRobot,
        scope: TodoScope,
        of: TodoPanel = panel,
    ) = fixture.fx(robot) { of.tabs.getValue(scope).text }

    private fun add(
        robot: FxRobot,
        scope: TodoScope,
        summary: String,
        description: String = "",
    ) = fixture.fx(robot) { todos.add(scope, folder.takeIf { scope == TodoScope.PROJECT }, summary, description) }

    private fun cards(
        robot: FxRobot,
        scope: TodoScope = TodoScope.SHARED,
    ) = fixture.fx(robot) { list(scope).cards }

    private fun click(
        robot: FxRobot,
        node: Node,
        count: Int = 1,
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
                count,
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
    fun `the panel starts collapsed, the stripe expands it and the collapse button collapses it`(robot: FxRobot) {
        assertFalse(panel.expanded.value)
        assertTrue(panel.center === panel.stripe)
        click(robot, panel.stripe)
        assertTrue(panel.expanded.value)
        assertEquals(320.0, panel.prefWidth)
        robot.interact { panel.collapseButton.fire() }
        assertFalse(panel.expanded.value)
        assertTrue(panel.center === panel.stripe)
    }

    @Test
    fun `Enter or Space on the focused stripe expands the panel`(robot: FxRobot) {
        assertTrue(panel.stripe.isFocusTraversable)
        robot.interact {
            Event.fireEvent(
                panel.stripe,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false),
            )
        }
        assertTrue(panel.expanded.value)
        robot.interact { panel.expanded.set(false) }
        robot.interact {
            Event.fireEvent(
                panel.stripe,
                KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false),
            )
        }
        assertTrue(panel.expanded.value)
    }

    @Test
    fun `expanding focuses the Add button of the selected tab and the buttons are reachable by Tab`(robot: FxRobot) {
        robot.interact { panel.expanded.set(true) }
        assertTrue(scene.focusOwner === list().addButton)
        add(robot, TodoScope.SHARED, "one", "text")
        val card = cards(robot).single()
        assertTrue(listOf(card.toggle, card.edit, card.delete, panel.collapseButton).all { it.isFocusTraversable })
    }

    @Test
    fun `collapsing with the focus inside hands the focus back`(robot: FxRobot) {
        var returned = false
        panel.returnFocus = { returned = true }
        robot.interact { panel.expanded.set(true) }
        robot.interact { panel.collapseButton.fire() }
        assertTrue(returned)
    }

    @Test
    fun `Add puts the TODO at the top of the tab and a draft of the other scope selects that tab`(robot: FxRobot) {
        fixture.draft = TodoDraft(TodoScope.SHARED, "first", "")
        robot.interact { list().addButton.fire() }
        fixture.draft = TodoDraft(TodoScope.SHARED, "second", "")
        robot.interact { list().addButton.fire() }
        assertEquals(TodoScope.SHARED, fixture.requests.first().scope)
        assertEquals(null, fixture.requests.first().todo)
        assertEquals(listOf("second", "first"), cards(robot).map { it.summary.text })
        assertEquals("Shared (2)", tabText(robot, TodoScope.SHARED))

        fixture.draft = TodoDraft(TodoScope.PROJECT, "mine", "")
        robot.interact { list().addButton.fire() }
        assertEquals(listOf("mine"), fixture.summaries(robot, TodoScope.PROJECT))
        assertEquals("Project (1)", tabText(robot, TodoScope.PROJECT))
        assertTrue(
            fixture.fx(robot) {
                panel.tabPane.selectionModel.selectedItem ===
                    panel.tabs.getValue(TodoScope.PROJECT)
            },
        )
    }

    @Test
    fun `Edit with another scope moves the TODO to the other tab and selects it`(robot: FxRobot) {
        val added = assertNotNull(add(robot, TodoScope.SHARED, "move me", "text"))
        val card = cards(robot).single()
        fixture.draft = TodoDraft(TodoScope.PROJECT, "moved", "text")
        robot.interact { card.edit.fire() }
        assertEquals(added, fixture.requests.single().todo)
        assertEquals(emptyList(), fixture.summaries(robot, TodoScope.SHARED, null))
        assertEquals(listOf("moved"), fixture.summaries(robot, TodoScope.PROJECT))
        assertEquals("Project (1)", tabText(robot, TodoScope.PROJECT))
        assertEquals("Shared (0)", tabText(robot, TodoScope.SHARED))
        assertTrue(
            fixture.fx(robot) {
                panel.tabPane.selectionModel.selectedItem ===
                    panel.tabs.getValue(TodoScope.PROJECT)
            },
        )
    }

    @Test
    fun `a double click on the summary edits the TODO`(robot: FxRobot) {
        add(robot, TodoScope.SHARED, "double", "")
        val card = cards(robot).single()
        click(robot, card.summary, 1)
        assertEquals(0, fixture.requests.size)
        click(robot, card.summary, 2)
        assertEquals(
            "double",
            fixture.requests
                .single()
                .todo
                ?.summary,
        )
    }

    @Test
    fun `Delete happens only when it is confirmed`(robot: FxRobot) {
        add(robot, TodoScope.SHARED, "keep?", "")
        robot.interact { cards(robot).single().delete.fire() }
        assertEquals(listOf("keep?"), fixture.summaries(robot, TodoScope.SHARED, null))
        fixture.allowDelete = true
        robot.interact { cards(robot).single().delete.fire() }
        assertEquals(emptyList(), fixture.summaries(robot, TodoScope.SHARED, null))
    }

    @Test
    fun `an empty tab says No TODOs`(robot: FxRobot) {
        fun labels() =
            fixture.fx(robot) {
                list()
                    .cardsBox.children
                    .filterIsInstance<Label>()
                    .map { it.text }
            }
        assertEquals(listOf("No TODOs"), labels())
        add(robot, TodoScope.SHARED, "one")
        assertEquals(emptyList(), labels())
    }

    @Test
    fun `an expanded card shows the Markdown and a task checkbox saves the toggled description`(robot: FxRobot) {
        add(robot, TodoScope.SHARED, "tasks", "- [ ] one")
        val card = cards(robot).single()
        assertEquals(null, fixture.fx(robot) { card.body })
        robot.interact { card.toggle.fire() }
        val box = fixture.fx(robot) { fixture.descendants(card).filterIsInstance<CheckBox>().single() }
        robot.interact { box.fire() }
        assertEquals("- [x] one", fixture.fx(robot) { todos.list(TodoScope.SHARED, null).single().description })
        // The refresh kept the card and its expansion, and rendered the body again.
        fixture.fx(robot) {
            val after = list().cards.single()
            assertTrue(after === card && after.isExpanded)
            assertTrue(
                fixture
                    .descendants(after)
                    .filterIsInstance<CheckBox>()
                    .single()
                    .isSelected,
            )
        }
    }

    @Test
    fun `a card without a description has no expand toggle but keeps its space`(robot: FxRobot) {
        add(robot, TodoScope.SHARED, "plain", " ")
        add(robot, TodoScope.SHARED, "described", "text")
        val (described, plain) = cards(robot)
        assertFalse(plain.toggle.isVisible)
        assertTrue(plain.toggle.isDisabled)
        // The summaries line up with those of cards that have a chevron.
        val (describedX, plainX) =
            fixture.fx(robot) {
                panel.expanded.set(true)
                root.layout()
                described.summary.layoutX to plain.summary.layoutX
            }
        assertEquals(describedX, plainX)
    }

    @Test
    fun `panels on the same Todos follow each other until closed`(robot: FxRobot) {
        val second = fixture.fx(robot) { fixture.newPanel().also { root.children.add(it) } }
        add(robot, TodoScope.SHARED, "shared one")
        assertEquals(
            listOf("shared one"),
            fixture.fx(robot) {
                second.lists
                    .getValue(TodoScope.SHARED)
                    .cards
                    .map { it.summary.text }
            },
        )
        assertEquals("Shared (1)", tabText(robot, TodoScope.SHARED, second))
        fixture.fx(robot) { second.close() }
        add(robot, TodoScope.SHARED, "shared two")
        assertEquals("Shared (1)", tabText(robot, TodoScope.SHARED, second))
        assertEquals("Shared (2)", tabText(robot, TodoScope.SHARED))
    }

    @Test
    fun `a change of another project does not refresh the Project tab`(robot: FxRobot) {
        val elsewhere = Files.createDirectories(fixture.dir.resolve("elsewhere"))
        fixture.fx(robot) { todos.add(TodoScope.PROJECT, elsewhere, "not here", "") }
        assertEquals("Project (0)", tabText(robot, TodoScope.PROJECT))
        fixture.fx(robot) { todos.add(TodoScope.PROJECT, folder.resolve("../proj"), "here", "") }
        assertEquals("Project (1)", tabText(robot, TodoScope.PROJECT))
    }

    @Test
    fun `the Project tab is disabled without a folder`(robot: FxRobot) {
        assertFalse(fixture.fx(robot) { panel.tabs.getValue(TodoScope.PROJECT).isDisable })
        robot.interact { panel.tabPane.selectionModel.select(panel.tabs.getValue(TodoScope.PROJECT)) }
        robot.interact { panel.open(null) }
        assertTrue(fixture.fx(robot) { panel.tabs.getValue(TodoScope.PROJECT).isDisable })
        assertTrue(
            fixture.fx(robot) {
                panel.tabPane.selectionModel.selectedItem ===
                    panel.tabs.getValue(TodoScope.SHARED)
            },
        )
        fixture.draft = TodoDraft(TodoScope.SHARED, "x", "")
        robot.interact { list().addButton.fire() }
        assertFalse(fixture.requests.single().projectPossible)
    }

    @Test
    fun `a failed add shows the error and asks again with the draft`(robot: FxRobot) {
        Files.writeString(fixture.dir.resolve("shared.json"), "{ not json")
        val draft = TodoDraft(TodoScope.SHARED, "lost?", "text")
        fixture.drafts += draft
        robot.interact { list().addButton.fire() }
        assertEquals(1, fixture.errors.size)
        assertTrue(fixture.errors.single().startsWith("Could not save the TODO."))
        assertEquals(2, fixture.requests.size)
        assertEquals(draft, fixture.requests[1].draft)
        assertEquals("lost?", fixture.requests[1].initialSummary)
        assertEquals(null, fixture.requests[0].draft)
    }

    @Test
    fun `a failed delete shows the error`(robot: FxRobot) {
        add(robot, TodoScope.SHARED, "stuck")
        Files.writeString(fixture.dir.resolve("shared.json"), "{ not json")
        fixture.allowDelete = true
        robot.interact { cards(robot).single().delete.fire() }
        assertEquals(1, fixture.errors.size)
    }

    @Test
    fun `a failed task toggle shows the error and the checkbox goes back`(robot: FxRobot) {
        add(robot, TodoScope.PROJECT, "tasks", "- [ ] one")
        fixture.makeProjectReadOnly()
        robot.interact { panel.tabPane.selectionModel.select(panel.tabs.getValue(TodoScope.PROJECT)) }
        val card = cards(robot, TodoScope.PROJECT).single()
        robot.interact { card.toggle.fire() }
        val box = fixture.fx(robot) { fixture.descendants(card).filterIsInstance<CheckBox>().single() }
        robot.interact { box.fire() }
        assertEquals(1, fixture.errors.size)
        fixture.fx(robot) {
            assertFalse(
                fixture
                    .descendants(card)
                    .filterIsInstance<CheckBox>()
                    .single()
                    .isSelected,
            )
            assertEquals("- [ ] one", todos.list(TodoScope.PROJECT, folder).single().description)
        }
    }

    @Test
    fun `closing the panel stops its refreshes`(robot: FxRobot) {
        robot.interact { panel.close() }
        add(robot, TodoScope.SHARED, "after close")
        assertEquals("Shared (0)", tabText(robot, TodoScope.SHARED))
    }
}
