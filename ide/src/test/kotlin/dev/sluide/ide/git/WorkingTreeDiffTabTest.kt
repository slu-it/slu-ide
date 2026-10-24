package dev.sluide.ide.git

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitChange
import dev.sluide.git.GitHunk
import dev.sluide.gitlog.WorkingTreeFileDiff
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkingTreeDiffTabTest {
    private val commit = "1a2b3c4d".repeat(5)

    private fun diff(
        change: GitChange = GitChange.MODIFIED,
        oldPath: String? = null,
        label: String = "feature",
    ) = WorkingTreeFileDiff(
        Path.of("/repo"),
        "src/Main.kt",
        oldPath,
        change,
        commit,
        label,
        "old\n",
        "new\n",
        listOf(GitHunk(0..0, 0..0)),
    )

    @Test
    fun `titles the original with the commit as chosen and its short hash, the right side Working Tree`() {
        assertEquals(
            TextDiff("old\n", listOf(LineChange(0..0, 0..0)), "feature (1a2b3c4d)", "Working Tree"),
            diff().toTextDiff(),
        )
    }

    @Test
    fun `a label that is the short hash shows it only once`() {
        assertEquals("1a2b3c4d", diff(label = "1a2b3c4d").toTextDiff().originalTitle)
    }

    @Test
    fun `a renamed file names its old path, an added one has no original`() {
        assertEquals(
            "feature (1a2b3c4d), src/Old.kt",
            diff(GitChange.RENAMED, oldPath = "src/Old.kt").toTextDiff().originalTitle,
        )
        assertEquals("feature (1a2b3c4d)", diff(oldPath = "src/Main.kt").toTextDiff().originalTitle)
        assertEquals("(none)", diff(GitChange.ADDED).toTextDiff().originalTitle)
    }

    @Test
    fun `the right side says if the file is deleted or an editor has unsaved changes of it`() {
        assertEquals("Working Tree (deleted)", diff(GitChange.DELETED).toTextDiff().currentTitle)
        assertEquals("Working Tree (saved)", diff().toTextDiff(unsaved = true).currentTitle)
    }
}
