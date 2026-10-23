package dev.sluide.githubactions.mermaid

import dev.sluide.githubactions.SourceLocation
import dev.sluide.githubactions.WorkflowGraph
import dev.sluide.githubactions.mermaid.MermaidLabels.bold
import dev.sluide.githubactions.mermaid.MermaidLabels.label
import dev.sluide.githubactions.mermaid.MermaidLabels.type
import dev.sluide.githubactions.mermaid.PendingInputs.Wanted
import dev.sluide.githubactions.model.Job
import dev.sluide.githubactions.model.JobBody
import dev.sluide.githubactions.model.Problem
import dev.sluide.githubactions.model.Step
import dev.sluide.githubactions.model.StepAction
import dev.sluide.githubactions.model.UsesReference
import dev.sluide.githubactions.model.Workflow
import dev.sluide.githubactions.resolve.ReferenceResolver
import dev.sluide.githubactions.resolve.Resolution
import dev.sluide.githubactions.resolve.SourceFile
import dev.sluide.githubactions.resolve.Target

/**
 * Draws a [Workflow]: its events in a `Triggers` subgraph, one subgraph per job with its steps chained top to bottom,
 * and the jobs linked by `needs` (from the triggers when a job needs none). With a [resolver], reusable workflows and
 * composite actions (local ones, remote ones if it reads them) are drawn nested in place of their `uses:` box, the same
 * way; every other `uses:` is a box (a black box, or why it was not followed). Over [Budget]'s boxes, links or
 * characters, the rest is left out and the note says so; an expansion that does not fit stays a box marked as not
 * expanded, and after [MAX_FAILED_ATTEMPTS] of those no further ones are tried.
 */
