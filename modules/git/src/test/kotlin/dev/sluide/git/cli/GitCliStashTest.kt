package dev.sluide.git.cli

import dev.sluide.git.failingGit
import dev.sluide.git.fakeGit
import dev.sluide.git.recordedCalls
import dev.sluide.git.recordingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration.ofSeconds
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stash and tracked-state readers of [GitCli] (`GitCliStash.kt`) with the real git. */
class GitCliStashTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root
    private val calls get() = folder.resolve("calls.log")

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        repo.commit("first", "tracked.txt", "t")
        repo.commit("second", "other.txt", "o")
    }

    private fun stash(): String {
        repo.write("tracked.txt", "changed")
        repo.write("untracked one.txt", "u")
        repo.write("dir/untracked \"q\".txt", "u")
        repo.git("stash", "push", "-u", "-q")
        return repo.git("rev-parse", "refs/stash")
    }

    /** [trackedStates], which a regression could make loop for ever: the test fails after 30 s instead. */
    private fun GitCli.trackedStatesBounded(root: Path): Map<String, String>? =
        assertTimeoutPreemptively(ofSeconds(30), ThrowingSupplier { trackedStates(root) })

    @Test
    fun `stashTip is where refs-stash points, no commit while there is no stash`() {
        assertEquals(StashTip(null), git.stashTip(root))

        val stash = stash()

        assertEquals(StashTip(stash), git.stashTip(root))
        assertNull(failingGit(folder, "for-each-ref").stashTip(root))
    }

    @Test
    fun `a stash is kept only if both tips were read and differ`() {
        assertEquals(true, stashKept(StashTip(null), StashTip("a")))
        assertEquals(true, stashKept(StashTip("a"), StashTip("b")))
        assertEquals(true, stashKept(StashTip("a"), StashTip(null)))
        assertEquals(false, stashKept(StashTip("a"), StashTip("a")))
        assertEquals(false, stashKept(null, StashTip("a")))
        assertEquals(false, stashKept(StashTip("a"), null))
        assertEquals(false, stashKept(null, null))
    }

    @Test
    fun `stashUntrackedPaths are the files of the stash's untracked tree, none for a stash without one`() {
        val stash = stash()
        repo.write("tracked.txt", "again")
        repo.git("stash", "push", "-q")
        val plain = repo.git("rev-parse", "refs/stash")

        assertEquals(
            setOf("untracked one.txt", "dir/untracked \"q\".txt"),
            git.stashUntrackedPaths(root, stash).toSet(),
        )
        assertEquals(2, git.stashUntrackedPaths(root, stash).size)
        assertEquals(emptyList(), git.stashUntrackedPaths(root, plain))
        assertEquals(emptyList(), git.stashUntrackedPaths(root, "f".repeat(40)))
        assertEquals(emptyList(), unavailableGit(folder).stashUntrackedPaths(root, stash))
    }

    @Test
    fun `stashUntrackedPaths does not run git for a dash-leading commit`() {
        val recording = recordingGit(folder, calls)

        assertEquals(emptyList(), recording.stashUntrackedPaths(root, "--format=x"))
        assertEquals(emptyList(), recordedCalls(calls))
    }

    @Test
    fun `stashTrackedPaths are the tracked files the stash changes, with any characters in their names`() {
        repo.write("sp ace \"q\".txt", "s")
        repo.git("add", "sp ace \"q\".txt")
        repo.git("commit", "-q", "-m", "spaced")
        repo.write("tracked.txt", "changed")
        repo.write("sp ace \"q\".txt", "changed")
        repo.write("untracked.txt", "u")
        repo.git("stash", "push", "-u", "-q")
        val stash = repo.git("rev-parse", "refs/stash")

        assertEquals(setOf("tracked.txt", "sp ace \"q\".txt"), git.stashTrackedPaths(root, stash))
        assertNull(git.stashTrackedPaths(root, "f".repeat(40)))
        assertNull(unavailableGit(folder).stashTrackedPaths(root, stash))
    }

    @Test
    fun `stashTrackedPaths takes a dash-leading commit for no option, so no file is written or emptied`() {
        val output = folder.resolve("output.txt").apply { writeText("keep\n") }

        assertNull(git.stashTrackedPaths(root, "--output=$output"))
        assertEquals("keep\n", output.readText())
    }

    @Test
    fun `trackedStates lists changed tracked files by path, with the state without the path`() {
        repo.write("tracked.txt", "changed")
        repo.write("sp ace.txt", "s")
        repo.git("add", "sp ace.txt")
        repo.write("untracked.txt", "u")

        val states = git.trackedStatesBounded(root)

        assertEquals(setOf("tracked.txt", "sp ace.txt"), states?.keys)
        assertTrue(states!!.getValue("tracked.txt").startsWith("1 .M N... 100644 100644 100644 "), states.toString())
        assertTrue(states.getValue("sp ace.txt").startsWith("1 A. N... 000000 100644 100644 "), states.toString())
        assertTrue("sp ace.txt" !in states.getValue("sp ace.txt"), "the path is the key, not part of the state")
        assertEquals(emptyMap(), TestRepository(folder.resolve("clean")).let { git.trackedStatesBounded(it.root) })
        assertNull(unavailableGit(folder).trackedStatesBounded(root))
        assertNull(failingGit(folder, "status").trackedStatesBounded(root))
    }

    @Test
    fun `trackedStates has a renamed file under its new path, and does not read the old path as a record`() {
        // The old path looks like an ordinary record: "1", then seven fields and a path "9".
        val old = "1 2 3 4 5 6 7 8 9"
        repo.write(old, "renamed content that git sees as a rename")
        repo.git("add", old)
        repo.git("commit", "-q", "-m", "odd name")
        repo.git("mv", old, "new.txt")

        val states = git.trackedStatesBounded(root)

        assertEquals(setOf("new.txt"), states?.keys)
        assertTrue(states!!.getValue("new.txt").startsWith("2 R. N... 100644 100644 100644 "), states.toString())
        assertTrue(states.getValue("new.txt").endsWith(" R100"), states.toString())
    }

    @Test
    fun `trackedStates lists a conflicted file with its unmerged state`() {
        repo.git("checkout", "-q", "-b", "side")
        repo.commit("side", "tracked.txt", "side")
        repo.git("checkout", "-q", "main")
        repo.commit("main", "tracked.txt", "main")
        repo.gitStops("merge", "side")

        val states = git.trackedStatesBounded(root)

        assertEquals(setOf("tracked.txt"), states?.keys)
        assertTrue(
            states!!.getValue("tracked.txt").startsWith("u UU N... 100644 100644 100644 100644 "),
            states.toString(),
        )
    }

    @Test
    fun `trackedStates skips records git would not write, and an empty output has no states`() {
        val whole = "1 .M N... 100644 100644 100644 h1 h2 ok.txt"

        assertEquals(emptyMap(), fakeGit(folder, "true").trackedStatesBounded(root))
        assertEquals(
            mapOf("ok.txt" to "1 .M N... 100644 100644 100644 h1 h2"),
            fakeGit(folder, "printf '$whole\\000'").trackedStatesBounded(root),
        )
        assertEquals(
            mapOf("ok.txt" to "1 .M N... 100644 100644 100644 h1 h2"),
            fakeGit(
                folder,
                "printf '1 too few fields\\000$whole\\000? a b c d e f g h i path\\000'",
            ).trackedStatesBounded(root),
        )
        assertEquals(
            emptyMap(),
            fakeGit(folder, "printf '2 R. N... 100644 100644 100644 h1 h2 R100\\000'").trackedStatesBounded(root),
            "a rename without its path",
        )
        assertNull(fakeGit(folder, "exit 128").trackedStatesBounded(root))
    }
}
