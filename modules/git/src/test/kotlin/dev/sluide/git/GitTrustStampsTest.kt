package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The stamps of what git's trust check read ([GitTrustCheck.inputs], [GitFolderStamps.trust]) and of the state, with
 * the real git (ADR 0062): a change that can change what the check finds changes the stamp. Skipped without git.
 */
class GitTrustStampsTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")
    private val git = GitCli("git", testGitEnvironment())
    private val check = GitTrustCheck(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "main")
        commit("Initial")
    }

    private fun git(
        vararg arguments: String,
        directory: Path = repo,
    ): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        val exit = process.waitFor()
        assertEquals(0, exit, "git ${arguments.joinToString(" ")}: $output")
        return exit to output
    }

    private fun commit(
        message: String,
        directory: Path = repo,
    ) {
        git(
            "-c",
            "user.name=T",
            "-c",
            "user.email=t@e",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            message,
            directory = directory,
        )
    }

    /** Runs the check of [top] and gives the stamp of what it read. */
    private fun checked(top: Path = repo): String {
        check.check(top)
        return assertNotNull(GitFolderStamps.trust(check.inputs(top)), "a stamp")
    }

    /** The stamp of what the last check of [top] read, as it is now. */
    private fun now(top: Path = repo): String? = GitFolderStamps.trust(check.inputs(top))

    @Test
    fun `making a hook executable changes the stamp`() {
        val hook = repo.resolve(".git/hooks/pre-commit").apply { writeText("#!/bin/sh\nexit 0\n") }
        hook.toFile().setExecutable(false)
        val before = checked()

        hook.toFile().setExecutable(true)

        assertNotEquals(before, now())
    }

    @Test
    fun `an edit in place with the old size and time changes the stamp`() {
        git("config", "user.name", "Aaaa")
        val config = repo.resolve(".git/config")
        val time = Files.getLastModifiedTime(config)
        val before = checked()

        // Same length, same inode, same time: only the content tells.
        config.toFile().writeText(config.readText().replace("Aaaa", "Bbbb"))
        Files.setLastModifiedTime(config, time)

        assertNotEquals(before, now())
    }

    @Test
    fun `creating an included file that did not exist changes the stamp`() {
        git("config", "include.path", "../../later.cfg")
        val before = checked()

        folder.resolve("later.cfg").writeText("[core]\n\tsshCommand = ./ssh.sh\n")

        assertNotEquals(before, now())
    }

    @Test
    fun `a change of a submodule's configuration or of its gitdir file changes the stamp`() {
        val library = folder.resolve("library").createDirectories()
        git("init", "-q", "-b", "main", directory = library)
        commit("Library", library)
        git("-c", "protocol.file.allow=always", "submodule", "add", "-q", library.toString(), "lib")
        val before = checked()

        git("config", "filter.crypt.clean", "./clean.sh", directory = repo.resolve("lib"))
        val configured = now()
        assertNotEquals(before, configured, "the submodule's configuration")

        val gitFile = repo.resolve("lib/.git")
        gitFile.writeText(gitFile.readText().replace("modules/lib", "modules/lib/../lib"))
        assertNotEquals(configured, now(), "where the submodule's .git file leads")
    }

    @Test
    fun `a change of a linked worktree's commondir changes the stamp`() {
        val worktree = folder.resolve("worktree")
        git("worktree", "add", "-q", "-b", "other", worktree.toString())
        val before = checked(worktree)

        val commondir = repo.resolve(".git/worktrees/worktree/commondir")
        commondir.writeText(commondir.readText().trimEnd() + "/.\n")

        assertNotEquals(before, now(worktree))
    }

    @Test
    fun `a ref changed in a reftable repository changes the state's stamp`() {
        val reftable = folder.resolve("reftable").createDirectories()
        val process =
            ProcessBuilder("git", "init", "-q", "--ref-format=reftable")
                .directory(reftable.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.inputStream.readAllBytes()
        assumeTrue(process.waitFor() == 0, "git cannot make reftable repositories")
        commit("First", reftable)
        val before = GitFolderStamps.state(reftable)

        git("branch", "feature", directory = reftable)

        assertNotEquals(before, GitFolderStamps.state(reftable))
    }

    @Test
    fun `a FIFO among the hooks is stamped without being opened`() {
        val fifo = repo.resolve(".git/hooks/pre-push")
        val made =
            runCatching { ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(made, "mkfifo is not available")

        // Opening it for reading would wait for a writer forever.
        assertTimeoutPreemptively(Duration.ofSeconds(10)) { checked() }
    }

    @Test
    fun `a link to an endless device as gitdir or a hook is stamped without being read`() {
        val zero = Path.of("/dev/zero")
        assumeTrue(Files.exists(zero), "no /dev/zero")
        Files.createSymbolicLink(repo.resolve(".git/gitdir"), zero)
        Files.createSymbolicLink(repo.resolve(".git/hooks/post-merge"), zero)

        // Read to its end, it would fill the memory.
        assertTimeoutPreemptively(Duration.ofSeconds(10)) { checked() }
    }

    @Test
    fun `too many hooks or too much to read leave no stamp, so every change checks again`() {
        repeat(3) { repo.resolve(".git/hooks/hook-$it").writeText("#!/bin/sh\n") }
        check.check(repo)
        val inputs = check.inputs(repo)

        assertNotNull(GitFolderStamps.trust(inputs))
        assertNull(GitFolderStamps.trust(inputs, maxEntries = 2))
        assertNull(GitFolderStamps.trust(inputs, maxRead = 10))
    }
}
