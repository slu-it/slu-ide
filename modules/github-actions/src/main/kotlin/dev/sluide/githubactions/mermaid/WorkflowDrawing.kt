package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.mermaid.MermaidLabels.label
import dev.sluide.githubactions.model.Job
import dev.sluide.githubactions.model.JobBody
import dev.sluide.githubactions.model.Problem
import dev.sluide.githubactions.model.Step
import dev.sluide.githubactions.model.StepAction
import dev.sluide.githubactions.model.Workflow

/**
 * Draws a [Workflow]: its events in a `Triggers` subgraph, one subgraph per job with its steps chained top to bottom,
 * and the jobs linked by `needs` (from the triggers when a job needs none). Every `uses:` is a black box. Over
 * [maxNodes] boxes, [maxEdges] links or about [maxText] characters, the rest is left out and the note says so.
 */
internal class WorkflowDrawing private constructor(
    private val workflow: Workflow,
    private val maxNodes: Int,
    private val maxEdges: Int,
    private val maxText: Int,
) {
    private var nodes = 0
    private var edges = 0
    private var text = 0
    private var nodesCut = false
    private var edgesCut = false

    private fun run(): WorkflowGraph {
        val problems = problemsNote(workflow.problems)
        if (workflow.jobs.isEmpty()) return WorkflowGraph("", problems ?: "No jobs in this workflow.", true)
        val elements = mutableListOf<Element>()
        val triggers = triggers()
        if (triggers != null) elements += triggers
        val drawn = mutableListOf<Pair<Job, Cluster>>()
        for ((index, job) in workflow.jobs.withIndex()) {
            val cluster = (if (nodesCut) null else jobCluster(index, job)) ?: break
            drawn += job to cluster
        }
        val known = workflow.jobs.map { it.id }.toSet()
        val clusterOf = drawn.reversed().associate { (job, cluster) -> job.id to cluster.id }
        val unknown = mutableListOf<String>()
        val links = mutableListOf<Link>()
        for ((job, cluster) in drawn) {
            val condition = job.condition.ifPresent()?.let { label("if: $it") }
            job.needs.filter { it !in known }.forEach { unknown += "${job.id} needs $it" }
            val sources =
                if (job.needs.isEmpty()) {
                    listOfNotNull(triggers?.id)
                } else {
                    job.needs
                        .mapNotNull { clusterOf[it] }
                        .filter { it != cluster.id }
                        .distinct()
                }
            var linked = false
            for (source in sources) {
                if (!takeEdge(condition?.length ?: 0)) break
                links += Link(source, cluster.id, condition, dotted = condition != null)
                linked = true
            }
            // No link carries the condition (no events, unknown or left-out needs): the title shows it.
            elements +=
                if (condition == null || linked) {
                    cluster
                } else {
                    // Its length was reserved by jobCluster.
                    cluster.copy(
                        title = listOf(cluster.title, condition).filter { it.isNotEmpty() }.joinToString(SEPARATOR),
                    )
                }
        }
        val notes =
            listOfNotNull(
                problems,
                unknown.takeIf { it.isNotEmpty() }?.let { "Not linked, unknown jobs: ${it.joinToString(", ")}." },
                if (nodesCut) "Too large: showing $nodes boxes, ${drawn.size} of ${workflow.jobs.size} jobs." else null,
                if (edgesCut) "Too large: showing the first $edges links." else null,
            )
        val mermaid = MermaidWriter.write(Diagram(elements, links))
        return WorkflowGraph(mermaid, notes.joinToString(" ").ifEmpty { null }, false)
    }

    private fun triggers(): Cluster? {
        val events = mutableListOf<Element>()
        for ((index, event) in workflow.events.withIndex()) {
            // One part, cut as a whole: a stadium is as wide as its text.
            val line = label(listOf(event.name, event.detail.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            if (!takeNode(line.length)) break
            events += Node("t$index", listOf(line), Shape.STADIUM)
        }
        if (events.isEmpty()) return null
        val title = label("Triggers")
        text += title.length + CLUSTER_OVERHEAD
        return Cluster(TRIGGERS, title, events)
    }

    private fun jobCluster(
        index: Int,
        job: Job,
    ): Cluster? {
        val id = "j$index"
        // The condition may end up in the title (when no link carries it), so its length is reserved before the
        // children take the rest of the text budget.
        text += job.condition.ifPresent()?.let { SEPARATOR.length + label("if: $it").length } ?: 0
        val children = mutableListOf<Element>()
        val title: String
        when (val body = job.body) {
            is JobBody.Steps -> {
                title = title(job.name.ifPresent() ?: job.id, job.runsOn, job.matrix?.title())
                for ((position, step) in body.steps.withIndex()) {
                    val element = step("${id}s$position", step)
                    if (!takeNode(size(element))) break
                    children += element
                }
                if (body.steps.isEmpty() && takeNode(0)) children += Node("${id}none", listOf(label("no steps")))
            }

            is JobBody.WorkflowCall -> {
                title = title(job.name.ifPresent() ?: job.id, "calls ${body.text}", job.matrix?.title())
                val lines =
                    lines(
                        body.text,
                        body.inputs.keys
                            .takeIf { it.isNotEmpty() }
                            ?.let { "with: ${it.joinToString(", ")}" },
                        if (body.secretsInherit) "secrets: inherit" else null,
                    )
                val element = ReferenceNode("${id}w", lines, body.reference)
                if (takeNode(size(element))) children += element
            }
        }
        if (children.isEmpty()) return null
        val links = mutableListOf<Link>()
        for (position in 1 until children.size) {
            if (!takeEdge(0)) break
            links += Link(children[position - 1].id, children[position].id)
        }
        text += title.length + CLUSTER_OVERHEAD
        return Cluster(id, title, children, links)
    }

    private fun step(
        id: String,
        step: Step,
    ): Element {
        val condition = step.condition.ifPresent()?.let { "if: $it" }
        return when (val action = step.action) {
            is StepAction.Run -> {
                Node(id, lines(step.name.ifPresent() ?: "run: ${action.firstLine}".trimEnd(), condition))
            }

            is StepAction.Uses -> {
                ReferenceNode(id, lines(step.name, action.text, condition), action.reference)
            }
        }
    }

    /** The escaped label lines of the [texts] that are not blank. */
    private fun lines(vararg texts: String?): List<String> =
        texts.mapNotNull { text -> text?.takeIf { it.isNotBlank() }?.let { label(it) } }

    /** This text, or null when it is blank: blank names and conditions count as none. */
    private fun String?.ifPresent(): String? = this?.takeIf { it.isNotBlank() }

    /** The [parts] that are not blank, escaped and joined with ` · `. */
    private fun title(vararg parts: String?): String = lines(*parts).joinToString(SEPARATOR)

    private fun size(element: Element): Int =
        when (element) {
            is Node -> element.label.sumOf { it.length + BREAK }
            is ReferenceNode -> element.label.sumOf { it.length + BREAK }
            is Cluster -> element.title.length
        }

    private fun takeNode(labelLength: Int): Boolean {
        if (nodes >= maxNodes || text + labelLength + NODE_OVERHEAD > maxText) {
            nodesCut = true
            return false
        }
        nodes++
        text += labelLength + NODE_OVERHEAD
        return true
    }

    private fun takeEdge(labelLength: Int): Boolean {
        if (edges >= maxEdges || text + labelLength + EDGE_OVERHEAD > maxText) {
            edgesCut = true
            return false
        }
        edges++
        text += labelLength + EDGE_OVERHEAD
        return true
    }

    companion object {
        /** Mermaid draws at most 500 edges and 50,000 characters ([maxText] keeps a margin for the class lines). */
        const val MAX_NODES = 200
        const val MAX_EDGES = 400
        const val MAX_TEXT = 45_000

        private const val TRIGGERS = "triggers"
        private const val BREAK = 5

        /** Id, indent, brackets and line end of a box, and its id in a `class` line. */
        private const val NODE_OVERHEAD = 32

        /** `        j12s10 -.->|""| j12s11` and line end. */
        private const val EDGE_OVERHEAD = 28

        /** `subgraph j12 [""]`, `direction TB` and `end` with their indents and line ends. */
        private const val CLUSTER_OVERHEAD = 50
        private val SEPARATOR = label(" · ")
        private const val MAX_PROBLEM = 160

        fun draw(
            workflow: Workflow,
            maxNodes: Int = MAX_NODES,
            maxEdges: Int = MAX_EDGES,
            maxText: Int = MAX_TEXT,
        ): WorkflowGraph = WorkflowDrawing(workflow, maxNodes, maxEdges, maxText).run()

        /** `N problems; first: <message> (line L).`, with the first line of the first message, or null without any. */
        private fun problemsNote(problems: List<Problem>): String? {
            val first = problems.firstOrNull() ?: return null
            var message =
                first.message
                    .lineSequence()
                    .first()
                    .trim()
                    .trimEnd('.', ':')
            if (message.length > MAX_PROBLEM) message = message.take(MAX_PROBLEM - 1) + "…"
            val line = first.line?.let { " (line $it)" }.orEmpty()
            val count = if (problems.size == 1) "1 problem:" else "${problems.size} problems; first:"
            return "$count $message$line."
        }
    }
}
