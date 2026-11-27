package dev.sluide.gitlog.data

import dev.sluide.git.GitBranches
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitCommits
import dev.sluide.git.GitMerges
import dev.sluide.git.GitPulls
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRemoteDeletion
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitRewords
import dev.sluide.git.GitStashes
import dev.sluide.git.GitTagPushOutcome
import dev.sluide.git.cli.GitCli
import dev.sluide.git.ssh.SshAccess
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The remote calls of the real [GitLogCliWrites] (fetch, push, deletion, tag push) against a local bare repository:
 * each pass-through reaches the git module's call and the remote. `SshAccess.Batch` is also the git module's default,
 * so this does not show that the access is handed on. Needs git, no display.
 */
class GitLogCliWritesRemoteTest {
    @TempDir
    lateinit var folder: Path

    private val environment =
        System.getenv().filterKeys { !it.startsWith("GIT_") } +
            mapOf(
                "GIT_CONFIG_GLOBAL" to "/dev/null",
                "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_AUTHOR_NAME" to "Test",
                "GIT_AUTHOR_EMAIL" to "test@example.com",
                "GIT_COMMITTER_NAME" to "Test",
                "GIT_COMMITTER_EMAIL" to "test@example.com",
            )

    private fun git(
        dir: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + args)
                .directory(dir.toFile())
                .also { it.environment().apply { clear() }.putAll(environment) }
                .redirectErrorStream(true)
                .start()
        val out =
            process.inputStream
                .readAllBytes()
                .decodeToString()
                .trim()
        check(process.waitFor() == 0) { "git ${args.toList()}: $out" }
        return out
    }

    private fun writes(): GitLogCliWrites {
        val git = GitCli("git", environment)
        val rebases = GitRebases(git)
        return GitLogCliWrites(
            GitBranches(git),
            GitCommits(git),
            rebases,
            GitMerges(git),
            GitPulls(git, rebases),
            GitRewords(git),
            GitRemotes(git),
            GitPush(git),
            GitStashes(git),
        )
    }

    @Test
    fun `fetch, push, tag push and deletion reach the remote through the writes`() {
        val local = folder.resolve("local").createDirectories()
        val bare = folder.resolve("bare.git").createDirectories()
        git(bare, "init", "-q", "--bare", "-b", "main")
        git(local, "init", "-q", "-b", "main")
        local.resolve("a.txt").writeText("a")
        git(local, "add", "a.txt")
        git(local, "commit", "-q", "-m", "first")
        git(local, "remote", "add", "origin", bare.toString())
        val writes = writes()
        val cancellation = GitCancellation()
        val ssh = SshAccess.Batch

        val pushed = writes.push(local, "origin", "main", "main", false, null, true, cancellation, ssh)
        assertTrue(pushed.result.success, pushed.result.message)
        assertEquals("refs/heads/main", git(bare, "for-each-ref", "--format=%(refname)", "refs/heads"))

        git(local, "tag", "v1")
        val tags = writes.pushTags(local, "origin", listOf("v1"), cancellation, ssh)
        assertTrue(tags.result.success, tags.result.message)
        assertEquals(GitTagPushOutcome.PUSHED, tags.outcomes["v1"]?.outcome, "by short tag name")
        assertEquals("refs/tags/v1", git(bare, "for-each-ref", "--format=%(refname)", "refs/tags"))

        val deleted =
            writes.deleteRemoteRefs(
                local,
                "origin",
                listOf(GitRemoteDeletion("refs/tags/v1")),
                cancellation,
                ssh,
            )
        assertTrue(deleted.result.success, deleted.result.message)
        assertEquals("", git(bare, "for-each-ref", "--format=%(refname)", "refs/tags"))

        val fetched = writes.fetch(local, "origin", cancellation, ssh)
        assertTrue(fetched.result.success, fetched.result.message)
    }
}
