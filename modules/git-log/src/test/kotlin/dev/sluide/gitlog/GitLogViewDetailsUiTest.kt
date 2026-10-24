package dev.sluide.gitlog

import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import javafx.animation.Animation
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UI tests of the git log view: the details pane. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewDetailsUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `shows the details of the selected commit right of the table, the committer only if it differs`(
        robot: FxRobot,
    ) {
        refs = someRefs
        detailsOf = { hash ->
            val details = checkNotNull(defaultDetails(hash))
            when (hash) {
                "h2" -> {
                    details.copy(
                        committer = "Other",
                        committerEmail = "other@example.com",
                        committerTime = Instant.ofEpochSecond(9000),
                    )
                }

                "h5" -> {
                    details.copy(message = "")
                }

                else -> {
                    details
                }
            }
        }
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(view.inner.width * 0.3, details.width, 10.0, "the right 30 %")
        assertEquals("No commit selected", placeholderShown())

        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }
        robot.interact {
            assertTrue(details.body.isVisible)
            assertTrue(details.body.text.startsWith("Body of 1. Body of 1."), details.body.text)
            assertEquals("h1 Author 1\na1@example.com\non ${at(Instant.ofEpochSecond(60))}", details.author.text)
            assertFalse(details.committed.isVisible)
            assertEquals(
                listOf("HEAD", "main", "origin/main", "v1.0"),
                details.refs.children.map { (it as Label).text },
            )
            val subjectFonts =
                details.subject
                    .lookupAll(".text")
                    .filterIsInstance<Text>()
                    .map { it.font.style }
            assertTrue(subjectFonts.isNotEmpty() && subjectFonts.all { "bold" in it.lowercase() }, "$subjectFonts")
            // Wrapped and as high as its text: the body takes several lines, without a scroll bar of its own.
            assertEquals(emptyList(), details.body.lookupAll(".scroll-bar").filter { it.isVisible })
            val text =
                details.body
                    .lookupAll(".paragraph-text")
                    .filterIsInstance<TextFlow>()
                    .single()
            assertEquals(details.body.width, text.width, 1.0, "wrapped at the full width")
            assertTrue(
                text.height <= details.body.height,
                "${text.layoutBounds} in ${details.body.height}",
            )
            assertTrue(details.body.height > details.subject.height * 3, "${details.body.height}")
        }

        select(robot, 1)
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        robot.interact {
            assertTrue(details.committed.isVisible)
            assertEquals("committed on ${at(Instant.ofEpochSecond(9000))} by Other", details.committed.text)
            assertFalse(details.refs.isVisible, "no refs")
        }

        // The rules of the committer line and of splitting the message are CommitDetailsPaneTest's.
        select(robot, 4)
        eventually { assertTrue(details.author.text.startsWith("h5 Author 5")) }
        robot.interact {
            assertFalse(details.subject.isVisible, "no empty bold line")
            assertFalse(details.subject.isManaged)
            assertFalse(details.body.isVisible)
            assertTrue(details.scroll.isVisible)
        }
    }

    @Test
    fun `keeps the details shown while the next ones load, then says Loading`(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }

        detailGates["h2"] = CountDownLatch(1)
        select(robot, 1)
        robot.interact { assertTrue(showsDetailsOf("Subject 1"), "kept while the next ones load") }
        eventually { assertEquals("Loading…", placeholderShown()) }

        detailGates.getValue("h2").countDown()
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        robot.interact {
            assertEquals(
                javafx.animation.Animation.Status.STOPPED,
                details.loading.status,
                "Loading… does not come back",
            )
        }

        select(robot, 0, 3, 5)
        eventually { assertEquals("3 commits selected", placeholderShown()) }
        detailsOf = { null }
        select(robot, 4)
        eventually { assertEquals("The commit details could not be read.", placeholderShown()) }
    }

    @Test
    fun `the details pane keeps its share of the width when the log is resized`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }

        fun share() = details.width / view.inner.width

        fun resize(width: Double) {
            robot.interact { view.scene.window.width = width }
        }
        try {
            assertEquals(0.3, share(), 0.02)
            resize(500.0)
            assertEquals(0.3, share(), 0.02, "${details.width} of ${view.inner.width}")
            resize(800.0)
            assertEquals(0.3, share(), 0.02, "${details.width} of ${view.inner.width}")

            // Dragged by the user: that share is kept.
            robot.interact { view.inner.setDividerPositions(0.5) }
            resize(560.0)
            assertEquals(0.5, share(), 0.02, "${details.width} of ${view.inner.width}")
            resize(800.0)
            assertEquals(0.5, share(), 0.02, "${details.width} of ${view.inner.width}")
        } finally {
            // The stage is shared by the tests.
            resize(800.0)
        }
    }

    @Test
    fun `a refresh that keeps the commit selected shows its new refs without reading its details again`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        select(robot, 2)
        eventually { assertTrue(showsDetailsOf("Subject 3")) }
        refs = GitRefs(listOf(ref("refs/tags/new", GitRefKind.TAG, "h3")), null)

        refreshAndAwaitReload(robot)
        eventually {
            assertEquals(listOf("new"), details.refs.children.map { (it as Label).text })
            assertTrue(showsDetailsOf("Subject 3"))
        }
        assertEquals(listOf("h3"), detailLoads.toList(), "a commit's details do not change (ADR 0062)")
    }

    @Test
    fun `the table keeps the focus while the selection changes`(robot: FxRobot) {
        openAndExpand(robot)
        assertSame<Node?>(view.table, view.scene.focusOwner)
        for (index in listOf(0, 1, 2, 7)) {
            select(robot, index)
            eventually { assertTrue(showsDetailsOf("Subject ${index + 1}")) }
            assertSame<Node?>(view.table, view.scene.focusOwner, "after selecting row $index")
        }
        select(robot, 1, 2)
        eventually { assertEquals("2 commits selected", placeholderShown()) }
        assertSame<Node?>(view.table, view.scene.focusOwner)
    }

    @Test
    fun `the message can be selected and copied`(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }
        val subject = details.subject
        assertFalse(subject.isEditable)
        assertFalse(subject.isFocusTraversable, "not reached with Tab")

        // A click gives it the focus, so that Shortcut+C copies from it instead of the table.
        click(robot, subject)
        assertSame<Node?>(subject, view.scene.focusOwner)
        robot.interact {
            Clipboard.getSystemClipboard().clear()
            subject.selectRange(0, 7)
        }
        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(subject, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, !mac, false, mac))
        }
        assertEquals("Subject", clipboard())
        assertEquals("Subject 1", subject.text, "read-only")

        // A refresh keeps the commit as it is shown, not read again (ADR 0062), and so the selected text.
        refreshAndAwaitReload(robot)
        robot.interact { assertEquals("Subject", subject.selectedText) }
        assertEquals(1, detailLoads.count { it == "h1" })

        // Another commit and back: shown anew, nothing selected.
        select(robot, 1)
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        select(robot, 0)
        eventually { assertTrue(showsDetailsOf("Subject 1")) }
        robot.interact { assertEquals("", subject.selectedText) }
    }
}
