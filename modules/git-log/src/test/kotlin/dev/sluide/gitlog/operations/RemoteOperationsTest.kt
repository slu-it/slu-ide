package dev.sluide.gitlog.operations

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitRemoteRemoval
import dev.sluide.git.GitRemoteUrls
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.FakeWrites
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteOperationsTest {
    private val root = Path.of("/repo")
    private val secret = "https://user:token@example.com/x.git"

    @Test
    fun `add without fetch says it added the remote and does not fetch`() {
        val writes = FakeWrites()
        val add = AddRemote("upstream", secret, fetch = false)

        val outcome = add.run(writes, root)

        assertEquals(listOf("remote add upstream $secret"), writes.calls)
        assertEquals(RefOutcome(GitResult(true, ""), "Added remote upstream."), outcome)
        assertEquals("Adding remote upstream…", add.progress)
        assertFalse(add.cancellable)
        assertTrue(add.copy(fetch = true).cancellable)
        assertNull(add.fileChange)
        assertEquals("Add Remote Failed", add.errorTitle)
        assertEquals("Remote upstream could not be added.", add.failure)
    }

    @Test
    fun `add with fetch appends the fetch summary`() {
        val writes = FakeWrites()
        writes.fetchResult = { GitFetchResult(GitResult(true, ""), changes = GitFetchChanges(newBranches = 3)) }

        val outcome = AddRemote("upstream", secret, fetch = true).run(writes, root)

        assertEquals(listOf("remote add upstream $secret", "fetch upstream"), writes.calls)
        assertTrue(outcome.result.success)
        assertEquals("Added remote upstream. Fetched upstream: 3 new branches.", outcome.done)
        assertNull(outcome.errorTitle)
    }

    @Test
    fun `a failed add does not fetch and fails with git's message`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "error: remote upstream already exists.") }

        val outcome = AddRemote("upstream", secret, fetch = true).run(writes, root)

        assertEquals(listOf("remote add upstream $secret"), writes.calls)
        assertEquals(RefOutcome(GitResult(false, "error: remote upstream already exists."), null), outcome)
    }

    @Test
    fun `a failed fetch leaves the remote added and fails as Fetch Failed`() {
        val writes = FakeWrites()
        writes.fetchResult = { GitFetchResult(GitResult(false, "fatal: no route"), changes = GitFetchChanges()) }

        val outcome = AddRemote("upstream", secret, fetch = true).run(writes, root)

        assertEquals(GitResult(false, "fatal: no route"), outcome.result)
        assertEquals("Added remote upstream.", outcome.done)
        assertEquals("Fetch Failed", outcome.errorTitle)
        assertEquals("upstream could not be fetched.", outcome.failure)
        assertFalse(outcome.cancelled)
    }

    @Test
    fun `cancelling before the fetch leaves the remote added without fetching`() {
        val writes = FakeWrites()
        val cancellation = GitCancellation().apply { cancel() }

        val outcome = AddRemote("upstream", secret, fetch = true).run(writes, root, RunContext(cancellation))

        assertEquals(listOf("remote add upstream $secret"), writes.calls)
        assertEquals("Added remote upstream. Fetch cancelled.", outcome.done)
        assertTrue(outcome.cancelled)
        assertTrue(outcome.result.success)
    }

    @Test
    fun `cancelling during the fetch keeps the remote and says the fetch was cancelled`() {
        val writes = FakeWrites()
        val cancellation = GitCancellation()
        writes.fetchResult = {
            cancellation.cancel()
            GitFetchResult(GitResult(false, "Cancelled."), cancelled = true)
        }

        val outcome = AddRemote("upstream", secret, fetch = true).run(writes, root, RunContext(cancellation))

        assertEquals("Added remote upstream. Fetch cancelled.", outcome.done)
        assertTrue(outcome.cancelled)
    }

    @Test
    fun `progress, error title and failure of add and change URL hold no URL`() {
        val operations = listOf(AddRemote("upstream", secret, true), ChangeRemoteUrls("o", secret, secret, false))
        for (operation in operations) {
            val texts = listOf(operation.progress, operation.errorTitle, operation.failure)
            assertTrue(texts.none { "token" in it }, "$operation $texts")
        }
    }

    @Test
    fun `printing an operation or prompt masks the credentials of its URLs`() {
        val pushUrl = "https://u2:t2@example.org/p.git"
        val texts =
            listOf(
                AddRemote("upstream", secret, true),
                ChangeRemoteUrls("o", secret, pushUrl, false),
                RemoteUrlsPrompt("o", GitRemoteUrls(secret, pushUrl, false, false)),
            ).map { it.toString() }
        for (text in texts) assertTrue("token" !in text && "t2" !in text && "****" in text, text)
    }

    @Test
    fun `rename says what was renamed`() {
        val writes = FakeWrites()
        val rename = RenameRemote("origin", "upstream")

        val outcome = rename.run(writes, root)

        assertEquals(listOf("remote rename origin upstream"), writes.calls)
        assertEquals(RefOutcome(GitResult(true, ""), "Renamed remote origin to upstream."), outcome)
        assertEquals("Renaming remote origin…", rename.progress)
        assertEquals("Rename Remote Failed", rename.errorTitle)
        assertEquals("Remote origin could not be renamed.", rename.failure)
        assertNull(rename.fileChange)
        assertFalse(rename.cancellable)
    }

    @Test
    fun `rename names the refspecs it left alone`() {
        val writes = FakeWrites()
        writes.renameRefspecsUpdated = false

        val outcome = RenameRemote("origin", "upstream").run(writes, root)

        assertEquals(
            "Renamed remote origin to upstream. Its fetch refspecs were not updated, as they differ from git's " +
                "default: edit remote.upstream.fetch.",
            outcome.done,
        )
    }

    @Test
    fun `a failed rename fails with git's message and does nothing in the header`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "error: no such remote") }

        val outcome = RenameRemote("origin", "upstream").run(writes, root)

        assertEquals(RefOutcome(GitResult(false, "error: no such remote"), null), outcome)
    }

    @Test
    fun `a partial rename says the configuration was renamed but the branches were not moved`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "fatal: packed-refs.lock exists") }
        writes.renamePartial = true

        val outcome = RenameRemote("origin", "upstream").run(writes, root)

        assertEquals("fatal: packed-refs.lock exists", outcome.result.message)
        assertEquals(
            "Remote origin was renamed to upstream in the configuration, but its remote branches could not be moved.",
            outcome.failure,
        )
        assertNull(outcome.done)
    }

    @Test
    fun `remove says what was removed`() {
        val writes = FakeWrites()
        val remove = RemoveRemote("upstream")

        assertEquals(RefOutcome(GitResult(true, ""), "Removed remote upstream."), remove.run(writes, root))
        assertEquals(listOf("remote remove upstream"), writes.calls)
        assertEquals("Removing remote upstream…", remove.progress)
        assertEquals("Remove Remote Failed", remove.errorTitle)
        assertEquals("Remote upstream could not be removed.", remove.failure)
        assertNull(remove.fileChange)
    }

    @Test
    fun `a failed remove fails with git's message`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "error: locked") }

        assertEquals(
            RefOutcome(GitResult(false, "error: locked"), null),
            RemoveRemote("upstream").run(writes, root),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "https://x/a,,false,Changed the URL of o.",
        ",https://x/p,false,Changed the URL of o.",
        ",,true,Changed the URL of o.",
        "https://x/a,https://x/p,false,Changed the URLs of o.",
        "https://x/a,,true,Changed the URLs of o.",
    )
    fun `change URL says how many URLs changed and passes the values on`(
        url: String?,
        pushUrl: String?,
        removePushUrl: Boolean,
        done: String,
    ) {
        val writes = FakeWrites()
        val change = ChangeRemoteUrls("o", url, pushUrl, removePushUrl)

        val outcome = change.run(writes, root)

        assertEquals(listOf("remote set-url o url=$url push=$pushUrl removePush=$removePushUrl"), writes.calls)
        assertEquals(RefOutcome(GitResult(true, ""), done), outcome)
        assertEquals("Changing the URL of o…", change.progress)
        assertEquals("Change URL Failed", change.errorTitle)
        assertEquals("The URL of o could not be changed.", change.failure)
        assertNull(change.fileChange)
    }

    @Test
    fun `a failed change URL fails with git's message`() {
        val writes = FakeWrites()
        writes.result = { GitResult(false, "error: could not set") }

        assertEquals(
            RefOutcome(GitResult(false, "error: could not set"), null),
            ChangeRemoteUrls("o", "https://x/a", null, false).run(writes, root),
        )
    }

    @Test
    fun `reading the URLs hands them to the dialog`() {
        val writes = FakeWrites()
        val urls = GitRemoteUrls("https://u:t@x/y", "https://x/p", false, true)
        writes.urls = urls

        val outcome = PrepareChangeRemoteUrls("o").run(writes, root)

        assertEquals(listOf("remote urls o"), writes.calls)
        assertEquals(RefOutcome(GitResult(true, ""), null, remoteUrls = RemoteUrlsPrompt("o", urls)), outcome)
        assertEquals("Change URL Failed", PrepareChangeRemoteUrls("o").errorTitle)
    }

    @Test
    fun `URLs that cannot be read fail without a prompt`() {
        val writes = FakeWrites()
        writes.urls = null

        val outcome = PrepareChangeRemoteUrls("o").run(writes, root)

        assertFalse(outcome.result.success)
        assertNull(outcome.remoteUrls)
    }

    @Test
    fun `removing asks first, naming what is lost, with Cancel as the way out`() {
        val writes = FakeWrites()
        writes.removal = GitRemoteRemoval(12, listOf("main", "fix"), true)

        val outcome = PrepareRemoveRemote("upstream").run(writes, root)

        val confirmation = outcome.confirmation!!
        assertEquals("Remove Remote", confirmation.title)
        assertEquals("Remove remote 'upstream'?", confirmation.question)
        assertEquals(REMOVE_BUTTON, confirmation.button)
        assertEquals(RemoveRemote("upstream"), confirmation.confirmed)
        assertEquals(removalDetails("upstream", writes.removal!!), confirmation.details)
        assertEquals(listOf("remote removal upstream"), writes.calls)
        assertTrue(outcome.result.success)
        assertNull(outcome.done)
    }

    @Test
    fun `a removal that cannot be read fails without a question`() {
        val writes = FakeWrites()
        writes.removal = null

        val outcome = PrepareRemoveRemote("upstream").run(writes, root)

        assertFalse(outcome.result.success)
        assertNull(outcome.confirmation)
        assertEquals("Remove Remote Failed", PrepareRemoveRemote("upstream").errorTitle)
    }

    @Test
    fun `the removal details name each consequence, the lists capped`() {
        assertEquals(
            "Its 12 remote branches are deleted locally.\n" +
                "2 local branches (main and fix) lose their upstream.\n" +
                "upstream is no longer the default push remote.\n" +
                "Its URL is removed. This cannot be undone.",
            removalDetails("upstream", GitRemoteRemoval(12, listOf("main", "fix"), true)),
        )
        assertEquals(
            "Its 1 remote branch is deleted locally.\n1 local branch (main) loses its upstream.\n" +
                "Its URL is removed. This cannot be undone.",
            removalDetails("o", GitRemoteRemoval(1, listOf("main"), false)),
        )
        assertEquals(
            "Its URL is removed. This cannot be undone.",
            removalDetails("o", GitRemoteRemoval(0, emptyList(), false)),
        )
        assertEquals(
            "7 local branches (a, b, c and 4 more) lose their upstream.\nIts URL is removed. This cannot be undone.",
            removalDetails("o", GitRemoteRemoval(0, listOf("a", "b", "c", "d", "e", "f", "g"), false)),
        )
        assertEquals(
            "3 local branches (a, b and c) lose their upstream.\nIts URL is removed. This cannot be undone.",
            removalDetails("o", GitRemoteRemoval(0, listOf("a", "b", "c"), false)),
        )
    }
}
