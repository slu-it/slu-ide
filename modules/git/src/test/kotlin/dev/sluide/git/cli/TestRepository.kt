package dev.sluide.git.cli

import dev.sluide.git.testGitEnvironment
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A repository in [root] that tests change with the real git, to read it with the wrappers of [GitCli]. */
internal class TestRepository(
    val root: Path,
) {
    init {
        root.createDirectories()
        git("init", "-q", "-b", "main")
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
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.use { stdin -> input?.let { stdin.write(it.toByteArray(Charsets.UTF_8)) } }
        val output = process.inputStream.readAllBytes().decodeToString()
        val exit = process.waitFor()
        if (expectedExit == null) {
            assertNotEquals(0, exit, "git ${arguments.joinToString(" ")}: $output")
        } else {
            assertEquals(expectedExit, exit, "git ${arguments.joinToString(" ")}: $output")
        }
        return output.trim()
    }

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

    fun write(
        file: String,
        content: String,
    ) {
        root.resolve(file).apply { parent.createDirectories() }.writeText("$content\n")
    }

    fun head(): String = git("rev-parse", "HEAD")
}
