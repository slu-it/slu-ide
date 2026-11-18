package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitCancellation
import dev.sluide.git.failingGit
import dev.sluide.git.testGitEnvironment
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The reads of git's configuration, remotes, top level and gitlinks (`GitCliConfig.kt`) with the real git in a
 * temporary repository, and behind scripts for the answers it never gives but a failing one might (exit 128 and a
 * `fatal:` line for a failed command, exit 1 for "not set", checked with git 2.56).
 */
class GitCliConfigTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        run("init", "-q", "-b", "main")
    }

    private fun run(
        vararg arguments: String,
        input: String? = null,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray()) } }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    private var scripts = 0

    /** The real git behind a script that first runs [body] for the calls that match it (shell `case` arms). */
    private fun scripted(
        body: String,
        pipeGrace: Duration = 2.seconds,
    ): GitCli {
        val script = folder.resolve("script-git-${++scripts}")
        script.toFile().writeText(FAKE_GIT_START + "case \" \$* \" in\n$body\nesac\nexec git \"\$@\"\n")
        script.toFile().setExecutable(true)
        return GitCli(script.toString(), testGitEnvironment(), 2.seconds, pipeGrace)
    }

    private fun unavailable(): GitCli {
        val script = folder.resolve("versionless-git")
        script.toFile().writeText("#!/bin/sh\n[ \"\$1\" = --version ] && exit 1\nexec git \"\$@\"\n")
        script.toFile().setExecutable(true)
        return GitCli(script.toString(), testGitEnvironment()).also { assertEquals(false, it.available) }
    }

    /**
     * Answers `git <word> …` with `x` and exit 0, the output held open past the short grace by a process left behind.
     * It waits before it ends: the JDK drains and closes the pipe of a process that ends while nobody is reading it
     * yet, which would make the output look complete now and then.
     */
    private fun heldOpen(word: String) =
        scripted("*' $word '*) echo x; (sleep 20 &); sleep 0.3; exit 0;;", 100.milliseconds)

    private fun answering(
        word: String,
        script: String,
    ) = scripted("*' $word '*) $script;;")

    @Test
    fun `the user name is trimmed, and none when unset or blank`() {
        assertNull(git.userName(repo))
        run("config", "user.name", "  Spaced Name ")
        assertEquals("Spaced Name", git.userName(repo))
        run("config", "user.name", "   ")
        assertNull(git.userName(repo))
        assertNull(unavailable().userName(repo))
    }

    @Test
    fun `the remote names come in git's order, none for none, and nothing without an answer`() {
        assertEquals(emptyList(), git.remoteNames(repo))
        run("remote", "add", "origin", "https://example.com/a.git")
        run("remote", "add", "another", "https://example.com/b.git")

        assertEquals(listOf("another", "origin"), git.remoteNames(repo))
        assertNull(unavailable().remoteNames(repo))
        assertNull(failingGit(folder, "remote").remoteNames(repo))
        assertNull(git.remoteNames(repo, GitCancellation().apply { cancel() }))
    }

    @Test
    fun `remotes left out of fetch all are those whose last value is true, named as written`() {
        val global = folder.resolve("global.gitconfig")
        global.writeText("[remote \"Up\"]\n\tskipFetchAll = true\n[remote \"flip\"]\n\tskipFetchAll = true\n")
        val withGlobal = GitCli("git", testGitEnvironment() + ("GIT_CONFIG_GLOBAL" to global.toString()))
        assertEquals(setOf("Up", "flip"), withGlobal.remotesSkippedByFetchAll(repo))
        run("config", "remote.flip.skipFetchAll", "false")
        run("config", "remote.yes.skipFetchAll", "yes")
        run("config", "remote.no.skipFetchAll", "no")

        assertEquals(setOf("Up", "yes"), withGlobal.remotesSkippedByFetchAll(repo))
        assertEquals(setOf("yes"), git.remotesSkippedByFetchAll(repo))
    }

    @Test
    fun `no remote skips fetch all when none sets it, a value that is no boolean or no answer is unknown`() {
        assertEquals(emptySet(), git.remotesSkippedByFetchAll(repo))
        assertNull(unavailable().remotesSkippedByFetchAll(repo))
        assertNull(failingGit(folder, "config").remotesSkippedByFetchAll(repo))
        assertNull(heldOpen("config").remotesSkippedByFetchAll(repo))
        run("config", "remote.x.skipFetchAll", "perhaps")
        assertNull(git.remotesSkippedByFetchAll(repo))
    }

    @Test
    fun `all values of a variable come in order, also empty and multi-line ones, none when unset`() {
        assertEquals(emptyList(), git.configValues(repo, "test.values"))
        run("config", "--add", "test.values", "one")
        run("config", "--add", "test.values", "")
        run("config", "--add", "test.values", "two\nlines")

        assertEquals(listOf("one", "", "two\nlines"), git.configValues(repo, "test.values"))
    }

    @Test
    fun `the values of a variable are unknown without an answer, cancelled, or for an invalid name`() {
        run("config", "--add", "test.values", "one")

        assertNull(unavailable().configValues(repo, "test.values"))
        assertNull(failingGit(folder, "config").configValues(repo, "test.values"))
        assertNull(heldOpen("config").configValues(repo, "test.values"))
        assertNull(git.configValues(repo, "test.values", GitCancellation().apply { cancel() }))
        // git: "error: key does not contain a section" (exit 1) is "not set"; "invalid key" is exit 1 too.
        assertEquals(emptyList(), git.configValues(repo, "--list"))
    }

    @Test
    fun `a flag is read as git reads a boolean, false when unset, unknown when it is none or unread`() {
        assertEquals(false, git.configFlag(repo, "test.flag"))
        run("config", "test.flag", "yes")
        assertEquals(true, git.configFlag(repo, "test.flag"))
        run("config", "test.flag", "0")
        assertEquals(false, git.configFlag(repo, "test.flag"))
        run("config", "test.flag", "2")
        assertEquals(true, git.configFlag(repo, "test.flag"))
        repo.resolve(".git/config").toFile().appendText("[test]\n\tbare\n")
        run("config", "--unset-all", "test.flag")
        assertEquals(true, git.configFlag(repo, "test.bare"))
        run("config", "test.flag", "perhaps")

        assertNull(git.configFlag(repo, "test.flag"))
        assertNull(unavailable().configFlag(repo, "test.flag"))
        assertNull(failingGit(folder, "config").configFlag(repo, "test.flag"))
        assertNull(heldOpen("config").configFlag(repo, "test.bare"))
    }

    @Test
    fun `a flag is false for what git prints but true, and true only for exactly true`() {
        // Output other than "true" with exit 0 is false (git prints "true" or "false").
        assertEquals(false, answering("--get", "echo false; exit 0").configFlag(repo, "test.flag"))
        assertEquals(false, answering("--get", "echo truely; exit 0").configFlag(repo, "test.flag"))
        assertEquals(true, answering("--get", "echo true; exit 0").configFlag(repo, "test.flag"))
    }

    @Test
    fun `the entries carry scope, origin, key and value, none for a value without equals`() {
        run("config", "test.empty", "")
        run("config", "test.multi", "a\nb")
        repo.resolve(".git/config").toFile().appendText("[test]\n\tbare\n")

        val entries = git.configEntries(repo)!!.filter { it.key.startsWith("test.") }.associateBy { it.key }

        assertEquals(ConfigEntry("local", "file:.git/config", "test.empty", ""), entries["test.empty"])
        assertEquals("a\nb", entries["test.multi"]?.value)
        assertEquals(ConfigEntry("local", "file:.git/config", "test.bare", null), entries["test.bare"])
    }

    @Test
    fun `the entries are unknown without an answer, with a failed one, or with fields that do not add up`() {
        assertNull(unavailable().configEntries(repo))
        assertNull(failingGit(folder, "--list").configEntries(repo))
        assertNull(heldOpen("--list").configEntries(repo))
        // Two fields only: git always gives three per entry.
        assertNull(answering("--list", "printf 'local\\0file:x\\0'; exit 0").configEntries(repo))
        val three = answering("--list", "printf 'local\\0file:x\\0a.b\\nc\\0'; exit 0").configEntries(repo)
        assertEquals(listOf(ConfigEntry("local", "file:x", "a.b", "c")), three)
    }

    @Test
    fun `the top level is the real path of the work tree, from a folder below it too`() {
        val below = repo.resolve("a/b").createDirectories()

        assertEquals(repo.toRealPath(), git.topLevel(repo))
        assertEquals(repo.toRealPath(), git.topLevel(below))
        assertNull(git.topLevel(repo.resolve(".git")))
        assertNull(unavailable().topLevel(repo))
        assertNull(failingGit(folder, "--show-toplevel").topLevel(repo))
    }

    @Test
    fun `the top level is none for an empty line, a path that is not there or cannot be a path`() {
        assertNull(answering("--show-toplevel", "exit 0").topLevel(repo))
        assertNull(answering("--show-toplevel", "echo; exit 0").topLevel(repo))
        assertNull(answering("--show-toplevel", "echo missing/folder; exit 0").topLevel(repo))
        assertNull(answering("--show-toplevel", "printf 'a\\000b\\n'; exit 0").topLevel(repo))
        // A relative line is read from the folder asked about.
        repo.resolve("sub").createDirectories()
        assertEquals(repo.resolve("sub").toRealPath(), answering("--show-toplevel", "echo sub; exit 0").topLevel(repo))
    }

    @Test
    fun `the gitlinks are the submodule paths of the index, each once`() {
        val blob = run("hash-object", "-w", "--stdin", input = "x\n")
        run("update-index", "--add", "--cacheinfo", "100644,$blob,file.txt")
        run("update-index", "--add", "--cacheinfo", "160000,${"a".repeat(40)},sub one")
        run("update-index", "--add", "--cacheinfo", "160000,${"b".repeat(40)},z")

        assertEquals(listOf("sub one", "z"), git.gitlinks(repo))
        assertNull(unavailable().gitlinks(repo))
        assertNull(failingGit(folder, "--stage").gitlinks(repo))
    }

    @Test
    fun `a gitlink is an entry with the gitlink mode and a path, a path once whatever its stages`() {
        // The shape of `ls-files --stage -z`: <mode> SP <object> SP <stage> TAB <path> NUL (checked with git 2.56).
        val lines =
            listOf(
                "160000 ${"a".repeat(40)} 1\tconflicted",
                "160000 ${"b".repeat(40)} 2\tconflicted",
                "1600000 ${"c".repeat(40)} 0\tlonger-mode",
                "100644 ${"d".repeat(40)} 0\tfile",
                "160000 ${"e".repeat(40)} 0",
            ).joinToString("") { "$it\u0000" }
        val output = folder.resolve("stage.out").also { it.writeText(lines) }

        val read = answering("--stage", "cat '$output'; exit 0").gitlinks(repo)

        assertEquals(listOf("conflicted"), read)
    }

    @Test
    fun `ssh is configured when git finds core sshCommand or ssh variant, unknown when it cannot say`() {
        assertEquals(false, git.sshConfigured(repo))
        run("config", "core.sshCommand", "ssh")
        assertEquals(true, git.sshConfigured(repo))
        assertNull(unavailable().sshConfigured(repo))
        assertNull(failingGit(folder, "--get-regexp").sshConfigured(repo))
    }

    /** Every read of the configuration, in the repository as it is now, as [GitCli] answers it. */
    private fun answers(reader: GitCli): List<Any?> =
        listOf(
            reader.userName(repo),
            reader.remoteNames(repo),
            reader.remotesSkippedByFetchAll(repo),
            reader.configValues(repo, "test.values"),
            reader.configFlag(repo, "test.flag"),
            reader.configEntries(repo),
            reader.topLevel(repo),
            reader.gitlinks(repo),
            reader.sshConfigured(repo),
        )

    @Test
    fun `every read has an answer in a repository that is in order, and none without a git`() {
        run("config", "user.name", "Name")
        run("remote", "add", "origin", "https://example.com/a.git")

        assertEquals(9, answers(git).size)
        assertEquals(emptyList(), answers(git).withIndex().filter { it.value == null }.map { it.index })
        assertEquals(List(9) { null }, answers(unavailable()))
    }

    @Test
    fun `every read has no answer when git cannot be started`() {
        run("config", "user.name", "Name")
        val script = folder.resolve("doomed-git")
        script.toFile().writeText(FAKE_GIT_START + "exec git \"\$@\"\n")
        script.toFile().setExecutable(true)
        val doomed = GitCli(script.toString(), testGitEnvironment())
        Files.delete(script)

        assertEquals(List(9) { null }, answers(doomed))
    }

    @Test
    fun `reads of the configuration that take too long have no answer`() {
        run("config", "user.name", "Name")
        val hanging = scripted("*' config '*) sleep 30;;")
        val reads =
            listOf<() -> Any?>(
                { hanging.remotesSkippedByFetchAll(repo) },
                { hanging.configValues(repo, "test.values") },
                { hanging.configFlag(repo, "test.flag") },
                { hanging.configEntries(repo) },
                { hanging.sshConfigured(repo) },
            ).map { read -> CompletableFuture.supplyAsync(read) }

        // The reads wait three seconds each, side by side.
        assertEquals(List(5) { null }, reads.map { it.get(30, TimeUnit.SECONDS) })
    }
}
