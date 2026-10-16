package dev.sluide.git

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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
}
