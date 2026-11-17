package dev.sluide.git

import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The target, tracking ref, remote branch and outgoing commits of [GitPush]; skipped if git is not installed. */
internal class GitPushTargetTest : GitPushFixture() {
    // pushTarget

    @Test
    fun `the target keeps the branch's name when its upstream has another, as push_default simple does`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")
        assertEquals(GitPushTarget("origin", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))

        for (mode in listOf("simple", "current", "matching", "nothing")) {
            git("config", "push.default", mode)
            assertEquals(GitPushTarget("origin", "main", hasUpstream = true), pushes.pushTarget(repo, "main"), mode)
        }
    }

    @Test
    fun `a branch created from a remote branch is not pushed into it`() {
        // Git's default branch.autoSetupMerge makes origin/main the upstream of the new branch.
        git("branch", "feature", "refs/remotes/origin/main")

        assertEquals(GitPushTarget("origin", "feature", hasUpstream = true), pushes.pushTarget(repo, "feature"))
    }

    @Test
    fun `the target is the upstream with push_default upstream or tracking`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")
        git("config", "push.default", "upstream")
        assertEquals(GitPushTarget("origin", "trunk", hasUpstream = true), pushes.pushTarget(repo, "main"))

        git("config", "push.default", "tracking")
        assertEquals(GitPushTarget("origin", "trunk", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `the push remote and remote_pushDefault come before the upstream, with the branch's own name`() {
        git("remote", "add", "fork", folder.resolve("fork").toString())
        git("remote", "add", "extra", folder.resolve("extra").toString())
        git("config", "branch.main.merge", "refs/heads/trunk")
        // Even where git would push to the upstream: these remotes are not the upstream's.
        git("config", "push.default", "upstream")
        git("config", "remote.pushDefault", "extra")
        assertEquals(GitPushTarget("extra", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))

        git("config", "branch.main.pushRemote", "fork")
        assertEquals(GitPushTarget("fork", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `a push remote that is the upstream's remote takes the upstream's branch with push_default upstream`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/trunk")
        git("config", "branch.main.pushRemote", "origin")
        git("config", "push.default", "upstream")

        assertEquals(GitPushTarget("origin", "trunk", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `a local upstream is not a remote`() {
        git("config", "branch.feature.remote", ".")
        git("config", "branch.feature.merge", "refs/heads/main")

        assertEquals(GitPushTarget("origin", "feature", hasUpstream = true), pushes.pushTarget(repo, "feature"))
    }

    @Test
    fun `without an upstream the only remote, else origin, else the first remote`() {
        assertEquals(GitPushTarget("origin", "topic"), pushes.pushTarget(repo, "topic"))

        git("remote", "rename", "origin", "alpha")
        assertEquals(GitPushTarget("alpha", "topic"), pushes.pushTarget(repo, "topic"))

        git("remote", "add", "beta", folder.resolve("beta").toString())
        assertEquals(GitPushTarget("alpha", "topic"), pushes.pushTarget(repo, "topic"), "the first")

        git("remote", "add", "origin", folder.resolve("o").toString())
        assertEquals(GitPushTarget("origin", "topic"), pushes.pushTarget(repo, "topic"), "origin among several")
    }

    /** The real git behind a wrapper that fails (exit 128; 1 would mean "not set" for config) calls with [failing]. */
    private fun wrapped(failing: String) = GitPush(failingGit(folder, failing.trim()))

    @Test
    fun `there is no target when git fails to read the remotes or a setting`() {
        assertNotNull(pushes.pushTarget(repo, "main"))

        assertNull(wrapped(" remote ").pushTarget(repo, "main"), "remote names")
        for (key in listOf("pushRemote", "remote", "merge")) {
            assertNull(wrapped("branch.main.$key").pushTarget(repo, "main"), key)
        }
        assertNull(wrapped("remote.pushDefault").pushTarget(repo, "main"), "remote.pushDefault")
        assertNull(wrapped("push.default").pushTarget(repo, "main"), "push.default")
    }

    @Test
    fun `nothing is outgoing when the fetch refspecs of the remote cannot be read`() {
        commit("Second")
        assertNotNull(pushes.outgoing(repo, "origin", "main"))

        assertNull(wrapped("remote.origin.fetch").outgoing(repo, "origin", "main"))
    }

    @Test
    fun `there is no target without a remote`() {
        git("remote", "remove", "origin")

        assertNull(pushes.pushTarget(repo, "main"))
    }

    // trackingRef

    @Test
    fun `the tracking ref follows the fetch refspec, the default one without any`() {
        assertEquals("refs/remotes/origin/main", pushes.trackingRef(repo, "origin", "main"))
        assertEquals("refs/remotes/origin/feature/x", pushes.trackingRef(repo, "origin", "feature/x"))

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/*:refs/remotes/mirror/*")
        assertEquals("refs/remotes/mirror/feature/x", pushes.trackingRef(repo, "origin", "feature/x"))

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/trunk")
        assertEquals("refs/remotes/origin/trunk", pushes.trackingRef(repo, "origin", "main"))
        assertEquals("refs/remotes/origin/other", pushes.trackingRef(repo, "origin", "other"), "unmapped: fallback")

        git("config", "--unset-all", "remote.origin.fetch")
        assertEquals("refs/remotes/origin/main", pushes.trackingRef(repo, "origin", "main"))
    }

    // outgoing

    @Test
    fun `a tracked branch ahead by two`() {
        val first = rev("HEAD")
        val second = commit("Second")
        val third = commit("Third")

        val outgoing = pushes.outgoing(repo, "origin", "main")

        assertEquals(listOf(third, second), outgoing?.commits?.map { it.hash })
        assertEquals(listOf("Third", "Second"), outgoing?.commits?.map { it.subject })
        assertEquals(GitOutgoing(outgoing!!.commits, false, first, 0, first), outgoing)
    }

    @Test
    fun `a branch the remote does not have holds the commits of no remote branch`() {
        val first = rev("HEAD")
        git("switch", "-q", "-c", "topic")
        val second = commit("Second")
        val third = commit("Third")

        val outgoing = pushes.outgoing(repo, "origin", "topic")

        assertEquals(listOf(third, second), outgoing?.commits?.map { it.hash })
        assertNull(outgoing?.remoteCommit)
        assertEquals(0, outgoing?.behind)
        assertEquals(first, outgoing?.base, "the first parent of the oldest commit")
    }

    @Test
    fun `a new branch with a root commit has no base`() {
        git("switch", "-q", "--orphan", "fresh")
        commit("Root")

        val outgoing = pushes.outgoing(repo, "origin", "fresh")

        assertEquals(1, outgoing?.commits?.size)
        assertNull(outgoing?.base)
    }

    @Test
    fun `nothing outgoing when the branch is where the remote has it`() {
        val outgoing = pushes.outgoing(repo, "origin", "main")

        assertEquals(GitOutgoing(emptyList(), false, rev("HEAD"), 0, null), outgoing)
    }

    @Test
    fun `behind and diverged counts, and the merge base`() {
        val first = rev("HEAD")
        moveRemote("Remote 2")
        moveRemote("Remote 3")
        git("fetch", "-q")
        assertEquals(
            GitOutgoing(emptyList(), false, rev("origin/main"), 2, null),
            pushes.outgoing(repo, "origin", "main"),
        )

        val local = commit("Local")
        val diverged = pushes.outgoing(repo, "origin", "main")

        assertEquals(listOf(local), diverged?.commits?.map { it.hash })
        assertEquals(2, diverged?.behind)
        assertEquals(first, diverged?.base)
    }

    @Test
    fun `more than the limit says so and the new branch's base is still the first parent of the oldest`() {
        val first = rev("HEAD")
        git("switch", "-q", "-c", "topic")
        repeat(5) { commit("Commit $it") }

        val limited = pushes.outgoing(repo, "origin", "topic", limit = 3)

        assertEquals(listOf("Commit 4", "Commit 3", "Commit 2"), limited?.commits?.map { it.subject })
        assertEquals(true, limited?.more)
        assertEquals(first, limited?.base)
        assertEquals(false, pushes.outgoing(repo, "origin", "topic", limit = 5)?.more)

        git("push", "-q", "origin", "topic")
        for (i in 0 until 4) commit("More $i")
        val tracked = pushes.outgoing(repo, "origin", "topic", limit = 3)
        assertEquals(true, tracked?.more)
        assertEquals(rev("origin/topic"), tracked?.base)
    }

    @Test
    fun `nothing is outgoing for a repository without commits`() {
        val empty = folder.resolve("empty")
        empty.createDirectories()
        git("init", "-q", "-b", "main", directory = empty)
        git("remote", "add", "origin", server.toString(), directory = empty)

        assertNull(pushes.outgoing(empty, "origin", "main"))
    }

    @Test
    fun `a new branch is found among 600 remote branches, with a custom refspec too`() {
        val head = rev("HEAD")
        val names = (1..600).joinToString("") { "$head refs/heads/a-rather-long-branch-name-number-$it\n" }
        server.resolve("packed-refs").writeText("# pack-refs with: peeled fully-peeled sorted\n$names")
        git("fetch", "-q")
        git("switch", "-q", "-c", "topic")
        val second = commit("Second")
        val third = commit("Third")

        val outgoing = pushes.outgoing(repo, "origin", "topic", limit = 1)

        assertEquals(listOf(third), outgoing?.commits?.map { it.hash })
        assertEquals(true, outgoing?.more)
        assertEquals(head, outgoing?.base, "the first parent of the oldest outgoing commit")
        assertEquals(listOf(third, second), pushes.outgoing(repo, "origin", "topic")?.commits?.map { it.hash })

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/*:refs/remotes/mirror/*")
        git("fetch", "-q")
        val mirrored = pushes.outgoing(repo, "origin", "topic")
        assertEquals(listOf(third, second), mirrored?.commits?.map { it.hash })
        assertEquals(head, mirrored?.base)
    }
    // remoteBranch

    @Test
    fun `the remote branch maps a tracking ref back through the fetch refspecs`() {
        assertEquals("refs/heads/main", pushes.remoteBranch(repo, "origin", "refs/remotes/origin/main"))
        assertEquals("refs/heads/feature/x", pushes.remoteBranch(repo, "origin", "refs/remotes/origin/feature/x"))
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/upstream/main"), "another remote's")

        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/trunk")
        assertEquals("refs/heads/main", pushes.remoteBranch(repo, "origin", "refs/remotes/origin/trunk"))
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/origin/main"), "no refspec maps it")

        git("config", "--add", "remote.origin.fetch", "+refs/tags/*:refs/remotes/origin/tags/*")
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/origin/tags/v1"), "no branch")

        git("config", "--unset-all", "remote.origin.fetch")
        assertNull(pushes.remoteBranch(repo, "origin", "refs/remotes/origin/main"), "without refspecs")
        assertNull(pushes.remoteBranch(repo, "-x", "refs/remotes/-x/main"))
    }

    @Test
    fun `a remote named like an option has no branches even with a refspec, and unreadable refspecs are no answer`() {
        git("config", "remote.-x.fetch", "+refs/heads/*:refs/remotes/-x/*")

        assertNull(pushes.remoteBranch(repo, "-x", "refs/remotes/-x/main"))
        assertEquals("refs/heads/main", pushes.remoteBranch(repo, "origin", "refs/remotes/origin/main"))
        assertNull(wrapped("remote.origin.fetch").remoteBranch(repo, "origin", "refs/remotes/origin/main"))
    }

    @Test
    fun `an upstream that is no branch is not pushed to, the branch's own name is`() {
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "trunk")
        git("config", "push.default", "upstream")

        assertEquals(GitPushTarget("origin", "main", hasUpstream = true), pushes.pushTarget(repo, "main"))
    }

    @Test
    fun `refspecs that are negative, have no destination or do not fit leave the default tracking ref`() {
        fun tracking(
            branch: String,
            vararg refspecs: String,
        ): String {
            git("config", "--unset-all", "remote.origin.fetch")
            for (refspec in refspecs) git("config", "--add", "remote.origin.fetch", refspec)
            return pushes.trackingRef(repo, "origin", branch)
        }
        val fallback = "refs/remotes/origin/main"

        assertEquals(fallback, tracking("main", "refs/heads/main"), "no destination")
        assertEquals(fallback, tracking("main", "^refs/heads/main:refs/remotes/origin/x"), "negative")
        assertEquals(fallback, tracking("main", "refs/heads/*:refs/remotes/origin/fixed"), "a star on one side only")
        assertEquals(fallback, tracking("main", "refs/heads/*-x:refs/remotes/origin/*"), "the suffix does not match")
        assertEquals("refs/remotes/o/n", tracking("main", "refs/heads/mai*:refs/remotes/o/*"))
        // The prefix and the suffix overlap in the source: too short for both.
        assertEquals("refs/remotes/origin/a", tracking("a", "refs/heads/a*a:refs/remotes/m/*"))
        assertEquals("refs/remotes/m/", tracking("aa", "refs/heads/a*a:refs/remotes/m/*"))
    }

    @Test
    fun `a refspec without destination maps no tracking ref back to a branch`() {
        git("config", "--unset-all", "remote.origin.fetch")
        git("config", "remote.origin.fetch", "refs/heads/main")

        assertNull(pushes.remoteBranch(repo, "origin", "refs/heads/main"))
    }

    @Test
    fun `the limit of outgoing commits must be positive`() {
        assertFailsWith<IllegalArgumentException> { pushes.outgoing(repo, "origin", "main", limit = 0) }
    }

    @Test
    fun `names git cannot take are nothing outgoing`() {
        commit("Second")

        assertNull(pushes.outgoing(repo, "-x", "main"))
        assertNull(pushes.outgoing(repo, "", "main"))
        assertNull(pushes.outgoing(repo, "origin", "-x"))
        assertNull(pushes.outgoing(repo, "origin", ""))
        assertNotNull(pushes.outgoing(repo, "origin", "main"))
    }

    @Test
    fun `a tracked branch sends what its tracking ref lacks, whatever other remote branches have`() {
        val second = commit("Second")
        git("update-ref", "refs/remotes/origin/feature", second)

        val outgoing = pushes.outgoing(repo, "origin", "main")

        assertEquals(listOf(second), outgoing?.commits?.map { it.hash })
    }

    @Test
    fun `the base of a complete list comes from it and of a list cut short or without remote branches from git`() {
        val first = rev("HEAD")
        git("switch", "-q", "-c", "topic")
        commit("Second")
        commit("Third")
        // rev-list is what reads the oldest commit from git: a complete list must not need it.
        assertEquals(first, wrapped(" rev-list ").outgoing(repo, "origin", "topic")?.base)
        assertNull(wrapped(" rev-list ").outgoing(repo, "origin", "topic", limit = 1), "a cut list asks git")
        assertNull(wrapped(" for-each-ref ").outgoing(repo, "origin", "topic"), "refs unreadable")

        // No remote-tracking ref at all: everything is outgoing, down to a root, without asking git.
        git("update-ref", "-d", "refs/remotes/origin/main")
        val all = wrapped(" rev-list ").outgoing(repo, "origin", "topic", limit = 1)
        assertEquals(true, all?.more)
        assertNull(all?.base)
    }

    @Test
    fun `a symbolic ref in place of the tracking ref is no tracked branch and excludes nothing`() {
        val second = commit("Second")
        git("config", "--replace-all", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/main")
        git("symbolic-ref", "refs/remotes/origin/feature", "refs/heads/main")

        val outgoing = pushes.outgoing(repo, "origin", "feature")

        assertNull(outgoing?.remoteCommit)
        assertEquals(listOf(second), outgoing?.commits?.map { it.hash })
    }

    /** Commits a file named after the [subject] at the committer date [epoch] (seconds), which date order goes by. */
    private fun commitAt(
        subject: String,
        epoch: Int,
    ) {
        repo.resolve("$subject.txt").writeText("$subject\n")
        git("add", "$subject.txt")
        val process =
            ProcessBuilder("git", "commit", "-q", "-m", subject)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                    environment()["GIT_COMMITTER_DATE"] = "$epoch +0000"
                    environment()["GIT_AUTHOR_DATE"] = "$epoch +0000"
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), output)
    }

    @Test
    fun `outgoing commits come in topological order, not by date`() {
        git("switch", "-q", "-c", "topic")
        git("switch", "-q", "-c", "side")
        commitAt("a1", 1_000_000_100)
        commitAt("a2", 1_000_000_300)
        git("switch", "-q", "topic")
        commitAt("b1", 1_000_000_200)
        commitAt("b2", 1_000_000_400)
        git("merge", "-q", "--no-ff", "-m", "Merge", "side")

        val subjects = pushes.outgoing(repo, "origin", "topic")?.commits?.map { it.subject }

        // Date order would mix the two lines: b2, a2, b1, a1.
        assertEquals(listOf("Merge", "a2", "a1", "b2", "b1"), subjects)
    }

    @Test
    fun `nothing is outgoing when git fails to list the commits or to count how far behind`() {
        commit("Second")
        assertNotNull(pushes.outgoing(repo, "origin", "main"))

        assertNull(wrapped("--topo-order").outgoing(repo, "origin", "main"), "the commits")
        assertNull(wrapped("--left-right").outgoing(repo, "origin", "main"), "the count")
    }
}
