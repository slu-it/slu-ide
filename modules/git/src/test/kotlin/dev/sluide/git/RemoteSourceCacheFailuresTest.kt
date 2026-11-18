package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.GitCommandLogSink
import dev.sluide.git.remote.RemoteRefStore
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What [RemoteSourceCache] does when git or the cache folder misbehaves, with the real git behind a script that fails,
 * hangs or vanishes for one command (a shape of git checked with git 2.56: exit 128 and a `fatal:` line on standard
 * error for a failed command, `rev-parse --verify --quiet` exit 1 for an unknown commit) and a local "server".
 */
class RemoteSourceCacheFailuresTest {
    @TempDir
    lateinit var folder: Path

    private val upstream get() = folder.resolve("server/owner/repo")

    private val cacheFolder get() = folder.resolve("cache")

    private val git = GitCli("git", testGitEnvironment())

    private val repository get() = RemoteRepository("example.com", "owner", "repo", "file://$upstream")

    private val bare get() = cacheFolder.resolve("example.com/owner/repo.git")

    private lateinit var head: String

    @BeforeEach
    fun createUpstream() {
        assumeTrue(git.available, "git is not installed")
        upstream.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("config", "uploadpack.allowFilter", "true")
        git("config", "uploadpack.allowAnySHA1InWant", "true")
        upstream.resolve("action.yml").writeText("name: one\n")
        git("add", "action.yml")
        git("commit", "-q", "-m", "Commit")
        head = git("rev-parse", "HEAD")
    }

    private fun git(vararg arguments: String): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(upstream.toFile())
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

    private fun cacheOf(
        using: GitCli = git,
        localTimeout: Duration = 10.seconds,
        networkTimeout: Duration = 2.minutes,
    ) = RemoteSourceCache(using, cacheFolder, true, localTimeout, networkTimeout)

    /** What a cache that went through the real git holds: the trees of `main`, and the file with [content]. */
    private fun fetchedMain(content: Boolean = false): RemoteSourceCache =
        cacheOf().also {
            assertIs<RemoteFetch.Fetched>(it.fetch(repository, "main"))
            if (content) assertEquals(RemoteRead.Found("name: one\n"), it.read(repository, head, "action.yml", true))
        }

    private var scripts = 0

    /** The real git behind a script that first runs [body] (shell) for each call; `$0` is the script itself. */
    private fun scriptPath(body: String): Path {
        val script = folder.resolve("script-git-${++scripts}")
        script.toFile().writeText(FAKE_GIT_START + body + "\nexec git \"\$@\"\n")
        script.toFile().setExecutable(true)
        return script
    }

    private fun scriptGit(
        body: String,
        pipeGrace: Duration = 2.seconds,
    ) = GitCli(scriptPath(body).toString(), testGitEnvironment(), 2.seconds, pipeGrace)

    /** A git that was there when the cache was made and is gone at the first command after. */
    private fun doomedGit(): GitCli {
        val script = scriptPath("")
        return GitCli(script.toString(), testGitEnvironment()).also { Files.delete(script) }
    }

    /**
     * Runs [block], and cancels [cancellation] as soon as git has ended its [nth] command (1-based), before the cache
     * looks at the answer. Counted, not named: the log keeps only the start of long arguments.
     */
    private fun <T> cancelledAfter(
        nth: Int,
        cancellation: GitCancellation,
        block: () -> T,
    ): T {
        val previous = GitCommandLogSink.sink
        var ended = 0
        GitCommandLogSink.sink = { level, message, error ->
            previous(level, message, error)
            if (message.substringAfter(' ').startsWith("exit ") && ++ended == nth) cancellation.cancel()
        }
        try {
            return block()
        } finally {
            GitCommandLogSink.sink = previous
        }
    }

    @Test
    fun `a stored commit of an invalid ref or repository is not given`() {
        val cached = CachedCommit(head, Instant.ofEpochMilli(1000))
        val stranger = repository.copy(owner = "ow ner")
        for (directory in listOf(bare, cacheFolder.resolve("example.com/ow ner/repo.git"))) {
            directory.createDirectories()
            RemoteRefStore.put(directory, "main", cached)
            RemoteRefStore.put(directory, "a..b", cached)
        }

        assertEquals(cached, cacheOf().commit(repository, "main"))
        assertNull(cacheOf().commit(repository, "a..b"))
        assertNull(cacheOf().commit(stranger, "main"))
    }

