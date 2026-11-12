package dev.sluide.ide

import dev.sluide.codereview.ReviewScope
import dev.sluide.gitlog.REVIEW_BRANCH_KEYS
import dev.sluide.gitlog.ReviewRequest
import javafx.scene.control.Label
import javafx.stage.Stage
import javafx.stage.Window
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI tests of the main window's wiring of the review windows (ADR 0168): the Git menu, the git log's requests, the
 * links of the reviews, the repository changes and the close. A recording `Reviews` stands in for the windows
 * themselves. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowReviewUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val commit = arrayOf("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q")

    /** A repository with one commit, opened in the window once its git log is there. */
    private fun openRepository(robot: FxRobot) {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q", "-b", "main")
        project.resolve("notes.txt").writeText("one\ntwo\nthree\n")
        runGit("add", ".")
        runGit(*commit, "-m", "Initial commit")
        robot.interact { projectService.open(project) }
        eventually(robot) { assertFalse(menu(robot, "Git").items.single { it.text == "Review Branch" }.isDisable) }
    }

    /** The review windows shown: top-level windows besides the main one; no other window is open in these tests. */
    private fun reviewStages() = Window.getWindows().filterIsInstance<Stage>().filter { it.isShowing && it !== stage }

    /** Opens the review of HEAD and waits until its load has read the fingerprint. */
    private fun openReviewAndWaitForLoad(robot: FxRobot) {
        robot.interact { menu(robot, "Git").items.single { it.text == "Review Branch" }.fire() }
        eventually(robot) { assertTrue(reviewSource.fingerprints.get() >= 1) }
        // No signal for "nothing more is coming": the window's own focus and loads settle on their own threads.
        Thread.sleep(500)
    }

    @Test
    fun `Git - Review Branch on Shortcut+Shift+R opens the review of HEAD once the git log has a repository`(
        robot: FxRobot,
    ) {
        val item = menu(robot, "Git").items.single { it.text == "Review Branch" }
        assertEquals(REVIEW_BRANCH_KEYS, item.accelerator)
        assertTrue(item.isDisable, "no folder, no git log")

        openRepository(robot)
        robot.interact { item.fire() }

        eventually(robot) { assertEquals(1, reviewStages().size) }
        val (root, scope) = reviewSource.resolved.single()
        assertEquals(project.toRealPath(), root.toRealPath())
        assertEquals(ReviewScope.Branch("HEAD"), scope)
    }

    @Test
    fun `the git log's review requests open the review of a branch or of the commits from the oldest to the newest`(
        robot: FxRobot,
    ) {
        openRepository(robot)

        robot.interact {
            gitLogView.onReview(ReviewRequest.Branch("refs/heads/main"))
            gitLogView.onReview(ReviewRequest.Commits(listOf("c3", "c2", "c1")))
        }

        eventually(robot) {
            assertEquals(
                setOf(ReviewScope.Branch("refs/heads/main"), ReviewScope.Commits(oldest = "c1", newest = "c3")),
                reviewSource.resolved.map { it.second }.toSet(),
            )
        }
    }

    @Test
    fun `a review's file link opens the file in the editor at the 0-based line, its history link the git log`(
        robot: FxRobot,
    ) {
        openRepository(robot)
        val file = project.resolve("notes.txt")

        robot.interact { reviews.openFile(file, 2) }

        assertEquals(listOf(file.toRealPath()), editorArea.openPaths.map { it.toRealPath() })
        eventually(robot) {
            val area = robot.lookup(".code-area").queryAllAs(CodeArea::class.java).single()
            assertEquals(2, area.currentParagraph)
        }
        robot.interact { reviews.showHistory(file) }
        assertTrue(gitLogView.expanded.get())
        eventually(robot) {
            val titles = robot.lookup(".git-log-tab-title").queryAllAs(Label::class.java).map { it.text }
            assertTrue("History: notes.txt" in titles, titles.toString())
        }
    }

    @Test
    fun `the reviews check for changes when the git folder changes`(robot: FxRobot) {
        openRepository(robot)
        openReviewAndWaitForLoad(robot)
        val before = reviewSource.fingerprints.get()

        // Only .git changes (its configuration, which the watch sees on every platform): the status snapshot stays as it is.
        runGit("config", "review.test", "1")

        eventually(robot) { assertTrue(reviewSource.fingerprints.get() > before) }
    }

    @Test
    fun `the reviews check for changes when the status changes`(robot: FxRobot) {
        openRepository(robot)
        openReviewAndWaitForLoad(robot)
        val before = reviewSource.fingerprints.get()

        // An untracked file: the status changes, `.git` does not.
        project.resolve("untracked.txt").writeText("new")

        eventually(robot) { assertTrue(reviewSource.fingerprints.get() > before) }
    }

    @Test
    fun `closing the window closes its reviews`(robot: FxRobot) {
        openRepository(robot)
        robot.interact { menu(robot, "Git").items.single { it.text == "Review Branch" }.fire() }
        eventually(robot) { assertEquals(1, reviewStages().size) }

        robot.interact { mainWindow.dispose() }

        eventually(robot) { assertEquals(0, reviewStages().size) }
    }
}
