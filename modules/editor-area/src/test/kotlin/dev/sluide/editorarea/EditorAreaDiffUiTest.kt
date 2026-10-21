package dev.sluide.editorarea

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.previews.markdown.MarkdownFileViewer
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the editor area: the diff shown in and over the editor. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaDiffUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** The change markers in the gutter of the shown editor by line number; lines without a marker are left out. */
    private fun shownMarkers(robot: FxRobot): Map<Int, String> {
        WaitForAsyncUtils.waitForFxEvents()
        return robot
            .lookup(".editor-content .code-editor .gutter")
            .queryAllAs(HBox::class.java)
            .mapNotNull { row ->
                val line = (row.children[0] as Label).text.trim().toInt()
                row.children[1]
                    .styleClass
                    .singleOrNull { it != "change-marker" }
                    ?.let { line to it }
            }.toMap()
    }

    @Test
    fun `files with a diff open in the editor with their changes marked, following the diff`(robot: FxRobot) {
        val a = textFile("a.txt", "new\nsame\n")
        var diff: TextDiff? = TextDiff("old\nsame\n", listOf(LineChange(0..0, 0..0)))
        editor.diffOf = immediately { diff }
        open(robot, a)
        val tab = editor.selectedTab

        assertEquals("new\nsame\n", shownText(robot))
        assertEquals(emptyList(), shownDiff(robot))
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))

        diff = null
        robot.interact { editor.refreshDiffs() }
        assertEquals(emptyMap(), shownMarkers(robot))

        diff = TextDiff("new\n", listOf(LineChange(1 until 1, 1..1)))
        robot.interact { editor.refreshDiffs() }
        assertEquals(mapOf(2 to "diff-added"), shownMarkers(robot))
        assertEquals(emptyList(), shownDiff(robot))
        assertSame(tab, editor.selectedTab)
        assertEquals(listOf(a), editor.openPaths)
    }

    private fun pressEscape(
        robot: FxRobot,
        target: Node,
    ) = robot.interact {
        Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))
    }

    @Test
    fun `the diff shows on demand over the editor, which keeps its unsaved changes, caret and undo`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        editor.diffOf = immediately { TextDiff("old\n", listOf(LineChange(0..0, 0..0)), "HEAD", "Working Tree") }
        open(robot, a)
        val area = shownArea(robot)
        robot.interact {
            area.requestFocus()
            area.insertText(0, "edited ")
        }
        val caret = area.caretPosition
        assertEquals(true, editor.canToggleDiff.value)

        robot.interact { editor.toggleDiff() }
        assertEquals(listOf("old", "new"), shownDiff(robot), "the saved file")
        assertNull(shownText(robot), "the editor is hidden")
        val titles =
            robot
                .lookup(".diff-title")
                .queryAllAs(Label::class.java)
                .map { it.text }
                .toSet()
        assertEquals(setOf("HEAD", "Working Tree (saved)"), titles)
        assertEquals(listOf("● a.txt"), tabTitles(robot))

        pressEscape(robot, robot.lookup(".editor-content .diff-view").query())
        assertEquals(emptyList(), shownDiff(robot))
        assertSame(area, shownArea(robot))
        assertEquals("edited new\n", area.text)
        assertEquals(caret, area.caretPosition)
        assertSame(area, stage.scene.focusOwner)
        robot.interact { area.undo() }
        assertEquals("new\n", area.text)

        robot.interact { editor.toggleDiff() }
        val titlesWhenClean =
            robot
                .lookup(".diff-title")
                .queryAllAs(Label::class.java)
                .map { it.text }
                .toSet()
        assertEquals(setOf("HEAD", "Working Tree"), titlesWhenClean)
        robot.interact { editor.toggleDiff() }
        assertEquals(emptyList(), shownDiff(robot))
    }

    @Test
    fun `clicking a change marker shows the diff at its change`(robot: FxRobot) {
        val lines = (1..80).map { "line $it" }
        val a = textFile("a.txt", lines.joinToString("\n", postfix = "\n"))
        val original = lines.toMutableList().apply { set(59, "old line 60") }.joinToString("\n", postfix = "\n")
        editor.diffOf = immediately { TextDiff(original, listOf(LineChange(59..59, 59..59))) }
        open(robot, a)
        robot.interact { shownArea(robot).showParagraphAtTop(50) }
        WaitForAsyncUtils.waitForFxEvents()

        val marker =
            robot
                .lookup(".editor-content .gutter")
                .queryAllAs(HBox::class.java)
                .single { (it.children[0] as Label).text.trim() == "60" }
                .children[1]
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            robot.interact { fireMouse(marker, type) }
        }
        WaitForAsyncUtils.waitForFxEvents()

        val right =
            robot
                .lookup(".editor-content .diff-view .code-area")
                .queryAllAs(CodeArea::class.java)
                .maxBy { it.localToScene(0.0, 0.0).x }
        assertEquals(59, right.currentParagraph)
        assertTrue(right.firstVisibleParToAllParIndex() in 50..59, "shown near the top")
        val diff = robot.lookup(".editor-content .diff-view").query<Node>()
        assertTrue(checkNotNull(focusOwner()).isInside(diff), "the diff has the focus")
    }

    @Test
    fun `changes shown right after a preview file opened keep the focus in the diff`(robot: FxRobot) {
        val field = fieldAbove(robot, listOf(MarkdownFileViewer()))
        val plain = textFile("README.md", "# New\n")
        val changed = textFile("CHANGES.md", "# New\n")
        editor.diffOf = immediately { if (it == changed) TextDiff("# Old\n", listOf(LineChange(0..0, 0..0))) else null }
        // The source is in a split pane, which adds it to the scene only once skinned: it is focused later.
        robot.interact { field.requestFocus() }
        open(robot, plain)
        eventually(robot) {
            val owner = stage.scene.focusOwner
            assertTrue(owner is CodeArea && owner.isInside(editor.selectedTab!!.content), "the source, not $owner")
        }

        robot.interact { field.requestFocus() }
        robot.interact {
            editor.open(changed)
            checkNotNull(checkNotNull(editor.editorTabs.find(changed)).editor.host).showChanges(changed, 0)
        }
        // Until the source is in the scene, below the diff, and the focus it was to get is due.
        val source = checkNotNull(editor.editorTabs.find(changed)).editor.node
        eventually(robot) { assertNotNull(source.lookup(".code-area")?.scene) }
        WaitForAsyncUtils.waitForFxEvents()

        val diff = robot.lookup(".editor-content .diff-view").query<Node>()
        assertTrue(checkNotNull(focusOwner()).isInside(diff), "the diff has the focus, not ${focusOwner()}")
    }

    @Test
    fun `the Edit button of the diff goes back to the editor`(robot: FxRobot) {
        editor.diffOf = immediately { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        open(robot, textFile("a.txt", "new\n"))
        robot.interact { editor.toggleDiff() }

        robot.interact {
            robot
                .lookup(".diff-toolbar .button")
                .queryAllAs(Button::class.java)
                .single { it.text == "Edit" }
                .fire()
        }

        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("new\n", shownText(robot))
    }

    @Test
    fun `closing the diff gives its focus to the text, also when the editor had none`(robot: FxRobot) {
        val field = fieldAbove(robot)
        editor.diffOf = immediately { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        open(robot, textFile("a.txt", "new\n"))
        robot.interact {
            field.requestFocus()
            editor.toggleDiff()
        }
        val diff = robot.lookup(".editor-content .diff-view").query<Node>()
        assertTrue(stage.scene.focusOwner.isInside(diff), "the diff has the focus")

        pressEscape(robot, diff)

        assertSame(shownArea(robot), stage.scene.focusOwner)
    }

    @Test
    fun `a diff going away leaves the focus elsewhere alone`(robot: FxRobot) {
        val field = fieldAbove(robot)
        var diff: TextDiff? = TextDiff("old\n", listOf(LineChange(0..0, 0..0)))
        editor.diffOf = immediately { diff }
        open(robot, textFile("a.txt", "new\n"))
        robot.interact { editor.toggleDiff() }
        robot.interact { field.requestFocus() }

        diff = null
        robot.interact { editor.refreshDiffs() }

        assertEquals(emptyList(), shownDiff(robot))
        assertSame(field, stage.scene.focusOwner)
    }

    @Test
    fun `closing a tab with unsaved changes while its diff is shown asks to save`(robot: FxRobot) {
        val a = textFile("a.txt", "a\n")
        editor.diffOf = immediately { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        val asked = openEdited(robot, a) { SaveChoice.SAVE }
        robot.interact { editor.toggleDiff() }
        val close = tabLabel(robot, "● a.txt").parent.lookup(".close-button") as Button

        robot.interact { close.fire() }

        assertEquals(listOf(listOf("a.txt")), asked)
        assertEquals("edited a\n", a.readText())
        assertEquals(emptyList(), tabTitles(robot))
    }

    @Test
    fun `Save while the diff is shown saves the editor's text`(robot: FxRobot) {
        val a = textFile("a.txt", "a\n")
        editor.diffOf = immediately { TextDiff("old\n", listOf(LineChange(0..0, 0..0))) }
        openEdited(robot, a) { SaveChoice.CANCEL }
        robot.interact { editor.toggleDiff() }

        robot.interact { assertTrue(editor.save()) }

        assertEquals("edited a\n", a.readText())
        assertEquals(listOf("a.txt"), tabTitles(robot))
    }

    @Test
    fun `files without a diff have none to show`(robot: FxRobot) {
        open(robot, textFile("a.txt", "new\n"))
        assertEquals(false, editor.canToggleDiff.value)

        robot.interact { editor.toggleDiff() }

        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("new\n", shownText(robot))
    }

    @Test
    fun `a shown diff follows the file and gives way to the editor when it goes`(robot: FxRobot) {
        val a = textFile("a.txt", "one\n")
        var diff: TextDiff? = TextDiff("zero\n", listOf(LineChange(0..0, 0..0)))
        editor.diffOf = immediately { diff }
        open(robot, a)
        robot.interact { editor.toggleDiff() }

        a.writeText("two\n")
        eventually(robot) { assertEquals(listOf("zero", "two"), shownDiff(robot)) }

        diff = null
        robot.interact { editor.refreshDiffs() }
        assertEquals(emptyList(), shownDiff(robot))
        assertEquals("two\n", shownText(robot))
        assertEquals(false, editor.canToggleDiff.value)
    }

    @Test
    fun `lines edited apart stay apart when the diffs are asked for again`(robot: FxRobot) {
        val lines = (1..300).map { "line $it" }
        val a = textFile("a.txt", lines.joinToString("\n", postfix = "\n"))
        val diff = TextDiff(lines.joinToString("\n", prefix = "old\n", postfix = "\n"), listOf(LineChange(0..0, 0..0)))
        editor.diffOf = immediately { diff }
        open(robot, a)
        val area = shownArea(robot)
        robot.interact {
            area.insertText(area.getAbsolutePosition(1, 0), "edited ")
            area.insertText(area.getAbsolutePosition(299, 0), "edited ")
            editor.refreshDiffs()
        }

        assertEquals(mapOf(1 to "diff-modified", 2 to "diff-modified"), shownMarkers(robot))
        robot.interact { area.showParagraphAtBottom(300) }
        assertEquals(mapOf(300 to "diff-modified"), shownMarkers(robot))
    }

    @Test
    fun `only files shown as text are asked for their diff`(robot: FxRobot) {
        val fallback = DefaultTextFileViewer()
        val area = EditorArea(FileViewers(listOf(fallback, UnsavableViewer()), fallback), watcher, fontSettings)
        val asked = mutableListOf<String>()
        area.diffOf =
            immediately { path ->
                asked += path.name
                null
            }
        val notes = textFile("notes.unsavable")
        val a = textFile("a.txt")
        robot.interact {
            area.open(notes)
            area.open(a)
            area.refreshDiffs()
        }
        // Changes on disk may ask again.
        assertEquals(setOf("a.txt"), asked.toSet())
    }

    @Test
    fun `saving keeps the editor and its changes come from the diff again`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        editor.diffOf =
            immediately { path ->
                if (path.readText() == "new\n") null else TextDiff("new\n", listOf(LineChange(0..0, 0..0)))
            }
        open(robot, a)
        robot.interact { shownArea(robot).insertText(0, "newer ") }
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))

        robot.interact { editor.save() }

        eventually(robot) { assertEquals("newer new\n", a.readText()) }
        robot.interact { editor.refreshDiffs() }
        assertEquals("newer new\n", shownText(robot))
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))
        assertEquals(emptyList(), shownDiff(robot))
    }

    @Test
    fun `an edit on disk asks for the diff again`(robot: FxRobot) {
        val a = textFile("a.txt", "one\n")
        editor.diffOf =
            immediately { path ->
                // A line removed before the new text, unlike the edit from one to two.
                if (path.readText() == "one\n") null else TextDiff("zero\ntwo\n", listOf(LineChange(0..0, 0 until 0)))
            }
        open(robot, a)
        assertEquals(emptyMap(), shownMarkers(robot))

        a.writeText("two\n")

        eventually(robot) {
            assertEquals("two\n", shownText(robot))
            assertEquals(mapOf(1 to "diff-removed-above"), shownMarkers(robot))
        }
    }

    @Test
    fun `markers keep the old answer until the newest arrives, and a stale answer is dropped`(robot: FxRobot) {
        val a = textFile("a.txt", "new\nsame\n")
        val answers = mutableListOf<(TextDiff?) -> Unit>()
        editor.diffOf = { _, done -> answers += done }
        open(robot, a)
        assertEquals(emptyMap(), shownMarkers(robot), "no answer yet")
        robot.interact { answers[0](TextDiff("old\nsame\n", listOf(LineChange(0..0, 0..0)))) }
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))

        robot.interact {
            editor.refreshDiffs()
            editor.refreshDiffs()
        }
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot), "the old markers until the answer")
        robot.interact { answers[2](TextDiff("new\n", listOf(LineChange(1 until 1, 1..1)))) }
        assertEquals(mapOf(2 to "diff-added"), shownMarkers(robot))
        robot.interact { answers[1](null) }
        assertEquals(mapOf(2 to "diff-added"), shownMarkers(robot), "an answer to an older request")
        assertEquals(true, editor.canToggleDiff.value)
    }

    @Test
    fun `a stale answer while the diff is shown leaves it as the newest answer made it`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        val answers = mutableListOf<(TextDiff?) -> Unit>()
        editor.diffOf = { _, done -> answers += done }
        open(robot, a)
        robot.interact {
            answers[0](TextDiff("old\n", listOf(LineChange(0..0, 0..0))))
            editor.toggleDiff()
        }
        assertEquals(listOf("old", "new"), shownDiff(robot))

        robot.interact {
            editor.refreshDiffs()
            editor.refreshDiffs()
        }
        assertEquals(listOf("old", "new"), shownDiff(robot), "the shown diff until the answer")
        robot.interact { answers[2](TextDiff("older\n", listOf(LineChange(0..0, 0..0)))) }
        assertEquals(listOf("older", "new"), shownDiff(robot))
        robot.interact { answers[1](null) }

        assertEquals(listOf("older", "new"), shownDiff(robot), "an answer to an older request")
        assertNull(shownText(robot), "the editor stays hidden")
    }

    @Test
    fun `an answer for a tab that was closed is dropped`(robot: FxRobot) {
        val a = textFile("a.txt", "new\n")
        val answers = mutableListOf<(TextDiff?) -> Unit>()
        editor.diffOf = { _, done -> answers += done }
        open(robot, a)
        robot.interact { editor.editorTabs.close(checkNotNull(editor.editorTabs.find(a))) }
        open(robot, a)

        robot.interact { answers[0](TextDiff("old\n", listOf(LineChange(0..0, 0..0)))) }

        assertEquals(emptyMap(), shownMarkers(robot), "the answer for the closed tab")
        assertEquals(false, editor.canToggleDiff.value)
        robot.interact { answers[1](TextDiff("old\n", listOf(LineChange(0..0, 0..0)))) }
        assertEquals(mapOf(1 to "diff-modified"), shownMarkers(robot))
    }
}
