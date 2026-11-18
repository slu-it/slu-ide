package dev.sluide.git

import dev.sluide.git.cli.BranchRef
import kotlin.test.Test
import kotlin.test.assertEquals

/** [defaultBranches] and [reviewBases] on lists of refs, as `git for-each-ref` gives them. */
class GitDefaultBranchesTest {
    private fun ref(
        name: String,
        upstream: String = "",
    ) = BranchRef(name, "", upstream)

    /** A symbolic ref, such as `refs/remotes/origin/HEAD`, with the ref it points at as its target. */
    private fun symbolic(
        name: String,
        target: String,
    ) = BranchRef(name, target, "")

    @Test
    fun `a remote's HEAD names its default branch, without one main and master in any case`() {
        val refs =
            listOf(
                symbolic("refs/remotes/origin/HEAD", "refs/remotes/origin/trunk"),
                ref("refs/remotes/origin/trunk"),
                ref("refs/remotes/origin/main"),
                ref("refs/remotes/other/Main"),
                ref("refs/remotes/other/MASTER"),
                ref("refs/remotes/other/dev"),
                ref("refs/remotes/third/main"),
                // Not a symbolic ref: no HEAD to follow.
                ref("refs/remotes/fourth/HEAD"),
                ref("refs/remotes/fourth/master"),
            )

        assertEquals(
            listOf(
                "refs/remotes/origin/trunk",
                "refs/remotes/other/Main",
                "refs/remotes/other/MASTER",
                "refs/remotes/fourth/master",
            ),
            defaultBranches(refs, listOf("origin", "other", "fourth")),
        )
    }

    @Test
    fun `the local main and master in any case are default, other branches and unprefixed names are not`() {
        val refs =
            listOf(
                ref("refs/heads/main"),
                ref("refs/heads/Master"),
                ref("refs/heads/develop"),
                ref("refs/heads/main-2"),
                ref("main"),
                ref("master"),
            )

        assertEquals(listOf("refs/heads/main", "refs/heads/Master"), defaultBranches(refs, emptyList()))
    }

    @Test
    fun `a local branch is default if it tracks the remote branch of its own name that is default`() {
        val refs =
            listOf(
                symbolic("refs/remotes/origin/HEAD", "refs/remotes/origin/develop"),
                ref("refs/remotes/origin/develop"),
                ref("refs/heads/develop", upstream = "refs/remotes/origin/develop"),
                // Another name, though it tracks the default branch.
                ref("refs/heads/fix-123", upstream = "refs/remotes/origin/develop"),
                // Tracks a branch of its name, which is not a default one.
                ref("refs/remotes/origin/topic"),
                ref("refs/heads/topic", upstream = "refs/remotes/origin/topic"),
                // Not a local branch, whatever its name is without the prefix.
                ref("develop", upstream = "refs/remotes/origin/develop"),
            )

        assertEquals(
            listOf("refs/remotes/origin/develop", "refs/heads/develop"),
            defaultBranches(refs, listOf("origin")),
        )
    }

    @Test
    fun `the default branches are listed once`() {
        val refs =
            listOf(
                ref("refs/remotes/origin/main"),
                ref("refs/heads/main", upstream = "refs/remotes/origin/main"),
                ref("refs/heads/main"),
            )

        assertEquals(
            listOf("refs/remotes/origin/main", "refs/heads/main"),
            defaultBranches(refs, listOf("origin", "origin")),
        )
    }

    private val reviewRefs =
        listOf(
            ref("refs/remotes/origin/main"),
            ref("refs/remotes/origin/master"),
            ref("refs/remotes/fork/main"),
            symbolic("refs/remotes/upstream/HEAD", "refs/remotes/upstream/develop"),
            ref("refs/remotes/upstream/develop"),
            ref("refs/heads/master"),
            ref("refs/heads/main"),
            ref("refs/heads/develop", upstream = "refs/remotes/upstream/develop"),
        )

    @Test
    fun `review bases are the upstream's remote first, then origin, then the rest, then local ones`() {
        val bases = reviewBases(reviewRefs, listOf("origin", "fork", "upstream"), "refs/remotes/fork/feature")

        assertEquals(
            listOf(
                "refs/remotes/fork/main",
                "refs/remotes/origin/main",
                "refs/remotes/origin/master",
                "refs/remotes/upstream/develop",
                "refs/heads/main",
                "refs/heads/master",
                "refs/heads/develop",
            ),
            bases,
        )
    }

    @Test
    fun `without an upstream origin comes first and the other remotes follow in name order`() {
        val refs = listOf("zeta", "alpha", "origin", "fork").map { ref("refs/remotes/$it/main") }

        assertEquals(
            listOf("origin", "alpha", "fork", "zeta").map { "refs/remotes/$it/main" },
            reviewBases(refs, listOf("zeta", "alpha", "origin", "fork"), ""),
        )
        assertEquals(
            listOf("zeta", "origin", "alpha", "fork").map { "refs/remotes/$it/main" },
            reviewBases(refs, listOf("zeta", "alpha", "origin", "fork"), "refs/remotes/zeta/topic"),
        )
    }

    @Test
    fun `a remote's main comes before its master whatever the case, a HEAD target of another name first`() {
        val refs =
            listOf(
                ref("refs/remotes/origin/master"),
                ref("refs/remotes/origin/Main"),
                symbolic("refs/remotes/other/HEAD", "refs/remotes/other/trunk"),
                ref("refs/remotes/other/trunk"),
                ref("refs/heads/Master"),
                ref("refs/heads/main"),
            )

        assertEquals(
            listOf(
                "refs/remotes/origin/Main",
                "refs/remotes/origin/master",
                "refs/remotes/other/trunk",
                "refs/heads/main",
                "refs/heads/Master",
            ),
            reviewBases(refs, listOf("origin", "other"), ""),
        )
    }

    @Test
    fun `a base that does not exist or is a symbolic ref is no candidate`() {
        val refs =
            listOf(
                // HEAD points at a branch that is not there.
                symbolic("refs/remotes/origin/HEAD", "refs/remotes/origin/gone"),
                ref("refs/remotes/origin/main"),
                symbolic("refs/heads/master", "refs/heads/main"),
                ref("refs/heads/main"),
            )

        // The remote's HEAD decides, so its main is no default branch either.
        assertEquals(listOf("refs/heads/main"), reviewBases(refs, listOf("origin"), ""))
    }

    @Test
    fun `the remote of a branch is the one with the longest matching name`() {
        val refs = listOf(ref("refs/remotes/a/main"), ref("refs/remotes/a/b/main"))

        assertEquals(
            listOf("refs/remotes/a/b/main", "refs/remotes/a/main"),
            reviewBases(refs, listOf("a", "a/b"), "refs/remotes/a/b/feature"),
        )
        assertEquals(
            listOf("refs/remotes/a/main", "refs/remotes/a/b/main"),
            reviewBases(refs, listOf("a", "a/b"), "refs/remotes/a/feature"),
        )
    }
}
