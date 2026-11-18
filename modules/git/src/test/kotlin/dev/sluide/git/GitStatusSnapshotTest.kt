package dev.sluide.git

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitStatusSnapshotTest {
    private val root = Path.of("/work/project")

    private val snapshot =
        GitStatusSnapshot(
            root,
            mapOf(
                "README.md" to GitStatus.MODIFIED,
                "src/main/New.kt" to GitStatus.ADDED,
                "src/main/Gone.kt" to GitStatus.MODIFIED,
                "src/test/scratch.kt" to GitStatus.UNVERSIONED,
                "src/test/out.log" to GitStatus.IGNORED,
                "build/" to GitStatus.IGNORED,
                "drafts/" to GitStatus.UNVERSIONED,
            ),
        )

    private fun path(relative: String) = root.resolve(relative)

    @Test
    fun `files have their own status or none`() {
        assertEquals(GitStatus.MODIFIED, snapshot.fileStatus(path("README.md")))
        assertEquals(GitStatus.ADDED, snapshot.fileStatus(path("src/main/New.kt")))
        assertEquals(GitStatus.IGNORED, snapshot.fileStatus(path("src/test/out.log")))
        assertNull(snapshot.fileStatus(path("src/main/Clean.kt")))
        assertNull(snapshot.fileStatus(Path.of("/elsewhere/README.md")))
    }

    @Test
    fun `files and folders inside whole untracked or ignored folders inherit their status`() {
        assertEquals(GitStatus.IGNORED, snapshot.fileStatus(path("build/libs/app.jar")))
        assertEquals(GitStatus.UNVERSIONED, snapshot.fileStatus(path("drafts/idea.md")))
        assertEquals(setOf(GitStatus.IGNORED), snapshot.folderStatuses(path("build")))
        assertEquals(setOf(GitStatus.IGNORED), snapshot.folderStatuses(path("build/libs")))
        assertEquals(setOf(GitStatus.UNVERSIONED), snapshot.folderStatuses(path("drafts/old")))
    }

    @Test
    fun `folders collect the statuses below them in order, ignored ones excepted`() {
        assertEquals(listOf(GitStatus.ADDED, GitStatus.MODIFIED), snapshot.folderStatuses(path("src/main")).toList())
        assertEquals(listOf(GitStatus.UNVERSIONED), snapshot.folderStatuses(path("src/test")).toList())
        assertEquals(
            listOf(GitStatus.UNVERSIONED, GitStatus.ADDED, GitStatus.MODIFIED),
            snapshot.folderStatuses(path("src")).toList(),
        )
        assertEquals(
            listOf(GitStatus.UNVERSIONED, GitStatus.ADDED, GitStatus.MODIFIED),
            snapshot.folderStatuses(root).toList(),
        )
        assertEquals(emptySet(), snapshot.folderStatuses(path("docs")))
        assertEquals(emptySet(), snapshot.folderStatuses(root.parent))
    }

    @Test
    fun `local changes are any status but ignored, untracked ones included`() {
        fun changes(vararg entries: Pair<String, GitStatus>) = GitStatusSnapshot(root, mapOf(*entries)).hasLocalChanges

        assertFalse(changes("out.log" to GitStatus.IGNORED, "build/" to GitStatus.IGNORED), "only ignored")
        assertTrue(changes("out.log" to GitStatus.IGNORED, "scratch.kt" to GitStatus.UNVERSIONED), "untracked")
        assertTrue(changes("README.md" to GitStatus.MODIFIED), "modified")
        assertFalse(GitStatusSnapshot.EMPTY.hasLocalChanges, "empty")
    }

    @Test
    fun `the empty snapshot has no statuses`() {
        assertNull(GitStatusSnapshot.EMPTY.fileStatus(path("README.md")))
        assertEquals(emptySet(), GitStatusSnapshot.EMPTY.folderStatuses(root))
    }

    @Test
    fun `snapshots with the same entries are equal`() {
        val entries = mapOf("a.kt" to GitStatus.ADDED)
        assertEquals(GitStatusSnapshot(root, entries), GitStatusSnapshot(root, entries.toMap()))
        assertNotEquals(GitStatusSnapshot(root, entries), GitStatusSnapshot(root, emptyMap()))
        // A checkout that leaves the working tree unchanged must still count as a new snapshot.
        val onMaster = GitStatusSnapshot(root, entries, GitHead("master", "abc"))
        assertNotEquals(onMaster, GitStatusSnapshot(root, entries, GitHead("other", "abc")))
        assertNotEquals(onMaster, GitStatusSnapshot(root, entries))
        assertEquals(onMaster, GitStatusSnapshot(root, entries, GitHead("master", "abc")))
    }

    @Test
    fun `the start time of the run is kept but does not make snapshots differ`() {
        val entries = mapOf("a.kt" to GitStatus.ADDED)
        val early = GitStatusSnapshot(root, entries, startedAt = Instant.EPOCH)
        val late = GitStatusSnapshot(root, entries, startedAt = Instant.EPOCH.plusSeconds(60))

        assertEquals(early, late)
        assertEquals(early.hashCode(), late.hashCode())
        assertEquals(Instant.EPOCH, early.startedAt)
        assertEquals(Instant.EPOCH, GitStatusSnapshot.parse(root, "", Instant.EPOCH).startedAt)
        assertNull(GitStatusSnapshot.EMPTY.startedAt)
    }

    @Test
    fun `a snapshot without a root has no statuses whatever its entries`() {
        val rootless = GitStatusSnapshot(null, mapOf("a.kt" to GitStatus.ADDED, "dir/" to GitStatus.UNVERSIONED))

        assertNull(rootless.fileStatus(Path.of("a.kt")))
        assertNull(rootless.fileStatus(Path.of("/dir/x")))
        assertEquals(emptySet(), rootless.folderStatuses(Path.of("/")))
        assertTrue(rootless.hasLocalChanges)
    }

    @Test
    fun `an entry outside the root counts for no folder, and a path outside the root finds no folder`() {
        val outside =
            GitStatusSnapshot(
                root,
                mapOf("../sibling.kt" to GitStatus.MODIFIED, "../sibling/" to GitStatus.UNVERSIONED),
            )

        assertEquals(emptySet(), outside.folderStatuses(root.parent))
        assertEquals(emptySet(), outside.folderStatuses(root))
        assertNull(outside.fileStatus(root.parent.resolve("sibling/x.kt")), "the folder is outside the root")
        assertNull(outside.fileStatus(Path.of("/")), "a path without a parent")
    }

    @Test
    fun `a root at the top of the file system is walked up to its end`() {
        val top = Path.of("/")
        val atTop = GitStatusSnapshot(top, mapOf("a/b.kt" to GitStatus.ADDED))

        assertEquals(setOf(GitStatus.ADDED), atTop.folderStatuses(top.resolve("a")))
        assertEquals(setOf(GitStatus.ADDED), atTop.folderStatuses(top))
        assertEquals(GitStatus.ADDED, atTop.fileStatus(top.resolve("a/b.kt")))
    }

    @Test
    fun `snapshots of another root or another kind of object are not equal`() {
        val entries = mapOf("a.kt" to GitStatus.ADDED)
        val head = GitHead("main", "abc")

        assertNotEquals(GitStatusSnapshot(root, entries, head), GitStatusSnapshot(Path.of("/other"), entries, head))
        assertNotEquals<Any?>(GitStatusSnapshot(root, entries), "snapshot")
        assertNotEquals<Any?>(GitStatusSnapshot(root, entries), null)
    }

    @Test
    fun `the hash and the text of a snapshot tell root, head and entries`() {
        val entries = mapOf("a.kt" to GitStatus.ADDED)
        val head = GitHead("main", "abc")
        val snapshot = GitStatusSnapshot(root, entries, head)

        // The same fields as equals compares, in this order: 31 * (31 * root + entries) + head.
        assertEquals((31 * root.hashCode() + entries.hashCode()) * 31 + head.hashCode(), snapshot.hashCode())
        assertEquals((31 * root.hashCode() + entries.hashCode()) * 31, GitStatusSnapshot(root, entries).hashCode())
        assertEquals("GitStatusSnapshot($root, $head, $entries)", snapshot.toString())
    }

    @Test
    fun `the empty snapshot has the hash of no root, no entries and no head`() {
        assertEquals(31 * emptyMap<String, GitStatus>().hashCode() * 31, GitStatusSnapshot.EMPTY.hashCode())
    }
}
