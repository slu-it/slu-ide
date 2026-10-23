package dev.sluide.ide.github

import dev.sluide.git.CachedCommit
import dev.sluide.git.GitCancellation
import dev.sluide.git.RemoteFetch
import dev.sluide.git.RemoteRead
import dev.sluide.git.RemoteRepository
import dev.sluide.githubactions.RemoteFile
import dev.sluide.githubactions.RemoteText
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowWorkflowSourcesTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val fetchedAt: Instant = Instant.parse("2026-10-20T10:00:00Z")
    private val action = RemoteFile("Actions", "Checkout", "v6", "action.yml")
    private val ghes = "https://user:token@ghes.example.com/team/project.git"

    /** A cache in memory: commits by (host, ref), files by (host, path); records what it was asked. */
    private class FakeCache : RemoteCache {
        val commits = mutableMapOf<Pair<String, String>, CachedCommit>()
        val files = mutableMapOf<Pair<String, String>, RemoteRead>()
        val fetchable = mutableMapOf<Pair<String, String>, RemoteFetch>()
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var onFetch: (GitCancellation) -> Unit = {}
        var onRead: (GitCancellation) -> Unit = {}

        override fun commit(
            repository: RemoteRepository,
            ref: String,
        ): CachedCommit? {
            calls += "commit ${repository.host} ${repository.owner}/${repository.name}@$ref"
            return commits[repository.host to ref]
        }

        override fun fetch(
            repository: RemoteRepository,
            ref: String,
            cancellation: GitCancellation,
        ): RemoteFetch {
            calls += "fetch ${repository.url} $ref"
            onFetch(cancellation)
            if (cancellation.cancelled) return RemoteFetch.Failed("cancelled")
            val fetched = fetchable[repository.host to ref] ?: RemoteFetch.Failed("ref not found")
            if (fetched is RemoteFetch.Fetched) commits[repository.host to ref] = fetched.commit
            return fetched
        }

        override fun read(
            repository: RemoteRepository,
            commit: String,
            path: String,
            network: Boolean,
            cancellation: GitCancellation,
        ): RemoteRead {
            calls += "read ${repository.host} $commit $path network=$network"
            onRead(cancellation)
            if (cancellation.cancelled) return RemoteRead.Failed("cancelled")
            val read = files[repository.host to path] ?: RemoteRead.Missing
            return if (read == RemoteRead.NotCached && network) RemoteRead.Found("fetched $path") else read
        }
    }

    private val cache = FakeCache()
    private val originReads = mutableListOf<Path>()

    private fun sources(
        origin: String? = null,
        trusted: (cancelled: () -> Boolean) -> Boolean = { true },
        folder: Path? = Path.of("/project"),
    ) = WindowWorkflowSources(
        cache,
        folder = { folder },
        trusted = trusted,
        originUrl = {
            originReads.add(it)
            origin
        },
    )

    /** The page of actions/checkout's [path] at [commit] on [host]. */
    private fun page(
        commit: String,
        path: String = "action.yml",
        host: String = "github.com",
    ) = URI("https://$host/actions/checkout/blob/$commit/$path")

    @Test
    fun `a file found comes with its page at the commit by https on the host it was read from`() {
        val origins =
            mapOf(
                "https://ghes.example.com:8443/team/project.git" to "ghes.example.com:8443",
                "https://ghes.example.com/team/project.git" to "ghes.example.com",
                "git@ghes.example.com:team/project.git" to "ghes.example.com",
                "ssh://git@ghes.example.com:2222/team/project.git" to "ghes.example.com",
                "https://github.com/team/project.git" to "github.com",
                "git@github.com:team/project.git" to "github.com",
            )
        for ((origin, host) in origins) {
            cache.commits[host to "v6"] = CachedCommit("abc123", fetchedAt)
            cache.files[host to "my action/action.yml"] = RemoteRead.Found("text")
            val text =
                sources(
                    origin,
                ).reader(network = false) { false }.read(action.copy(path = "my action/action.yml"))
            assertEquals(page("abc123", "my%20action/action.yml", host), (text as RemoteText.Found).web, origin)
            cache.commits.clear()
        }
    }

    @Test
    fun `percent signs, hashes and question marks of a path are encoded in its page, which has no query or fragment`() {
        cached("github.com")
        val path = "a%2e%2e/b#c?d.yml"
        cache.files["github.com" to path] = RemoteRead.Found("text")

        val web = (sources().reader(network = false) { false }.read(action.copy(path = path)) as RemoteText.Found).web

        assertEquals(page("c-github.com", "a%252e%252e/b%23c%3Fd.yml"), web)
        assertEquals("/actions/checkout/blob/c-github.com/$path", web?.path)
        assertEquals(null, web?.rawQuery)
        assertEquals(null, web?.rawFragment)
    }

    private fun cached(
        host: String,
        ref: String = "v6",
    ) {
        cache.commits[host to ref] = CachedCommit("c-$host", fetchedAt)
    }

    @Test
    fun `without the network the cache is read, never fetched, with the date the ref was fetched`() {
        cached("github.com")
        cache.files["github.com" to "action.yml"] = RemoteRead.Found("name: Checkout")
        val text = sources().reader(network = false) { false }.read(action)
        assertEquals(RemoteText.Found("name: Checkout", "c-github.com", fetchedAt, page("c-github.com")), text)
        assertEquals(
            listOf("commit github.com actions/checkout@v6", "read github.com c-github.com action.yml network=false"),
            cache.calls,
        )
    }

    @Test
    fun `without the network what is not cached is not fetched, missing files are missing, failures say why`() {
        val reader = sources().reader(network = false) { false }
        assertEquals(RemoteText.NotFetched, reader.read(action))
        cached("github.com")
        cache.files["github.com" to "action.yml"] = RemoteRead.NotCached
        cache.files["github.com" to "broken/action.yml"] = RemoteRead.Failed("could not read the file")
        assertEquals(RemoteText.NotFetched, reader.read(action))
        assertEquals(RemoteText.Missing, reader.read(action.copy(path = "action.yaml")))
        assertEquals(RemoteText.Failed("could not read the file"), reader.read(action.copy(path = "broken/action.yml")))
        assertTrue(cache.calls.none { it.startsWith("fetch") || it.endsWith("network=true") }, "${cache.calls}")
    }

    @Test
    fun `a full commit SHA never changes, so it has no date`() {
        cached("github.com", sha)
        cache.files["github.com" to "action.yml"] = RemoteRead.Found("name: Checkout")
        val text = sources().reader(network = false) { false }.read(action.copy(ref = sha))
        assertEquals(RemoteText.Found("name: Checkout", "c-github.com", null, page("c-github.com")), text)
    }

    @Test
    fun `a cancelled reader reads nothing`() {
        cached("github.com")
        assertEquals(RemoteText.NotFetched, sources().reader(network = false) { true }.read(action))
        assertEquals(RemoteText.NotFetched, sources().reader(network = true) { true }.read(action))
        assertEquals(emptyList(), cache.calls)
    }

    @Test
    fun `the origin's host is read first, only while git is trusted, at most once per reader`() {
        cached("ghes.example.com")
        cached("github.com")
        cache.files["ghes.example.com" to "action.yml"] = RemoteRead.Found("from GHES")
        cache.files["github.com" to "action.yml"] = RemoteRead.Found("from github.com")
        val reader = sources(ghes).reader(network = false) { false }
        assertEquals("from GHES", (reader.read(action) as RemoteText.Found).text)
        reader.read(action.copy(path = "action.yaml"))
        assertEquals(listOf(Path.of("/project")), originReads)

        val untrusted = sources(ghes, trusted = { false }).reader(network = false) { false }
        assertEquals("from github.com", (untrusted.read(action) as RemoteText.Found).text)
        val noFolder = sources(ghes, folder = null).reader(network = false) { false }
        assertEquals("from github.com", (noFolder.read(action) as RemoteText.Found).text)
        assertEquals(listOf(Path.of("/project")), originReads, "not read when untrusted or without a folder")
    }

    @Test
    fun `the trust is asked when the reader first reads, not when it is created`() {
        cached("ghes.example.com")
        cache.files["ghes.example.com" to "action.yml"] = RemoteRead.Found("from GHES")
        var trusted = false
        var asked = 0
        val reader =
            sources(ghes, trusted = {
                asked++
                trusted
            }).reader(network = false) { false }
        trusted = true
        assertEquals("from GHES", (reader.read(action) as RemoteText.Found).text)
        reader.read(action.copy(path = "action.yaml"))
        assertEquals(1, asked)
    }

    /** A trust that waits until [cancelled] says so (as `ProjectTrust.awaitAllows` does), then `false`. */
    private fun waitingUntilCancelled(asked: CountDownLatch): (() -> Boolean) -> Boolean =
        { cancelled ->
            asked.countDown()
            while (!cancelled()) Thread.sleep(10)
            false
        }

    @Test
    fun `a reader cancelled while it waits for the trust reads no origin and nothing else`() {
        cached("ghes.example.com")
        cached("github.com")
        for (network in listOf(false, true)) {
            val asked = CountDownLatch(1)
            val cancelled = AtomicBoolean(false)
            val reader = sources(ghes, trusted = waitingUntilCancelled(asked)).reader(network) { cancelled.get() }
            Thread {
                asked.await(10, TimeUnit.SECONDS)
                cancelled.set(true)
            }.start()

            assertEquals(RemoteText.NotFetched, reader.read(action), "network=$network")
            assertEquals(RemoteText.NotFetched, reader.read(action.copy(path = "action.yaml")), "network=$network")
        }
        assertEquals(emptyList(), originReads)
        assertEquals(emptyList(), cache.calls)
    }

    @Test
    fun `a wait for the trust cancelled by an edit that was undone decides nothing`() {
        cached("ghes.example.com")
        cached("github.com")
        cache.files["ghes.example.com" to "action.yml"] = RemoteRead.Found("from GHES")
        cache.fetchable["github.com" to "v6"] = RemoteFetch.Fetched(CachedCommit("c-github.com", fetchedAt))
        val cancelled = AtomicBoolean(false)
        var asked = 0

        /** The first time edited while it waits, which stops the wait, then undone; allows the second time. */
        val trusted = { readerCancelled: () -> Boolean ->
            if (++asked == 1) {
                cancelled.set(true)
                val stopped = readerCancelled()
                cancelled.set(false)
                !stopped
            } else {
                !readerCancelled()
            }
        }

        val fetching = sources(ghes, trusted).reader(network = true) { cancelled.get() }
        assertEquals(RemoteText.NotFetched, fetching.read(action))
        assertEquals(RemoteText.NotFetched, fetching.read(action), "stays cancelled")
        assertEquals(emptyList(), cache.calls, "nothing fetched from github.com")
        assertEquals(emptyList(), originReads)

        asked = 0
        val reading = sources(ghes, trusted).reader(network = false) { cancelled.get() }
        assertEquals(RemoteText.NotFetched, reading.read(action))
        assertEquals(emptyList(), originReads, "the cancelled wait decided no origin")
        assertEquals("from GHES", (reading.read(action) as RemoteText.Found).text, "asked again")
        assertEquals(listOf(Path.of("/project")), originReads)
        assertEquals(2, asked)
    }

    @Test
    fun `the trust may allow after a wait the reader's cancellation did not stop`() {
        cached("ghes.example.com")
        cache.files["ghes.example.com" to "action.yml"] = RemoteRead.Found("from GHES")
        val given = mutableListOf<Boolean>()
        val reader =
            sources(ghes, trusted = { readerCancelled ->
                given += readerCancelled()
                Thread.sleep(50)
                given += readerCancelled()
                true
            }).reader(network = true) { false }

        cache.fetchable["ghes.example.com" to "v6"] = RemoteFetch.Fetched(CachedCommit("c-ghes", fetchedAt))
        assertEquals("from GHES", (reader.read(action) as RemoteText.Found).text)
        assertEquals(listOf(false, false), given)
        assertEquals(listOf(Path.of("/project")), originReads)
    }

    @Test
    fun `without the network github com is read when the origin's host does not have the ref`() {
        cached("github.com")
        cache.files["github.com" to "action.yml"] = RemoteRead.Found("from github.com")
        val text = sources(ghes).reader(network = false) { false }.read(action)
        assertEquals("from github.com", (text as RemoteText.Found).text)
    }

    @Test
    fun `with the network each repository and ref is fetched once per reader, files read with the network`() {
        cache.fetchable["github.com" to "v6"] = RemoteFetch.Fetched(CachedCommit("new", fetchedAt))
        cache.files["github.com" to "action.yml"] = RemoteRead.Missing
        cache.files["github.com" to "action.yaml"] = RemoteRead.NotCached
        val reader = sources().reader(network = true) { false }
        assertEquals(RemoteText.Missing, reader.read(action))
        assertEquals(
            RemoteText.Found("fetched action.yaml", "new", fetchedAt, page("new", "action.yaml")),
            reader.read(action.copy(path = "action.yaml")),
        )
        assertEquals(1, cache.calls.count { it.startsWith("fetch") })
        assertEquals("fetch https://github.com/actions/checkout.git v6", cache.calls.first { it.startsWith("fetch") })
        assertTrue(cache.calls.filter { it.startsWith("read") }.all { it.endsWith("network=true") })

        sources().reader(network = true) { false }.read(action)
        assertEquals(2, cache.calls.count { it.startsWith("fetch") }, "a branch or tag again with a new reader")
    }

    @Test
    fun `with the network a full commit SHA already cached is not fetched`() {
        cached("github.com", sha)
        cache.files["github.com" to "action.yml"] = RemoteRead.Found("pinned")
        val text = sources().reader(network = true) { false }.read(action.copy(ref = sha))
        assertEquals(RemoteText.Found("pinned", "c-github.com", null, page("c-github.com")), text)
        assertTrue(cache.calls.none { it.startsWith("fetch") }, "${cache.calls}")
    }

    @Test
    fun `with the network github com is tried when the origin's host fails, which is remembered`() {
        cache.fetchable["ghes.example.com" to "v6"] = RemoteFetch.Failed("repository not found or no access")
        cache.fetchable["github.com" to "v6"] = RemoteFetch.Fetched(CachedCommit("gh", fetchedAt))
        cache.files["github.com" to "action.yml"] = RemoteRead.Found("from github.com")
        val reader = sources("git@ghes.example.com:team/project.git").reader(network = true) { false }
        assertEquals(RemoteText.Found("from github.com", "gh", fetchedAt, page("gh")), reader.read(action))
        reader.read(action.copy(path = "action.yaml"))
        assertEquals(
            listOf(
                "fetch git@ghes.example.com:actions/checkout.git v6",
                "fetch https://github.com/actions/checkout.git v6",
            ),
            cache.calls.filter { it.startsWith("fetch") },
        )
    }

    @Test
    fun `with the network a failure of every host says why for each`() {
        cache.fetchable["ghes.example.com" to "v6"] = RemoteFetch.Failed("repository not found or no access")
        cache.fetchable["github.com" to "v6"] = RemoteFetch.Failed("network error")
        assertEquals(
            RemoteText.Failed("ghes.example.com: repository not found or no access; github.com: network error"),
            sources(ghes).reader(network = true) { false }.read(action),
        )
        assertEquals(
            RemoteText.Failed("ref not found"),
            sources(ghes).reader(network = true) { false }.read(action.copy(ref = "v99")),
        )
    }

    @Test
    fun `a host out of reach is not asked again by the reader`() {
        cache.fetchable["ghes.example.com" to "v6"] = RemoteFetch.Failed("timed out")
        cache.fetchable["github.com" to "v6"] = RemoteFetch.Fetched(CachedCommit("gh", fetchedAt))
        cache.files["github.com" to "action.yml"] = RemoteRead.Found("from github.com")
        cache.commits["ghes.example.com" to sha] = CachedCommit("pinned", fetchedAt)
        cache.files["ghes.example.com" to "pinned.yml"] = RemoteRead.NotCached
        val reader = sources(ghes).reader(network = true) { false }

        assertEquals("from github.com", (reader.read(action) as RemoteText.Found).text)
        assertEquals("from github.com", (reader.read(action.copy(repo = "cache")) as RemoteText.Found).text)
        assertEquals(RemoteText.NotFetched, reader.read(action.copy(repo = "cache", ref = sha, path = "pinned.yml")))
        assertEquals(
            listOf(
                "fetch https://ghes.example.com/actions/checkout.git v6",
                "fetch https://github.com/actions/checkout.git v6",
                "fetch https://github.com/actions/cache.git v6",
            ),
            cache.calls.filter { it.startsWith("fetch") },
        )
        assertTrue("read ghes.example.com pinned pinned.yml network=false" in cache.calls, "${cache.calls}")

        sources(ghes).reader(network = true) { false }.read(action)
        assertEquals(2, cache.calls.count { it.startsWith("fetch https://ghes.example.com") }, "a new reader asks")
    }

    @Test
    fun `a host that failed otherwise is asked again for other repositories`() {
        cache.fetchable["ghes.example.com" to "v6"] = RemoteFetch.Failed("repository not found or no access")
        val reader = sources(ghes).reader(network = true) { false }
        reader.read(action)
        reader.read(action.copy(repo = "cache"))
        assertEquals(2, cache.calls.count { it.startsWith("fetch https://ghes.example.com") })
    }

    /** Blocks until its cancellation is cancelled (at most 10 s), which it records in [stopped]; counts down [started]. */
    private fun blocking(
        started: CountDownLatch,
        stopped: AtomicBoolean,
    ): (GitCancellation) -> Unit =
        { cancellation ->
            started.countDown()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!cancellation.cancelled && System.nanoTime() < until) Thread.sleep(10)
            stopped.set(cancellation.cancelled)
        }

    @Test
    fun `cancelling the reader stops a running read`() {
        val started = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        cached("github.com", sha)
        cache.onRead = blocking(started, stopped)
        val cancelled = AtomicBoolean(false)
        val reader = sources().reader(network = true) { cancelled.get() }
        Thread {
            started.await(10, TimeUnit.SECONDS)
            cancelled.set(true)
        }.start()
        val begin = System.nanoTime()
        assertEquals(RemoteText.NotFetched, reader.read(action.copy(ref = sha)))
        assertTrue(stopped.get(), "the read saw its cancellation")
        assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun `cancelling the reader stops a running fetch`() {
        val started = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        cache.onFetch = blocking(started, stopped)
        val cancelled = AtomicBoolean(false)
        val reader = sources().reader(network = true) { cancelled.get() }
        Thread {
            started.await(10, TimeUnit.SECONDS)
            cancelled.set(true)
        }.start()
        val begin = System.nanoTime()
        assertEquals(RemoteText.NotFetched, reader.read(action))
        assertTrue(stopped.get(), "the fetch saw its cancellation")
        assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(5))
        assertEquals(1, cache.calls.count { it.startsWith("fetch") }, "github.com is not tried after a cancel")
    }
}
