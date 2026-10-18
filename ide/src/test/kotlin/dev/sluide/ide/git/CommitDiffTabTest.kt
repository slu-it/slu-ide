package dev.sluide.ide.git

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitChange
import dev.sluide.git.GitHunk
import dev.sluide.gitlog.CommitFileDiff
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitDiffTabTest {
    private val commit = "c".repeat(40)
    private val parent = "p".repeat(40)

    private fun diff(
        change: GitChange,
        parent: String? = this.parent,
        oldPath: String? = null,
    ) = CommitFileDiff(
        Path.of("/repo"),
        "src/Main.kt",
        oldPath,
        change,
        commit,
        parent,
        "old\n",
        "new\n",
        listOf(GitHunk(0..0, 0..0)),
    )

    @Test
    fun `titles the sides with the short hashes of the parent and the commit`() {
        assertEquals(
            TextDiff("old\n", listOf(LineChange(0..0, 0..0)), "pppppppp", "cccccccc"),
            diff(GitChange.MODIFIED).toTextDiff(),
        )
    }

    @Test
    fun `renamed and copied files name their old path, added ones and root commits have no original`() {
        assertEquals(
            "pppppppp (src/Old.kt)",
            diff(GitChange.RENAMED, oldPath = "src/Old.kt").toTextDiff().originalTitle,
        )
        assertEquals("pppppppp (src/Old.kt)", diff(GitChange.COPIED, oldPath = "src/Old.kt").toTextDiff().originalTitle)
        assertEquals("(none)", diff(GitChange.ADDED).toTextDiff().originalTitle)
        assertEquals("(none)", diff(GitChange.ADDED, parent = null).toTextDiff().originalTitle)
    }
}
