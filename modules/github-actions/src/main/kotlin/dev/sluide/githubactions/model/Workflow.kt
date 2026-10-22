package dev.sluide.githubactions.model

/** A workflow as the bundled parser read it (its JSON, schema 1, decoded by [WorkflowJson]). */
internal data class Workflow(
    val name: String?,
    val problems: List<Problem>,
    val events: List<Event>,
    val jobs: List<Job>,
)

/** One of the parser's errors; [line] and [column] are 1-based or null when unknown. */
internal data class Problem(
    val message: String,
    val line: Int?,
    val column: Int?,
)

/** An `on` event with a summary of its filters ([detail]), e.g. `branches: main, master`. */
internal data class Event(
    val name: String,
    val detail: String?,
)

internal data class Job(
    val id: String,
    val name: String?,
    val line: Int?,
    val needs: List<String>,
    val condition: String?,
    val runsOn: String?,
    val matrix: Matrix?,
    val body: JobBody,
)

/** What a job does: run [Steps], or call a reusable workflow ([WorkflowCall]). */
internal sealed interface JobBody {
    data class Steps(
        val steps: List<Step>,
    ) : JobBody

    /** A reusable workflow job: [text] as written, its `with` inputs (name to value text), `secrets: inherit`. */
    data class WorkflowCall(
        val text: String,
        val reference: UsesReference,
        val inputs: Map<String, String>,
        val secretsInherit: Boolean,
    ) : JobBody
}

internal data class Step(
    val id: String?,
    val name: String?,
    val line: Int?,
    val action: StepAction,
    val condition: String?,
)

internal sealed interface StepAction {
    /** A `run` step with the script's first non-blank line, trimmed (empty for an empty script). */
    data class Run(
        val firstLine: String,
    ) : StepAction

    /** A `uses` step: [text] as written and what it refers to. */
    data class Uses(
        val text: String,
        val reference: UsesReference,
    ) : StepAction
}
