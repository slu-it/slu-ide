package dev.sluide.git

import dev.sluide.git.cli.GitCli
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GitCommits] with a git that fails one command (`failingGit`) or records its calls: the answer of each read that
 * can fail, the names git is not run for, and what each result says. Real git; skipped without it.
 */
internal class GitCommitsGapsTest : GitCommitsFixture() {
    private val callLog get() = folder.resolve("calls.log")

    private fun failing(
        vararg words: String,
        after: Int = 0,
    ) = GitCommits(failingGit(folder, *words, after = after))

    /** A git that fails only the [nth] (1-based) call with [word] among its arguments, as git fails (exit 128). */
    private fun failingNth(
        word: String,
        nth: Int,
    ): GitCommits {
        val id = System.nanoTime()
        val counter = folder.resolve("nth-$id.count")
        val script = folder.resolve("nth-git-$id")
        val bump = "n=\$(cat '$counter' 2>/dev/null || echo 0); n=\$((n+1)); echo \$n > '$counter';"
        script.writeText(
            FAKE_GIT_START + "case \" \$* \" in *' $word '*) $bump " +
                "[ \$n -eq $nth ] && { echo 'fatal: refused by test' >&2; exit 128; };; esac\nexec git \"\$@\"\n",
        )
        script.toFile().setExecutable(true)
        return GitCommits(GitCli(script.toString(), testGitEnvironment()))
    }

    /** The real git behind a wrapper that writes each call's arguments as a line to [callLog]. */
    private fun recording(): GitCommits {
        val wrapper = folder.resolve("git-recorder")
        // The calls GitCli makes on its own (the version, the environment variables, a hook setting) are left out.
        wrapper.writeText(
            FAKE_GIT_START.replace("exit 1;; esac", "exec git \"\$@\";; esac") +
                "echo \"\$*\" >> '$callLog'\nexec git \"\$@\"\n",
        )
        wrapper.toFile().setExecutable(true)
        return GitCommits(GitCli(wrapper.toString(), testGitEnvironment()))
    }

    private fun calls() = if (callLog.exists()) callLog.readText().lines().filter(String::isNotEmpty) else emptyList()

    private fun modified(vararg paths: String) = paths.map { GitChangedFile(GitChange.MODIFIED, it) }

    private fun commitFile(
        subject: String,
        path: String,
    ): String {
        repo.resolve(path).parent.createDirectories()
        return commit(subject, path)
    }

    // --- commitChanges

    @Test
    fun `what can be committed is null when one of the reads fails`() {
        commit("Base")
        repo.resolve("new.txt").writeText("new\n")

        assertNotNull(commits.commitChanges(repo))
        assertNull(failing("rev-parse").commitChanges(repo), "is HEAD unborn")
        // Only `rev-parse --verify -q` reads the commit of HEAD; the other reads would fail on their own.
        assertNull(failing("--verify -q").commitChanges(repo), "the commit of HEAD")
        assertNull(failing("diff").commitChanges(repo), "the tracked changes")
        assertNull(failing("--others").commitChanges(repo), "the untracked files")
        assertNull(failing("--git-path").commitChanges(repo), "what is in progress")
        assertNull(failing("--git-path").inProgress(repo))
    }

    @Test
    fun `a branch without a commit that has an upstream is not pushed`() {
        val base = commit("Base")
        git("update-ref", "refs/remotes/origin/main", base)
        git("config", "remote.origin.url", "/nowhere")
        git("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*")
        git("switch", "-q", "--orphan", "fresh")
        git("config", "branch.fresh.remote", "origin")
        git("config", "branch.fresh.merge", "refs/heads/main")

        val changes = assertNotNull(commits.commitChanges(repo))

        assertFalse(changes.headPushed)
    }

