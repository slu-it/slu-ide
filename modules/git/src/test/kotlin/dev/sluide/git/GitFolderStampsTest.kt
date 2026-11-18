package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [GitFolderStamps] on plain files: what changes a stamp and what does not, without git. The stamp of a state is by
 * size, time and inode; the trust inputs are by content, permissions or what a link points to (ADR 0062).
 */
class GitFolderStampsTest {
    @TempDir
    lateinit var folder: Path

    private val before = FileTime.from(Instant.parse("2024-01-01T00:00:00Z"))

    private fun file(
        name: String,
        content: String = "content\n",
    ): Path = folder.resolve(name).also { it.parent.createDirectories() }.also { it.writeText(content) }

    private fun inputs(
        files: Set<Path> = emptySet(),
        folders: Set<Path> = emptySet(),
        redirects: Set<Path> = emptySet(),
        large: Set<Path> = emptySet(),
    ) = GitTrustInputs(files, folders, redirects, large)

    private fun trust(
        inputs: GitTrustInputs,
        maxEntries: Int = GitFolderStamps.MAX_ENTRIES,
        maxRead: Long = GitFolderStamps.MAX_READ,
    ) = GitFolderStamps.trust(inputs, maxEntries, maxRead)

    /** A root with a `.git` folder that has a `HEAD` file with the time [before]. */
    private fun repository(): Path {
        val root = folder.resolve("repo").createDirectories()
        val head = root.resolve(".git/HEAD")
        head.parent.createDirectories()
        head.writeText("ref: refs/heads/main\n")
        Files.setLastModifiedTime(head, before)
        return root
    }

    private fun touch(
        path: Path,
        time: String,
    ) = assertEquals(0, ProcessBuilder("touch", "-t", time, path.toString()).start().waitFor())

    // --- state

    @Test
    fun `only a git folder has a state stamp, not a git file, a link or nothing`() {
        val root = repository()
        assertNotNull(GitFolderStamps.state(root))

        val file = folder.resolve("worktree").createDirectories()
        file.resolve(".git").writeText("gitdir: ../repo/.git\n")
        assertNull(GitFolderStamps.state(file), "a .git file")
        assertNull(GitFolderStamps.state(folder.resolve("none")), "no .git")
        val linked = folder.resolve("linked").createDirectories()
        linked.resolve(".git").createSymbolicLinkPointingTo(root.resolve(".git"))
        assertNull(GitFolderStamps.state(linked), "a link to a folder is not followed")
    }

    @Test
    fun `a state file changes the stamp by its size, its time or its inode alone`() {
        val root = repository()
        val head = root.resolve(".git/HEAD")
        val original = assertNotNull(GitFolderStamps.state(root))
        assertEquals(original, GitFolderStamps.state(root), "stable")

        // The same inode and time, another size.
        Files.write(head, "ref: refs/heads/mainx\n".toByteArray())
        Files.setLastModifiedTime(head, before)
        val size = GitFolderStamps.state(root)
        assertNotEquals(original, size)

        // The same size and inode, another time.
        Files.setLastModifiedTime(head, FileTime.from(Instant.parse("2024-01-01T00:00:05Z")))
        val time = GitFolderStamps.state(root)
        assertNotEquals(size, time)

        // The same size and time, a new inode: git writes a lock file and renames it.
        val key = Files.readAttributes(head, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        assumeTrue(key != null, "this file system has no inode numbers")
        val replacement = root.resolve(".git/HEAD.lock")
        Files.write(replacement, Files.readAllBytes(head))
        Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(head))
        Files.move(replacement, head, StandardCopyOption.REPLACE_EXISTING)
        assertNotEquals(time, GitFolderStamps.state(root))
    }

