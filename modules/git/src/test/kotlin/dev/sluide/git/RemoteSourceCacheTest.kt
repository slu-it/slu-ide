package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fetches with the real git from a local "server" over `file://`; skipped if git is not installed. */
class RemoteSourceCacheTest {
    @TempDir
    lateinit var folder: Path

    /** The repository on the "server", with a working tree to commit in; its path ends with its owner and name. */
    private val upstream get() = folder.resolve("server/owner/repo")

    private val cacheFolder get() = folder.resolve("cache")

    private val git = GitCli("git", testGitEnvironment())

    private val cache by lazy { RemoteSourceCache(git, cacheFolder, allowFileUrls = true) }

    private val repository get() = RemoteRepository("example.com", "owner", "repo", "file://$upstream")

    private val bareRepository get() = cacheFolder.resolve("example.com/owner/repo.git")

    @BeforeEach
    fun createUpstream() {
        assumeTrue(git.available, "git is not installed")
        upstream.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        // What GitHub allows too; a local server needs it for partial clones and fetching commits by their hash.
        git("config", "uploadpack.allowFilter", "true")
        git("config", "uploadpack.allowAnySHA1InWant", "true")
    }

    private fun git(
        vararg arguments: String,
        directory: Path = upstream,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
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

    /** Commits [files] (path to content) in [upstream]; returns the commit's hash. */
    private fun commit(vararg files: Pair<String, String>): String {
        for ((path, content) in files) {
            upstream.resolve(path).also { it.parent.createDirectories() }.writeText(content)
            git("add", path)
        }
        git("commit", "-q", "-m", "Commit")
        return git("rev-parse", "HEAD")
    }

    private fun fetched(ref: String): CachedCommit = assertIs<RemoteFetch.Fetched>(cache.fetch(repository, ref)).commit

    @Test
    fun `fetches a branch and remembers its commit`() {
        val head = commit(".github/workflows/ci.yml" to "on: push\n")
        assertNull(cache.commit(repository, "main"))

        val fetched = fetched("main")

        assertEquals(head, fetched.commit)
        assertEquals(fetched, cache.commit(repository, "main"))
        assertTrue(bareRepository.resolve("HEAD").exists())
    }

    @Test
    fun `fetches an annotated tag as the commit it points to`() {
        val tagged = commit("action.yml" to "name: one\n")
        git("tag", "-a", "v1", "-m", "Version 1")
        commit("action.yml" to "name: two\n")

        assertEquals(tagged, fetched("v1").commit)
        assertEquals(tagged, cache.commit(repository, "v1")?.commit)
    }

    @Test
    fun `fetches a full commit hash once`() {
        val first = commit("action.yml" to "name: one\n")
        commit("action.yml" to "name: two\n")

        val fetched = fetched(first.uppercase())
        assertEquals(first, fetched.commit)
        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, first, "action.yml", network = true))
        // The server is gone: the cache answers.
        upstream.toFile().deleteRecursively()
        assertEquals(fetched, fetched(first))
    }

    @Test
    fun `refreshing a moved branch gives its new commit`() {
        commit("action.yml" to "name: one\n")
        val before = fetched("main")
        val moved = commit("action.yml" to "name: two\n")

        val after = fetched("main")

        assertEquals(moved, after.commit)
        assertFalse(after.fetchedAt.isBefore(before.fetchedAt))
        assertEquals(after, cache.commit(repository, "main"))
        assertEquals(RemoteRead.Found("name: two\n"), cache.read(repository, moved, "action.yml", network = true))
    }

    @Test
    fun `reads a file, tells a missing one and refuses one over 1 MB`() {
        val head = commit("action.yml" to "name: ä\n", "docs/big.txt" to "a".repeat(1024 * 1024 + 1))
        fetched("main")

        assertEquals(RemoteRead.Found("name: ä\n"), cache.read(repository, head, "action.yml", network = true))
        assertEquals(RemoteRead.Missing, cache.read(repository, head, "missing.yml", network = true))
        assertEquals(RemoteRead.Missing, cache.read(repository, head, "docs", network = true))
        assertEquals(RemoteRead.Missing, cache.read(repository, head, "action.yml/x", network = false))
        assertEquals(RemoteRead.Failed("over 1 MB"), cache.read(repository, head, "docs/big.txt", network = true))
    }

    @Test
    fun `reads without the network only what is in the cache`() {
        val head = commit("action.yml" to "name: one\n")
        assertEquals(RemoteRead.NotCached, cache.read(repository, head, "action.yml", network = false))
        assertFalse(bareRepository.exists())
        fetched("main")

        // The fetch has the trees, not the file contents.
        assertEquals(RemoteRead.NotCached, cache.read(repository, head, "action.yml", network = false))
        assertEquals(RemoteRead.Missing, cache.read(repository, head, "missing.yml", network = false))
        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, head, "action.yml", network = true))
        upstream.toFile().deleteRecursively()
        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, head, "action.yml", network = false))
    }

    @Test
    fun `reads a commit that was not fetched yet with the network only`() {
        val first = commit("action.yml" to "name: one\n")
        commit("action.yml" to "name: two\n")
        fetched("main")

        assertEquals(RemoteRead.NotCached, cache.read(repository, first, "action.yml", network = false))
        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, first, "action.yml", network = true))
    }

    @Test
    fun `a server without filters sends the file contents along`() {
        val head = commit("action.yml" to "name: one\n")
        git("config", "uploadpack.allowFilter", "false")

        assertEquals(head, fetched("main").commit)
        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, head, "action.yml", network = false))
    }

    @Test
    fun `tells a missing ref and a missing repository`() {
        commit("action.yml" to "name: one\n")

        assertEquals(RemoteFetch.Failed("ref not found"), cache.fetch(repository, "v9"))
        assertNull(cache.commit(repository, "v9"))
        val gone = repository.copy(url = "file://${folder.resolve("gone/owner/repo")}")
        assertEquals(RemoteFetch.Failed("repository not found or no access"), cache.fetch(gone, "main"))
    }

    @Test
    fun `nothing asks for credentials, also with an ssh the user chose`() {
        commit("action.yml" to "name: one\n")
        val seen = folder.resolve("seen")
        val hook = hook("echo \"\$GIT_ASKPASS|\$SSH_ASKPASS_REQUIRE|\$GCM_INTERACTIVE\" > '$seen'\nexec \"\$@\"")
        val chosen = cacheWith("[uploadpack]\n\tpackObjectsHook = $hook\n", mapOf("GIT_SSH_COMMAND" to "ssh"))

        assertIs<RemoteFetch.Fetched>(chosen.fetch(repository, "main"))
        assertEquals(listOf("|never|never"), seen.readLines())
    }

    @Test
    fun `a failed fetch deletes the folder it created, not one that was there`() {
        val gone = repository.copy(url = "file://${folder.resolve("gone/owner/repo")}")
        assertEquals(RemoteFetch.Failed("repository not found or no access"), cache.fetch(gone, "main"))
        assertFalse(bareRepository.exists(), "no folder for a repository that does not exist")
        assertTrue(bareRepository.resolveSibling("repo.git.lock").exists(), "the lock file stays")

        val head = commit("action.yml" to "name: one\n")
        fetched("main")
        assertEquals(RemoteFetch.Failed("ref not found"), cache.fetch(repository, "v9"))
        assertEquals(RemoteFetch.Failed("repository not found or no access"), cache.fetch(gone, "main"))
        assertEquals(head, cache.commit(repository, "main")?.commit)
        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, head, "action.yml", network = true))
    }

    @Test
    fun `refuses invalid names, refs, commits, paths and URLs before git runs`() {
        val commit = commit("action.yml" to "name: one\n")
        // One case per entry point; what each refusal covers is tested in RemoteRefusalsTest.
        assertIs<RemoteFetch.Failed>(cache.fetch(repository.copy(host = "-oProxyCommand=x"), "main"))
        assertIs<RemoteFetch.Failed>(cache.fetch(repository.copy(url = "ext::sh -c touch% /tmp/pwned"), "main"))
        assertIs<RemoteFetch.Failed>(cache.fetch(repository, "a..b"))
        assertNull(cache.commit(repository, "a..b"))
        assertIs<RemoteRead.Failed>(cache.read(repository, commit, "../action.yml", network = true))
        assertIs<RemoteRead.Failed>(cache.read(repository, "main", "action.yml", network = true))
        assertIs<RemoteRead.Failed>(cache.read(repository, commit.take(12), "action.yml", network = true))
        // Local servers only for tests.
        assertIs<RemoteFetch.Failed>(RemoteSourceCache(git, cacheFolder).fetch(repository, "main"))
        assertFalse(cacheFolder.exists())
    }

    @Test
    fun `a cache folder that cannot be created is not writable`() {
        commit("action.yml" to "name: one\n")
        cacheFolder.writeText("a file where the folder should be")

        assertEquals(RemoteFetch.Failed("cache folder not writable"), cache.fetch(repository, "main"))
        assertNull(cache.commit(repository, "main"))
    }

    @Test
    fun `a cancelled fetch fails and remembers nothing`() {
        commit("action.yml" to "name: one\n")
        val cancelled = GitCancellation().apply { cancel() }
        assertEquals(RemoteFetch.Failed("cancelled"), cache.fetch(repository, "main", cancelled))

        val started = folder.resolve("started")
        val slow = slowCache(started, folder.resolve("log"), seconds = "30")[0]
        val cancellation = GitCancellation()
        val fetch = CompletableFuture.supplyAsync { slow.fetch(repository, "main", cancellation) }
        awaitFile(started)
        cancellation.cancel()

        assertEquals(RemoteFetch.Failed("cancelled"), fetch.get(10, TimeUnit.SECONDS))
        assertNull(slow.commit(repository, "main"))
        assertFalse(bareRepository.exists(), "the folder the cancelled fetch created is deleted")
    }

    @Test
    fun `a fetch cancelled while it waits for another fetch of the repository fails at once`() {
        commit("action.yml" to "name: one\n")
        val started = folder.resolve("started")
        val slow = slowCache(started, folder.resolve("log"), seconds = "30")
        val running = GitCancellation()
        val first = CompletableFuture.supplyAsync { slow[0].fetch(repository, "main", running) }
        awaitFile(started)
        val waitingCancellation = GitCancellation()
        val waiting = CompletableFuture<RemoteFetch>()
        val waitingThread =
            Thread {
                try {
                    waiting.complete(slow[1].fetch(repository, "main", waitingCancellation))
                } catch (e: Throwable) {
                    waiting.completeExceptionally(e)
                }
            }.apply {
                isDaemon = true
                start()
            }
        // It polls the lock with a timeout; waiting in that state means it has reached the lock and not got it.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (waitingThread.state != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() < deadline, "the second fetch never waited, state ${waitingThread.state}")
            Thread.sleep(10)
        }
        assertFalse(waiting.isDone, "it waits for the lock")
        val cancelledAt = System.nanoTime()

        waitingCancellation.cancel()

        assertEquals(RemoteFetch.Failed("cancelled"), waiting.get(5, TimeUnit.SECONDS))
        assertTrue(System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(5), "well before the first fetch ends")
        assertFalse(first.isDone, "the first fetch is still running")
        running.cancel()
        assertEquals(RemoteFetch.Failed("cancelled"), first.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `fetches of one repository take turns`() {
        commit("action.yml" to "name: one\n")
        git("tag", "v1")
        commit("action.yml" to "name: two\n")
        val log = folder.resolve("log")
        // Two caches on one folder: also they take turns, with one lock.
        val slow = slowCache(folder.resolve("started"), log, seconds = "0.3")

        val fetches =
            listOf("main", "v1").zip(slow).map { (ref, cache) ->
                CompletableFuture.supplyAsync { cache.fetch(repository, ref) }
            }

        fetches.forEach { assertIs<RemoteFetch.Fetched>(it.get(30, TimeUnit.SECONDS)) }
        assertEquals(listOf("start", "end", "start", "end"), log.readLines())
    }

    @Test
    fun `a broken cache folder is created anew`() {
        val head = commit("action.yml" to "name: one\n")
        bareRepository.createDirectories()
        bareRepository.resolve("junk").writeText("junk")

        assertEquals(head, fetched("main").commit)
        assertEquals(RemoteRead.Found("name: one\n"), cache.read(repository, head, "action.yml", network = true))
    }

    @Test
    fun `an unreadable list of fetched refs counts as empty`() {
        commit("action.yml" to "name: one\n")
        fetched("main")
        bareRepository.resolve("sluide-refs.properties").writeText("main=nonsense\nv1=\\u00")

        assertNull(cache.commit(repository, "main"))
        assertNotNull(fetched("main"))
    }

    @Test
    fun `a failing server does not get the cache created anew`() {
        val head = commit("action.yml" to "name: one\n")
        fetched("main")
        val failing =
            cacheWith(
                "[uploadpack]\n\tpackObjectsHook = ${hook(
                    "echo \"fatal: not a git repository: '$bareRepository'\" >&2; exit 128",
                )}\n",
            )
        commit("action.yml" to "name: two\n")

        assertIs<RemoteFetch.Failed>(failing.fetch(repository, "main"))

        assertEquals(head, cache.commit(repository, "main")?.commit)
    }

    @Test
    fun `a URL the user's configuration rewrites to a local path is not fetched`() {
        commit("action.yml" to "name: one\n")
        val https = repository.copy(url = "https://example.com/owner/repo.git")
        // Allowing every protocol, also through the variable, which a cache sets itself.
        val config = "[url \"file://$upstream\"]\n\tinsteadOf = ${https.url}\n[protocol]\n\tallow = always\n"
        val environment = mapOf("GIT_ALLOW_PROTOCOL" to "file:https:ssh")

        val refused = cacheWith(config, environment, allowFileUrls = false, folder = folder.resolve("refused"))
        assertIs<RemoteFetch.Failed>(refused.fetch(https, "main"))
        assertNull(refused.commit(https, "main"))
        // The rewrite works where file:// is allowed (tests only).
        assertIs<RemoteFetch.Fetched>(cacheWith(config, environment).fetch(https, "main"))
    }

    @Test
    fun `a URL the user configured for origin does not come before the repository's`() {
        val head = commit("action.yml" to "name: one\n")
        val other = folder.resolve("other/owner/repo")
        other.createDirectories()
        git("init", "-q", "-b", "main", directory = other)
        git(
            "-c",
            "user.name=Test",
            "-c",
            "user.email=t@example.com",
            "commit",
            "-q",
            "--allow-empty",
            "-m",
            "Other",
            directory = other,
        )
        val configured = cacheWith("[remote \"origin\"]\n\turl = file://$other\n")

        assertEquals(head, assertIs<RemoteFetch.Fetched>(configured.fetch(repository, "main")).commit.commit)
        assertEquals(RemoteRead.Found("name: one\n"), configured.read(repository, head, "action.yml", network = true))
    }

    @Test
    fun `reads without the network start no fetch whatever the configuration says`() {
        val head = commit("action.yml" to "name: one\n")
        fetched("main")
        val log = folder.resolve("log")
        val config =
            "[remote \"sluide-cache\"]\n\turl = file://$upstream\n[protocol]\n\tallow = always\n" +
                "[uploadpack]\n\tpackObjectsHook = ${hook("echo fetched >> '$log'; exec \"$@\"")}\n"
        val configured = cacheWith(config, mapOf("GIT_ALLOW_PROTOCOL" to "file:https:ssh"))

        assertEquals(RemoteRead.NotCached, configured.read(repository, head, "action.yml", network = false))
        assertFalse(log.exists(), "the server was asked")
        // The server would have answered.
        assertEquals(RemoteRead.Found("name: one\n"), configured.read(repository, head, "action.yml", network = true))
        assertTrue(log.exists())
    }

    /**
     * Two caches on [cacheFolder] whose server runs `pack-objects` through a hook that appends `start` to [log],
     * creates [started], sleeps [seconds] and appends `end`.
     */
    private fun slowCache(
        started: Path,
        log: Path,
        seconds: String,
    ): List<RemoteSourceCache> {
        val hook = hook("echo start >> '$log'\ntouch '$started'\nsleep $seconds\necho end >> '$log'\nexec \"$@\"")
        return List(2) { cacheWith("[uploadpack]\n\tpackObjectsHook = $hook\n") }
    }

    private var hooks = 0

    /** A shell script running [body], e.g. for `uploadpack.packObjectsHook`. */
    private fun hook(body: String): Path =
        folder.resolve("hook-${++hooks}.sh").apply {
            writeText("#!/bin/sh\n$body\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
        }

    /**
     * A cache on [folder] whose git has [config] as global configuration (the only place, besides the system's, from
     * which the server honours `uploadpack.packObjectsHook`) and [environment] besides the tests'.
     */
    private fun cacheWith(
        config: String,
        environment: Map<String, String> = emptyMap(),
        allowFileUrls: Boolean = true,
        folder: Path = cacheFolder,
    ): RemoteSourceCache {
        val global = this.folder.resolve("global-${++hooks}.gitconfig")
        global.writeText(config)
        val variables = testGitEnvironment() + ("GIT_CONFIG_GLOBAL" to global.toString()) + environment
        return RemoteSourceCache(GitCli("git", variables), folder, allowFileUrls)
    }

    private fun awaitFile(file: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!Files.exists(file)) {
            assertTrue(System.nanoTime() < deadline, "$file was not created")
            Thread.sleep(20)
        }
    }
}