internal class WorkflowDrawing private constructor(
    private val workflow: Workflow,
    private val budget: Budget,
    private val resolver: ReferenceResolver?,
    path: String,
) {
    /** The targets being expanded, outermost first: the workflow drawn, then its expansions. */
    private val stack = mutableListOf(Target(null, path))

    /** The files of [stack], whose lines the boxes drawn come from: the last one's. */
    private val files = mutableListOf<SourceFile>(SourceFile.Local(path))

    /**
     * What expansions changed, taken back with an expansion that did not fit: the targets expanded with where
     * (`build / compile`), called workflows with problems, references not expanded for the diagram's limits, whether
     * a file was not read for the file limit, the remote files drawn as not fetched and the remote references drawn.
     */
    private data class Expanded(
        val keys: Map<Target, String>,
        val withProblems: Set<Target>,
        val unexpanded: Int,
        val filesCapped: Boolean,
        val unfetched: Set<Target> = emptySet(),
        val remote: Int = 0,
    )

    private var expanded = Expanded(mapOf(stack.single() to THIS_WORKFLOW), emptySet(), 0, false)

    /** The ids of the jobs being drawn, outermost first: where an expansion is. */
    private val jobPath = mutableListOf<String>()

    /** The inputs wanted, drawn once everything else is, in the order they were asked for. */
    private val inputs = PendingInputs(budget, ::size)

    /** Expansions that did not fit; after [MAX_FAILED_ATTEMPTS] none is tried, which bounds a graph's work. */
    private var failedAttempts = 0

    private fun run(): WorkflowGraph {
        val problems = problemsNote(workflow.problems)
        if (workflow.jobs.isEmpty()) return WorkflowGraph("", problems ?: "No jobs in this workflow.", true)
        val elements = mutableListOf<Element>()
        val triggers = triggers()
        if (triggers != null) elements += triggers
        val jobs = jobs("", workflow, 1, triggers?.id)
        elements += jobs.elements
        inputs.draw()
        val notes =
            listOfNotNull(
                problems,
                jobs.unknown
                    .takeIf { it.isNotEmpty() }
                    ?.let { "Not linked, unknown jobs: ${it.joinToString(", ")}." },
                expanded.withProblems
                    .takeIf { it.isNotEmpty() }
                    ?.let { "Called workflows with problems: ${it.joinToString(", ") { key -> key.written }}." },
                if (budget.nodesCut) {
                    "Too large: showing ${budget.nodes} boxes, ${jobs.drawn} of ${workflow.jobs.size} jobs."
                } else {
                    null
                },
                if (budget.edgesCut) "Too large: showing the first ${budget.edges} links." else null,
                if (expanded.unexpanded > 0) "Some references are not expanded, to stay within the limits." else null,
                if (expanded.filesCapped) {
                    "Read at most ${ReferenceResolver.MAX_FILES} referenced files."
                } else {
                    null
                },
                unfetchedNote(expanded.unfetched.size),
            )
        val diagram = Diagram(elements, jobs.links)
        val note = notes.joinToString(" ").ifEmpty { null }
        return WorkflowGraph(
            MermaidWriter.write(diagram),
            note,
            false,
            expanded.unfetched.size,
            expanded.remote,
            diagram.locations(),
        )
    }

    private fun triggers(): Cluster? {
        val events = mutableListOf<Element>()
        for ((index, event) in workflow.events.withIndex()) {
            // One part, cut as a whole: a stadium is as wide as its text.
            val line = label(listOf(event.name, event.detail.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            if (!budget.takeNode(line.length, "t$index", 2)) break
            events += Node("t$index", listOf(line), Shape.STADIUM)
        }
        if (events.isEmpty()) return null
        val title = label("Triggers")
        budget.addCluster(title.length, TRIGGERS, 1)
        return Cluster(TRIGGERS, title, events, direction = Direction.LR)
    }

    /** The jobs of a workflow drawn at [depth], with their links, how many were drawn and needs of unknown jobs. */
    private class Jobs(
        val elements: List<Element>,
        val links: List<Link>,
        val drawn: Int,
        val unknown: List<String>,
    )

    /** The jobs of [workflow] as subgraphs `<prefix>j<index>` at [depth], linked from [triggers] if needing none. */
    private fun jobs(
        prefix: String,
        workflow: Workflow,
        depth: Int,
        triggers: String?,
    ): Jobs {
        val drawn = mutableListOf<Pair<Job, Cluster>>()
        for ((index, job) in workflow.jobs.withIndex()) {
            val cluster = (if (budget.nodesCut) null else jobCluster("${prefix}j$index", job, depth)) ?: break
            drawn += job to cluster
        }
        val known = workflow.jobs.map { it.id }.toSet()
        val clusterOf = drawn.reversed().associate { (job, cluster) -> job.id to cluster.id }
        val unknown = mutableListOf<String>()
        val elements = mutableListOf<Element>()
        val links = mutableListOf<Link>()
        for ((job, cluster) in drawn) {
            val condition = job.condition.ifPresent()?.let { label("if: $it") }
            job.needs.filter { it !in known }.forEach { unknown += "${job.id} needs $it" }
            val sources =
                if (job.needs.isEmpty()) {
                    listOfNotNull(triggers)
                } else {
                    job.needs
                        .mapNotNull { clusterOf[it] }
                        .filter { it != cluster.id }
                        .distinct()
                }
            var linked = false
            for (source in sources) {
                if (!budget.takeEdge(condition?.length ?: 0, source, cluster.id, depth)) break
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
        return Jobs(elements, links, drawn.size, unknown)
    }

    private fun jobCluster(
        id: String,
        job: Job,
        depth: Int,
    ): Cluster? {
        // The condition may end up in the title (when no link carries it), so its length is reserved before the
        // children take the rest of the text budget.
        budget.reserve(job.condition.ifPresent()?.let { SEPARATOR.length + label("if: $it").length } ?: 0)
        jobPath += job.id
        try {
            return jobBody(id, job, depth)
        } finally {
            jobPath.removeAt(jobPath.lastIndex)
        }
    }

    private fun jobBody(
        id: String,
        job: Job,
        depth: Int,
    ): Cluster? {
        val children = mutableListOf<Element>()
        val links = mutableListOf<Link>()
        val title: String
        when (val body = job.body) {
            is JobBody.Steps -> {
                val name = job.name.ifPresent() ?: job.id
                // `name: Build (${{ matrix.os }})` with `runs-on: ${{ matrix.os }}` says the runner already.
                val runsOn = job.runsOn?.takeUnless { it.isNotBlank() && it in name }
                title = title(name, runsOn, job.matrix?.title())
                steps(id, body.steps, depth + 1)?.let { drawn ->
                    children += drawn.elements
                    links += chain(drawn.elements, depth + 1)
                    inputs.defer(drawn.inputs, depth + 1, children)
                }
            }

            is JobBody.WorkflowCall -> {
                title =
                    join(
                        esc(job.name.ifPresent() ?: job.id),
                        "calls " + bold(label(body.text)),
                        esc(job.matrix?.title()),
                    )
                val secrets = if (body.secretsInherit) "secrets: inherit" else null
                val element =
                    reference(
                        "${id}w",
                        lines(body.text).map { bold(it) } + lines(secrets),
                        body.reference,
                        depth + 1,
                        null,
                        here(job.line),
                    ) { called ->
                        join(called, esc(secrets))
                    }
                if (element != null) {
                    children += element
                    inputs.defer(listOf(Wanted("${id}w", body.inputs)), depth + 1, children)
                }
            }
        }
        if (children.isEmpty()) return null
        budget.addCluster(title.length, id, depth)
        return Cluster(id, title, children, links, here(job.line))
    }

    /** What [steps] drew: the [elements] to chain, and the [inputs] their `uses:` steps want shown. */
    private class Steps(
        val elements: MutableList<Element>,
        val inputs: List<Wanted>,
    )

    /**
     * The [steps] as `<prefix>s<position>` at [depth], or a `no steps` box; as many as fit, or null if not all of them
     * fit when [all] are needed.
     */
    private fun steps(
        prefix: String,
        steps: List<Step>,
        depth: Int,
        all: Boolean = false,
    ): Steps? {
        val elements = mutableListOf<Element>()
        val wanted = mutableListOf<Wanted>()
        for ((position, step) in steps.withIndex()) {
            val id = "${prefix}s$position"
            elements += step(id, step, depth) ?: if (all) return null else break
            if (step.action is StepAction.Uses) wanted += Wanted(id, step.inputs)
        }
        if (steps.isEmpty()) {
            val none = Node("${prefix}none", listOf(label("no steps")))
            if (budget.takeNode(size(none.label), none.id, depth)) elements += none
        }
        return Steps(elements, wanted)
    }

    /** Links from each of [children] to the next, written at [depth]. */
    private fun chain(
        children: List<Element>,
        depth: Int,
    ): MutableList<Link> {
        val links = mutableListOf<Link>()
        for (position in 1 until children.size) {
            val from = children[position - 1].id
            val to = children[position].id
            if (!budget.takeEdge(0, from, to, depth)) break
            links += Link(from, to)
        }
        return links
    }

    private fun step(
        id: String,
        step: Step,
        depth: Int,
    ): Element? {
        val condition = step.condition.ifPresent()?.let { "if: $it" }
        return when (val action = step.action) {
            is StepAction.Run -> {
                val label = lines(step.name.ifPresent() ?: "run: ${action.firstLine}".trimEnd(), condition)
                Node(id, label, location = here(step.line)).takeIf { budget.takeNode(size(label), id, depth) }
            }

            is StepAction.Uses -> {
                val label = lines(step.name) + lines(action.text).map { bold(it) }
                reference(id, label, action.reference, depth, condition, here(step.line)) { composite ->
                    join(esc(step.name), composite, esc(condition))
                }
            }
        }
    }

    /**
     * The box of a `uses:` [reference] at [depth] with [lines], what was found out about it and the step's
     * [condition]; or what it refers to drawn nested under its [id], with [title] made from what the target is
     * (`calls x.yml`, `Name <composite>`), made of escaped text and tags. Either comes from
     * [location], the line of the `uses:`. Null if it does not fit; a remote reference is counted once it is drawn.
     */
    private fun reference(
        id: String,
        lines: List<String>,
        reference: UsesReference,
        depth: Int,
        condition: String?,
        location: SourceLocation?,
        title: (what: String) -> String,
    ): Element? {
        fun drawn(node: ReferenceNode): ReferenceNode {
            if (reference is UsesReference.RemoteAction || reference is UsesReference.RemoteWorkflow) {
                expanded = expanded.copy(remote = expanded.remote + 1)
            }
            return node
        }

        fun box(
            found: List<String>,
            style: Style,
            type: String? = null,
        ): ReferenceNode? {
            val label = lines + listOfNotNull(type?.let { type(it) }) + found.map { label(it) } + lines(condition)
            val node = ReferenceNode(id, label, reference, style = style, location = location)
            return if (budget.takeNode(size(label), id, depth)) drawn(node) else null
        }

        val canExpand = failedAttempts < MAX_FAILED_ATTEMPTS && budget.hasRoom()
        val resolution = resolver?.resolve(reference, stack, expanded.keys, canExpand) ?: Resolution.Opaque
        when ((resolution as? Resolution.Stopped)?.reason) {
            Resolution.Reason.FILE_LIMIT -> {
                expanded = expanded.copy(filesCapped = true)
            }

            Resolution.Reason.DIAGRAM_LIMIT -> {
                expanded = expanded.copy(unexpanded = expanded.unexpanded + 1)
            }

            else -> {}
        }
        if (!canExpand && (resolution is Resolution.CalledWorkflow || resolution is Resolution.Composite)) {
            expanded = expanded.copy(unexpanded = expanded.unexpanded + 1)
            return box(listOf(NOT_EXPANDED), Style.LIMIT)
        }
        val expansion =
            when (resolution) {
                is Resolution.Opaque -> {
                    return box(emptyList(), Style.BLACKBOX)
                }

                is Resolution.Annotated -> {
                    return box(resolution.lines, Style.BLACKBOX, resolution.type)
                }

                is Resolution.Stopped -> {
                    val node = box(listOf(resolution.text), style(resolution.reason))
                    val key = resolution.key
                    if (node != null && key != null && resolution.reason == Resolution.Reason.NOT_FETCHED) {
                        expanded = expanded.copy(unfetched = expanded.unfetched + key)
                    }
                    return node
                }

                is Resolution.CalledWorkflow -> {
                    expand(resolution.key, resolution.file) {
                        calledWorkflow(
                            id,
                            resolution,
                            depth,
                            title("calls " + bold(label(resolution.fileName))),
                        )
                    }
                }

                is Resolution.Composite -> {
                    expand(resolution.key, resolution.file) {
                        val clusterTitle = title(bold(label(resolution.title)) + SEPARATOR + type("composite"))
                        budget.addCluster(clusterTitle.length, id, depth)
                        steps(id, resolution.steps, depth + 1, all = true)?.let { drawn ->
                            val links = chain(drawn.elements, depth + 1)
                            inputs.defer(drawn.inputs, depth + 1, drawn.elements)
                            Cluster(id, clusterTitle, drawn.elements, links)
                        }
                    }
                }
            }
        return if (expansion != null) {
            drawn(ReferenceNode(id, lines, reference, expansion, location = location))
        } else {
            box(listOf(NOT_EXPANDED), Style.LIMIT)
        }
    }

    /**
     * [draw]s the expansion of [key], read from [file], on trial, or null if it does not fit: then everything it
     * changed is taken back, the reference is counted as not expanded and the attempt as failed.
     */
    private fun expand(
        key: Target,
        file: SourceFile,
        draw: () -> Cluster?,
    ): Cluster? {
        val before = expanded
        val deferred = inputs.mark()
        val where = jobPath.joinToString(" / ").ifEmpty { THIS_WORKFLOW }
        val cluster =
            budget.attempt {
                expanded = expanded.copy(keys = expanded.keys + (key to where))
                stack += key
                files += file
                try {
                    draw()
                } finally {
                    stack.removeAt(stack.lastIndex)
                    files.removeAt(files.lastIndex)
                }
            }
        if (cluster == null) {
            inputs.trim(deferred)
            expanded = before.copy(unexpanded = before.unexpanded + 1)
            failedAttempts++
        }
        return cluster
    }

    private fun calledWorkflow(
        id: String,
        called: Resolution.CalledWorkflow,
        depth: Int,
        title: String,
    ): Cluster? {
        budget.addCluster(title.length, id, depth)
        val jobs = jobs(id, called.workflow, depth + 1, triggers = null)
        if (jobs.elements.isEmpty()) return null
        if (called.workflow.problems.isNotEmpty()) {
            expanded = expanded.copy(withProblems = expanded.withProblems + called.key)
        }
        return Cluster(id, title, jobs.elements, jobs.links)
    }

    /** Where [line] of the file being drawn is. */
    private fun here(line: Int?): SourceLocation? = files.last().at(line)

    /** The escaped label lines of the [texts] that are not blank. */
    private fun lines(vararg texts: String?): List<String> =
        texts.mapNotNull { text -> text?.takeIf { it.isNotBlank() }?.let { label(it) } }

    /** This text, or null when it is blank: blank names and conditions count as none. */
    private fun String?.ifPresent(): String? = this?.takeIf { it.isNotBlank() }

    /** The [parts] that are not blank, escaped and joined with ` · `. */
    private fun title(vararg parts: String?): String = lines(*parts).joinToString(SEPARATOR)

    /** [text] escaped, or null when it is blank or null. */
    private fun esc(text: String?): String? = text.ifPresent()?.let { label(it) }

    /** The escaped [parts] that are not null or blank, joined with ` · `. */
    private fun join(vararg parts: String?): String = parts.filter { !it.isNullOrBlank() }.joinToString(SEPARATOR)

    private fun size(label: List<String>): Int = label.sumOf { it.length + BREAK }

    companion object {
        /** Mermaid draws at most 500 edges and 50,000 characters ([maxText] keeps a margin for the class lines). */
        const val MAX_NODES = 200
        const val MAX_EDGES = 400
        const val MAX_TEXT = 45_000

        /** The line of a reference box whose expansion did not fit. */
        const val NOT_EXPANDED = ReferenceResolver.NOT_EXPANDED

        /** Expansions that may fail to fit before no further one is tried. */
        const val MAX_FAILED_ATTEMPTS = 10

        /** Where the workflow drawn is, for a cycle back to it. */
        private const val THIS_WORKFLOW = "this workflow"

        private const val TRIGGERS = "triggers"
        private const val BREAK = 5
        private val SEPARATOR = label(" · ")
        private const val MAX_PROBLEM = 160

        /**
         * Draws [workflow], at [path] in its repository (`.github/workflows/ci.yml`), following its local references
         * with [resolver] if there is one.
         */
        fun draw(
            workflow: Workflow,
            maxNodes: Int = MAX_NODES,
            maxEdges: Int = MAX_EDGES,
            maxText: Int = MAX_TEXT,
            resolver: ReferenceResolver? = null,
            path: String = "",
        ): WorkflowGraph = WorkflowDrawing(workflow, Budget(maxNodes, maxEdges, maxText), resolver, path).run()

        private fun style(reason: Resolution.Reason): Style =
            when (reason) {
                Resolution.Reason.NOT_FOUND, Resolution.Reason.NOT_FETCHED -> Style.UNRESOLVED
                Resolution.Reason.FAILED -> Style.FAILED
                Resolution.Reason.CYCLE -> Style.CYCLE
                Resolution.Reason.LIMIT, Resolution.Reason.FILE_LIMIT, Resolution.Reason.DIAGRAM_LIMIT -> Style.LIMIT
                Resolution.Reason.SEEN -> Style.BLACKBOX
            }

        /** What the note says about [count] remote files not fetched, or null for none. */
        private fun unfetchedNote(count: Int): String? =
            when (count) {
                0 -> null
                1 -> "1 reference not fetched — Fetch References to show it."
                else -> "$count references not fetched — Fetch References to show them."
            }

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
