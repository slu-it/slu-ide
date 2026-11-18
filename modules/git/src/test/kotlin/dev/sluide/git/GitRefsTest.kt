package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertEquals

class GitRefsTest {
    @Test
    fun `the refs of a commit are ordered by kind first, then by name`() {
        val commit = "c".repeat(40)
        // Names that sort against the kinds (a tag "a" before a branch "b"): the kind decides, not the name.
        val tag = GitRef("a", GitRefKind.TAG, commit)
        val remote = GitRef("b", GitRefKind.REMOTE_BRANCH, commit)
        val laterBranch = GitRef("d", GitRefKind.LOCAL_BRANCH, commit)
        val earlierBranch = GitRef("c", GitRefKind.LOCAL_BRANCH, commit)
        val head = GitRef("z", GitRefKind.HEAD, commit)

        val refs = GitRefs(listOf(tag, remote, laterBranch, head, earlierBranch), currentBranch = null)

        assertEquals(listOf(head, earlierBranch, laterBranch, remote, tag), refs.at(commit))
    }
}
