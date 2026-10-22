package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.model.Step
import dev.sluide.githubactions.model.Workflow

/** What a `uses:` leads to ([ReferenceResolver]); [key] is the target's normalised path in the repository. */
internal sealed interface Resolution {
    /** Not a local reference (remote, `docker://`, unparsed): a black box as written. */
    data object Opaque : Resolution

    /** A local reusable workflow, to draw nested. */
    data class CalledWorkflow(
        val key: String,
        val fileName: String,
        val workflow: Workflow,
    ) : Resolution

    /** A local composite action, [title] its name or path, to draw nested. */
    data class Composite(
        val key: String,
        val title: String,
        val steps: List<Step>,
    ) : Resolution

    /** A local action that stays a black box, with [lines] saying what it is (`JS action (node20)`, its inputs). */
    data class Annotated(
        val lines: List<String>,
    ) : Resolution

    /** A local reference not followed, for the [reason] a box with [text] shows. */
    data class Stopped(
        val reason: Reason,
        val text: String,
    ) : Resolution

    enum class Reason {
        /** No such file. */
        NOT_FOUND,

        /** Outside the repository, unreadable, or does not parse. */
        FAILED,

        /** Already being expanded further up. */
        CYCLE,

        /** Too deep, or the graph is no longer wanted. */
        LIMIT,

        /** Not read: the graph read as many files as it may. */
        FILE_LIMIT,

        /** Not read: its expansion could not be drawn within the diagram's limits. */
        DIAGRAM_LIMIT,

        /** Expanded elsewhere in the graph already. */
        SEEN,
    }
}
