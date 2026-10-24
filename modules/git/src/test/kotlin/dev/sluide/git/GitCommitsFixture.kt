package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/** Runs the real git in a temporary repository; skipped if git is not installed. */
internal abstract class GitCommitsFixture {
    @TempDir
    lateinit var folder: Path

    protected val repo get() = folder.resolve("repo")

    protected val git = GitCli("git", testGitEnvironment())
    protected val commits = GitCommits(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    protected fun git(
        vararg arguments: String,
        input: String? = null,
        directory: Path = repo,
    ): String = gitRaw(*arguments, input = input, directory = directory).trim()

    /** The output of git with [arguments], untrimmed. */
    protected fun gitRaw(
        vararg arguments: String,
        input: String? = null,
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
        process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray(Charsets.UTF_8)) } }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output
    }

    /** Commits [file] with [content] (a line); returns the commit's hash. */
    protected fun commit(
        subject: String,
        file: String = "file.txt",
        content: String = subject,
    ): String {
        repo.resolve(file).writeText("$content\n")
        git("add", file)
        git("commit", "-q", "-m", subject)
        return head()
    }

    protected fun head() = git("rev-parse", "HEAD")

    protected fun status() =
        gitRaw("status", "--porcelain", "-z", "--untracked-files=all").split('\u0000').filter(String::isNotEmpty)

    protected fun gitFile(name: String) = repo.resolve(".git").resolve(name)

    /** A commit on a branch `other` that changes file.txt, and one on main that changes it too: they conflict. */
    protected fun conflicting(): String {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val theirs = commit("Theirs")
        git("switch", "-q", "main")
        commit("Ours")
        return theirs
    }
}
