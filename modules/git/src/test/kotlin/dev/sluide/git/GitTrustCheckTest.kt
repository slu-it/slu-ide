package dev.sluide.git

import dev.sluide.git.cli.ConfigEntry
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.configEntries
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitTrustCheckTest {
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
    }

    private fun git(
        vararg arguments: String,
        directory: Path = repo,
    ) {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
    }

    private fun hook(
        name: String,
        content: String = "#!/bin/sh\nexit 0\n",
        folder: Path = repo.resolve(".git/hooks"),
        executable: Boolean = true,
    ) = folder.createDirectories().resolve(name).apply {
        writeText(content)
        toFile().setExecutable(executable)
    }

    @Test
    fun `a repository as clone and everyday commands leave it finds nothing`() {
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("remote", "add", "origin", "https://example.com/repo.git")
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/main")
        git("config", "pull.rebase", "true")
        git("config", "core.fsmonitor", "true")
        git("config", "alias.co", "!sh -c 'echo only in a terminal'")

        assertEquals(emptyList(), check.check(repo))
    }

    @Test
    fun `a folder that is no repository finds nothing`() {
        assertEquals(emptyList(), check.check(folder.resolve("plain").createDirectories()))
    }

    @Test
    fun `commands of the configuration are found with where they are and when they run`() {
        git("config", "core.fsmonitor", "./watch.sh")
        git("config", "filter.crypt.clean", "./clean.sh")
        git("config", "something.new", "x")

        val findings = check.check(repo)!!

        assertEquals(
            listOf(
                "core.fsmonitor = ./watch.sh (.git/config)" to "runs on every git status",
                "filter.crypt.clean = ./clean.sh (.git/config)" to
                    "runs on every git status, for the files .gitattributes names",
                "something.new = x (.git/config)" to null,
            ),
            findings.map { it.text to it.note },
        )
    }

    @Test
    fun `git lfs as it installs itself is harmless, other lfs filters are not`() {
        git("config", "filter.lfs.clean", "git-lfs clean -- %f")
        git("config", "filter.lfs.smudge", "git-lfs smudge -- %f")
        git("config", "filter.lfs.process", "git-lfs filter-process")
        git("config", "filter.lfs.required", "true")
        assertEquals(emptyList(), check.check(repo))

        git("config", "filter.lfs.smudge", "./fetch.sh %f")
        assertEquals(listOf("filter.lfs.smudge = ./fetch.sh %f (.git/config)"), check.check(repo)!!.map { it.text })
    }

    @Test
    fun `included files are followed`() {
        folder.resolve("shared.cfg").writeText("[core]\n\tsshCommand = ./ssh.sh\n")
        git("config", "include.path", "../../shared.cfg")

        val findings = check.check(repo)!!

        assertEquals(listOf("include.path", "core.sshcommand"), findings.map { it.text.substringBefore(' ') })
        assertTrue("shared.cfg" in findings[1].text, findings[1].text)
        assertEquals("runs on fetch and push", findings[1].note)
    }

    @Test
    fun `a value-less fsmonitor is git's daemon`() {
        repo.resolve(".git/config").appendText("[core]\n\tfsmonitor\n")

        assertEquals(emptyList(), check.check(repo))
    }

    @Test
    fun `hooks git could run are found by their content, samples and others are not`() {
        hook("pre-commit.sample")
        hook("post-checkout", executable = false)
        val preCommit = hook("pre-commit")

        val findings = check.check(repo)!!
        assertEquals(
            listOf("hook pre-commit (.git/hooks/pre-commit)" to "runs on commit"),
            findings.map { it.text to it.note },
        )

        preCommit.writeText("#!/bin/sh\ncurl example.com\n")
        assertNotEquals(findings.single().fingerprint, check.check(repo)!!.single().fingerprint)
    }

    @Test
    fun `hooks of a hooks path the repository sets are found with the setting`() {
        git("config", "core.hooksPath", ".husky/_")
        hook("pre-push", folder = repo.resolve(".husky/_"))

        assertEquals(
            listOf("core.hookspath = .husky/_ (.git/config)", "hook pre-push (.husky/_/pre-push)"),
            check.check(repo)!!.map { it.text },
        )
    }

    @Test
    fun `hooks of a hooks path the user set up for all repositories are the user's`() {
        val hooks = folder.resolve("my-hooks")
        hook("pre-commit", folder = hooks)
        val global = folder.resolve("gitconfig").apply { writeText("[core]\n\thooksPath = $hooks\n") }
        val userGit = GitCli("git", testGitEnvironment() + ("GIT_CONFIG_GLOBAL" to global.toString()))

        assertEquals(emptyList(), GitTrustCheck(userGit).check(repo))
    }

    @Test
    fun `keys are matched with their subsections as wildcards, and only the repository's scopes count`() {
        val entries =
            listOf(
                ConfigEntry("local", "file:.git/config", "remote.Upstream.Fork.url", "x"),
                ConfigEntry("local", "file:.git/config", "includeif.gitdir:~/a.b/.path", "~/x.cfg"),
                ConfigEntry("global", "file:/home/u/.gitconfig", "core.fsmonitor", "./watch.sh"),
                ConfigEntry("command", "command line:", "core.sshcommand", "ssh -v"),
            )

        val findings = configFindings(entries)

        assertEquals(listOf("includeif.gitdir:~/a.b/.path = ~/x.cfg (.git/config)"), findings.map { it.text })
        assertEquals("reads more configuration from there", findings.single().note)
        assertEquals(emptyList(), configFindings(entries.take(1)))
    }

    @Test
    fun `submodules are checked too, since git status looks into them`() {
        val library = folder.resolve("library").createDirectories()
        git("init", "-q", "-b", "main", directory = library)
        git(
            "-c",
            "user.name=Test",
            "-c",
            "user.email=t@example.com",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "Library",
            directory = library,
        )
        git("-c", "protocol.file.allow=always", "submodule", "add", "-q", library.toString(), "lib")
        git("config", "filter.crypt.clean", "./clean.sh", directory = repo.resolve("lib"))

        val findings = check.check(repo)!!

        assertEquals(
            listOf("submodule lib: filter.crypt.clean = ./clean.sh (.git/modules/lib/config)"),
            findings.map { it.text },
        )
        assertEquals("runs on every git status, for the files .gitattributes names", findings.single().note)
    }

    @Test
    fun `the worktree's own configuration counts`() {
        git("config", "extensions.worktreeConfig", "true")
        git("config", "--worktree", "core.sshCommand", "./ssh.sh")

        assertEquals(listOf("core.sshcommand = ./ssh.sh (.git/config.worktree)"), check.check(repo)!!.map { it.text })
    }

    @Test
    fun `a repository git cannot read could not be checked`() {
        val broken = folder.resolve("broken").createDirectories()
        broken.resolve(".git").writeText("gitdir: ${folder.resolve("nowhere")}\n")

        assertNull(check.check(broken))
    }

    @Test
    fun `a hooks path of the user's relative to each repository finds the repository's own hooks`() {
        hook("pre-commit", folder = repo.resolve(".githooks"))
        val global = folder.resolve("gitconfig").apply { writeText("[core]\n\thooksPath = .githooks\n") }
        val userGit = GitCli("git", testGitEnvironment() + ("GIT_CONFIG_GLOBAL" to global.toString()))

        assertEquals(
            listOf("hook pre-commit (.githooks/pre-commit)"),
            GitTrustCheck(userGit).check(repo)!!.map { it.text },
        )
    }

    @Test
    fun `fetching missing objects of a partial clone asks`() {
        git("config", "remote.origin.promisor", "true")

        assertEquals("fetches missing objects from the remote on reads", check.check(repo)!!.single().note)
    }

    @Test
    fun `values over several lines are read whole, and an entry without a value is not one with the text null`() {
        git("config", "something.new", "a\nb")
        repo.resolve(".git/config").appendText("[other]\n\tflag\n")

        val entries = git.configEntries(repo)!!.filter { it.scope == "local" }

        assertEquals("a\nb", entries.single { it.key == "something.new" }.value)
        assertNull(entries.single { it.key == "other.flag" }.value)
        val bare = configFindings(entries).single { it.text.startsWith("other.flag") }
        val nullText = configFindings(listOf(ConfigEntry("local", "file:.git/config", "other.flag", "null"))).single()
        assertNotEquals(bare.fingerprint, nullText.fingerprint)
    }

    @Test
    fun `a work tree elsewhere is found, since git would write its files there`() {
        git("config", "core.worktree", "../..")

        val findings = check.check(repo)!!

        assertEquals(listOf("core.worktree = ../.. (.git/config)"), findings.map { it.text })
        assertEquals("git reads and writes the files there, also on checkout and reset", findings.single().note)
    }

    @Test
    fun `a work tree set to the folder itself is harmless`() {
        git("config", "core.worktree", "..")

        assertEquals(emptyList(), check.check(repo))
    }
}
