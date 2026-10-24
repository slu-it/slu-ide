package dev.sluide.gitlog.push

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitOutgoing
import dev.sluide.git.GitPushTarget
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.operations.Fetch
import dev.sluide.gitlog.operations.commitCount
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Push dialog reads (ADR 0049), with a fake source, tasks run when the test says so, and its texts and rules;
 * without JavaFX.
 */
class PushReaderTest {
    private val repo = Path.of("/repo")

    private var currentBranch: String? = "refs/heads/main"
    private var head: String? = "h1"
    private var remotes: List<String>? = listOf("origin", "fork")
    private var target: GitPushTarget? = GitPushTarget("origin", "main", hasUpstream = true)
    private val outgoingReads = mutableListOf<String>()

    /** What a read of the refs, of the files of a commit and of what a push sends answers; `null` fails. */
    private var refsFail = false
    private var filesFail = false
    private var outgoingResult: GitOutgoing? = GitOutgoing(emptyList(), false, null, 0, null)

    /** The cancellation of every read of what a push sends, and what happens meanwhile. */
    private val outgoingCancellations = mutableListOf<GitCancellation?>()
    private var whileReadingOutgoing: () -> Unit = {}

    private val source =
        object : GitLogSource {
            override fun page(
                root: Path,
                filter: GitLogFilter,
                skip: Int,
                limit: Int,
                cancellation: GitCancellation?,
            ): GitLogPage? = null

            override fun hashMatch(
                root: Path,
                filter: GitLogFilter,
                cancellation: GitCancellation?,
            ): GitCommit? = null

            override fun userName(root: Path): String? = null

            override fun paths(root: Path): List<GitTreePath>? = null

            override fun refs(
                root: Path,
                cancellation: GitCancellation?,
            ) = if (refsFail) null else GitRefs(emptyList(), currentBranch)

            override fun remotes(
                root: Path,
                cancellation: GitCancellation?,
            ) = remotes

            override fun details(
                root: Path,
                hash: String,
                cancellation: GitCancellation?,
            ): GitCommitDetails? = null

            override fun changedFiles(
                root: Path,
                commit: String,
                firstParent: String?,
                cancellation: GitCancellation?,
            ) = if (filesFail) null else listOf(GitChangedFile(GitChange.MODIFIED, "$commit-$firstParent"))

            override fun fileDiff(
                root: Path,
                commit: String,
                firstParent: String?,
                file: GitChangedFile,
                cancellation: GitCancellation?,
            ): GitFileDiff? = null

            override fun commitChanges(root: Path): GitCommitChanges? = null

            override fun headCommit(
                root: Path,
                cancellation: GitCancellation?,
            ) = head

            override fun headMessage(root: Path): String? = null

            override fun pushTarget(
                root: Path,
                branch: String,
                cancellation: GitCancellation?,
            ) = target

            override fun outgoing(
                root: Path,
                remote: String,
                branch: String,
                limit: Int,
                cancellation: GitCancellation?,
            ): GitOutgoing? {
                outgoingReads += "$remote $branch"
                outgoingCancellations += cancellation
                whileReadingOutgoing()
                return outgoingResult
            }
        }

    /** The tasks handed to the background with their delays, run by [runAll]. */
    private val tasks = mutableListOf<Pair<Long, Runnable>>()

    private val reader =
        PushReader(repo, source, { delay, task -> tasks += delay to task }, Runnable::run) { !it.contains('~') }

