package dev.sluide.previews.workflow

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteSources
import dev.sluide.githubactions.RemoteText
import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.SourceLocation
import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.WorkflowGraphs
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.WorkflowSources
import dev.sluide.previews.markdown.MarkdownRenderer
import dev.sluide.previews.markdown.MermaidPreview
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewAction
import dev.sluide.previews.preview.PreviewMode
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.web.WebEngine
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

private val log = System.getLogger(WorkflowPreview::class.java.name)

/**
 * A GitHub Actions workflow drawn by Mermaid ([WorkflowGraphs], ADR 0075) like a Mermaid file ([MermaidPreview]):
 * zoomable, fitted at first, drawn again in the other colour scheme. A workflow without jobs to draw shows a note.
 *
 * One per view ([attach]): parsing blocks ([WebViewWorkflowParser]), so the page says "Reading the workflow…" while
 * [executor] (the view's own thread, shut down by [detach]; a view waiting for its references holds up no other)
 * parses the latest saved text, then the view renders again ([attach]'s rerender) with the graph, which
 * this preview keeps for that text; GitHub's errors are kept like a graph. If the parser itself fails (a timeout, for
 * example), its note shows and the text is tried once more right away; after that the note is kept until the file is
 * edited or opened again. A result for a text that is no longer the latest is dropped; nothing comes after [detach].
 * Calls other than the executors' are on the FX thread, which [runLater] goes back to.
 *
 * A workflow in `<root>/.github/workflows/` has its local reusable workflows and actions read from `<root>`
 * ([RepositoryFiles]) and drawn nested; their reading stops once the text is outdated. A parser failure for one of them
 * counts like one for the workflow (parsed once more, then kept). The graph is kept for the workflow's own text, so a
 * change to a file it refers to shows once the workflow is edited or opened again.
 *
 * Remote references are read from the window's [RemoteSources] (a window service of the host), from its cache only;
 * the [fetchAction] (Fetch References) draws the graph again on [fetchExecutor], letting the reader fetch them, as a
 * background task of the window. Its graph is kept like a parsed one; later drawings find the files in the cache.
 *
 * The boxes with a source line link to it: a click opens the file at that line, or a remote file's page ([openNode]).
 */