    @Test
    fun `a detached HEAD is not pushed`() {
        val base = commit("Base")
        git("update-ref", "refs/remotes/origin/main", base)
        git("config", "remote.origin.url", "/nowhere")
        git("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*")
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/main")
        assertTrue(assertNotNull(commits.commitChanges(repo)).headPushed)

        git("switch", "-q", "--detach")

        assertFalse(assertNotNull(commits.commitChanges(repo)).headPushed)
    }

    @Test
    fun `without a commit every indexed file is added, and nothing is pushed`() {
        repo.resolve("a.txt").writeText("a\n")
        git("add", "a.txt")

        val changes = assertNotNull(commits.commitChanges(repo))

        assertEquals(listOf(GitChangedFile(GitChange.ADDED, "a.txt")), changes.files)
        assertFalse(changes.headPushed)
        assertNull(failingNth("ls-files", 1).commitChanges(repo), "the indexed files")
        assertNull(failing("--others").commitChanges(repo), "the untracked files")
    }

    // --- commit

    @Test
    fun `an amend without a commit is refused with its reason, before git commits`() {
        repo.resolve("a.txt").writeText("a\n")
        val recorded = recording()

        val result = recorded.commit(repo, "Message", modified("a.txt"), emptyList(), amend = true)

        assertEquals(GitCommitResult(GitResult(false, "There is no commit to amend."), null), result)
        assertTrue(calls().none { " commit " in " $it " }, calls().toString())
    }

    @Test
    fun `a commit says why it did nothing when HEAD or what is in progress cannot be read`() {
        commit("Base")
        repo.resolve("file.txt").writeText("changed\n")

        val head = failing("rev-parse").commit(repo, "Message", modified("file.txt"), emptyList(), false)
        val busy = failing("--git-path").commit(repo, "Message", modified("file.txt"), emptyList(), false)

        assertEquals(GitCommitResult(GitResult(false, "HEAD could not be read."), null), head)
        assertEquals(GitCommitResult(GitResult(false, IN_PROGRESS_UNREADABLE), null), busy)
    }

    @Test
    fun `untracked files that cannot be staged stop the commit`() {
        val base = commit("Base")
        repo.resolve("new.txt").writeText("new\n")

        val result = failing("add").commit(repo, "Message", emptyList(), listOf("new.txt"), false)

        assertEquals(GitCommitResult(GitResult(false, "fatal: refused by test"), null), result)
        assertEquals(base, head())
    }

    @Test
    fun `untracked files are staged only when there are some, and the list of paths is deleted`() {
        commit("Base")
        repo.resolve("file.txt").writeText("changed\n")
        repo.resolve("new.txt").writeText("new\n")
        val recorded = recording()

        assertTrue(recorded.commit(repo, "Tracked", modified("file.txt"), emptyList(), false).result.success)
        assertTrue(calls().none { " add " in " $it " }, "nothing to stage: ${calls()}")

        assertTrue(recorded.commit(repo, "Both", emptyList(), listOf("new.txt"), false).result.success)

        assertEquals(1, calls().count { " add " in " $it " }, calls().toString())
        val lists = calls().mapNotNull { Regex("--pathspec-from-file=(\\S+)").find(it)?.groupValues?.get(1) }
        assertEquals(3, lists.size, "one for the staging, one for each commit: $lists")
        assertTrue(lists.none { Path.of(it).exists() }, "the temporary lists are deleted: $lists")
    }

    @Test
    fun `an amend of only the message passes no paths to git`() {
        commit("Base")
        val recorded = recording()

        val result = recorded.commit(repo, "Reworded", emptyList(), emptyList(), amend = true)

        assertTrue(result.result.success, result.result.message)
        val call = calls().single { " commit " in " $it " }
        assertFalse("--pathspec-from-file" in call, call)
        assertTrue("--amend" in call, call)
    }

    // --- pick, continue, abort

    @Test
    fun `a pick of a name git cannot take fails with the reason and runs no git`() {
        commit("Base")
        val recorded = recording()

        assertEquals(
            GitPickResult.Failed("'-x' cannot be passed to git: it starts with '-'."),
            recorded.pick(repo, GitPick.CHERRY_PICK, "-x", firstParent = false),
        )
        assertEquals(emptyList(), calls())
    }

