package dev.sluide.git

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The bases, branches, commits and fingerprints the review window reads (ADR 0168), with the real git. */
internal class GitReviewsRefsTest : GitLogFixture() {
    private val reviews = GitReviews(git)

    /**
     * Remotes `zeta`, `origin`, `alpha` and `upstream`, with `origin/HEAD` pointing at `origin/develop`; local `main`,
     * `master`, `develop` (tracking `origin/develop`), `other` (tracking `alpha/master`) and the current branch
     * `feature` (tracking `upstream/main`).
     */
    private fun remotes() {
        val hash = commit("First", 1000)
        for (remote in listOf("zeta", "origin", "alpha", "upstream")) {
            git("remote", "add", remote, "https://example.com/$remote.git")
        }
        for (ref in listOf("upstream/master", "upstream/main", "origin/main", "origin/develop", "alpha/master")) {
            git("update-ref", "refs/remotes/$ref", hash)
        }
        git("update-ref", "refs/remotes/zeta/main", hash)
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/develop")
        git("branch", "-q", "master")
        git("branch", "-q", "--track", "develop", "origin/develop")
        git("branch", "-q", "--track", "other", "alpha/master")
        git("checkout", "-q", "-b", "feature", "--track", "upstream/main")
    }

    @Test
    fun `the default bases are the remote ones first, the upstream remote's before origin's before the others`() {
        remotes()

        assertEquals(
            listOf(
                "refs/remotes/upstream/main",
                "refs/remotes/upstream/master",
                "refs/remotes/origin/develop",
                "refs/remotes/alpha/master",
                "refs/remotes/zeta/main",
                "refs/heads/main",
                "refs/heads/master",
                "refs/heads/develop",
            ),
            reviews.defaultBases(repo),
        )
    }

    @Test
    fun `the remote of the reviewed head comes first, also for a branch that is not checked out`() {
        remotes()

        assertEquals(
            listOf(
                "refs/remotes/alpha/master",
                "refs/remotes/origin/develop",
                "refs/remotes/upstream/main",
                "refs/remotes/upstream/master",
                "refs/remotes/zeta/main",
                "refs/heads/main",
                "refs/heads/master",
                "refs/heads/develop",
            ),
            reviews.defaultBases(repo, "refs/heads/other"),
        )
        assertEquals(
            listOf("refs/remotes/zeta/main", "refs/remotes/origin/develop", "refs/remotes/alpha/master"),
            reviews.defaultBases(repo, "refs/remotes/zeta/main").take(3),
        )
        // No upstream: origin first.
        assertEquals("refs/remotes/origin/develop", reviews.defaultBases(repo, "refs/heads/master").first())
    }

    @Test
    fun `a symbolic default branch is no base of its own, nor one that points nowhere`() {
        commit("First", 1000)
        git("branch", "-q", "master")
        git("checkout", "-q", "-b", "feature")
        // A main that is another name for master (git lists it, with its target).
        git("symbolic-ref", "refs/heads/main", "refs/heads/master")
        // A remote HEAD pointing at a branch that does not exist (git skips such a broken ref).
        git("remote", "add", "origin", "https://example.com/origin.git")
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/trunk")

        assertEquals(listOf("refs/heads/master"), reviews.defaultBases(repo))
    }

    @Test
    fun `without an upstream origin comes first, and without remotes the local main and master`() {
        commit("First", 1000)
        git("branch", "-q", "master")
        assertEquals(listOf("refs/heads/main", "refs/heads/master"), reviews.defaultBases(repo))

        git("remote", "add", "zeta", "https://example.com/zeta.git")
        git("remote", "add", "origin", "https://example.com/origin.git")
        git("update-ref", "refs/remotes/zeta/main", "HEAD")
        git("update-ref", "refs/remotes/origin/master", "HEAD")
        assertEquals(
            listOf("refs/remotes/origin/master", "refs/remotes/zeta/main", "refs/heads/main", "refs/heads/master"),
            reviews.defaultBases(repo),
        )
    }

    @Test
    fun `lists the local then the remote branches, each sorted, without symbolic refs`() {
        remotes()

        assertEquals(
            listOf(
                "refs/heads/develop",
                "refs/heads/feature",
                "refs/heads/main",
                "refs/heads/master",
                "refs/heads/other",
                "refs/remotes/alpha/master",
                "refs/remotes/origin/develop",
                "refs/remotes/origin/main",
                "refs/remotes/upstream/main",
                "refs/remotes/upstream/master",
                "refs/remotes/zeta/main",
            ),
            reviews.branches(repo),
        )
    }

