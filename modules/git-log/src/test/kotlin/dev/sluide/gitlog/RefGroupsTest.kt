package dev.sluide.gitlog

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RefGroupsTest {
    private val head = GitRef("HEAD", GitRefKind.HEAD, "c")

    private fun local(name: String) = GitRef("refs/heads/$name", GitRefKind.LOCAL_BRANCH, "c")

    private fun remote(name: String) = GitRef("refs/remotes/$name", GitRefKind.REMOTE_BRANCH, "c")

    private fun tag(name: String) = GitRef("refs/tags/$name", GitRefKind.TAG, "c")

    @Test
    fun `a local branch and its remote branches form one group`() {
        assertEquals(listOf("origin & main"), refGroups(listOf(local("main"), remote("origin/main")), null))
        assertEquals(
            listOf("origin & upstream & main"),
            refGroups(listOf(local("main"), remote("upstream/main"), remote("origin/main")), null),
        )
        assertEquals(listOf("main"), refGroups(listOf(local("main")), null))
    }

    @Test
    fun `remote branches without a local one are groups of their own`() {
        assertEquals(listOf("origin/main"), refGroups(listOf(remote("origin/main")), null))
        assertEquals(
            listOf("origin/main", "upstream/main"),
            refGroups(listOf(remote("upstream/main"), remote("origin/main")), null),
        )
    }

    @Test
    fun `a remote branch without a remote name does not join a local branch`() {
        assertEquals(listOf("trunk", "trunk"), refGroups(listOf(local("trunk"), remote("trunk")), null))
        assertEquals(
            listOf("origin & trunk", "trunk"),
            refGroups(listOf(local("trunk"), remote("origin/trunk"), remote("trunk")), null),
        )
    }

    @Test
    fun `HEAD joins the current branch, a detached HEAD is a group of its own`() {
        val refs = listOf(head, local("feature"), local("main"), remote("origin/main"))
        assertEquals(listOf("origin & main", "feature"), refGroups(refs, "refs/heads/main"))
        assertEquals(listOf("HEAD", "feature", "origin & main"), refGroups(refs, null))
        assertEquals(listOf("HEAD"), refGroups(listOf(head), null))
        // Attached to a branch that is not on this row (refs read while it moved): HEAD stays alone.
        assertEquals(listOf("HEAD", "main"), refGroups(listOf(head, local("main")), "refs/heads/other"))
    }

    @Test
    fun `tags are groups of their own, after the branches, all by name within their kind`() {
        val refs =
            listOf(
                head,
                local("zeta"),
                local("alpha"),
                remote("origin/beta"),
                remote("origin/alpha"),
                tag("v2"),
                tag("v1"),
            )

        assertEquals(
            listOf("zeta", "origin & alpha", "origin/beta", "v1", "v2"),
            refGroups(refs, "refs/heads/zeta"),
        )
    }

    @Test
    fun `the label shows the first group and how many more there are`() {
        assertEquals("", refLabelText(emptyList()))
        assertEquals("origin & main", refLabelText(listOf("origin & main")))
        assertEquals("origin & main +2", refLabelText(listOf("origin & main", "feature", "v1")))
    }

    @Test
    fun `labels take at most a third of the cell and leave the subject its minimum`() {
        assertEquals(50.0, labelsWidth(600.0, 570.0, 50.0), "as wide as they want")
        assertEquals(200.0, labelsWidth(600.0, 570.0, 400.0), "a third of the cell")
        assertEquals(30.0, labelsWidth(300.0, 98.0, 80.0), "the subject keeps its minimum")
        assertEquals(0.0, labelsWidth(100.0, 40.0, 80.0), "no room at all")
    }
}
