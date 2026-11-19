package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.TestRepository
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Runs the real git in a temporary repository; skipped if git is not installed. */
internal abstract class GitLogFixture {
    @TempDir
    lateinit var repo: Path

    protected val git = GitCli("git", testGitEnvironment())
    protected val log = GitLog(git)
    private lateinit var repository: TestRepository

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repository = TestRepository(repo)
    }

    protected fun git(
        vararg arguments: String,
        environment: Map<String, String> = emptyMap(),
        input: String = "",
    ): String = runGit(repo, *arguments, input = input, environment = testGitEnvironment() + environment)

    /** Commits with the given subject at [time] seconds after the epoch, as author and committer date. */
    protected fun commit(
        subject: String,
        time: Long,
    ): String = repository.commitStaged(subject, time)

    protected fun subjects(page: GitLogPage?) = page?.commits?.map { it.subject }

    /** Writes the files (path to content, `null` deletes it), stages everything and commits; gives the commit. */
    protected fun commitFiles(
        subject: String,
        vararg files: Pair<String, String?>,
    ): String {
        for ((path, content) in files) {
            val file = repo.resolve(path)
            if (content == null) {
                git("rm", "-q", path)
            } else {
                file.parent.createDirectories()
                file.writeText(content)
            }
        }
        git("add", "-A")
        git("commit", "-q", "--allow-empty", "-m", subject)
        return git("rev-parse", "HEAD")
    }

    protected val lines = (1..20).joinToString("") { "line $it\n" }
}
