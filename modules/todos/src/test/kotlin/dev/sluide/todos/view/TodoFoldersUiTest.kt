package dev.sluide.todos.view

import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoPanel
import dev.sluide.todos.TodoScope
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.CheckBox
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

/** UI tests of the folders in the TODO panel over a real [dev.sluide.todos.Todos]; the dialogs are test seams. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TodoFoldersUiTest {
    private val fixture = PanelFixture()
    private val todos = fixture.todos

    private lateinit var panel: TodoPanel

    @Start
    fun start(stage: Stage) {
        panel = fixture.newPanel()
        stage.scene = Scene(HBox(BorderPane(), panel), 500.0, 600.0)
        stage.show()
    }

    @AfterEach
    fun clean() = fixture.cleanup()

    private fun list(scope: TodoScope = TodoScope.SHARED) = panel.lists.getValue(scope)

    private fun at(scope: TodoScope) = fixture.folder.takeIf { scope == TodoScope.PROJECT }

    private fun items(
        robot: FxRobot,
        scope: TodoScope = TodoScope.SHARED,
    ) = fixture.fx(robot) { todos.items(scope, at(scope)) }

    private fun folders(
        robot: FxRobot,
        scope: TodoScope = TodoScope.SHARED,
    ) = items(robot, scope).filterIsInstance<TodoFolder>()

    /** A folder with TODOs named after [summaries]; returns it as stored. */
    private fun folderWith(
        robot: FxRobot,
        name: String,
        vararg summaries: String,
        scope: TodoScope = TodoScope.SHARED,
    ): TodoFolder {
        val id =
            fixture.fx(robot) {
                val created = assertNotNull(todos.addFolder(scope, at(scope), name))
                summaries.reversed().forEach { todos.add(scope, at(scope), it, "", created.id) }
                created.id
            }
        return folders(robot, scope).single { it.id == id }
    }

    private fun view(
        robot: FxRobot,
        id: String,
        scope: TodoScope = TodoScope.SHARED,
    ) = fixture.fx(robot) { list(scope).folders.single { it.folder.id == id } }

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

    private fun selected(
        robot: FxRobot,
        scope: TodoScope,
    ) = fixture.fx(robot) { panel.tabPane.selectionModel.selectedItem === panel.tabs.getValue(scope) }

    @Test
    fun `New Folder puts an expanded folder at the top of the tab and a draft of the other scope selects that tab`(
        robot: FxRobot,
    ) {
        fixture.folderDraft = FolderDraft(TodoScope.SHARED, "First")
        robot.interact { list().newFolderButton.fire() }
        fixture.folderDraft = FolderDraft(TodoScope.SHARED, "Second")
        robot.interact { list().newFolderButton.fire() }
        assertEquals(TodoScope.SHARED, fixture.folderRequests.first().scope)
        assertEquals(null, fixture.folderRequests.first().folder)
        assertEquals(listOf("Second", "First"), folders(robot).map { it.name })
        assertFalse(folders(robot).first().collapsed)
        assertEquals(listOf("Second (0)", "First (0)"), fixture.fx(robot) { list().folders.map { it.name.text } })

        fixture.folderDraft = FolderDraft(TodoScope.PROJECT, "Mine")
        robot.interact { list().newFolderButton.fire() }
        assertEquals(listOf("Mine"), folders(robot, TodoScope.PROJECT).map { it.name })
        assertTrue(selected(robot, TodoScope.PROJECT))
    }

    @Test
    fun `the chevron stores the collapsed state, hides the body and a second panel follows`(robot: FxRobot) {
        val stored = folderWith(robot, "Release", "a")
        val second = fixture.fx(robot) { fixture.newPanel() }
        val folderView = view(robot, stored.id)
        assertTrue(fixture.fx(robot) { folderView.body.parent === folderView })

        robot.interact { folderView.toggle.fire() }

        assertTrue(folders(robot).single().collapsed)
        assertFalse(fixture.fx(robot) { folderView.body.parent === folderView })
        assertTrue(
            fixture.fx(robot) {
                second.lists
                    .getValue(TodoScope.SHARED)
                    .folders
                    .single()
                    .body.parent == null
            },
        )

        robot.interact { folderView.toggle.fire() }
        assertFalse(folders(robot).single().collapsed)
        assertTrue(fixture.fx(robot) { folderView.body.parent === folderView })
    }

    @Test
    fun `plus on a folder offers that folder and adds the TODO at its top`(robot: FxRobot) {
        val stored = folderWith(robot, "Release", "old")
        fixture.draft = TodoDraft(TodoScope.SHARED, "new", "", stored.id)
        robot.interact { view(robot, stored.id).add.fire() }
        assertEquals(stored.id, fixture.requests.single().initialFolderId)
        assertEquals(listOf("new", "old"), folders(robot).single().todos.map { it.summary })

        fixture.draft = TodoDraft(TodoScope.SHARED, "root", "")
        robot.interact { list().addButton.fire() }
        assertEquals(null, fixture.requests.last().initialFolderId)
    }

    @Test
    fun `plus on a collapsed folder expands it after the TODO is saved, not after a cancel`(robot: FxRobot) {
        val stored = folderWith(robot, "Release", "old")
        fixture.fx(robot) { todos.setCollapsed(TodoScope.SHARED, null, stored.id, true) }
        fixture.draft = null
        robot.interact { view(robot, stored.id).add.fire() }
        assertTrue(folders(robot).single().collapsed)

        fixture.draft = TodoDraft(TodoScope.SHARED, "new", "", stored.id)
        robot.interact { view(robot, stored.id).add.fire() }
        assertFalse(folders(robot).single().collapsed)
        assertEquals(listOf("new", "old"), folders(robot).single().todos.map { it.summary })
    }

    @Test
    fun `editing a TODO in a folder keeps it in place unless the folder changes`(robot: FxRobot) {
        val release = folderWith(robot, "Release", "a", "b")
        val other = folderWith(robot, "Other")
        val b = release.todos.last()
        val card = fixture.fx(robot) { view(robot, release.id).cards.last() }

        fixture.draft = TodoDraft(TodoScope.SHARED, "b2", "", release.id)
        robot.interact { card.edit.fire() }
        assertEquals(release.id, fixture.requests.single().initialFolderId)
        assertEquals(listOf("a", "b2"), folders(robot).single { it.id == release.id }.todos.map { it.summary })

        fixture.draft = TodoDraft(TodoScope.SHARED, "b2", "", other.id)
        robot.interact { card.edit.fire() }
        assertEquals(listOf("a"), folders(robot).single { it.id == release.id }.todos.map { it.summary })
        assertEquals(listOf(b.id), folders(robot).single { it.id == other.id }.todos.map { it.id })

        fixture.draft = TodoDraft(TodoScope.SHARED, "b2", "", null)
        robot.interact { card.edit.fire() }
        assertEquals(listOf(b.id, other.id, release.id), items(robot).map { it.id })
        assertEquals(emptyList(), folders(robot).single { it.id == other.id }.todos)
    }

    @Test
    fun `saving a folder with another scope moves it with its TODOs to the other tab and selects it`(robot: FxRobot) {
        val stored = folderWith(robot, "Release", "a")
        fixture.folderDraft = FolderDraft(TodoScope.PROJECT, "Renamed")
        robot.interact { view(robot, stored.id).edit.fire() }
        assertEquals(
            stored.id,
            fixture.folderRequests
                .single()
                .folder
                ?.id,
        )
        assertEquals(emptyList(), folders(robot))
        val moved = folders(robot, TodoScope.PROJECT).single()
        assertEquals("Renamed", moved.name)
        assertEquals(listOf("a"), moved.todos.map { it.summary })
        assertTrue(selected(robot, TodoScope.PROJECT))
    }

    @Test
    fun `a double click on the folder name opens the folder dialog`(robot: FxRobot) {
        val stored = folderWith(robot, "Release")
        fixture.folderDraft = null
        click(robot, view(robot, stored.id).name, 1)
        assertTrue(fixture.folderRequests.isEmpty())
        click(robot, view(robot, stored.id).name, 2)
        assertEquals(
            stored.id,
            fixture.folderRequests
                .single()
                .folder
                ?.id,
        )
    }

    @Test
    fun `deleting a folder removes it with its TODOs, keeps them at the root, or changes nothing`(robot: FxRobot) {
        val stored = folderWith(robot, "Release", "a", "b")
        robot.interact { view(robot, stored.id).delete.fire() }
        assertEquals(1, folders(robot).size)

        fixture.folderDeletion = FolderDeletion.KEEP_TODOS
        robot.interact { view(robot, stored.id).delete.fire() }
        assertEquals(listOf("a", "b"), fixture.summaries(robot, TodoScope.SHARED, null))
        assertEquals(emptyList(), folders(robot))

        val second = folderWith(robot, "Gone", "c")
        fixture.folderDeletion = FolderDeletion.ALL
        robot.interact { view(robot, second.id).delete.fire() }
        assertEquals(listOf("a", "b"), fixture.summaries(robot, TodoScope.SHARED, null))
        assertEquals(emptyList(), folders(robot))
    }

    @Test
    fun `the tab count includes the TODOs in folders`(robot: FxRobot) {
        folderWith(robot, "Release", "a", "b")
        fixture.fx(robot) { todos.add(TodoScope.SHARED, null, "root", "") }
        assertEquals("Shared (3)", fixture.fx(robot) { panel.tabs.getValue(TodoScope.SHARED).text })
    }

    @Test
    fun `an empty expanded folder shows the drag hint`(robot: FxRobot) {
        val empty = folderWith(robot, "Empty")
        val filled = folderWith(robot, "Filled", "a")
        assertEquals(
            listOf(true),
            fixture.fx(robot) { view(robot, empty.id).body.children.map { "todo-folder-empty" in it.styleClass } },
        )
        assertEquals("Drag TODOs here", fixture.fx(robot) { view(robot, empty.id).emptyHint.text })
        assertFalse(
            fixture.fx(robot) { view(robot, filled.id).body.children.contains(view(robot, empty.id).emptyHint) },
        )
    }

    @Test
    fun `a TODO card moved to another folder stays expanded and then edits with the new folder`(robot: FxRobot) {
        val a = folderWith(robot, "A", "x")
        val b = folderWith(robot, "B")
        fixture.fx(robot) {
            val todo = a.todos.single()
            todos.edit(todo.id, TodoScope.SHARED, TodoScope.SHARED, null, "x", "details", a.id)
        }
        val card = fixture.fx(robot) { view(robot, a.id).cards.single() }
        robot.interact { card.toggle.fire() }
        assertTrue(fixture.fx(robot) { card.isExpanded })
        fixture.draft = TodoDraft(TodoScope.SHARED, "x", "details", b.id)
        robot.interact { card.edit.fire() }
        val moved = fixture.fx(robot) { view(robot, b.id).cards.single() }
        assertTrue(moved === card)
        assertTrue(fixture.fx(robot) { card.isExpanded })
        fixture.requests.clear()
        robot.interact { card.edit.fire() }
        assertEquals(b.id, fixture.requests.single().initialFolderId)
    }

    @Test
    fun `a task checkbox in a folder saves in place`(robot: FxRobot) {
        val release = folderWith(robot, "Release", "first")
        // The card of the new TODO is made with its folder, not updated into it.
        fixture.fx(robot) { todos.add(TodoScope.SHARED, null, "tasks", "- [ ] one", release.id) }
        val card = fixture.fx(robot) { view(robot, release.id).cards.first() }
        robot.interact { card.toggle.fire() }
        val box = fixture.fx(robot) { fixture.descendants(card).filterIsInstance<CheckBox>().single() }
        robot.interact { box.fire() }
        val stored = folders(robot).single().todos
        assertEquals(listOf("tasks", "first"), stored.map { it.summary })
        assertEquals("- [x] one", stored.first().description)
    }

    @Test
    fun `a failed folder save shows the folder error and asks again with the draft`(robot: FxRobot) {
        Files.writeString(fixture.dir.resolve("shared.json"), "{ not json")
        val draft = FolderDraft(TodoScope.SHARED, "lost?")
        fixture.folderDrafts += draft
        fixture.folderDraft = null
        robot.interact { list().newFolderButton.fire() }
        assertEquals(2, fixture.folderRequests.size)
        assertEquals(null, fixture.folderRequests[0].draft)
        assertEquals(draft, fixture.folderRequests[1].draft)
        assertTrue(fixture.errors.all { it.startsWith("Could not save the folder.") })
        assertEquals(1, fixture.errors.size)
    }
}
