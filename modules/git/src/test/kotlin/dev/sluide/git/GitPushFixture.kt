package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** Real git with a bare "remote" and two clones in temporary folders; skipped if git is not installed. */
internal abstract class GitPushFixture {
    @TempDir
    lateinit var folder: Path

    /** The remote `origin`: a bare repository. */
    protected val server get() = folder.resolve("server.git")

    /** The repository that pushes, a clone of [server]. */
    protected val repo get() = folder.resolve("repo")

    /** A second clone, to move the remote behind the back of [repo]. */
    protected val other: Path by lazy {
        val clone = folder.resolve("other")
        git("clone", "-q", server.toString(), clone.toString(), directory = folder)
        configure(clone)
        clone
    }

    protected val git = GitCli("git", testGitEnvironment())
    protected val pushes = GitPush(git)

    @BeforeEach
    fun createRepositories() {
        assumeTrue(git.available, "git is not installed")
        folder.createDirectories()
        git("init", "-q", "--bare", "-b", "main", server.toString(), directory = folder)
        git("clone", "-q", server.toString(), repo.toString(), directory = folder)
        configure(repo)
        commit("First")
        git("push", "-q", "origin", "main")
    }

    protected fun configure(directory: Path) {
        git("config", "user.name", "Test", directory = directory)
        git("config", "user.email", "test@example.com", directory = directory)
    }

    protected fun git(
        vararg arguments: String,
        directory: Path = repo,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    protected fun commit(
        subject: String,
        directory: Path = repo,
    ): String {
        directory.resolve("file.txt").writeText("$subject\n")
        git("add", "file.txt", directory = directory)
        git("commit", "-q", "-m", subject, directory = directory)
        return git("rev-parse", "HEAD", directory = directory)
    }

    protected fun rev(
        ref: String,
        directory: Path = repo,
    ) = git("rev-parse", ref, directory = directory)

    protected fun remoteTip(branch: String) = git("rev-parse", "refs/heads/$branch", directory = server)

    /** The remote moves on, by a commit of [other], which [repo] has not fetched. */
    protected fun moveRemote(subject: String): String {
        git("pull", "-q", "--ff-only", "origin", "main", directory = other)
        val moved = commit(subject, other)
        git("push", "-q", "origin", "main", directory = other)
        return moved
    }

    protected fun push(
        target: String = "main",
        branch: String = "main",
        force: Boolean = false,
        expected: String? = null,
        setUpstream: Boolean = false,
        cancellation: GitCancellation = GitCancellation(),
    ) = pushes.push(repo, "origin", branch, target, force, expected, setUpstream, cancellation)
}