    @Test
    fun `a cherry-pick and a revert pass their options and the commit after end-of-options`() {
        commit("Base")
        val added = commit("Add", "added.txt", "added")
        val recorded = recording()

        val reverted = recorded.pick(repo, GitPick.REVERT, added, firstParent = false)
        assertEquals(GitPickResult.Done(head()), reverted)
        assertEquals(
            listOf("revert --no-edit --end-of-options $added"),
            calls().filter { it.startsWith("revert") },
        )
        val revert = head()
        assertIs<GitPickResult.Done>(recorded.pick(repo, GitPick.REVERT, revert, firstParent = false))
        git("reset", "-q", "--hard", added)
        assertIs<GitPickResult.Done>(recorded.pick(repo, GitPick.CHERRY_PICK, revert, firstParent = false))
        assertEquals(
            listOf("cherry-pick --end-of-options $revert"),
            calls().filter { it.startsWith("cherry-pick") },
        )
    }

    @Test
    fun `picking, continuing and aborting say when what is in progress cannot be read`() {
        commit("Base")
        val failing = failing("--git-path")

        assertEquals(
            GitPickResult.Failed(IN_PROGRESS_UNREADABLE),
            failing.pick(repo, GitPick.CHERRY_PICK, head(), firstParent = false),
        )
        assertEquals(GitPickResult.Failed(IN_PROGRESS_UNREADABLE), failing.continuePick(repo, GitPick.REVERT))
        assertEquals(GitResult(false, IN_PROGRESS_UNREADABLE), failing.abortPick(repo, GitPick.REVERT))
    }

    @Test
    fun `continuing or aborting a pick of the other kind, or none, says which is not in progress`() {
        val theirs = conflicting()
        assertEquals(
            GitPickResult.Failed("No cherry-pick is in progress."),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
        assertEquals(GitResult(false, "No revert is in progress."), commits.abortPick(repo, GitPick.REVERT))

        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertEquals(GitPickResult.Failed("No revert is in progress."), commits.continuePick(repo, GitPick.REVERT))
        assertEquals(GitResult(false, "No revert is in progress."), commits.abortPick(repo, GitPick.REVERT))
        assertIs<GitPendingPick>(commits.inProgress(repo), "left as it was")
        assertTrue(commits.abortPick(repo, GitPick.CHERRY_PICK).success)
    }

    @Test
    fun `continuing a pick stops where the conflicts cannot be read, checked or staged`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        repo.resolve("file.txt").writeText("resolved\n")

        assertEquals(
            GitPickResult.Failed(CONFLICTS_UNREADABLE),
            failing("ls-files", after = 1).continuePick(repo, GitPick.CHERRY_PICK),
            "the unmerged entries",
        )
        assertEquals(
            GitPickResult.Failed(CONFLICTS_UNREADABLE),
            failing("check-attr").continuePick(repo, GitPick.CHERRY_PICK),
            "the attributes",
        )
        val staging = failing("update-index").continuePick(repo, GitPick.CHERRY_PICK)
        assertEquals(GitPickResult.Failed("fatal: refused by test"), staging)
        assertIs<GitPendingPick>(commits.inProgress(repo), "nothing was committed")
    }

    @Test
    fun `continuing asks the commits' own can-name-file, so a file the JVM cannot name goes through git`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        repo.resolve("file.txt").writeText("resolved\n")
        val unnamed = failing("hash-object")
        unnamed.canNameFile = { false }