    private fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().second.run()
    }

    @Test
    fun `a newer read or closing stops the git of the read going on`() {
        whileReadingOutgoing = { if (outgoingReads.size == 1) reader.load("fork", "main") }
        reader.load(null, null)
        runAll()

        assertEquals(listOf(true, false), outgoingCancellations.map { it?.cancelled })
        assertFalse(reader.loading.get(), "the newer read was taken")

        whileReadingOutgoing = { reader.close() }
        reader.load("origin", "main")
        runAll()
        assertTrue(outgoingCancellations.last()?.cancelled == true, "closed while it read")
    }

    @Test
    fun `the first read takes the default target, later ones keep the chosen one`() {
        reader.load(null, null)
        assertTrue(reader.loading.get())
        runAll()

        assertFalse(reader.loading.get())
        val state = checkNotNull(reader.state.get())
        assertEquals("main", state.branch)
        assertEquals("origin" to "main", state.remote to state.target)
        assertTrue(state.hasUpstream)
        assertEquals("refs/remotes/origin/main", state.trackingRef)

        reader.load("fork", "feature")
        runAll()
        assertEquals("fork" to "feature", reader.state.get()?.let { it.remote to it.target })
        assertEquals(listOf("origin main", "fork feature"), outgoingReads)
    }

    @Test
    fun `another checked-out branch, or a remote that is gone, takes the defaults again`() {
        reader.load(null, null)
        runAll()
        currentBranch = "refs/heads/topic"
        target = GitPushTarget("origin", "topic")

        reader.load("origin", "main")
        runAll()
        assertEquals("topic", reader.state.get()?.target)

        reader.load("gone", "x")
        runAll()
        assertEquals("origin" to "topic", reader.state.get()?.let { it.remote to it.target })
    }

    @Test
    fun `the latest read wins and waits for its delay`() {
        reader.load(null, null)
        runAll()
        reader.load("origin", "f", 300)
        reader.load("origin", "fe", 300)
        assertEquals(listOf(300L, 300L), tasks.map { it.first })

        runAll()

        assertEquals(listOf("origin main", "origin fe"), outgoingReads)
        assertEquals("fe", reader.state.get()?.target)
    }

    @Test
    fun `a name git cannot take is not read, detached, unborn and remote-less repositories are not read further`() {
        reader.load(null, null)
        runAll()
        reader.load("origin", "a~b")
        runAll()
        assertEquals("a~b", reader.state.get()?.target)
        assertNull(reader.state.get()?.outgoing)
        assertEquals(listOf("origin main"), outgoingReads)

        currentBranch = null
        reader.load(null, null)
        runAll()
        assertEquals(PushState.DETACHED, reader.state.get()?.blocked)

        currentBranch = "refs/heads/main"
        head = null
        reader.load(null, null)
        runAll()
        assertEquals(PushState.UNBORN, reader.state.get()?.blocked)

        head = "h1"
        remotes = emptyList()
        reader.load(null, null)
        runAll()
        assertEquals(PushState.NO_REMOTE, reader.state.get()?.blocked)

        remotes = null
        reader.load(null, null)
        runAll()
        assertTrue(reader.state.get()?.failed == true)
        assertEquals(listOf("origin main"), outgoingReads)
    }

    @Test
    fun `files are read for a commit against its parent, the latest request wins, and close drops everything`() {
        reader.files("c1", "p1")
        reader.files("c2", null)
        assertEquals(PushFiles.Loading("c2", null), reader.files.get())
        runAll()
        assertEquals("c2-null", (reader.files.get() as PushFiles.Loaded).files.single().path)

        reader.load(null, null)
        reader.files("c3", null)
        reader.close()
        runAll()
        assertNull(reader.state.get())
        assertEquals(PushFiles.Loading("c3", null), reader.files.get(), "a read going on at close stays unfinished")
    }

    @Test
    fun `files that cannot be read fail, and no files drops the read going on`() {
        filesFail = true
        reader.files("c1", null)
        runAll()
        assertEquals(PushFiles.Failed, reader.files.get())

        filesFail = false
        reader.files("c2", "p2")
        reader.noFiles()
        runAll()
        assertEquals(PushFiles.None, reader.files.get(), "the read that waited is dropped")

        reader.files("c3", null)
        tasks.single().second.run()
        reader.noFiles()
        runAll()
        assertEquals(PushFiles.None, reader.files.get(), "the result of a read going on is dropped")
    }

    @Test
    fun `a failed read of the refs, the push target or what is sent fails the state, the next read recovers`() {
        refsFail = true
        reader.load(null, null)
        runAll()
        assertTrue(reader.state.get()?.failed == true, "refs")

        refsFail = false
        target = null
        reader.load(null, null)
        runAll()
        assertTrue(reader.state.get()?.failed == true, "push target")

        target = GitPushTarget("origin", "main", hasUpstream = true)
        outgoingResult = null
        reader.load(null, null)
        runAll()
        assertTrue(reader.state.get()?.failed == true, "outgoing")

        outgoingResult = GitOutgoing(emptyList(), false, null, 0, null)
        reader.load("fork", "feature")
        runAll()
        val state = reader.state.get()
        assertEquals(false, state?.failed)
        assertEquals("origin" to "main", state?.remote to state?.target, "defaults again after a failed state")
    }

    @Test
    fun `the notes and the force push question count the commits the target has`() {
        assertNull(behindNote("origin", "main", 0))
        assertEquals(
            "origin/main has 1 commit that your branch does not have; a normal push will be rejected. " +
                "Fetch, then merge or rebase, or force push.",
            behindNote("origin", "main", 1),
        )
        assertEquals(
            "Force push feature to origin/feature?" to
                "2 commits on origin/feature that are not in your branch will be lost.",
            forcePushQuestion("feature", "origin", "feature", 2, "0123456789abcdef"),
        )
        assertEquals(
            "1 commit on origin/feature that is not in your branch will be lost.",
            forcePushQuestion("feature", "origin", "feature", 1, "0123456789abcdef").second,
        )
        assertEquals(
            "Force push feature to origin/f?" to "origin/f will be set to 01234567.",
            forcePushQuestion("feature", "origin", "f", 0, "0123456789abcdef"),
        )
    }

    private fun commit(
        hash: String,
        parent: String?,
    ) = GitCommit(hash, listOfNotNull(parent), "A", "a@x", Instant.EPOCH, "S $hash")

    @Test
    fun `the rows list All, the commits and that there are more, and All's files are HEAD's against the base`() {
        val outgoing = GitOutgoing(listOf(commit("c2", "c1"), commit("c1", "b")), true, "r", 0, "b")
        val rows = pushRows(outgoing)
        assertEquals(
            listOf(PushRow.All(2, true), PushRow.Commit(outgoing.commits[0]), PushRow.Commit(outgoing.commits[1])) +
                PushRow.More,
            rows,
        )
        assertEquals("c2" to "b", pushFilesOf(rows[0], outgoing))
        assertEquals("c1" to "b", pushFilesOf(rows[2], outgoing))
        assertEquals("c2" to "b", pushFilesOf(PushRow.More, outgoing))
        assertEquals("c2" to "b", pushFilesOf(null, outgoing))
        assertEquals(emptyList(), pushRows(outgoing.copy(commits = emptyList(), more = false)))
        assertNull(pushFilesOf(null, outgoing.copy(commits = emptyList())))
        assertEquals("1000+ commits", commitCount(1000, more = true))
    }

    @Test
    fun `Push needs something to push, a valid name, no read going on and no running operation`() {
        val outgoing = GitOutgoing(listOf(commit("c2", "c1")), false, "c1", 0, "c1")
        val state =
            PushState("main", "c2", listOf("origin"), true, "origin", "main", outgoing, "refs/remotes/origin/main")
        assertTrue(canPush(state, null, loading = false, running = false))
        assertFalse(canPush(null, null, loading = false, running = false))
        assertFalse(canPush(state, "bad", loading = false, running = false))
        assertFalse(canPush(state, null, loading = true, running = false))
        assertFalse(canPush(state, null, loading = false, running = true))
        assertFalse(canPush(PushState.FAILED, null, loading = false, running = false))

        val upToDate = state.copy(outgoing = outgoing.copy(commits = emptyList(), remoteCommit = "c2"))
        assertTrue(upToDate.upToDate)
        assertEquals(PushState.UP_TO_DATE, upToDate.nothingToPushNote)
        assertFalse(canPush(upToDate, null, loading = false, running = false))
        assertFalse(canForcePush(upToDate, null, loading = false, running = false), "it would move nothing")

        // HEAD is behind the target: nothing to send, but a force push moves the target back.
        val behind = state.copy(outgoing = outgoing.copy(commits = emptyList(), remoteCommit = "c3", behind = 1))
        assertFalse(behind.upToDate)
        assertTrue(behind.nothingToPush)
        assertEquals("Your branch is behind origin/main; nothing to push.", behind.nothingToPushNote)
        assertFalse(canPush(behind, null, loading = false, running = false))
        assertFalse(canForcePush(behind, null, loading = false, running = false), "main is protected")
        val behindFeature = behind.copy(target = "feature")
        assertTrue(canForcePush(behindFeature, null, loading = false, running = false))
        assertFalse(canForcePush(behindFeature, null, loading = false, running = true))
        assertEquals("Nothing to push.", pushPlaceholder(behindFeature))

        // A new branch without new commits: the push creates it.
        val newBranch =
            state.copy(
                target = "feature",
                outgoing = outgoing.copy(commits = emptyList(), remoteCommit = null),
            )
        assertFalse(newBranch.nothingToPush)
        assertNull(newBranch.nothingToPushNote)
        assertTrue(canPush(newBranch, null, loading = false, running = false))
        assertEquals("No new commits: pushing creates origin/feature at c2.", pushPlaceholder(newBranch))
        assertNull(state.nothingToPushNote)
        assertTrue(canForcePush(state.copy(target = "feature"), null, loading = false, running = false))

        assertEquals(PushState.DETACHED, state.copy(branch = null).blocked)
        assertEquals(PushState.UNBORN, state.copy(head = null).blocked)
        assertEquals(PushState.NO_REMOTE, state.copy(remotes = emptyList()).blocked)
        assertNull(state.blocked)
        assertTrue(state.copy(outgoing = outgoing.copy(remoteCommit = null)).isNew)
        assertEquals("Loading…", pushPlaceholder(null))
        assertEquals("The commits could not be read.", pushPlaceholder(PushState.FAILED))
        assertEquals(PushState.DETACHED, pushPlaceholder(state.copy(branch = null)))
        assertEquals("Nothing to push.", pushPlaceholder(upToDate))
    }
}
