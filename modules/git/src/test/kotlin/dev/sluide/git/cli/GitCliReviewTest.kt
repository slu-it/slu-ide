package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitCancellation
import dev.sluide.git.parse.GitLogParser
import dev.sluide.git.recordedCalls
import dev.sluide.git.recordingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The review readers of [GitCli] (`GitCliReview.kt`) with the real git, and with a stand-in for what git may print. */
class GitCliReviewTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root
    private lateinit var first: String
    private lateinit var second: String
    private val calls get() = folder.resolve("calls.log")

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        first = repo.commit("first", "a.txt", "a")
        second = repo.commit("second", "b.txt", "bb")
    }

    /** A stand-in for git that runs [body] for anything but `--version`; it holds no pipe open unless [body] does. */
    private fun fakeGit(body: String): GitCli {
        val script =
            folder.resolve("fake-git-${System.nanoTime()}").apply {
                writeText(FAKE_GIT_START + body + "\n")
                toFile().setExecutable(true)
            }
        return GitCli(script.toString(), testGitEnvironment(), 2.seconds, 300.milliseconds).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
        }
    }

    private fun hashes(log: String?) = GitLogParser.parse(log.orEmpty()).map { it.hash }

    @Test
    fun `reviewLog lists the commits of to that from does not have, newest first, to a limit`() {
        val third = repo.commit("third", "c.txt", "c")

        assertEquals(listOf(third, second, first), hashes(git.reviewLog(root, null, third, 10)))
        assertEquals(listOf(third, second), hashes(git.reviewLog(root, first, third, 10)))
        assertEquals(listOf(third), hashes(git.reviewLog(root, null, third, 1)))
        assertNull(unavailableGit(folder).reviewLog(root, null, third, 10))
        assertNull(git.reviewLog(root, null, "f".repeat(40), 10), "no such commit")
    }

    @Test
    fun `reviewLog gives to and from after end-of-options and ends with the paths separator`() {
        val recording = recordingGit(folder, calls)

        recording.reviewLog(root, first, second, 7)
        recording.reviewLog(root, null, second, 7)

        val (withFrom, without) = recordedCalls(calls)
        assertTrue(withFrom.startsWith("--no-optional-locks log --topo-order "), withFrom)
        assertTrue(withFrom.endsWith(" --max-count=7 --end-of-options $second ^$first --"), withFrom)
        assertTrue(without.endsWith(" --max-count=7 --end-of-options $second --"), without)
    }

    @Test
    fun `reviewLog leaves a dash-leading to or from no option`() {
        val output = folder.resolve("output.txt").apply { writeText("keep\n") }

        assertNull(git.reviewLog(root, null, "--output=$output", 5))
        assertNull(git.reviewLog(root, "--output=$output", second, 5))
        assertEquals("keep\n", output.readText())
    }

    @Test
    fun `objectInfos gives one line per name in order, runs git again for names beyond its input limit`() {
        val names = listOf("$second:a.txt", "$second:nothing", "$second:b.txt")
        val recording = recordingGit(folder, calls)

        val infos = recording.objectInfos(root, names)

        assertEquals(repo.git("cat-file", "--batch-check", input = names.joinToString("\n") + "\n").lines(), infos)
        assertEquals("$second:nothing missing", infos?.get(1))
        assertEquals(1, recordedCalls(calls).count { it.endsWith("cat-file --batch-check") })

        val many = List(400) { "HEAD:" + "n".repeat(95) }
        val manyInfos = recording.objectInfos(root, many)

        assertEquals(400, manyInfos?.size)
        assertEquals(
            3,
            recordedCalls(calls).count { it.endsWith("cat-file --batch-check") } - 1,
            "400 names of 101 bytes with their line break are 40 KB: three runs below 15 KB",
        )
        assertEquals(emptyList(), recording.objectInfos(root, emptyList()))
    }

    @Test
    fun `objectInfos refuses a name with a line break or a carriage return, without running git`() {
        val recording = recordingGit(folder, calls)

        assertNull(recording.objectInfos(root, listOf("HEAD:a.txt", "HEAD:a\nb")))
        assertNull(recording.objectInfos(root, listOf("HEAD:a.txt\r")))
        assertNull(unavailableGit(folder).objectInfos(root, listOf("HEAD:a.txt")))
        assertEquals(emptyList(), recordedCalls(calls))
    }

    @Test
    fun `objectInfos is null for fewer lines than names, and takes only as many as there are names`() {
        // A real cat-file --batch-check prints one line per name; this is a git that prints too few or too many.
        assertNull(fakeGit("true").objectInfos(root, listOf("a", "b")), "no line for two names")
        assertNull(fakeGit("printf 'one'").objectInfos(root, listOf("a", "b")), "one line for two names")
        assertEquals(listOf("one", "two"), fakeGit("printf 'one\\ntwo\\nthree\\n'").objectInfos(root, listOf("a", "b")))
        assertNull(fakeGit("exit 128").objectInfos(root, listOf("a")))
    }

    /** The full name of the blob of [file] in the second commit, as `git cat-file --batch` names it. */
    private fun blobName(file: String) = repo.git("rev-parse", "$second:$file")

    @Test
    fun `blobs reads the content of each blob, leaves out one git does not find`() {
        val gone = "e".repeat(40)
        val sizes = mapOf(blobName("a.txt") to 2L, gone to 3L, blobName("b.txt") to 3L)

        val blobs = git.blobs(root, sizes)

        assertEquals(setOf(blobName("a.txt"), blobName("b.txt")), blobs?.keys)
        assertContentEquals("a\n".toByteArray(), blobs?.get(blobName("a.txt")))
        assertContentEquals("bb\n".toByteArray(), blobs?.get(blobName("b.txt")))
        assertEquals(emptyMap(), git.blobs(root, emptyMap()))
        assertNull(unavailableGit(folder).blobs(root, sizes))
        assertNull(git.blobs(root, mapOf("${blobName("a.txt")}\n$gone" to 1L)))
        assertNull(git.blobs(root, mapOf("${blobName("a.txt")}\r" to 1L)))
        assertNull(fakeGit("exit 128").blobs(root, sizes))
    }

    @Test
    fun `blobs allows git the size of the blob and its name and header, not less`() {
        // A name of 64 characters (SHA-256): its header line alone is longer than the slack for the headers.
        val long = TestRepository(folder.resolve("sha256"), objectFormat = "sha256")
        long.commit("long", "x.txt", "x".repeat(10))
        val name = long.git("rev-parse", "HEAD:x.txt")

        val blobs = git.blobs(long.root, mapOf(name to 11L))

        assertEquals(64, name.length)
        assertContentEquals(("x".repeat(10) + "\n").toByteArray(), blobs?.get(name))
    }

    @Test
    fun `blobs kills git that writes more than the sizes announce, though it ended with exit 0 and a valid start`() {
        // One blob announced with size 0: 1 + 64 = 65 bytes allowed. The script prints exactly one valid record of 65
        // bytes ("a blob 54", 54 bytes, a line break), then more, and ends with 0 before it can be killed.
        val content = "x".repeat(54)

        assertNull(fakeGit("cat > /dev/null; printf 'a blob 54\\n$content\\nextra'").blobs(root, mapOf("a" to 0L)))
        assertEquals(
            mapOf("a" to content),
            fakeGit("cat > /dev/null; printf 'a blob 54\\n$content\\n'")
                .blobs(root, mapOf("a" to 54L))
                ?.mapValues { String(it.value) },
        )
    }

    @Test
    fun `blobs is null for a git that fails after it wrote a valid answer, or whose output is not read to its end`() {
        val answer = "printf 'a blob 1\\nx\\n'"

        assertNotNull(fakeGit(answer).blobs(root, mapOf("a" to 1L)))
        assertNull(fakeGit("$answer; exit 1").blobs(root, mapOf("a" to 1L)))
        assertNull(
            fakeGit("(sleep 3 &); $answer; sleep 0.3").blobs(root, mapOf("a" to 1L)),
            "a process git started holds the output open",
        )
        val cancelled = GitCancellation().apply { cancel() }
        assertNull(fakeGit(answer).blobs(root, mapOf("a" to 1L), cancelled))
    }

    @Test
    fun `blobs is null for output that does not match the names, one broken shape at a time`() {
        val sizes = mapOf("a" to 1L)
        val shapes =
            mapOf(
                "no header line" to "printf 'a blob 1'",
                "two fields" to "printf 'a blob\\nx\\n'",
                "four fields" to "printf 'a blob 1 2\\nx\\n'",
                "another name" to "printf 'b blob 1\\nx\\n'",
                "a tree" to "printf 'a tree 1\\nx\\n'",
                "a size that is no number" to "printf 'a blob x\\nx\\n'",
                "a negative size" to "printf 'a blob -1\\nx\\n'",
                "a size beyond the output" to "printf 'a blob 5\\nx\\n'",
                "content to the end of the output, no line break" to "printf 'a blob 1\\nx'",
                "content not followed by a line break" to "printf 'a blob 1\\nxy\\n'",
                "output after the last name" to "printf 'a blob 1\\nx\\nmore\\n'",
                "nothing" to "true",
            )

        for ((shape, body) in shapes) {
            assertNull(fakeGit("cat > /dev/null; $body").blobs(root, sizes), shape)
        }
        assertNull(fakeGit("cat > /dev/null; printf 'a missing\\n'").blobs(root, mapOf("a" to 1L, "b" to 1L)), "b")
    }

    @Test
    fun `blobs reads a zero-size blob and a blob that holds a line break and a header-like line`() {
        val tricky = "a blob 1\nx\n"
        val blobs =
            fakeGit("cat > /dev/null; printf 'a blob 0\\n\\nb blob 11\\na blob 1\\nx\\n\\n'")
                .blobs(root, mapOf("a" to 0L, "b" to 11L))

        assertEquals(setOf("a", "b"), blobs?.keys)
        assertEquals("", String(blobs!!.getValue("a")))
        assertEquals(tricky, String(blobs.getValue("b")))
    }

    @Test
    fun `blobs reads in runs, the names of a run on the input of one git`() {
        val recording = recordingGit(folder, calls)
        val sizes = mapOf(blobName("a.txt") to 2L, blobName("b.txt") to 3L)

        val blobs = recording.blobs(root, sizes, maxRunOutput = 3)

        assertEquals(2, blobs?.size)
        assertEquals(2, recordedCalls(calls).count { it.endsWith("cat-file --batch") })
    }

    @Test
    fun `porcelainStatus lists changed, staged and untracked files, each untracked file in its folder`() {
        repo.write("a.txt", "changed")
        repo.write("new dir/with space.txt", "n")
        repo.write("new dir/second.txt", "n")
        repo.write("staged.txt", "s")
        repo.git("add", "staged.txt")

        val status = git.porcelainStatus(root)

        assertEquals(
            setOf(" M a.txt", "A  staged.txt", "?? new dir/with space.txt", "?? new dir/second.txt"),
            status?.toSet(),
        )
        assertEquals(4, status?.size)
        assertNull(unavailableGit(folder).porcelainStatus(root))
        assertNull(fakeGit("exit 128").porcelainStatus(root))
        assertEquals(listOf("x", "y"), fakeGit("printf 'x\\0\\0y\\0'").porcelainStatus(root))
    }

    @Test
    fun `porcelainStatus of a clean tree is an empty list, not null`() {
        assertEquals(emptyList(), git.porcelainStatus(root))
    }

    @Test
    fun `inputChunks keeps each run below the input limit in bytes, not characters`() {
        val line = "x".repeat(GitCli.MAX_INPUT - 1)
        val huge = "x".repeat(GitCli.MAX_INPUT + 5)

        assertEquals(listOf(listOf(line)), inputChunks(listOf(line)), "exactly the limit with its line break")
        assertEquals(listOf(listOf(line), listOf("y")), inputChunks(listOf(line, "y")))
        assertEquals(listOf(listOf(huge)), inputChunks(listOf(huge)), "too long for any run: a run of its own")
        assertEquals(listOf(listOf(huge), listOf("y")), inputChunks(listOf(huge, "y")))
        // Two bytes per character: 5000 of them and a line break are 10001 bytes, two do not fit.
        val wide = "é".repeat(5000)
        assertEquals(listOf(listOf(wide), listOf(wide)), inputChunks(listOf(wide, wide)))
        // Short lines of 10 bytes fill a run up to the limit and start the next.
        assertEquals(listOf(1536, 464), inputChunks(List(2000) { "abcdefghi" }).map { it.size })
        assertEquals(emptyList(), inputChunks(emptyList()))
    }

    @Test
    fun `a name with a line break or a carriage return is refused, no other`() {
        assertEquals(true, refusedName("a\nb"))
        assertEquals(true, refusedName("a\r"))
        assertEquals(false, refusedName("a b\t\"é"))
        assertEquals(false, refusedName(""))
    }
}