    @Test
    fun `the cache is under the home folder unless another is given`() {
        val home = folder.resolve("home")
        val directory = home.resolve(".sluide/cache/git/example.com/owner/repo.git").createDirectories()
        val cached = CachedCommit(head, Instant.ofEpochMilli(1000))
        RemoteRefStore.put(directory, "main", cached)
        val before = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            assertEquals(cached, RemoteSourceCache(git).commit(repository, "main"))
        } finally {
            System.setProperty("user.home", before)
        }
    }

    @Test
    fun `a read checks the URL only when it may use the network, and says what is refused`() {
        val badUrl = repository.copy(url = "ext::sh -c touch% /tmp/pwned")
        val cache = cacheOf()

        assertEquals(RemoteRead.NotCached, cache.read(badUrl, head, "action.yml", network = false))
        assertEquals(RemoteRead.Failed("unsupported repository URL"), cache.read(badUrl, head, "action.yml", true))
        val stranger = badUrl.copy(owner = "ow ner")
        assertEquals(RemoteRead.Failed("invalid owner 'ow ner'"), cache.read(stranger, "main", "../x", network = false))
        assertEquals(RemoteRead.Failed("invalid owner 'ow ner'"), cache.read(stranger, head, "x", network = true))
        assertEquals(RemoteRead.Failed("invalid commit 'main'"), cache.read(repository, "main", "../x", false))
        assertEquals(RemoteRead.Failed("invalid path '../x'"), cache.read(repository, head, "../x", false))
        assertEquals(RemoteRead.Failed("invalid path '../x'"), cache.read(repository, head, "../x", true))
        assertFalse(cacheFolder.exists())
    }

    @Test
    fun `without a git that works nothing is read or fetched, a full commit already fetched is still known`() {
        val fetched = fetchedMain(content = true)
        val full = assertIs<RemoteFetch.Fetched>(fetched.fetch(repository, head.uppercase())).commit
        val script = folder.resolve("versionless-git")
        script.toFile().writeText("#!/bin/sh\n[ \"\$1\" = --version ] && exit 1\nexec git \"\$@\"\n")
        script.toFile().setExecutable(true)
        val versionless = GitCli(script.toString(), testGitEnvironment())
        assertFalse(versionless.available)
        val unavailable = cacheOf(versionless)

        assertEquals(RemoteRead.Failed("git is not available"), unavailable.read(repository, head, "action.yml", false))
        assertEquals(RemoteRead.Failed("git is not available"), unavailable.read(repository, head, "action.yml", true))
        assertEquals(RemoteFetch.Failed("git is not available"), unavailable.fetch(repository, "main"))
        assertEquals(RemoteFetch.Fetched(full), unavailable.fetch(repository, head))
    }

    @Test
    fun `a cancelled read or fetch starts no git and touches no folder`() {
        val log = folder.resolve("calls.log")
        val cache = cacheOf(recordingGit(folder, log))
        val cancelled = GitCancellation().apply { cancel() }

        assertEquals(RemoteRead.Failed("cancelled"), cache.read(repository, head, "action.yml", true, cancelled))
        assertEquals(RemoteFetch.Failed("cancelled"), cache.fetch(repository, "main", cancelled))
        assertEquals(RemoteRead.Failed("cancelled"), cache.read(repository, head, "action.yml", false, cancelled))

        assertEquals(emptyList(), recordedCalls(log))
        assertFalse(cacheFolder.exists(), "not even the lock file of the repository")
    }

    @Test
    fun `a read says when git cannot be run for the commit, the file or its content`() {
        fetchedMain(content = true)
        val couldNot = RemoteRead.Failed("git could not be run")

        assertEquals(couldNot, cacheOf(doomedGit()).read(repository, head, "action.yml", network = false))
        // The commands of a read: rev-parse for the commit, ls-tree for the file, cat-file for its content.
        val afterCommit = scriptGit("case \" \$* \" in *' rev-parse '*) rm -f \"\$0\";; esac")
        assertEquals(couldNot, cacheOf(afterCommit).read(repository, head, "action.yml", network = false))
        val afterFile = scriptGit("case \" \$* \" in *' ls-tree '*) rm -f \"\$0\";; esac")
        assertEquals(couldNot, cacheOf(afterFile).read(repository, head, "action.yml", network = false))
    }

    @Test
    fun `a read whose git cannot find the file in the tree or read it fails with the reason`() {
        fetchedMain(content = true)

        assertEquals(
            RemoteRead.Failed("could not read the file"),
            cacheOf(failingGit(folder, "ls-tree")).read(repository, head, "action.yml", network = false),
        )
        assertEquals(
            RemoteRead.Failed("could not read the file"),
            cacheOf(failingGit(folder, "blob")).read(repository, head, "action.yml", network = false),
        )
        val unreachable = "case \" \$* \" in *' blob '*) echo 'ssh: Could not resolve hostname x' >&2; exit 128;; esac"
        assertEquals(
            RemoteRead.Failed("network error"),
            cacheOf(scriptGit(unreachable)).read(repository, head, "action.yml", network = true),
        )
    }

    @Test
    fun `a content whose output is not read to its end is no content`() {
        fetchedMain(content = true)
        // A process the command left behind holds its output open past the short grace.
        val partial = "case \" \$* \" in *' blob '*) echo partial; (sleep 20 &); sleep 0.3; exit 0;; esac"
        val cache = cacheOf(scriptGit(partial, pipeGrace = 100.milliseconds))

        assertEquals(RemoteRead.Failed("could not read the file"), cache.read(repository, head, "action.yml", false))
        assertEquals(RemoteRead.Failed("could not read the file"), cache.read(repository, head, "action.yml", true))
    }

    @Test
    fun `a content that cannot be read offline, whose existence cannot be asked, fails`() {
        fetchedMain()
        val gone = "case \" \$* \" in *' blob '*) rm -f \"\$0\"; exit 128;; esac"

        assertEquals(
            RemoteRead.Failed("git could not be run"),
            cacheOf(scriptGit(gone)).read(repository, head, "action.yml", network = false),
        )
    }

    @Test
    fun `a commit the server does not have is not read, offline or online`() {
        fetchedMain()
        val unknown = "a".repeat(40)

        assertEquals(RemoteRead.NotCached, cacheOf().read(repository, unknown, "action.yml", network = false))
        assertEquals(RemoteRead.Failed("ref not found"), cacheOf().read(repository, unknown, "action.yml", true))
    }

    @Test
    fun `a read cancelled as the answer arrives fails whatever git answered`() {
        fetchedMain(content = true)
        val cancelled = RemoteRead.Failed("cancelled")

        // rev-parse for the commit, ls-tree for the file, cat-file for its content.
        for ((word, nth) in listOf("rev-parse" to 1, "ls-tree" to 2, "blob" to 3)) {
            val cancellation = GitCancellation()
            val read =
                cancelledAfter(nth, cancellation) {
                    cacheOf().read(repository, head, "action.yml", network = true, cancellation = cancellation)
                }
            assertEquals(cancelled, read, "after $word")
        }
    }

    @Test
    fun `an offline read cancelled as git says the content is not there fails`() {
        fetchedMain()
        val cancellation = GitCancellation()

        val read =
            cancelledAfter(4, cancellation) {
                cacheOf().read(repository, head, "action.yml", network = false, cancellation = cancellation)
            }

        assertEquals(RemoteRead.Failed("cancelled"), read)
    }

    @Test
    fun `a command that takes too long times out`() {
        val cache = fetchedMain()
        val hangs = "case \" \$* \" in *' rev-parse '*) sleep 30;; esac"

        assertEquals(
            RemoteRead.Failed("timed out"),
            cacheOf(scriptGit(hangs), localTimeout = 300.milliseconds).read(repository, head, "action.yml", false),
        )
        val stalls = "case \" \$* \" in *' fetch '*) sleep 30;; esac"
        assertEquals(
            RemoteFetch.Failed("timed out"),
            cacheOf(
                scriptGit(stalls),
                localTimeout = 1.minutes,
                networkTimeout = 300.milliseconds,
            ).fetch(repository, "main"),
        )
        assertEquals(head, cache.commit(repository, "main")?.commit, "what was cached stays")
    }

    @Test
    fun `a listing of the tree or the making of the repository that takes too long fails`() {
        fetchedMain()
        val listing = "case \" \$* \" in *' ls-tree '*) sleep 30;; esac"
        assertEquals(
            RemoteRead.Failed("timed out"),
            cacheOf(scriptGit(listing), localTimeout = 300.milliseconds).read(repository, head, "action.yml", false),
        )
        bare.toFile().deleteRecursively()
        val making = "case \" \$* \" in *' init '*) sleep 30;; esac"

        assertEquals(
            RemoteFetch.Failed("could not create the cache"),
            cacheOf(scriptGit(making), localTimeout = 300.milliseconds).fetch(repository, "main"),
        )
    }

    @Test
    fun `a file or a dangling link where the repository goes is replaced`() {
        bare.parent.createDirectories()
        bare.writeText("a file")
        assertIs<RemoteFetch.Fetched>(cacheOf().fetch(repository, "main"))
        assertTrue(Files.isDirectory(bare))

        bare.toFile().deleteRecursively()
        Files.createSymbolicLink(bare, folder.resolve("nowhere"))
        assertIs<RemoteFetch.Fetched>(cacheOf().fetch(repository, "main"))
        assertTrue(Files.isDirectory(bare, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `a repository that cannot be created says so and leaves no folder, also when made anew`() {
        val cannot = RemoteFetch.Failed("could not create the cache")

        assertEquals(cannot, cacheOf(failingGit(folder, "init")).fetch(repository, "main"))
        assertFalse(bare.exists())

        bare.createDirectories()
        bare.resolve("junk").writeText("junk")
        assertEquals(cannot, cacheOf(failingGit(folder, "init")).fetch(repository, "main"))
        assertFalse(bare.exists(), "the broken one is gone, nothing took its place")
    }

    @Test
    fun `leftovers of an earlier creation or replacement do not get into the new repository`() {
        val sibling = bare.parent
        sibling
            .resolve("repo.git.new")
            .createDirectories()
            .resolve("stale")
            .writeText("stale")
        sibling
            .resolve("repo.git.old")
            .createDirectories()
            .resolve("junk")
            .writeText("junk")
        bare.createDirectories()
        bare.resolve("junk").writeText("junk")

        assertIs<RemoteFetch.Fetched>(cacheOf().fetch(repository, "main"))

        assertFalse(sibling.resolve("repo.git.new").exists())
        assertFalse(sibling.resolve("repo.git.old").exists())
        assertFalse(bare.resolve("stale").exists())
        assertFalse(bare.resolve("junk").exists())
        assertTrue(bare.resolve("HEAD").exists())
    }

    @Test
    fun `a new repository is configured for no hooks and no garbage collection, files only for local servers`() {
        assertIs<RemoteFetch.Fetched>(cacheOf().fetch(repository, "main"))
        val config = bare.resolve("config").readText()
        assertTrue("hooksPath = /dev/null" in config, config)
        assertTrue("[gc]\nauto = 0\n" in config.replace("\t", ""), config)
        assertTrue("[protocol \"file\"]\n\tallow = always\n" in config, config)

        // Where the fetch is, git has made the repository: copy its configuration, and fail the fetch.
        val copy = folder.resolve("config.copy")
        val copying = "case \" \$* \" in *' fetch '*) cp \"\${1#--git-dir=}/config\" '$copy'; exit 1;; esac"
        val https = repository.copy(url = "https://example.com/owner/repo.git")
        val remote = RemoteSourceCache(scriptGit(copying), folder.resolve("remote-cache"), false)

        assertEquals(RemoteFetch.Failed("fetch failed"), remote.fetch(https, "main"))
        val copied = copy.readText()
        assertTrue("hooksPath = /dev/null" in copied, copied)
        assertFalse("protocol \"file\"" in copied, copied)
    }

    @ParameterizedTest
    @MethodSource("brokenCaches")
    fun `only a cache git takes for no repository is made anew, after a fetch that failed with 128`(case: BrokenCase) {
        fetchedMain()
        val body =
            "case \" \$* \" in\n*' fetch '*) ${case.fetch};;\n*' --git-dir '*) ${case.isRepository};;\nesac"
        val cache = cacheOf(scriptGit(body, pipeGrace = 100.milliseconds), localTimeout = 1.seconds)

        assertEquals(RemoteFetch.Failed("fetch failed"), cache.fetch(repository, "main"), case.name)

        // Made anew, the cache holds nothing and is deleted with the failed fetch that made it.
        assertEquals(!case.remade, bare.exists(), case.name)
        assertEquals(if (case.remade) null else head, cacheOf().commit(repository, "main")?.commit, case.name)
    }

    data class BrokenCase(
        val name: String,
        val fetch: String,
        val isRepository: String,
        val remade: Boolean,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val FETCH_FAILS = "echo 'fatal: nope' >&2; exit 128"

        @JvmStatic
        fun brokenCaches() =
            listOf(
                BrokenCase(
                    "git says it is none",
                    FETCH_FAILS,
                    "echo 'fatal: not a git repository' >&2; exit 128",
                    true,
                ),
                BrokenCase("git says it is one", FETCH_FAILS, "exit 0", false),
                BrokenCase("a failure of another kind", FETCH_FAILS, "exit 1", false),
                BrokenCase("an answer cut off", FETCH_FAILS, "(sleep 20 &); sleep 0.3; exit 128", false),
                BrokenCase("an answer that takes too long", FETCH_FAILS, "sleep 30", false),
                BrokenCase("no answer at all", "rm -f \"\$0\"; echo 'fatal: nope' >&2; exit 128", "exit 0", false),
                BrokenCase(
                    "a fetch failing otherwise",
                    "echo 'fatal: nope' >&2; exit 1",
                    "echo 'fatal: not a git repository' >&2; exit 128",
                    false,
                ),
            )
    }

    @Test
    fun `a fetch that says it fetched but gives no commit fails, a hash with blanks is trimmed`() {
        fun fetchWith(answer: String) =
            RemoteSourceCache(
                scriptGit("case \" \$* \" in *'FETCH_HEAD'*) $answer;; esac"),
                folder.resolve("answers-$scripts"),
                true,
            ).fetch(repository, "main")

        assertEquals(RemoteFetch.Failed("'main' is no commit"), fetchWith("exit 1"))
        assertEquals(RemoteFetch.Failed("'main' is no commit"), fetchWith("echo not-a-commit; exit 0"))
        assertEquals(RemoteFetch.Failed("'main' is no commit"), fetchWith("echo ${"g".repeat(40)}; exit 0"))
        assertEquals(RemoteFetch.Failed("'main' is no commit"), fetchWith("echo ${head.take(39)}; exit 0"))
        val padded = fetchWith("echo \"  $head  \"; exit 0")
        assertEquals(head, assertIs<RemoteFetch.Fetched>(padded).commit.commit)
    }

    @Test
    fun `a commit that cannot be kept from collection fails the fetch`() {
        val refused = RemoteFetch.Failed("could not update the cache")

        assertEquals(
            refused,
            cacheOf(scriptGit("case \" \$* \" in *' update-ref '*) exit 1;; esac")).fetch(repository, "main"),
        )
        assertNull(cacheOf().commit(repository, "main"))
        assertFalse(bare.exists())
    }

    @Test
    fun `every fetched ref is kept under a name of its own, one component long`() {
        git("branch", "dev/x")
        val cache = cacheOf()
        assertIs<RemoteFetch.Fetched>(cache.fetch(repository, "main"))
        assertIs<RemoteFetch.Fetched>(cache.fetch(repository, "dev/x"))

        val process =
            ProcessBuilder("git", "--git-dir=$bare", "for-each-ref", "--format=%(refname) %(objectname)", "refs/sluide")
                .redirectErrorStream(true)
                .apply { environment().putAll(testGitEnvironment()) }
                .start()
        val refs =
            process.inputStream
                .readAllBytes()
                .decodeToString()
                .lines()
                .filter { it.isNotEmpty() }
        process.waitFor()

        assertEquals(2, refs.size, refs.toString())
        assertTrue("refs/sluide/cache/0d6e4079e36703ebd37c00722f5891d28b0e2811dc114b129215123adcce3605 $head" in refs)
        assertTrue(refs.all { Regex("refs/sluide/cache/[0-9a-f]{64} $head").matches(it) }, refs.toString())
    }

    @Test
    fun `a fetch cancelled as git answers fails whatever the answer was, and remembers nothing`() {
        // init makes the repository, then fetch, rev-parse FETCH_HEAD, update-ref.
        for ((word, nth) in listOf("fetch" to 2, "FETCH_HEAD" to 3, "update-ref" to 4)) {
            val cancellation = GitCancellation()
            val result =
                cancelledAfter(nth, cancellation) { cacheOf().fetch(repository, "main", cancellation) }

            assertEquals(RemoteFetch.Failed("cancelled"), result, "after $word")
            assertNull(cacheOf().commit(repository, "main"), "after $word")
            assertFalse(bare.exists(), "after $word")
        }
    }

    @Test
    fun `a list of fetched refs that cannot be written fails the fetch and removes only a repository it made`() {
        val blocking =
            "case \" \$* \" in *' update-ref '*) d=\"\${1#--git-dir=}\"; rm -rf \"\$d/sluide-refs.properties\"; " +
                "mkdir -p \"\$d/sluide-refs.properties\"; echo x > \"\$d/sluide-refs.properties/f\";; esac"

        assertEquals(
            RemoteFetch.Failed("cache folder not writable"),
            cacheOf(scriptGit(blocking)).fetch(repository, "main"),
        )
        assertFalse(bare.exists(), "a repository this fetch made")

        fetchedMain()
        assertEquals(
            RemoteFetch.Failed("cache folder not writable"),
            cacheOf(scriptGit(blocking)).fetch(repository, "main"),
        )
        assertTrue(bare.resolve("HEAD").exists(), "a repository that was there")
    }

    @Test
    fun `a git that vanishes after the repository was made fails the fetch, one that cannot make it says so`() {
        assertEquals(
            RemoteFetch.Failed("could not create the cache"),
            cacheOf(doomedGit()).fetch(repository, "main"),
        )
        val afterInit = scriptGit("case \" \$* \" in *' init '*) rm -f \"\$0\";; esac")

        assertEquals(RemoteFetch.Failed("git could not be run"), cacheOf(afterInit).fetch(repository, "main"))
        assertFalse(bare.exists())
    }

    @Test
    fun `a fetched commit is taken only from a complete answer of a successful rev-parse`() {
        fun fetchWith(
            answer: String,
            pipeGrace: Duration = 2.seconds,
        ) = RemoteSourceCache(
            scriptGit("case \" \$* \" in *'FETCH_HEAD'*) $answer;; esac", pipeGrace),
            folder.resolve("answers-$scripts"),
            true,
        ).fetch(repository, "main")

        assertEquals(RemoteFetch.Failed("'main' is no commit"), fetchWith("echo $head; exit 1"))
        assertEquals(
            RemoteFetch.Failed("'main' is no commit"),
            fetchWith("echo $head; (sleep 20 &); sleep 0.3; exit 0", 100.milliseconds),
        )
    }

    @Test
    fun `a ref is fetched and remembered by its name as it is, in upper case too`() {
        git("branch", "Feature-X")
        val cache = cacheOf()

        assertIs<RemoteFetch.Fetched>(cache.fetch(repository, "Feature-X"))

        assertEquals(head, cache.commit(repository, "Feature-X")?.commit)
    }

    @Test
    fun `a file is the entry of the tree with its name that is a blob, and not one git failed to list`() {
        fetchedMain(content = true)
        // The shape of `ls-tree -z`: <mode> SP <type> SP <object> TAB <path> NUL (checked with git 2.56).
        val other = "100644 blob ${"b".repeat(40)}\tother.yml"
        val tree = "040000 tree ${"c".repeat(40)}\taction.yml"

        fun readWith(exit: Int) =
            cacheOf(
                scriptGit("case \" \$* \" in *' ls-tree '*) printf '%s\\0%s\\0' '$other' '$tree'; exit $exit;; esac"),
            ).read(repository, head, "action.yml", network = false)

        assertEquals(RemoteRead.Missing, readWith(0))
        assertEquals(RemoteRead.Failed("could not read the file"), readWith(128))
        val listed = "100644 blob $head\taction.yml"
        val failing =
            cacheOf(scriptGit("case \" \$* \" in *' ls-tree '*) printf '%s\\0' '$listed'; exit 1;; esac"))
        assertEquals(RemoteRead.Failed("could not read the file"), failing.read(repository, head, "action.yml", false))
    }

    @Test
    fun `commands that cannot connect run without protocols, those that may only with https and ssh`() {
        val log = folder.resolve("env.log")
        val recording =
            "case \" \$* \" in *' rev-parse '*|*' ls-tree '*|*' cat-file '*|*' fetch '*) " +
                "printf '%s|%s|%s\\n' \"\$GIT_NO_LAZY_FETCH\" \"\$GIT_ALLOW_PROTOCOL\" \"\$*\" >> '$log';; esac"
        fetchedMain(content = true)
        log.toFile().delete()
        val cache = cacheOf(scriptGit(recording))

        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, head, "action.yml", network = false))
        val offline = log.toFile().readLines()
        assertTrue(offline.isNotEmpty() && offline.all { it.startsWith("1||") }, offline.toString())
        assertTrue(offline.all { "protocol.https.allow=never" in it && "remote.sluide-cache.url" !in it }, "$offline")

        log.toFile().delete()
        assertIs<RemoteFetch.Fetched>(cache.fetch(repository, "main"))
        val online = log.toFile().readLines().filter { " fetch " in it }
        assertEquals(1, online.size, online.toString())
        assertTrue(online.single().startsWith("|https:ssh:file|"), online.single())
        assertTrue(
            "protocol.file.allow=always -c remote.sluide-cache.url=file://$upstream" in online.single(),
            "$online",
        )

        // Without local servers (the https address stands in; the fetch itself is the script's to fail).
        log.toFile().delete()
        val https = repository.copy(url = "https://example.com/owner/repo.git")
        val remote = RemoteSourceCache(scriptGit(recording), folder.resolve("remote-cache"), false)
        remote.fetch(https, "main")
        val strict = log.toFile().readLines().single { " fetch " in it }
        assertTrue(strict.startsWith("|https:ssh|"), strict)
        assertTrue("protocol.file.allow=never -c remote.sluide-cache.url=${https.url}" in strict, strict)
    }

    @Test
    fun `a repository folder is deleted only below a host folder of the cache, links not followed`() {
        val cache = cacheOf()
        val outside = folder.resolve("far/away/outside").createDirectories()
        outside.resolve("precious").writeText("keep")
        val host = cacheFolder.resolve("example.com").createDirectories()
        val owner = host.resolve("owner").createDirectories()
        owner
            .resolve("repo.git.old/inner")
            .createDirectories()
            .resolve("file")
            .writeText("x")
        Files.createSymbolicLink(owner.resolve("link"), outside)

        assertFailsWith<IOException> { cache.deleteRecursively(outside) }
        assertFailsWith<IOException> { cache.deleteRecursively(cacheFolder) }
        assertFailsWith<IOException> { cache.deleteRecursively(host) }
        assertFailsWith<IOException> {
            cache.deleteRecursively(
                cacheFolder.resolve("example.com/owner/../../../far/away/outside"),
            )
        }
        assertTrue(host.exists() && outside.resolve("precious").exists())

        cache.deleteRecursively(owner.resolve("link"))
        cache.deleteRecursively(owner.resolve("repo.git.old"))
        cache.deleteRecursively(owner.resolve("missing"))

        assertFalse(owner.resolve("link").exists(java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(outside.resolve("precious").exists(), "the link's target is not deleted")
        assertFalse(owner.resolve("repo.git.old").exists())
        cache.deleteRecursively(owner)
        assertFalse(owner.exists())
        assertTrue(host.exists())
    }
}
