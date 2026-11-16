package dev.sluide.gitlog

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitBranches
import dev.sluide.git.GitCommits
import dev.sluide.git.GitLog
import dev.sluide.git.GitMerges
import dev.sluide.git.GitPulls
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitRewords
import dev.sluide.git.GitStashes
import dev.sluide.git.cli.GitCli
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * A [GitLogView] on the real git CLI, shown in a stage, with a temporary folder for repositories and helpers that run
 * git in them. The fixture's fake writes cannot show what git really does.
 */
abstract class GitLogRealGitUiFixture : GitLogWritesUiFixture() {
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

    /** Shows a view on the real git CLI; the test class's own `@Start` method calls it. */
    internal fun startRealGit(stage: Stage) {
        val git = GitCli("git", environment)
        val rebases = GitRebases(git)
        view =
            GitLogView(
                GitLog(git),
                GitBranches(git),
                GitCommits(git),
                rebases,
                GitMerges(git),
                GitPulls(git, rebases),
                GitRewords(git),
                GitRemotes(git),
                GitPush(git),
                GitStashes(git),
                BackgroundTasks.forTests(),
            )
        stage.scene = Scene(view, 900.0, 500.0)
        stage.show()
    }

    internal fun git(
        directory: Path,
        vararg arguments: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                }.start()
        process.outputStream.close()
        val output =
            process.inputStream
                .readAllBytes()
                .decodeToString()
                .trim()
        process.waitFor()
        return output
    }

    internal fun commit(
        directory: Path,
        file: String,
    ): String {
        directory.resolve(file).writeText(file)
        git(directory, "add", file)
        git(directory, "commit", "-q", "-m", "Add $file")
        return git(directory, "rev-parse", "HEAD")
    }
}
