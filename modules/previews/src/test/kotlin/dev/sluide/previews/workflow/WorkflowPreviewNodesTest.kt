package dev.sluide.previews.workflow

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteSources
import dev.sluide.githubactions.RemoteText
import dev.sluide.githubactions.WorkflowParser
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [WorkflowPreview.openNode]: a click on a box of the graph opens its source through the host; no WebEngine. */
class WorkflowPreviewNodesTest {
    private val file = Path.of("/repo/.github/workflows/ci.yml")
    private val background = ArrayDeque<Runnable>()
    private val fxThread = ArrayDeque<Runnable>()

    /** Job `build` (line 3): a `run` step (5), a missing local action (6), a remote composite action (7). */
    private val parser =
        WorkflowParser { _, _ ->
            """{"schema":1,"errors":[],"events":[],"jobs":[{"id":"build","type":"job","needs":[],"line":3,
            "runsOn":"ubuntu-latest","steps":[{"run":"make","line":5},{"uses":"./.github/actions/x","line":6},
            {"uses":"acme/setup@v1","line":7}]}]}"""
        }

    private val web = URI("https://github.com/acme/setup/blob/c0ffee/action.yml")
    private val manifest = "runs:\n  using: composite\n  steps:\n    - run: echo hi\n      shell: bash\n"
    private val remote =
        object : RemoteSources {
            override fun reader(
                network: Boolean,
                cancelled: () -> Boolean,
            ) = RemoteReader { file ->
                if (file.path == "action.yml") RemoteText.Found(manifest, "c0ffee", null, web) else RemoteText.Missing
            }
        }
    private val services = WindowServices().apply { register(RemoteSources::class.java, remote) }

    private val opened = mutableListOf<Pair<Path, Int>>()
    private val pages = mutableListOf<URI>()
    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = error("opened without a line")

            override fun openFile(
                file: Path,
                line: Int,
            ): Boolean {
                opened += file to line
                return true
            }

            override fun openWebPage(uri: URI) {
                pages += uri
            }

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    private fun run() {
        while (background.isNotEmpty() || fxThread.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (fxThread.isNotEmpty()) fxThread.removeFirst().run()
        }
    }

    private fun drawn(): Pair<WorkflowPreview, String> {
        val preview = WorkflowPreview(parser, background::addLast, fxThread::addLast).apply { attach({}) { host } }
        preview.page(file, "text", dark = false)
        run()
        return preview to preview.page(file, "text", dark = false)
    }

    @Test
    fun `the page lists the linked boxes, and a click opens the workflow, local files or remote pages at the line`() {
        val (preview, page) = drawn()
        val links = Regex("data-node-links=\"([^\"]*)\"").find(page)?.groupValues?.get(1)
        assertEquals(setOf("j0", "j0s0", "j0s1", "j0s2", "j0s2s0"), links?.split(" ")?.toSet(), page)

        for (id in listOf("j0", "j0s0", "j0s1", "j0s2", "j0s2s0", "t0", "unknown")) preview.openNode(id, host)

        val workflow = Path.of("/repo/.github/workflows/ci.yml")
        assertEquals(listOf(workflow to 2, workflow to 4, workflow to 5, workflow to 6), opened, "0-based lines")
        assertEquals(listOf(URI("$web#L4")), pages)
    }

    @Test
    fun `nothing opens before the graph is drawn or once the text changed`() {
        val preview = WorkflowPreview(parser, background::addLast, fxThread::addLast).apply { attach({}) { host } }
        preview.page(file, "text", dark = false)
        preview.openNode("j0s0", host)

        run()
        preview.page(file, "edited", dark = false)
        preview.openNode("j0s0", host)

        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a remote page opens at the line only by https, its path kept, a fragment replaced, as ASCII`() {
        val encoded = URI("https://ghes.example.com:8443/o/r/blob/c/a%252e/b%23c%3Fd.yml")
        assertEquals(
            "https://ghes.example.com:8443/o/r/blob/c/a%252e/b%23c%3Fd.yml#L7",
            WorkflowPreview.webPageAt(encoded, 7)?.toString(),
        )
        assertEquals(
            "https://github.com/o/r/blob/c/x.yml#L2",
            WorkflowPreview.webPageAt(URI("https://github.com/o/r/blob/c/x.yml#L9"), 2)?.toString(),
        )
        assertEquals(
            "https://github.com/o/r/blob/c/%C3%BC.yml#L1",
            WorkflowPreview.webPageAt(URI("https", "github.com", "/o/r/blob/c/ü.yml", null), 1)?.toString(),
        )
        assertNull(WorkflowPreview.webPageAt(URI("http://github.com/o/r/blob/c/x.yml"), 1))
        assertNull(WorkflowPreview.webPageAt(URI("javascript:alert(1)"), 1))
        assertNull(WorkflowPreview.webPageAt(URI("x.yml"), 1), "no scheme")
    }

    @Test
    fun `local files stay in the workflow's repository`() {
        val root = Path.of("/repo")
        assertEquals(
            root.resolve(".github/actions/x/action.yml"),
            WorkflowPreview.localFile(file, ".github/actions/x/action.yml"),
        )
        assertEquals(root.resolve("a"), WorkflowPreview.localFile(file, "b/../a"))
        assertNull(WorkflowPreview.localFile(file, "../outside.yml"))
        assertNull(WorkflowPreview.localFile(file, "/etc/passwd"))
        assertNull(WorkflowPreview.localFile(Path.of("/repo/ci.yml"), "ci.yml"), "not in a workflows folder")
        assertEquals(root.resolve("a"), WorkflowPreview.localFile(Path.of("/repo/.github/x/../workflows/ci.yml"), "a"))
    }
}
