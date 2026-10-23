package dev.sluide.previews.workflow

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.githubactions.RemoteFile
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteSources
import dev.sluide.githubactions.RemoteText
import dev.sluide.githubactions.WorkflowParser
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [WorkflowPreview]'s Fetch References with a fake [RemoteSources]: when it may run, what it draws and keeps. */
class WorkflowPreviewFetchTest {
    private val file = Path.of("/elsewhere/ci.yml")

    /** Work handed to the parsing thread, the fetching thread and back to the FX thread, run when the test says. */
    private val background = ArrayDeque<Runnable>()
    private val fetches = ArrayDeque<Runnable>()
    private val fxThread = ArrayDeque<Runnable>()
    private var rerenders = 0

    /** A text starting with `remote` uses `acme/setup@v1`, others have a `run` step. */
    private val parser = WorkflowParser { _, text -> workflow(text) }

    /** The cache of the fake remote sources, filled by readers that may fetch from [repository]. */
    private val cache = mutableMapOf<RemoteFile, RemoteText>()
    private val repository = mutableMapOf<RemoteFile, RemoteText>(SETUP to COMPOSITE)

    /** The `network` of each reader made, in order. */
    private val readers = mutableListOf<Boolean>()

    /** Runs before each read of a reader that may fetch. */
    private var beforeFetch: () -> Unit = {}

    /** Thrown when a reader is made, if set. */
    private var readerFailure: Exception? = null

    private val remoteSources =
        object : RemoteSources {
            override fun reader(
                network: Boolean,
                cancelled: () -> Boolean,
            ): RemoteReader {
                readers += network
                readerFailure?.let { throw it }
                return RemoteReader { file ->
                    if (network) {
                        beforeFetch()
                        if (cancelled()) return@RemoteReader RemoteText.NotFetched
                        val text = repository[file] ?: RemoteText.Missing
                        if (text !is RemoteText.Failed) cache[file] = text
                        text
                    } else {
                        cache[file] ?: RemoteText.NotFetched
                    }
                }
            }
        }

    private val logged = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests { logged += it }
    private val services =
        WindowServices().apply {
            register(RemoteSources::class.java, remoteSources)
            register(BackgroundTasks::class.java, tasks)
        }