    @Test
    fun `lists the commits of a range newest first and resolves revisions and merge bases`() {
        val first = commit("First", 1000)
        val second = commit("Second", 2000)
        git("checkout", "-q", "-b", "side")
        val side = commit("Side", 3000)
        git("checkout", "-q", "main")
        val third = commit("Third", 4000)

        assertEquals(listOf("Third", "Second"), reviews.commits(repo, first, third)?.map { it.subject })
        assertEquals(listOf("Second", "First"), reviews.commits(repo, null, second)?.map { it.subject })
        assertEquals(listOf("Third"), reviews.commits(repo, null, third, limit = 1)?.map { it.subject })
        assertEquals(listOf(first), reviews.commits(repo, first, second)?.single()?.parents)
        assertEquals(second, reviews.mergeBase(repo, "refs/heads/side", third))
        assertEquals(side, reviews.resolveCommit(repo, "refs/heads/side"))
        assertEquals(null, reviews.resolveCommit(repo, "refs/heads/none"))
        assertEquals(true, reviews.isAncestor(repo, first, side))
        assertEquals(false, reviews.isAncestor(repo, side, third))
    }

    @Test
    fun `lists the commits of a merged branch together, in topological order rather than by date`() {
        commit("A", 1000)
        git("checkout", "-q", "-b", "side")
        commit("S1", 2000)
        git("checkout", "-q", "main")
        commit("M1", 3000)
        git("checkout", "-q", "side")
        commit("S2", 4000)
        git("checkout", "-q", "main")
        val date = "@5000 +0000"
        git(
            "merge",
            "-q",
            "--no-ff",
            "-m",
            "X",
            "side",
            environment =
                mapOf(
                    "GIT_AUTHOR_DATE" to date,
                    "GIT_COMMITTER_DATE" to date,
                ),
        )
        val merge = git("rev-parse", "HEAD")

        val subjects = reviews.commits(repo, null, merge)?.map { it.subject }

        // By date it would be X, S2, M1, S1, A.
        val topological = listOf(listOf("X", "S2", "S1", "M1", "A"), listOf("X", "M1", "S2", "S1", "A"))
        assertTrue(subjects in topological, "$subjects")
    }

    @Test
    fun `the fingerprint changes when a revision moves and stays the same otherwise`() {
        commit("First", 1000)
        val revisions = listOf("refs/heads/main", "refs/heads/none")
        val before = assertNotNull(reviews.fingerprint(repo, revisions, workingTree = false))
        assertEquals(before, reviews.fingerprint(repo, revisions, workingTree = false))

        commit("Second", 2000)

        assertNotEquals(before, reviews.fingerprint(repo, revisions, workingTree = false))
    }

    @Test
    fun `the working tree's fingerprint changes with the status and with the time of a changed file`() {
        commitFiles("First", "a.txt" to "a\n", "b.txt" to "b\n")
        val revisions = listOf("HEAD")
        val clean = assertNotNull(reviews.fingerprint(repo, revisions, workingTree = true))
        val file = repo.resolve("a.txt")
        file.writeText("changed\n")
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(1_000_000)))
        val modified = assertNotNull(reviews.fingerprint(repo, revisions, workingTree = true))
        assertNotEquals(clean, modified)
        assertEquals(modified, reviews.fingerprint(repo, revisions, workingTree = true))
        // The unchanged b.txt does not count.
        Files.setLastModifiedTime(repo.resolve("b.txt"), FileTime.from(Instant.ofEpochSecond(3_000_000)))
        assertEquals(modified, reviews.fingerprint(repo, revisions, workingTree = true))

        // Edited again: the same status and size, another time.
        file.writeText("edited!\n")
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(2_000_000)))

        assertNotEquals(modified, reviews.fingerprint(repo, revisions, workingTree = true))
        // Without the working tree, only the revisions count.
        val revisionsOnly = reviews.fingerprint(repo, revisions, workingTree = false)
        file.writeText("again\n")
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(4_000_000)))
        assertEquals(revisionsOnly, reviews.fingerprint(repo, revisions, workingTree = false))
    }
}
