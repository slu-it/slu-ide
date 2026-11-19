package dev.sluide.git.cli

import dev.sluide.git.runGit
import dev.sluide.git.testGitEnvironment
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A repository in [root] that tests change with the real git, to read it with the wrappers of [GitCli]. Its git runs
 * with [environment] (a test adds the variables it needs: `GIT_EDITOR` to conclude a merge, the author's identity).
 */
internal class TestRepository(
    val root: Path,
    objectFormat: String = "sha1",
    initialBranch: String = "main",
    private val environment: Map<String, String> = testGitEnvironment(),
) {
    init {
        root.createDirectories()
        git("init", "-q", "-b", initialBranch, "--object-format=$objectFormat")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    /** Runs git in [root] with [input] on its standard input; its output, trimmed. */
    fun git(
        vararg arguments: String,
        input: String? = null,
    ): String = run(arguments, input, expectedExit = 0)

    /** Runs git that is meant to stop with an exit value other than 0 (a conflict); its output, trimmed. */
    fun gitStops(vararg arguments: String): String = run(arguments, null, expectedExit = null)

    private fun run(
        arguments: Array<out String>,
        input: String?,
        expectedExit: Int?,
    ): String = runGit(root, *arguments, input = input, expectedExit = expectedExit, environment = environment)

    /** Writes [file] with [content], commits it with [message], and gives the new commit's hash. */
    fun commit(
        message: String,
        file: String = "file.txt",
        content: String = message,
    ): String {
        write(file, content)
        git("add", file)
        git("commit", "-q", "-m", message)
        return head()
    }

    /** Commits nothing but [message] (`--allow-empty`), dated [seconds] after the epoch if given; the new commit's hash. */
    fun commitEmpty(
        message: String,
        seconds: Long? = null,
    ): String {
        val date = "@$seconds +0000"
        val dates = seconds?.let { mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date) }.orEmpty()
        runGit(root, "commit", "-q", "--allow-empty", "-m", message, environment = environment + dates)
        return head()
    }

    fun write(
        file: String,
        content: String,
    ) {
        root.resolve(file).apply { parent.createDirectories() }.writeText("$content\n")
    }

    fun head(): String = git("rev-parse", "HEAD")
}
