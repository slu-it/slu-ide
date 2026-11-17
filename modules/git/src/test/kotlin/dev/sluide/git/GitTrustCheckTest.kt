package dev.sluide.git

import dev.sluide.git.cli.ConfigEntry
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.configEntries
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
    fun `what the check read is recorded as its inputs, also included files that do not exist yet`() {
        folder.resolve("shared.cfg").writeText("[user]\n\tname = Test\n")
        git("config", "include.path", "../../shared.cfg")
        git("config", "includeIf.onbranch:other.path", "other.cfg")
        addSubmodule()

        assertTrue(check.check(repo)!!.isNotEmpty())
        val inputs = check.inputs(repo)!!

        val git = repo.resolve(".git")
        val files = listOf("config", "HEAD", "commondir", "gitdir", "other.cfg").map(git::resolve)
        for (file in files + listOf(folder.resolve("shared.cfg"))) {
            assertTrue(file in inputs.files, "$file in ${inputs.files}")
        }
        assertTrue(inputs.files.any { it.endsWith(".git/modules/lib/config") }, "${inputs.files}")
        assertTrue(git.resolve("hooks") in inputs.folders, "${inputs.folders}")
        assertTrue(inputs.folders.any { it.endsWith(".git/modules/lib/hooks") }, "${inputs.folders}")
        assertTrue(git.resolve("index") in inputs.large && git.resolve("index") !in inputs.files, "index by attributes")
        assertTrue(repo.resolve(".git") in inputs.redirects && repo.resolve("lib/.git") in inputs.redirects)
    }

    @Test
    fun `a check that cannot name what it read records no inputs`() {
        check.check(repo)
        assertTrue(check.inputs(repo) != null)

        // git expands ~root (the home of another user) itself; the JVM cannot tell where that is.
        assumeTrue(Files.readAllLines(Path.of("/etc/passwd")).any { it.startsWith("root:") }, "no user root")
        git("config", "include.path", "~root/shared.cfg")
        check.check(repo)

        assertNull(check.inputs(repo))
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
        addSubmodule()
        git("config", "filter.crypt.clean", "./clean.sh", directory = repo.resolve("lib"))

        val findings = check.check(repo)!!

        assertEquals(
            listOf("submodule lib: filter.crypt.clean = ./clean.sh (.git/modules/lib/config)"),
            findings.map { it.text },
        )
        assertEquals("runs on every git status, for the files .gitattributes names", findings.single().note)
    }

    /** Adds a repository of its own as the submodule `lib`. */
    private fun addSubmodule() {
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
        assertEquals("other.flag (.git/config)", bare.text)
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
    fun `a folder opened through a symbolic link shows the files git names by their real path relative to it`() {
        // As on macOS, where the temporary folder /var/… is /private/var/….
        val link = Files.createSymbolicLink(folder.resolve("link"), repo)
        git("config", "core.worktree", "../..")

        assertEquals(listOf("core.worktree = ../.. (.git/config)"), check.check(link)!!.map { it.text })
    }

    @Test
    fun `a work tree set to the folder itself is harmless`() {
        git("config", "core.worktree", "..")

        assertEquals(emptyList(), check.check(repo))
    }

    @Test
    fun `without git nothing is found or recorded, and a folder that stops being a repository loses its inputs`() {
        check.check(repo)
        assertNotNull(check.inputs(repo))
        val missing = GitTrustCheck(GitCli(folder.resolve("no-such-git").toString(), testGitEnvironment()))

        assertEquals(emptyList(), missing.check(repo))
        assertNull(missing.inputs(repo))

        Files.move(repo.resolve(".git"), folder.resolve("moved-git"))
        assertEquals(emptyList(), check.check(repo))
        assertNull(check.inputs(repo), "what the earlier check read is dropped")
    }

    @Test
    fun `a check that started earlier does not replace what a later one recorded`() {
        val started = folder.resolve("started")
        val go = folder.resolve("go")
        git("config", "include.path", "../../extra.cfg")
        // The first call that is not git's own start-up stops until `go` exists.
        val script = folder.resolve("slow-first-git")
        script.writeText(
            FAKE_GIT_START +
                "if [ ! -e '$started' ]; then touch '$started'; while [ ! -e '$go' ]; do sleep 0.05; done; fi\n" +
                "exec git \"\$@\"\n",
        )
        script.toFile().setExecutable(true)
        val slow = GitTrustCheck(GitCli(script.toString(), testGitEnvironment()))
        val first = CompletableFuture.supplyAsync { slow.check(repo) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!started.toFile().exists()) {
            if (System.nanoTime() > deadline) fail("the first check did not start")
            Thread.sleep(20)
        }

        slow.check(repo)
        assertTrue(folder.resolve("extra.cfg") in slow.inputs(repo)!!.files, "the later check read the include")
        // The earlier one reads the configuration only now, without the include.
        git("config", "--unset", "include.path")
        go.writeText("")
        first.get(10, TimeUnit.SECONDS)

        assertTrue(folder.resolve("extra.cfg") in slow.inputs(repo)!!.files, "the later check's inputs stay")
    }

    /** The repository at [directory] with the gitlinks [names] in its index. */
    private fun withSubmodules(
        directory: Path,
        vararg names: String,
    ) {
        git("init", "-q", "-b", "main", directory = directory.createDirectories())
        for (name in names) {
            git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},$name", directory = directory)
        }
    }

    @Test
    fun `fifty repositories are checked, more than that and submodules nested deeper than five are not`() {
        val many = folder.resolve("many")
        val names = (1..50).map { "s$it" }
        withSubmodules(many, *names.take(49).toTypedArray())
        for (name in names.take(49)) git("init", "-q", "-b", "main", directory = many.resolve(name).createDirectories())
        assertNotNull(check.check(many), "the opened one and 49")
        git("init", "-q", "-b", "main", directory = many.resolve("s50").createDirectories())
        git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},s50", directory = many)
        assertNull(check.check(many), "the opened one and 50")

        fun chain(depth: Int): Path {
            val top = folder.resolve("chain$depth")
            var directory = top
            repeat(depth) {
                withSubmodules(directory, "sub")
                directory = directory.resolve("sub")
            }
            git("init", "-q", "-b", "main", directory = directory.createDirectories())
            return top
        }
        assertNotNull(check.check(chain(5)), "five levels")
        assertNull(check.check(chain(6)), "six levels")
    }

    @Test
    fun `git failing to name files or list submodules, or an unreadable hooks folder, is no check`() {
        assertNotNull(check.check(repo))

        assertNull(GitTrustCheck(failingGit(folder, "--git-path")).check(repo), "git paths")
        assertNull(GitTrustCheck(failingGit(folder, "--stage")).check(repo), "gitlinks")

        val hooks = repo.resolve(".git/hooks")
        hooks.toFile().setReadable(false)
        try {
            assumeTrue(!Files.isReadable(hooks), "permissions are not enforced (root)")
            assertNull(check.check(repo), "a hooks folder that cannot be read")
        } finally {
            hooks.toFile().setReadable(true)
        }
    }

    @Test
    fun `a missing hooks folder has no hooks`() {
        repo.resolve(".git/hooks").toFile().deleteRecursively()

        assertEquals(emptyList(), check.check(repo))
    }

    @Test
    fun `a hooks path the repository sets to a folder elsewhere is checked with the hook's whole path`() {
        val elsewhere = folder.resolve("elsewhere")
        hook("pre-commit", folder = elsewhere)
        git("config", "core.hooksPath", elsewhere.toString())

        val findings = check.check(repo)!!

        assertEquals(
            listOf("core.hookspath = $elsewhere (.git/config)", "hook pre-commit ($elsewhere/pre-commit)"),
            findings.map { it.text },
        )
    }

    @Test
    fun `a work tree is elsewhere when git cannot say where the top level is`() {
        git("config", "core.worktree", "..")
        assertEquals(emptyList(), check.check(repo))

        assertEquals(
            listOf("core.worktree = .. (.git/config)"),
            GitTrustCheck(failingGit(folder, "--show-toplevel")).check(repo)!!.map { it.text },
        )
    }

    @Test
    fun `the inputs have the submodule folders not checked out, not the user's files, and only includes name more`() {
        git("config", "user.name", "Test")
        git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},lib")
        val global = folder.resolve("global.cfg").apply { writeText("[user]\n\temail = a@b.c\n") }
        val userCheck = GitTrustCheck(GitCli("git", testGitEnvironment() + ("GIT_CONFIG_GLOBAL" to global.toString())))

        userCheck.check(repo)
        val inputs = userCheck.inputs(repo)!!

        assertTrue(repo.resolve("lib/.git") in inputs.redirects, "a submodule not checked out yet: its .git is watched")
        assertTrue(global !in inputs.files, "the user's own files are not the repository's")
        assertTrue(inputs.files.none { it.fileName.toString() == "Test" }, "no include path: ${inputs.files}")
    }

    @Test
    fun `an include in the user's home is an input`() {
        git("config", "include.path", "~/shared-by-test.cfg")

        check.check(repo)

        val home = Path.of(System.getProperty("user.home")).resolve("shared-by-test.cfg")
        assertTrue(home in check.inputs(repo)!!.files)
    }

    @Test
    fun `a file below the folder is shown relative to it, one elsewhere as it is`() {
        fun entry(file: String) = ConfigEntry("local", "file:$file", "something.new", "x")

        assertEquals(
            listOf("something.new = x (a.cfg)", "something.new = x (/outside/a.cfg)"),
            configFindings(listOf(entry("$repo/a.cfg"), entry("/outside/a.cfg")), repo).map { it.text },
        )
    }
}