    @Test
    fun `a state folder that appears changes the stamp, empty or not`() {
        val root = repository()
        val none = GitFolderStamps.state(root)

        root.resolve(".git/rebase-apply").createDirectories()
        val empty = GitFolderStamps.state(root)
        assertNotEquals(none, empty, "missing is not an empty folder")

        file("repo/.git/rebase-apply/next")
        assertNotEquals(empty, GitFolderStamps.state(root))
    }

    @Test
    fun `a submodule's refs and files count, its objects, logs and lfs do not`() {
        val root = repository()
        val submodule = root.resolve(".git/modules/sub")
        file("repo/.git/modules/sub/HEAD")
        file("repo/.git/modules/sub/refs/heads/main")
        for (skipped in listOf("objects/ab/cdef", "logs/HEAD", "lfs/tmp/x")) file("repo/.git/modules/sub/$skipped")
        val original = GitFolderStamps.state(root)

        for (skipped in listOf("objects/ab/cdef", "logs/HEAD", "lfs/tmp/x")) {
            submodule.resolve(skipped).writeText("something else entirely\n")
            assertEquals(original, GitFolderStamps.state(root), "$skipped is not read")
        }
        submodule.resolve("refs/heads/main").writeText("something else entirely\n")
        val changed = GitFolderStamps.state(root)
        assertNotEquals(original, changed, "a ref")
        submodule.resolve("HEAD").writeText("something else entirely\n")
        assertNotEquals(changed, GitFolderStamps.state(root), "HEAD")
    }

    // --- trust

    @Test
    fun `no inputs have no stamp`() {
        assertNull(GitFolderStamps.trust(null))
        assertNotNull(trust(inputs()))
    }

    @Test
    fun `a large file is stamped by its attributes, a missing one differently from none`() {
        val large = file("index", "index\n")
        Files.setLastModifiedTime(large, before)
        val original = assertNotNull(trust(inputs(large = setOf(large))))

        assertNotEquals(trust(inputs()), original, "a file that is an input counts")
        Files.write(large, "index!\n".toByteArray())
        Files.setLastModifiedTime(large, before)
        assertNotEquals(original, trust(inputs(large = setOf(large))), "its size")

        val missing = folder.resolve("missing")
        assertNotEquals(trust(inputs()), trust(inputs(large = setOf(missing))), "a missing input is recorded")
        assertEquals(trust(inputs(large = setOf(missing))), trust(inputs(large = setOf(missing))))
    }

    @Test
    fun `a file is stamped by its content and permissions, a missing one differently from none`() {
        val config = file("config", "[core]\n")
        Files.setLastModifiedTime(config, before)
        val original = assertNotNull(trust(inputs(files = setOf(config))))

        Files.write(config, "[core]\n\n".toByteArray())
        Files.setLastModifiedTime(config, before)
        assertNotEquals(original, trust(inputs(files = setOf(config))), "an edit in place with the old time")

        val missing = folder.resolve("missing")
        assertNotEquals(trust(inputs()), trust(inputs(files = setOf(missing))), "a missing input is recorded")
        assertNotEquals(
            trust(inputs(files = setOf(missing))),
            trust(inputs(files = setOf(folder.resolve("other-missing")))),
            "by its path",
        )
    }

    @Test
    fun `a file up to one megabyte is stamped by its content, a larger one by its attributes`() {
        val limit = GitFolderStamps.MAX_HASHED
        val atLimit = folder.resolve("at-limit").also { it.writeBytes(ByteArray(limit) { 1 }) }
        val above = folder.resolve("above").also { it.writeBytes(ByteArray(limit + 1) { 1 }) }
        for (path in listOf(atLimit, above)) Files.setLastModifiedTime(path, before)
        val hashed = assertNotNull(trust(inputs(files = setOf(atLimit))))
        val large = assertNotNull(trust(inputs(files = setOf(above))))

        // A byte flipped in place, with the old time: only a stamp by content sees it.
        for (path in listOf(atLimit, above)) {
            val bytes = Files.readAllBytes(path)
            bytes[0] = 2
            Files.write(path, bytes)
            Files.setLastModifiedTime(path, before)
        }

        assertNotEquals(hashed, trust(inputs(files = setOf(atLimit))), "at the limit: by content")
        assertEquals(large, trust(inputs(files = setOf(above))), "above the limit: by attributes")
    }