        assertEquals(GitPickResult.Failed(CONFLICTS_UNREADABLE), unnamed.continuePick(repo, GitPick.CHERRY_PICK))
        assertIs<GitPickResult.Done>(commits.continuePick(repo, GitPick.CHERRY_PICK))
    }

    // --- what a pick leaves

    @Test
    fun `a commit that does not exist is a failure, not an empty pick, for a cherry-pick and a revert`() {
        commit("Base")
        val missing = "0".repeat(40)

        val picked = commits.pick(repo, GitPick.CHERRY_PICK, missing, firstParent = false)
        val reverted = commits.pick(repo, GitPick.REVERT, missing, firstParent = false)

        assertIs<GitPickResult.Failed>(picked)
        assertIs<GitPickResult.Failed>(reverted)
    }

    @Test
    fun `a pick stopped by a hook without conflicts is stopped with no conflicts`() {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val added = commit("Add", "added.txt", "added")
        git("switch", "-q", "main")
        // A commit that cannot be signed stops the pick after it staged the changes.
        git("config", "commit.gpgSign", "true")
        git("config", "gpg.program", "false")

        val result = commits.pick(repo, GitPick.CHERRY_PICK, added, firstParent = false)

        assertIs<GitPickResult.Stopped>(result)
        assertEquals(emptyList(), result.conflicts)
        assertTrue(result.message.isNotBlank(), "git says why")
        assertEquals(listOf("A  added.txt"), status())
        assertIs<GitPendingPick>(commits.inProgress(repo))
    }

    @Test
    fun `a cherry-pick with nothing to commit that cannot be skipped is a failure`() {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val picked = commit("Change", "kept.txt", "kept")
        git("switch", "-q", "main")
        git("merge", "-q", "--ff-only", "other")

        val result = failing("--skip").pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)

        assertEquals(GitPickResult.Failed("fatal: refused by test"), result)
    }

    @Test
    fun `a revert with nothing to commit that cannot be cleared is a failure, with what went wrong`() {
        commit("Base")
        val added = commit("Add", "added.txt", "added")
        assertIs<GitPickResult.Done>(commits.pick(repo, GitPick.REVERT, added, firstParent = false))

        assertEquals(
            GitPickResult.Failed("fatal: refused by test"),
            failing("update-ref").pick(repo, GitPick.REVERT, added, firstParent = false),
        )
        assertEquals(
            GitPickResult.Failed(IN_PROGRESS_UNREADABLE),
            // The reads before it (the pick's and the outcome's) work, the one for MERGE_MSG's path fails.
            failing("--git-path", after = 2).pick(repo, GitPick.REVERT, added, firstParent = false),
        )
    }

    @Test
    fun `a revert whose message file cannot be deleted says so`() {
        commit("Base")
        val added = commit("Add", "added.txt", "added")
        assertIs<GitPickResult.Done>(commits.pick(repo, GitPick.REVERT, added, firstParent = false))
        // After a revert, MERGE_MSG becomes a folder with a file in it, which cannot be deleted as a file.
        val wrapper = folder.resolve("git-folder-message")
        wrapper.writeText(
            FAKE_GIT_START +
                "git \"\$@\"; code=\$?\ncase \" \$* \" in *' revert '*) m=.git/MERGE_MSG; rm -rf \$m; " +
                "mkdir \$m; echo x > \$m/x;; esac\nexit \$code\n",
        )
        wrapper.toFile().setExecutable(true)
        val stuck = GitCommits(GitCli(wrapper.toString(), testGitEnvironment()))

        val result = stuck.pick(repo, GitPick.REVERT, added, firstParent = false)

        assertIs<GitPickResult.Failed>(result)
        assertTrue(" could not be deleted: " in result.message, result.message)
        val message = repo.resolve(".git/MERGE_MSG").toString()
        assertTrue(result.message.startsWith(message), result.message)
        assertTrue(result.message.endsWith(": $message"), "what the file system said: ${result.message}")
    }

    @Test
    fun `a pick stopped without conflicts is continued without looking at conflicts`() {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val added = commit("Add", "added.txt", "added")
        git("switch", "-q", "main")
        git("config", "commit.gpgSign", "true")
        git("config", "gpg.program", "false")
        assertIs<GitPickResult.Stopped>(commits.pick(repo, GitPick.CHERRY_PICK, added, firstParent = false))
        git("config", "commit.gpgSign", "false")
        val noConflicts = failing("--deleted", "check-attr", "update-index", "hash-object")

        val result = noConflicts.continuePick(repo, GitPick.CHERRY_PICK)

        assertEquals(GitPickResult.Done(head()), result)
        assertEquals("Add", git("log", "-1", "--format=%s"))
    }

    private fun appliedAlready(): String {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val picked = commit("Change", "kept.txt", "kept")
        git("switch", "-q", "main")
        git("merge", "-q", "--ff-only", "other")
        return picked
    }

    @Test
    fun `a pick whose state cannot be read afterwards is no stop and no empty pick`() {
        val picked = appliedAlready()

        // The first read of what is in progress is the pick's own, the second the outcome's.
        val empty = failing("--git-path", after = 1).pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)
        assertIs<GitPickResult.Failed>(empty)
        git("cherry-pick", "--quit")
    }

    @Test
    fun `a conflict whose state cannot be read afterwards is a failure`() {
        val theirs = conflicting()

        val conflict = failing("--git-path", after = 1).pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertIs<GitPickResult.Failed>(conflict)
    }

    @Test
    fun `unreadable unmerged paths leave a pick stopped without conflicts and a revert not cleared`() {
        val picked = appliedAlready()
        // Reads of ls-files: the pick's, the outcome's, then the unmerged paths, which fail.
        val cherry = failing("ls-files", after = 2).pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)
        assertIs<GitPickResult.Stopped>(cherry)
        assertEquals(emptyList(), cherry.conflicts)
        git("cherry-pick", "--quit")

        val added = commit("Add", "added.txt", "added")
        assertIs<GitPickResult.Done>(commits.pick(repo, GitPick.REVERT, added, firstParent = false))
        val revert = failing("ls-files", after = 2).pick(repo, GitPick.REVERT, added, firstParent = false)
        assertIs<GitPickResult.Failed>(revert)
    }

    @Test
    fun `names git cannot take give no answer for what a reset or pick reads, without running git`() {
        commit("Base")
        val recorded = recording()

        assertNull(recorded.unreachableCount(repo, "-x"))
        assertNull(recorded.overwrittenFiles(repo, "-x"))
        assertNull(recorded.pickOverwrites(repo, GitPick.CHERRY_PICK, "-x", firstParent = false))
        assertNull(recorded.pickOverwrites(repo, GitPick.REVERT, "", firstParent = false))

        assertEquals(emptyList(), calls())
    }

    @Test
    fun `staged paths that cannot be read give no count even if the unstaged ones can`() {
        commit("Base")

        assertNull(failing("--cached").stagedOnlyCount(repo))
    }

    @Test
    fun `a pick that changes a tracked file is merged onto HEAD, not onto nothing`() {
        commit("Base", "kept.txt")
        git("switch", "-q", "-c", "other")
        repo.resolve("kept.txt").writeText("changed\n")
        repo.resolve(".env").writeText("FROM_COMMIT=1\n")
        git("add", "-f", "kept.txt", ".env")
        git("commit", "-q", "-m", "Change and add")
        val picked = head()
        git("switch", "-q", "main")
        repo.resolve(".gitignore").writeText(".env\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore .env")
        repo.resolve(".env").writeText("MY_SECRET=1\n")

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, picked, firstParent = false))
        assertNull(failing("merge-tree").pickOverwrites(repo, GitPick.CHERRY_PICK, picked, firstParent = false))
    }

    // --- reset and its reads

    @Test
    fun `a reset to a name git cannot take fails with the reason and runs no git`() {
        commit("Base")
        val recorded = recording()

        assertEquals(
            GitResult(false, "'-x' cannot be passed to git: it starts with '-'."),
            recorded.reset(repo, "-x", GitResetMode.HARD),
        )
        assertEquals(emptyList(), calls())
    }

    @Test
    fun `the commits a reset would leave are null for a target or branch git cannot take, or a failing git`() {
        val first = commit("First")
        commit("Second")

        assertNull(commits.unreachableCount(repo, "-x"))
        assertNull(failing("symbolic-ref").unreachableCount(repo, first))
        assertEquals(1, commits.unreachableCount(repo, first))
        // A branch whose name starts with a dash: it cannot be excluded by name.
        git("update-ref", "refs/heads/-odd", head())
        git("symbolic-ref", "HEAD", "refs/heads/-odd")
        assertNull(commits.unreachableCount(repo, first))
        git("switch", "-q", "--detach", "main")
        assertEquals(0, commits.unreachableCount(repo, head()))
    }

    @Test
    fun `counting files with staged changes that differ from the disk is null when git fails`() {
        commit("Base")

        assertEquals(0, commits.stagedOnlyCount(repo))
        assertNull(failing("diff").stagedOnlyCount(repo), "the staged paths")
        assertNull(failing("diff", after = 1).stagedOnlyCount(repo), "the unstaged paths")
    }

    @Test
    fun `a soft reset is refused during a merge without conflicts, and not otherwise`() {
        commit("Base")
        assertEquals(false, commits.softResetRefused(repo))
        assertNull(failing("ls-files").softResetRefused(repo))

        git("switch", "-q", "-c", "other")
        commit("Other", "other.txt")
        git("switch", "-q", "main")
        git("merge", "-q", "--no-commit", "--no-ff", "other")

        assertEquals(emptyList(), git("ls-files", "--unmerged").lines().filter(String::isNotEmpty))
        assertEquals(true, commits.softResetRefused(repo))
    }

    // --- overwrittenFiles

    @Test
    fun `what a hard reset would overwrite is null when git cannot say`() {
        commit("Base")
        val target = commit("Target", "t.txt")
        git("rm", "-q", "--cached", "t.txt")
        git("commit", "-q", "-m", "Untrack")
        repo.resolve("t.txt").writeText("mine\n")

        assertEquals(listOf("t.txt"), commits.overwrittenFiles(repo, target))
        assertNull(failing("diff-index").overwrittenFiles(repo, target))
        assertNull(failing("config").overwrittenFiles(repo, target), "core.ignorecase")
        val unnamed = failing("ls-files")
        unnamed.canNameFile = { false }
        assertNull(unnamed.overwrittenFiles(repo, target), "the files git lists, for a file")
    }

    @Test
    fun `a file the JVM cannot name is looked for through git, also its folders and the folder it replaces`() {
        commit("Base")
        val target = commitFile("Target", "dir/t.txt")
        git("rm", "-q", "--cached", "dir/t.txt")
        git("commit", "-q", "-m", "Untrack")
        val unnamed = failing("ls-files")
        unnamed.canNameFile = { false }
        repo.resolve("dir").createDirectories()

        assertNull(unnamed.overwrittenFiles(repo, target), "the folder dir")
        // git lists the files that are not in the index: the folder is there as soon as one of them is below it.
        repo.resolve("dir/t.txt").writeText("mine\n")
        val named = commits
        named.canNameFile = { false }
        assertEquals(listOf("dir/t.txt"), named.overwrittenFiles(repo, target))
        repo.resolve("dir/t.txt").toFile().delete()
        assertEquals(emptyList(), named.overwrittenFiles(repo, target), "an empty folder is no file")
    }

    @Test
    fun `a file that replaced a folder in the index is tracked, so the folder's files overwrite nothing`() {
        commit("Base")
        val target = commitFile("Target", "dir/f.txt")
        git("rm", "-q", "dir/f.txt")
        git("commit", "-q", "-m", "Remove")
        repo.resolve("dir").writeText("a file now\n")
        git("add", "dir")

        assertEquals(emptyList(), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `a file that replaced a folder in a conflicted index is tracked as well`() {
        commit("Base")
        val target = commitFile("Target", "dir/f.txt")
        git("rm", "-q", "dir/f.txt")
        git("commit", "-q", "-m", "Remove")
        git("switch", "-q", "-c", "other")
        commit("Other", "dir", "theirs")
        git("switch", "-q", "main")
        commit("Ours", "dir", "ours")
        assertTrue(runCatching { git("merge", "other") }.isFailure, "add/add conflict")
        assertEquals(listOf("dir"), git("ls-files", "--unmerged").lines().map { it.substringAfter('\t') }.distinct())

        assertEquals(emptyList(), commits.overwrittenFiles(repo, target))
    }

    @Test
    fun `the folder of a submodule is kept, the folder of a file is not`() {
        val base = commit("Base")
        git("update-index", "--add", "--cacheinfo", "160000,$base,sub")
        git("update-index", "--add", "--cacheinfo", "100644,${git("hash-object", "-w", "file.txt")},plain")
        git("commit", "-q", "-m", "Gitlink and file")
        val target = head()
        git("rm", "-q", "--cached", "sub", "plain")
        git("commit", "-q", "-m", "Untrack")
        repo.resolve("sub").createDirectories()
        repo.resolve("sub/x.txt").writeText("x\n")
        repo.resolve("plain").createDirectories()
        repo.resolve("plain/y.txt").writeText("y\n")

        assertEquals(listOf("plain/y.txt"), commits.overwrittenFiles(repo, target))
    }

    // --- pickOverwrites

    /** A commit on `other` that adds `.env`, which main ignores and has its own copy of. */
    private fun addingEnv(): String {
        commit("Base", "kept.txt")
        git("switch", "-q", "-c", "other")
        val added = commit("Add .env", ".env", "FROM_COMMIT=1")
        git("switch", "-q", "main")
        repo.resolve(".gitignore").writeText(".env\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore .env")
        repo.resolve(".env").writeText("MY_SECRET=1\n")
        return added
    }

    @Test
    fun `what a pick would overwrite is null when git cannot say`() {
        val added = addingEnv()

        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false))
        assertNull(failing("rev-list").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false), "parents")
        assertNull(failing("rev-parse").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false), "unborn")
        assertNull(
            failing("diff-index", after = 1).pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false),
            "what the merged tree would overwrite",
        )
        assertNull(
            failing("ls-files").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false),
            "the untracked files",
        )
    }

    @Test
    fun `nothing found needs no list of untracked files`() {
        val added = addingEnv()
        repo.resolve(".env").toFile().delete()

        val result = failing("ls-files").pickOverwrites(repo, GitPick.CHERRY_PICK, added, firstParent = false)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `a root commit and a branch without commits need the empty tree, which git must give`() {
        val root = commit("Root", ".env", "FROM_ROOT=1")
        val second = commit("Second", "second.txt")
        assertNull(failing("hash-object").pickOverwrites(repo, GitPick.CHERRY_PICK, root, firstParent = false))

        git("switch", "-q", "--orphan", "fresh")
        assertNull(failing("hash-object").pickOverwrites(repo, GitPick.CHERRY_PICK, second, firstParent = false))
        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, second, firstParent = false))
    }

    @Test
    fun `a merge commit is looked at only relative to its first parent`() {
        commit("Base", "kept.txt")
        git("switch", "-q", "-c", "other")
        repo.resolve(".env").writeText("FROM_MERGE=1\n")
        git("add", "-f", ".env")
        git("commit", "-q", "-m", "Add .env")
        git("switch", "-q", "-c", "feature", "main")
        commit("Feature", "feature.txt")
        git("merge", "-q", "--no-ff", "--no-edit", "other")
        val merge = head()
        git("switch", "-q", "main")
        repo.resolve(".gitignore").writeText(".env\n")
        git("add", ".gitignore")
        git("commit", "-q", "-m", "Ignore .env")
        repo.resolve(".env").writeText("MY_SECRET=1\n")

        assertEquals(emptyList(), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, merge, firstParent = false))
        assertEquals(listOf(".env"), commits.pickOverwrites(repo, GitPick.CHERRY_PICK, merge, firstParent = true))
    }
}
