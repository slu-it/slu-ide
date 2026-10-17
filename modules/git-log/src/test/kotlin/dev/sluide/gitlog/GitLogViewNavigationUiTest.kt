package dev.sluide.gitlog

import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import javafx.animation.Animation
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI tests of the git log view: the branches containing a commit, links in commit messages and Go to (ADR 0045). Needs
 * a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewNavigationUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** The branches line as shown, `null` while hidden. */
    private fun branchesLine(): String? =
        WaitForAsyncUtils
            .asyncFx<String?> {
                details.branchesLine.takeIf { it.isVisible && details.scroll.isVisible }?.text
            }.get()

    @Test
    fun `lists the branches containing the selected commit below its refs, the current branch first`(robot: FxRobot) {
        refs = someRefs
        branchesOf = { hash ->
            when (hash) {
                "h1" -> listOf("refs/remotes/origin/main", "refs/heads/feature/x", "refs/heads/main")
                "h2" -> listOf("refs/heads/main")
                else -> emptyList()
            }
        }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("In 3 branches: main, feature/x, origin/main", branchesLine()) }
        robot.interact {
            val content = details.branchesLine.parent
            assertTrue(
                content.childrenUnmodifiable.indexOf(details.branchesLine) >
                    content.childrenUnmodifiable.indexOf(details.refs),
                "below the refs",
            )
        }

        select(robot, 1)
        eventually { assertEquals("In 1 branch: main", branchesLine()) }
        select(robot, 2)
        eventually { assertEquals("In no branch", branchesLine()) }

        // Read once per commit while the refs stay the same.
        select(robot, 0)
        eventually { assertEquals("In 3 branches: main, feature/x, origin/main", branchesLine()) }
        assertEquals(listOf("h1", "h2", "h3"), branchLoads)
        assertEquals(listOf("h1", "h2", "h3", "h1"), detailLoads, "the details are read again")
    }

    @Test
    fun `more than six branches are cut short until Show all is clicked, for that commit`(robot: FxRobot) {
        val many = (1..9).map { "refs/heads/b$it" }
        branchesOf = { many }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("In 9 branches: b1, b2, b3, b4, b5, b6, … Show all (9)", branchesLine()) }

        clickText(robot, details.branchesLine, "Show all (9)")
        eventually { assertEquals("In 9 branches: ${(1..9).joinToString(", ") { "b$it" }}", branchesLine()) }

        select(robot, 1)
        eventually { assertEquals("In 9 branches: b1, b2, b3, b4, b5, b6, … Show all (9)", branchesLine()) }
    }

    @Test
    fun `says when the branches cannot be read, and looks for them only after a while`(robot: FxRobot) {
        branchesOf = { hash -> if (hash == "h1") null else listOf("refs/heads/main") }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("Branches could not be read.", branchesLine()) }

        branchGates["h2"] = CountDownLatch(1)
        select(robot, 1)
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        robot.interact {
            assertFalse(details.branchesLine.isVisible, "nothing at first")
            assertEquals(Animation.Status.RUNNING, details.lookingForBranches.status)
        }
        eventually { assertEquals("Looking for branches…", branchesLine()) }
        branchGates.getValue("h2").countDown()
        eventually { assertEquals("In 1 branch: main", branchesLine()) }
    }

    @Test
    fun `new refs read the branches of the shown commit again, a result for another commit is dropped`(
        robot: FxRobot,
    ) {
        refs = someRefs
        branchesOf = { listOf("refs/heads/main") }
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertEquals("In 1 branch: main", branchesLine()) }

        // The same refs: nothing read again.
        robot.interact { view.refresh() }
        eventually { assertEquals(listOf("h1", "h1"), detailLoads) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("h1"), branchLoads)

        branchesOf = { listOf("refs/heads/main", "refs/heads/new") }
        refs = GitRefs(someRefs.refs + ref("refs/heads/new", GitRefKind.LOCAL_BRANCH, "h1"), "refs/heads/main")
        robot.interact { view.refresh() }
        eventually { assertEquals("In 2 branches: main, new", branchesLine()) }
        assertEquals(listOf("h1", "h1"), branchLoads)

        branchGates["h2"] = CountDownLatch(1)
        select(robot, 1)
        eventually { assertTrue(showsDetailsOf("Subject 2")) }
        select(robot, 2)
        eventually { assertTrue(showsDetailsOf("Subject 3")) }
        branchesOf = { hash -> if (hash == "h2") listOf("refs/heads/h2-only") else listOf("refs/heads/main") }
        branchGates.getValue("h2").countDown()
        eventually { assertEquals("In 1 branch: main", branchesLine()) }
        assertEquals(listOf("h1", "h1", "h2", "h3"), branchLoads)
    }

    /** Clicks [text] in [area] with the primary button. */
    private fun clickText(
        robot: FxRobot,
        area: LinkText,
        text: String,
    ) {
        val point =
            WaitForAsyncUtils
                .asyncFx<javafx.geometry.Point2D> {
                    val start = area.text.indexOf(text)
                    check(start >= 0) { "'$text' not in '${area.text}'" }
                    val bounds = checkNotNull(area.getCharacterBoundsOnScreen(start + 1, start + 2).orElse(null))
                    val screen = javafx.geometry.Point2D(bounds.centerX, bounds.centerY)
                    area.localToScene(area.screenToLocal(screen))
                }.get()
        click(robot, area, 1, point.x, point.y)
    }
}
