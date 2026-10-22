package dev.sluide.previews.workflow

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.WorkflowGraphs
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.WorkflowSources
import dev.sluide.previews.markdown.MarkdownRenderer
import dev.sluide.previews.markdown.MermaidPreview
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewMode
import javafx.application.Platform
import javafx.scene.web.WebEngine
import org.jsoup.nodes.Element
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * A GitHub Actions workflow drawn by Mermaid ([WorkflowGraphs], ADR 0075) like a Mermaid file ([MermaidPreview]):
 * zoomable, fitted at first, drawn again in the other colour scheme. A workflow without jobs to draw shows a note.
 *
 * One per view ([attach]): parsing blocks ([WebViewWorkflowParser]), so the page says "Reading the workflow…" while
 * [executor] parses the latest saved text, then the view renders again ([attach]'s rerender) with the graph, which
 * this preview keeps for that text; GitHub's errors are kept like a graph. If the parser itself fails (a timeout, for
 * example), its note shows and the text is tried once more right away; after that the note is kept until the file is
 * edited or opened again. A result for a text that is no longer the latest is dropped; nothing comes after [detach].
 * Calls other than the executor's are on the FX thread, which [runLater] goes back to.
 *
 * A workflow in `<root>/.github/workflows/` has its local reusable workflows and actions read from `<root>`
 * ([RepositoryFiles]) and drawn nested; their reading stops once the text is outdated. A parser failure for one of them
 * counts like one for the workflow (parsed once more, then kept). The graph is kept for the workflow's own text, so a
 * change to a file it refers to shows once the workflow is edited or opened again.
 */
internal class WorkflowPreview(
    private val parser: WorkflowParser,
    private val executor: Executor = parsingThread,
    private val runLater: (Runnable) -> Unit = Platform::runLater,
) : Preview {
    /** A workflow's file and text: what its graph depends on (besides the files it refers to). */
    private data class Input(
        val file: Path,
        val text: String,
    ) {
        val fileName get() = file.fileName.toString()
    }

    private var rerender: (() -> Unit)? = null
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

    /** The input last asked for; the executor's thread reads it to skip outdated work. */
    @Volatile
    private var latest: Input? = null

    override fun attach(
        rerender: () -> Unit,
        host: () -> FileViewHost?,
    ) {
        this.rerender = rerender
    }

    override fun detach() {
        detached = true
        rerender = null
        latest = null
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
        val graph = drawn?.takeIf { it.first == input }?.second
        if (graph != null) return render(graph, dark)
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

    private fun parse(input: Input) {
        parsing = input
        executor.execute {
            var threw = false
            var outdated = false
            val recording =
                WorkflowParser { fileName, text ->
                    try {
                        parser.parse(fileName, text)
                    } catch (e: Exception) {
                        // Also for a referenced workflow: its box says the parser failed, so the graph is retried.
                        threw = true
                        throw e
                    }
                }
            val cancelled = { (latest != input).also { if (it) outdated = true } }
            val graph =
                if (latest == input) {
                    WorkflowGraphs.of(input.fileName, input.text, recording, sources(input.file), cancelled)
                } else {
                    null
                }
            // A graph cut short for being outdated is never kept, even if its text is the latest again by now.
            runLater { parsed(input, graph.takeUnless { outdated }, threw) }
        }
    }

    /**
     * Back on the FX thread with the [graph] of [input], or null if it was skipped as outdated. If the parser [threw],
     * the graph is its note: the first time it is shown while the text is parsed once more (the parser has a new
     * engine by then), the second time it is kept like a graph. The view renders unchanged text only again when asked
     * to, so a later save or reload would not retry; editing the file or opening it again does.
     */
    private fun parsed(
        input: Input,
        graph: WorkflowGraph?,
        threw: Boolean,
    ) {
        if (parsing == input) parsing = null
        if (detached || graph == null || input != latest || drawn?.first == input) return
        if (threw && retried != input) {
            retried = input
            failed = input to graph
            parse(input)
        } else {
            drawn = input to graph
            failed = null
        }
        rerender?.invoke()
    }

    override val defaultMode get() = PreviewMode.SOURCE

    override val zooms get() = true

    companion object {
        /** What the page says while the workflow is parsed. */
        const val READING = "Reading the workflow…"

        /** Where [file]'s local references are read from: its repository if it is in `<root>/.github/workflows/`. */
        fun sources(file: Path): WorkflowSources {
            val workflows = file.toAbsolutePath().parent
            val github = workflows?.parent
            val root = github?.parent
            val inWorkflows =
                workflows?.fileName?.toString() == "workflows" && github?.fileName?.toString() == ".github"
            return if (root != null && inWorkflows) RepositoryFiles(root) else WorkflowSources.NONE
        }

        /** The thread all workflow views parse on, one workflow after the other, as the parser takes them anyway. */
        private val parsingThread: Executor by lazy { NamedThreads.single("Workflow graph") }

        fun render(
            graph: WorkflowGraph,
            dark: Boolean,
        ): String =
            if (graph.isEmpty) {
                note(graph.note ?: "No jobs in this workflow.", dark)
            } else {
                MermaidPreview.render(graph.mermaid, dark, graph.note)
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