    /** A window without services. */
    private val bareHost =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}
        }

    private val host =
        object : FileViewHost by bareHost {
            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    private fun preview(host: FileViewHost? = this.host): WorkflowPreview =
        WorkflowPreview(parser, background::addLast, fxThread::addLast, fetches::addLast).apply {
            attach({ rerenders++ }) { host }
        }

    private fun run(queue: ArrayDeque<Runnable>) {
        while (queue.isNotEmpty()) queue.removeFirst().run()
        while (fxThread.isNotEmpty()) fxThread.removeFirst().run()
    }

    private fun WorkflowPreview.page(text: String) = page(file, text, dark = false)

    private val WorkflowPreview.fetchEnabled get() = !fetchAction.disabled.get()

    /** The preview showing the graph of `remote`, parsed from the cache. */
    private fun drawn(): WorkflowPreview =
        preview().apply {
            page("remote")
            run(background)
            assertTrue(page("remote").contains("not fetched"))
        }

    @Test
    fun `fetch is offered for a graph with remote references while the window has remote sources`() {
        assertFalse(preview(host = null).apply { page("remote") }.fetchEnabled, "no graph yet")

        val preview = drawn()
        assertTrue(preview.fetchEnabled)
        assertEquals(listOf(false), readers, "drawn from the cache")

        preview.page("local")
        assertFalse(preview.fetchEnabled, "not drawn yet")
        run(background)
        preview.page("local")
        assertFalse(preview.fetchEnabled, "no remote references")

        val noService = preview(bareHost)
        noService.page("remote")
        run(background)
        noService.page("remote")
        assertFalse(noService.fetchEnabled, "no remote sources")
    }

    @Test
    fun `fetching draws the graph with a reader that may fetch, keeps it, and later drawings read the cache`() {
        val preview = drawn()
        val rendered = rerenders

        preview.fetchAction.run()
        assertFalse(preview.fetchEnabled, "while fetching")
        assertTrue(WorkflowPreview.FETCHING in preview.page("remote"))
        assertTrue("not fetched" in preview.page("remote"), "the graph stays meanwhile")
        assertEquals(rendered + 1, rerenders)
        run(fetches)

        assertEquals(listOf(false, true), readers)
        assertEquals(rendered + 2, rerenders)
        val page = preview.page("remote")
        assertTrue("acme/setup@v1 (composite)" in page && "Set up inside" in page, page)
        assertFalse(WorkflowPreview.FETCHING in page)
        assertTrue(preview.fetchEnabled, "fetching again refreshes")
        assertTrue(background.isEmpty(), "kept, not parsed again")
        assertTrue(logged.any { "finished in" in it && WorkflowPreview.FETCHING_TASK in it }, "$logged")

        preview.page("remote, edited")
        run(background)
        assertTrue("Set up inside" in preview.page("remote, edited"), "from the cache")
        assertEquals(listOf(false, true, false), readers)
    }

    @Test
    fun `editing the text while fetching stops the fetch and drops its graph`() {
        val preview = drawn()
        beforeFetch = { preview.page("remote, edited") }

        preview.fetchAction.run()
        run(fetches)

        assertTrue(cache.isEmpty(), "nothing read once outdated")
        assertTrue(logged.any { "stopped, the workflow changed" in it }, "$logged")
        assertTrue(preview.page("remote, edited").contains(WorkflowPreview.READING))
        run(background)
        assertTrue("not fetched" in preview.page("remote, edited"))
        assertTrue(preview.fetchEnabled)
    }

    @Test
    fun `cancelling the task stops the fetch and shows the graph without the fetching note`() {
        val preview = drawn()
        beforeFetch = { tasks.shown.single().cancel() }

        preview.fetchAction.run()
        val rendered = rerenders
        run(fetches)

        assertEquals(rendered + 1, rerenders)
        val page = preview.page("remote")
        assertTrue("not fetched" in page && WorkflowPreview.FETCHING !in page, page)
        assertTrue(logged.any { "cancelled" in it && "task#" in it }, "$logged")
        assertTrue(preview.fetchEnabled)
    }

    @Test
    fun `a fetch whose every read failed fails its task, its graph says why`() {
        repository[SETUP] = RemoteText.Failed("Repository not found")
        val preview = drawn()

        preview.fetchAction.run()
        run(fetches)

        assertTrue("Repository not found" in preview.page("remote"))
        assertTrue(logged.any { "failed after" in it && it.endsWith(": Repository not found") }, "$logged")
    }

    @Test
    fun `a graph drawn before the view had its host is drawn again with the window's remote sources`() {
        var current: FileViewHost? = null
        val preview =
            WorkflowPreview(parser, background::addLast, fxThread::addLast, fetches::addLast).apply {
                attach({ rerenders++ }) { current }
            }
        cache[SETUP] = COMPOSITE

        preview.page("remote")
        current = host
        run(background)
        assertEquals(0, rerenders, "drawn again first")
        run(background)

        assertEquals(1, rerenders)
        assertEquals(listOf(false), readers)
        assertTrue("Set up inside" in preview.page("remote"))
    }

    @Test
    fun `remote sources that cannot make a reader fail every remote box, and a fetch ends its task as failed`() {
        readerFailure = IOException("cache unreadable")
        val preview = preview()

        preview.page("remote")
        run(background)
        val page = preview.page("remote")
        assertTrue("class=\"mermaid" in page && "cache unreadable" in page, page)
        assertTrue(preview.fetchEnabled)

        preview.fetchAction.run()
        run(fetches)

        assertEquals(listOf(false, true), readers)
        assertTrue(logged.any { "failed after" in it && it.endsWith(": cache unreadable") }, "$logged")
        assertFalse(WorkflowPreview.FETCHING in preview.page("remote"))
        assertTrue(preview.fetchEnabled, "offered again")
    }

    @Test
    fun `a fetch that ends with an error still ends its task and offers fetching again`() {
        val preview = drawn()
        beforeFetch = { throw Boom() }

        preview.fetchAction.run()
        assertFailsWith<Boom> { fetches.removeFirst().run() }
        run(fxThread)

        assertTrue(logged.any { "failed after" in it && WorkflowPreview.FETCHING_TASK in it }, "$logged")
        val page = preview.page("remote")
        assertTrue("not fetched" in page && WorkflowPreview.FETCHING !in page, page)
        assertTrue(preview.fetchEnabled)
    }

    @Test
    fun `closing the view stops a fetch and says so`() {
        val preview = drawn()
        beforeFetch = { preview.detach() }

        preview.fetchAction.run()
        run(fetches)

        assertTrue(logged.any { "stopped, the view was closed" in it }, "$logged")
    }

    private class Boom : Error("boom")

    @Test
    fun `nothing is fetched after detach`() {
        val preview = drawn()
        preview.fetchAction.run()

        preview.detach()
        run(fetches)

        assertEquals(listOf(false), readers)
        assertFalse(preview.fetchEnabled)
    }

    private companion object {
        val SETUP = RemoteFile("acme", "setup", "v1", "action.yml")

        val COMPOSITE =
            RemoteText.Found(
                "name: Set up\nruns:\n  using: composite\n  steps:\n    - name: Set up inside\n      run: x\n",
                "c0ffee",
                null,
            )

        /** The parser's JSON for a workflow whose step uses `acme/setup@v1` if [text] starts with `remote`. */
        fun workflow(text: String): String {
            val step =
                if (text.startsWith("remote")) {
                    """{"id": null, "name": null, "line": 5, "uses": "acme/setup@v1", "run": null, "if": null}"""
                } else {
                    """{"id": null, "name": null, "line": 5, "uses": null, "run": "echo local", "if": null}"""
                }
            return """{"schema": 1, "errors": [], "name": null, "events": [], "jobs": [{"id": "build",
                "name": null, "line": 3, "type": "job", "needs": [], "if": null, "runsOn": "ubuntu-latest",
                "matrix": null, "uses": null, "with": null, "secretsInherit": false, "steps": [$step]}]}"""
        }
    }
}