internal class WorkflowPreview(
    private val parser: WorkflowParser,
    private val executor: Executor = NamedThreads.single("Workflow graph"),
    private val runLater: (Runnable) -> Unit = Platform::runLater,
    private val fetchExecutor: Executor = fetchingThread,
) : Preview {
    /** A workflow's file and text: what its graph depends on (besides the files it refers to). */
    private data class Input(
        val file: Path,
        val text: String,
    ) {
        val fileName get() = file.fileName.toString()
    }

    private var rerender: (() -> Unit)? = null
    private var host: () -> FileViewHost? = { null }
    private var detached = false

    /** The graph of the last input parsed that was the latest requested then. */
    private var drawn: Pair<Input, WorkflowGraph>? = null

    /**
     * The note of the latest input whose parser threw (a timeout, for example) while it is parsed once more, until a
     * page has shown it once.
     */
    private var failed: Pair<Input, WorkflowGraph>? = null

    /** The input parsed once more after its parser threw; a second failure for it is kept. */
    private var retried: Input? = null

    /** The input whose parsing is queued or running, if any. */
    private var parsing: Input? = null

    /** The input whose references are being fetched, if any. */
    private var fetching: Input? = null

    /** The input last asked for; the executors' threads read it to skip outdated work. */
    @Volatile
    private var latest: Input? = null

    private val fetchDisabled = SimpleBooleanProperty(true)

    /**
     * Fetch References: enabled while the latest text's graph has remote references, the window has [RemoteSources]
     * and no fetch runs.
     */
    val fetchAction = PreviewAction(FETCH, FETCH_TOOLTIP, fetchDisabled, ::fetch)

    override val actions get() = listOf(fetchAction)

    override fun attach(
        rerender: () -> Unit,
        host: () -> FileViewHost?,
    ) {
        this.rerender = rerender
        this.host = host
    }

    override fun detach() {
        detached = true
        rerender = null
        latest = null
        fetchDisabled.set(true)
        // A running parse finishes (the WebEngine call is not interrupted); queued ones find themselves outdated.
        (executor as? ExecutorService)?.shutdown()
    }

    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(page(file, text, dark))

    /** The page for [text] of [file]: its graph if parsed already, otherwise a note while it is parsed. */
    fun page(
        file: Path,
        text: String,
        dark: Boolean,
    ): String {
        val input = Input(file, text)
        if (!detached) latest = input
        return pageOf(input, dark).also { updateFetch() }
    }

    private fun pageOf(
        input: Input,
        dark: Boolean,
    ): String {
        val graph = drawn?.takeIf { it.first == input }?.second
        if (graph != null) return render(graph, dark, if (fetching == input) FETCHING else graph.note)
        val failure = failed?.takeIf { it.first == input }?.second
        if (failure != null) {
            // Shown once, while the text is parsed once more.
            failed = null
            return render(failure, dark)
        }
        if (!detached && parsing != input) {
            retried = null
            parse(input)
        }
        return note(READING, dark)
    }

    private fun remoteSources(): RemoteSources? = host()?.windowService(RemoteSources::class.java)

    private fun updateFetch() {
        val graph = drawn?.takeIf { it.first == latest }?.second
        fetchDisabled.set(
            detached || fetching != null || graph == null || graph.remoteReferences == 0 || remoteSources() == null,
        )
    }

    private fun parse(input: Input) {
        parsing = input
        val remote = remoteSources()
        executor.execute {
            val drawing = Drawing(input)
            var graph: WorkflowGraph? = null
            try {
                if (latest == input) graph = drawing.draw(remote?.let { drawing.reader(it, network = false) })
            } finally {
                // A graph cut short for being outdated is never kept, even if its text is the latest again by now.
                val kept = graph.takeUnless { drawing.outdated }
                runLater { parsed(input, kept, drawing.threw, remote != null) }
            }
        }
    }

    /**
     * One drawing of [input] on a background thread: whether the parser threw, whether it became outdated (also when
     * a reader asks from another thread).
     */
    private inner class Drawing(
        private val input: Input,
        private val stopped: AtomicBoolean = AtomicBoolean(false),
    ) {
        @Volatile
        var threw = false
            private set

        @Volatile
        var outdated = false
            private set

        val cancelled = { (stopped.get() || latest != input).also { if (it) outdated = true } }

        private val recording =
            WorkflowParser { fileName, text ->
                try {
                    parser.parse(fileName, text)
                } catch (e: Exception) {
                    // Also for a referenced workflow: its box says the parser failed, so the graph is retried.
                    threw = true
                    throw e
                }
            }

        fun draw(remote: RemoteReader?): WorkflowGraph =
            WorkflowGraphs.of(input.fileName, input.text, recording, sources(input.file), remote, cancelled)

        /**
         * A reader of [remote] for this drawing; if it cannot be made (its cache cannot be opened, say), one that
         * fails every file with the reason, which their boxes show.
         */
        fun reader(
            remote: RemoteSources,
            network: Boolean,
        ): RemoteReader =
            try {
                remote.reader(network, cancelled)
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                val failed = RemoteText.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
                RemoteReader { failed }
            }
    }

    /**
     * Back on the FX thread with the [graph] of [input], or null if it was skipped as outdated. If the parser [threw],
     * the graph is its note: the first time it is shown while the text is parsed once more (the parser has a new
     * engine by then), the second time it is kept like a graph. The view renders unchanged text only again when asked
     * to, so a later save or reload would not retry; editing the file or opening it again does.
     *
     * A graph with remote references drawn without the window's [RemoteSources] ([withRemote]) is drawn again if they
     * are there now: the view renders first, before it has a host.
     */
    private fun parsed(
        input: Input,
        graph: WorkflowGraph?,
        threw: Boolean,
        withRemote: Boolean,
    ) {
        if (parsing == input) parsing = null
        if (detached || graph == null || input != latest || drawn?.first == input) return
        if (!withRemote && graph.remoteReferences > 0 && remoteSources() != null) {
            parse(input)
            return
        }
        keep(input, graph, threw)
    }

    private fun keep(
        input: Input,
        graph: WorkflowGraph,
        threw: Boolean,
    ) {
        if (threw && retried != input) {
            retried = input
            failed = input to graph
            if (drawn?.first == input) drawn = null
            parse(input)
        } else {
            drawn = input to graph
            failed = null
        }
        updateFetch()
        rerender?.invoke()
    }

    /**
     * Draws the latest text again on [fetchExecutor] with a reader that may fetch, as a task of the window's
     * [BackgroundTasks] (if it has them), which can be cancelled. Editing the text or closing the view stops it too.
     */
    private fun fetch() {
        val input = latest ?: return
        val remote = remoteSources() ?: return
        if (detached || fetching != null || drawn?.first != input) return
        val stopped = AtomicBoolean(false)
        val task = host()?.windowService(BackgroundTasks::class.java)?.start(FETCHING_TASK) { stopped.set(true) }
        fetching = input
        updateFetch()
        rerender?.invoke()
        fetchExecutor.execute {
            val drawing = Drawing(input, stopped)
            val outcomes = Outcomes()
            var graph: WorkflowGraph? = null
            var error: Throwable? = null
            try {
                if (!drawing.cancelled()) {
                    val reader = drawing.reader(remote, network = true)
                    graph = drawing.draw(RemoteReader { file -> reader.read(file).also(outcomes::add) })
                }
            } catch (t: Throwable) {
                error = t
                throw t
            } finally {
                // The view's latest text is null once it is closed.
                val ending = if (latest == null) Ending.CLOSED else Ending.CHANGED
                task?.let { if (error != null) it.failed(error) else outcomes.end(it, stopped.get(), drawing, ending) }
                val kept = graph.takeUnless { drawing.outdated }
                runLater { fetched(input, kept, drawing.threw) }
            }
        }
    }

    /** Why a fetch that became outdated stopped. */
    private enum class Ending(
        val detail: String,
    ) {
        CHANGED("stopped, the workflow changed"),
        CLOSED("stopped, the view was closed"),
    }

    /** What the remote files of a fetch came to, to tell its task whether every one failed. */
    private class Outcomes {
        private var read = 0
        private var failure: String? = null
        private var failures = 0

        fun add(text: RemoteText) {
            read++
            if (text is RemoteText.Failed) {
                failures++
                if (failure == null) failure = text.reason
            }
        }

        fun end(
            task: BackgroundTask,
            cancelled: Boolean,
            drawing: Drawing,
            ending: Ending,
        ) = when {
            cancelled -> task.failed("cancelled")
            drawing.outdated -> task.close(ending.detail)
            read > 0 && failures == read -> task.failed(failure ?: "failed")
            else -> task.close()
        }
    }

    /** Back on the FX thread with what a fetch drew for [input]: kept like a parsed graph, null if it was stopped. */
    private fun fetched(
        input: Input,
        graph: WorkflowGraph?,
        threw: Boolean,
    ) {
        if (fetching == input) fetching = null
        if (detached) return
        if (graph == null || input != latest) {
            updateFetch()
            // Without its "Fetching references…" note.
            if (input == latest) rerender?.invoke()
            return
        }
        keep(input, graph, threw)
    }

    /**
     * Opens where the box [id] of the graph shown comes from ([WorkflowGraph.locations]): a file of the workflow's
     * repository in the IDE at its line, if it stays in the repository; a remote file's page at its commit and line on
     * the web.
     */
    override fun openNode(
        id: String,
        host: FileViewHost,
    ) {
        val input = latest ?: return
        val graph = drawn?.takeIf { it.first == input }?.second ?: return
        when (val location = graph.locations[id]) {
            is SourceLocation.Local -> {
                localFile(input.file, location.path)?.let { host.openFile(it, location.line - 1) }
            }

            is SourceLocation.Remote -> {
                webPageAt(location.web, location.line)?.let(host::openWebPage)
            }

            null -> {}
        }
    }

    override val defaultMode get() = PreviewMode.SOURCE

    override val zooms get() = true

    companion object {
        /** What the page says while the workflow is parsed. */
        const val READING = "Reading the workflow…"

        /** What the note above the graph says while its references are fetched. */
        const val FETCHING = "Fetching references…"

        const val FETCH = "Fetch References"
        const val FETCH_TOOLTIP = "Fetch the actions and workflows this workflow uses from their repositories"

        /** The background task's name. */
        const val FETCHING_TASK = "Fetching workflow references"

        /** Where [file]'s local references are read from: its repository if it is in `<root>/.github/workflows/`. */
        fun sources(file: Path): WorkflowSources = root(file)?.let(::RepositoryFiles) ?: WorkflowSources.NONE

        /**
         * The file at [path] (relative) in the repository of [workflow], normalised; null if the workflow is in none
         * (see [sources]) or the path leaves the repository's root.
         */
        fun localFile(
            workflow: Path,
            path: String,
        ): Path? {
            val root = root(workflow) ?: return null
            val file =
                try {
                    root.resolve(path).normalize()
                } catch (_: InvalidPathException) {
                    return null
                }
            return file.takeIf { it.startsWith(root) }
        }

        /**
         * The page [web] (https only) at [line]: its fragment, if any, replaced by `L<line>`, as ASCII; null (logged)
         * for another scheme or a page that is no valid URI then.
         */
        fun webPageAt(
            web: URI,
            line: Int,
        ): URI? {
            if (!web.scheme.equals("https", ignoreCase = true)) {
                log.log(System.Logger.Level.WARNING, "Not opening the workflow source $web: not https")
                return null
            }
            return try {
                URI(URI(web.scheme, web.authority, web.path, web.query, "L$line").toASCIIString())
            } catch (e: URISyntaxException) {
                log.log(System.Logger.Level.WARNING, "Not opening the workflow source $web", e)
                null
            } catch (e: IllegalArgumentException) {
                log.log(System.Logger.Level.WARNING, "Not opening the workflow source $web", e)
                null
            }
        }

        /** The root of [file]'s repository, normalised, if it is in `<root>/.github/workflows/`. */
        private fun root(file: Path): Path? {
            val workflows = file.toAbsolutePath().normalize().parent
            val github = workflows?.parent
            val inWorkflows =
                workflows?.fileName?.toString() == "workflows" && github?.fileName?.toString() == ".github"
            return if (inWorkflows) github?.parent else null
        }

        /**
         * The thread fetches draw on, one after the other: fetching may take long and must not hold up parsing (the
         * parser itself takes one call at a time from either). Each view parses on a thread of its own ([executor]).
         */
        private val fetchingThread: Executor by lazy { NamedThreads.single("Workflow references") }

        fun render(
            graph: WorkflowGraph,
            dark: Boolean,
            note: String? = graph.note,
        ): String =
            if (graph.isEmpty) {
                note(note ?: "No jobs in this workflow.", dark)
            } else {
                MermaidPreview.render(graph.mermaid, dark, note, graph.locations.keys)
            }

        private fun note(
            text: String,
            dark: Boolean,
        ): String {
            val body = Element("body")
            body.appendElement("p").addClass("diagram-note").text(text)
            return MarkdownRenderer.page(body, dark)
        }
    }
}
