package dev.sluide.gitlog

import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitRemoteRemoval
import dev.sluide.git.GitRemoteUrls
import dev.sluide.git.GitResult
import dev.sluide.gitlog.operations.ADD_BUTTON
import dev.sluide.gitlog.operations.CHANGE_BUTTON
import dev.sluide.gitlog.operations.REMOVE_BUTTON
import dev.sluide.gitlog.operations.RENAME_BUTTON
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI tests of the git log's remote writes (ADR 0183): the dialogs of Add, Rename and Change URL, the question before
 * Remove, and Fetch All, started through the branch commands the menus call. The dialogs block the JavaFX thread
 * until answered, so a command runs later. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesRemotesUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val commands get() = view.branchCommands

    /** Runs [command] later, as a menu item would: a dialog it opens blocks the JavaFX thread. */
    private fun later(command: () -> Unit) {
        Platform.runLater(command)
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun enabled(
        pane: DialogPane,
        button: ButtonType,
    ) = !pane.lookupButton(button).isDisable

    @Test
    fun `Add Remote asks for name and URL, tells what is wrong, and adds without a fetch when unchecked`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)

        later { commands.addRemote() }

        val pane = awaitDialog(robot)
        val name = pane.lookup(".git-ref-name") as TextField
        val url = pane.lookup(".git-remote-url") as TextField
        val problem = pane.lookup(".git-ref-name-problem") as Label
        val fetch = pane.lookup(".git-remote-fetch") as CheckBox
        assertEquals("Add Remote", (pane.scene.window as Stage).title)
        assertEquals("", name.text, "the repository has a remote")
        assertEquals("Enter a name.", problem.text)
        assertFalse(enabled(pane, ADD_BUTTON))
        assertTrue(fetch.isSelected)
        robot.interact { name.text = "origin" }
        assertEquals("A remote named 'origin' already exists.", problem.text)
        robot.interact { name.text = "upstream" }
        assertEquals("Enter a URL.", problem.text)
        assertFalse(enabled(pane, ADD_BUTTON))
        robot.interact {
            url.text = "  https://example.com/x.git "
            fetch.isSelected = false
        }
        assertEquals("", problem.text)
        assertTrue(enabled(pane, ADD_BUTTON))
        press(robot, pane, ADD_BUTTON)

        eventually { assertEquals("Added remote upstream.", status(robot)) }
        assertEquals(listOf("remote add upstream https://example.com/x.git"), writes.calls)
    }

    @Test
    fun `Add Remote without a remote starts with origin selected and fetches after adding`(robot: FxRobot) {
        currentRemotes = emptyList()
        openAndExpand(robot)

        later { commands.addRemote() }

        val pane = awaitDialog(robot)
        val name = pane.lookup(".git-ref-name") as TextField
        eventually { assertEquals("origin", name.selectedText) }
        robot.interact { (pane.lookup(".git-remote-url") as TextField).text = "../bare" }
        press(robot, pane, ADD_BUTTON)

        eventually { assertTrue(status(robot).startsWith("Added remote origin. Fetched origin")) }
        assertEquals(listOf("remote add origin ../bare", "fetch origin"), writes.calls)
    }

    @Test
    fun `a failed fetch after adding is told as Fetch Failed while the remote stays added`(robot: FxRobot) {
        currentRemotes = emptyList()
        writes.fetchResult = { GitFetchResult(GitResult(false, "fatal: no route to host")) }
        openAndExpand(robot)

        later { commands.addRemote() }
        val add = awaitDialog(robot)
        robot.interact { (add.lookup(".git-remote-url") as TextField).text = "../bare" }
        press(robot, add, ADD_BUTTON)

        val pane = awaitDialog(robot)
        assertEquals("Fetch Failed", (pane.scene.window as Stage).title)
        assertEquals("origin could not be fetched.", pane.headerText)
        assertEquals("Added remote origin.", status(robot))
        press(robot, pane, ButtonType.OK)
    }

    @Test
    fun `Rename Remote starts with the name selected and renames`(robot: FxRobot) {
        openAndExpand(robot)

        later { commands.renameRemote("origin") }

        val pane = awaitDialog(robot)
        val name = pane.lookup(".git-ref-name") as TextField
        assertEquals("Rename Remote", (pane.scene.window as Stage).title)
        eventually { assertEquals("origin", name.selectedText) }
        assertFalse(enabled(pane, RENAME_BUTTON), "the same name")
        robot.interact { name.text = "upstream" }
        assertTrue(enabled(pane, RENAME_BUTTON))
        press(robot, pane, RENAME_BUTTON)

        eventually { assertEquals("Renamed remote origin to upstream.", status(robot)) }
        assertEquals(listOf("remote rename origin upstream"), writes.calls)
    }

    @Test
    fun `Change URL reads the URLs, shows them masked and changes the one that was edited`(robot: FxRobot) {
        writes.urls = GitRemoteUrls("https://user:token@example.com/x.git", null, false, false)
        openAndExpand(robot)

        later { commands.changeRemoteUrls("origin") }

        val pane = awaitDialog(robot)
        val url = pane.lookup(".git-remote-url") as TextField
        val push = pane.lookup(".git-remote-push-url") as TextField
        assertEquals("Change URL of 'origin'", (pane.scene.window as Stage).title)
        assertEquals(view.scene.window, (pane.scene.window as Stage).owner)
        assertNull(pane.headerText)
        assertEquals("https://****@example.com/x.git", url.text)
        assertEquals("Same as URL", push.promptText)
        assertEquals("Credentials are hidden as ****; leave them to keep them.", message(pane).text)
        eventually { assertTrue(url.isFocused) }
        assertFalse(enabled(pane, CHANGE_BUTTON), "nothing changed")
        robot.interact { url.text = "https://****@example.org/y.git" }
        assertEquals("Enter the credentials for the new host, or remove them.", message(pane).text)
        assertFalse(enabled(pane, CHANGE_BUTTON))
        robot.interact { url.text = "https://****@example.com/y.git" }
        assertTrue(enabled(pane, CHANGE_BUTTON))
        press(robot, pane, CHANGE_BUTTON)

        eventually { assertEquals("Changed the URL of origin.", status(robot)) }
        assertEquals(
            listOf(
                "remote urls origin",
                "remote set-url origin url=https://user:token@example.com/y.git push=null removePush=false",
            ),
            writes.calls,
        )
    }

    private fun message(pane: DialogPane) = pane.lookup(".git-ref-name-problem") as Label

    @Test
    fun `Change URL leaves a field with several values alone and clears the push URL`(robot: FxRobot) {
        writes.urls = GitRemoteUrls("https://example.com/a.git", "git@example.com:p.git", true, false)
        openAndExpand(robot)

        later { commands.changeRemoteUrls("origin") }

        val pane = awaitDialog(robot)
        val url = pane.lookup(".git-remote-url") as TextField
        val push = pane.lookup(".git-remote-push-url") as TextField
        assertTrue(url.isDisable)
        assertFalse(push.isDisable)
        assertEquals("Several URLs are configured; change them in a terminal.", message(pane).text)
        assertFalse(enabled(pane, CHANGE_BUTTON))
        robot.interact { push.text = "" }
        assertTrue(enabled(pane, CHANGE_BUTTON))
        press(robot, pane, CHANGE_BUTTON)

        eventually { assertEquals("Changed the URL of origin.", status(robot)) }
        assertEquals(
            listOf("remote urls origin", "remote set-url origin url=null push=null removePush=true"),
            writes.calls,
        )
        writes.urls = GitRemoteUrls("https://example.com/a.git", "git@example.com:p.git", false, true)

        later { commands.changeRemoteUrls("origin") }

        assertTrue((awaitDialog(robot).lookup(".git-remote-push-url") as TextField).isDisable)
    }

    @Test
    fun `Cancel in the dialogs starts nothing`(robot: FxRobot) {
        openAndExpand(robot)

        later { commands.addRemote() }
        press(robot, awaitDialog(robot), ButtonType.CANCEL)
        later { commands.renameRemote("origin") }
        press(robot, awaitDialog(robot), ButtonType.CANCEL)
        later { commands.changeRemoteUrls("origin") }
        press(robot, awaitDialog(robot), ButtonType.CANCEL)

        WaitForAsyncUtils.waitForFxEvents()
        assertNoDialog()
        assertEquals(listOf("remote urls origin"), writes.calls, "only the read")
    }

    @Test
    fun `Remove asks first with Cancel the default and focused, naming what is lost, and removes once confirmed`(
        robot: FxRobot,
    ) {
        writes.removal = GitRemoteRemoval(12, listOf("main", "fix"), true)
        openAndExpand(robot)

        later { commands.removeRemote("origin") }

        val question = awaitDialog(robot)
        assertEquals("Remove Remote", (question.scene.window as Stage).title)
        assertEquals("Remove remote 'origin'?", question.headerText)
        assertEquals(
            "Its 12 remote branches are deleted locally.\n2 local branches (main and fix) lose their upstream.\n" +
                "origin is no longer the default push remote.\nIts URL is removed. This cannot be undone.",
            (question.lookup(".git-confirmation-details") as TextArea).text,
        )
        val cancel = question.lookupButton(ButtonType.CANCEL) as Button
        assertTrue(cancel.isDefaultButton)
        assertFalse((question.lookupButton(REMOVE_BUTTON) as Button).isDefaultButton)
        eventually { assertTrue(cancel.isFocused) }
        press(robot, question, ButtonType.CANCEL)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("remote removal origin"), writes.calls, "Cancel removes nothing")

        later { commands.removeRemote("origin") }
        press(robot, awaitDialog(robot), REMOVE_BUTTON)

        eventually { assertEquals("Removed remote origin.", status(robot)) }
        assertEquals(listOf("remote removal origin", "remote removal origin", "remote remove origin"), writes.calls)
    }

    @Test
    fun `Fetch All Remotes fetches all remotes`(robot: FxRobot) {
        writes.remotes = listOf("origin", "fork")
        openAndExpand(robot)

        later { commands.fetchAll() }

        eventually { assertEquals(listOf("fetch origin", "fetch fork"), writes.calls) }
    }

    @Test
    fun `Remove and Change URL read nothing while a write runs`(robot: FxRobot) {
        openAndExpand(robot)
        writes.gate = CountDownLatch(1)
        later { commands.fetchAll() }

        later { commands.removeRemote("origin") }
        later { commands.changeRemoteUrls("origin") }
        later { commands.addRemote() }
        later { commands.renameRemote("origin") }

        assertNoDialog()
        writes.gate?.countDown()
        eventually { assertEquals(listOf("fetch origin"), writes.calls) }
    }

    @Test
    fun `a read for a folder that is no longer open asks nothing`(robot: FxRobot) {
        openAndExpand(robot)
        writes.gate = CountDownLatch(1)

        later { commands.changeRemoteUrls("origin") }
        robot.interact { view.open(Path.of("/other")) }
        writes.gate?.countDown()
        // The run is over once its outcome was dropped or handled (the writer resets running in the same dispatch).
        eventually { assertFalse(view.actions.running.get()) }

        assertEquals(listOf("remote urls origin"), writes.calls)
        assertNoDialog()
    }
}
