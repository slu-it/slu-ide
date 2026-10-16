package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitRefsParserTest {
    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    private fun record(
        objectName: String,
        name: String,
        peeled: String = "",
        symref: String = "",
    ) = listOf(objectName, peeled, name, symref).joinToString("\u001f") + "\n"

    @Test
    fun `reads branches, remote branches and tags, with HEAD and the current branch`() {
        val output =
            record(a, "refs/heads/main") +
                record(b, "refs/remotes/origin/feature") +
                record(c, "refs/tags/v1")

        val refs = GitRefsParser.parse(output, a, "refs/heads/main")

        assertEquals(
            listOf(
                GitRef("HEAD", GitRefKind.HEAD, a),
                GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, a),
                GitRef("refs/remotes/origin/feature", GitRefKind.REMOTE_BRANCH, b),
                GitRef("refs/tags/v1", GitRefKind.TAG, c),
            ),
            refs.refs,
        )
        assertEquals("refs/heads/main", refs.currentBranch)
        assertEquals(listOf("HEAD", "main", "origin/feature", "v1"), refs.refs.map { it.shortName })
    }

    @Test
    fun `an annotated tag points at the commit it tags`() {
        val refs = GitRefsParser.parse(record(b, "refs/tags/v2", peeled = c), null, null)

        assertEquals(listOf(GitRef("refs/tags/v2", GitRefKind.TAG, c)), refs.refs)
    }

    @Test
    fun `skips symbolic refs, other namespaces and malformed records`() {
        val output =
            record(a, "refs/remotes/origin/HEAD", symref = "refs/remotes/origin/main") +
                record(a, "refs/notes/commits") +
                record("not a hash", "refs/heads/bad") +
                "${a}\u001frefs/heads/short\n" +
                record(a, "refs/remotes/origin/main")

        val refs = GitRefsParser.parse(output, null, "")

        assertEquals(listOf(GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, a)), refs.refs)
        assertNull(refs.currentBranch, "detached")
    }

    @Test
    fun `refs at a commit are ordered HEAD, local, remote, tag, and by name within each`() {
        val output =
            record(a, "refs/tags/v1") +
                record(a, "refs/remotes/upstream/main") +
                record(a, "refs/heads/zeta") +
                record(a, "refs/remotes/origin/main") +
                record(a, "refs/heads/main") +
                record(b, "refs/heads/other")

        val refs = GitRefsParser.parse(output, a, "refs/heads/zeta")

        assertEquals(
            listOf("HEAD", "main", "zeta", "origin/main", "upstream/main", "v1"),
            refs.at(a).map { it.shortName },
        )
        assertEquals(listOf("other"), refs.at(b).map { it.shortName })
        assertEquals(emptyList(), refs.at(c))
    }

    @Test
    fun `without a HEAD commit there is no HEAD ref`() {
        val refs = GitRefsParser.parse("", null, "refs/heads/main")

        assertEquals(GitRefs(emptyList(), "refs/heads/main"), refs)
    }
}