    @Test
    fun `reading up to the limit is allowed, one byte more leaves no stamp`() {
        val first = file("first", "12345")
        val second = file("second", "67890")
        val both = inputs(files = setOf(first, second))

        assertNotNull(trust(both, maxRead = 10))
        assertNull(trust(both, maxRead = 9))
    }

    @Test
    fun `a hooks folder is stamped by its entries, and up to the limit of entries`() {
        val hooks = folder.resolve("hooks").createDirectories()
        file("hooks/pre-commit")
        file("hooks/commit-msg")

        val original = assertNotNull(trust(inputs(folders = setOf(hooks)), maxEntries = 2))
        assertNull(trust(inputs(folders = setOf(hooks)), maxEntries = 1))

        file("hooks/post-commit")
        assertNull(trust(inputs(folders = setOf(hooks)), maxEntries = 2))
        assertNotEquals(original, trust(inputs(folders = setOf(hooks))))
    }

    @Test
    fun `a folder input that is no folder is stamped as a file, an empty one differently from none`() {
        val notAFolder = file("hooks-file", "not a folder\n")
        val empty = folder.resolve("empty").createDirectories()

        val asFile = assertNotNull(trust(inputs(folders = setOf(notAFolder))))
        assertEquals(trust(inputs(files = setOf(notAFolder))), asFile)
        assertNotEquals(trust(inputs()), trust(inputs(folders = setOf(empty))))
        assertNotNull(trust(inputs(folders = setOf(folder.resolve("missing")))))
    }

    @Test
    fun `a git redirect is stamped by where it leads`() {
        val one = folder.resolve("one").createDirectories()
        val two = folder.resolve("two").createDirectories()
        val link = folder.resolve("link")
        link.createSymbolicLinkPointingTo(one)
        val toOne = assertNotNull(trust(inputs(redirects = setOf(link))))

        Files.delete(link)
        link.createSymbolicLinkPointingTo(two)
        assertNotEquals(toOne, trust(inputs(redirects = setOf(link))), "another target")

        assertNotEquals(trust(inputs()), trust(inputs(redirects = setOf(one))), "a folder counts")
        val gitdir = file("gitdir-file", "gitdir: ../elsewhere\n")
        val pointing = trust(inputs(redirects = setOf(gitdir)))
        gitdir.writeText("gitdir: ../somewhere-else\n")
        assertNotEquals(pointing, trust(inputs(redirects = setOf(gitdir))), "a gitdir file by its content")
    }

    @Test
    fun `a git folder as redirect is stamped as a folder, whatever is in it or its permissions`() {
        val dot = folder.resolve("dot-git").createDirectories()
        assumeTrue(dot.fileSystem.supportedFileAttributeViews().contains("posix"), "no POSIX permissions")
        val original = trust(inputs(redirects = setOf(dot)))

        file("dot-git/HEAD")
        Files.setPosixFilePermissions(dot, PosixFilePermissions.fromString("rwxr-x---"))

        assertEquals(original, trust(inputs(redirects = setOf(dot))))
    }

    @Test
    fun `a named pipe is stamped by its attributes without being opened`() {
        val pipe = folder.resolve("pipe")
        val made = runCatching { ProcessBuilder("mkfifo", pipe.toString()).start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(made, "mkfifo is not available")
        // `touch`, as setting the time through the JDK would open the pipe and wait for ever.
        touch(pipe, "202401010000.00")
        val original = assertNotNull(trust(inputs(files = setOf(pipe))))

        touch(pipe, "202401010000.09")

        assertNotEquals(original, trust(inputs(files = setOf(pipe))))
    }
}
