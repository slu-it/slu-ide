package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.model.Step
import dev.sluide.githubactions.model.Workflow

/**
 * What a `uses:` leads to ([ReferenceResolver]); [key] is the target, [file] the file read, whose lines the nested
 * boxes come from.
 */
internal sealed interface Resolution {
    /** Not a reference to follow (`docker://`, unparsed, nothing to read it from): a black box as written. */
    data object Opaque : Resolution

    /** A reusable workflow, to draw nested. */
    data class CalledWorkflow(
        val key: Target,
        val fileName: String,
        val workflow: Workflow,
        val file: SourceFile,
    ) : Resolution

    /** A composite action, [title] its name or path (`owner/repo@ref` for a remote one), to draw nested. */
    data class Composite(
        val key: Target,
        val title: String,
        val steps: List<Step>,
        val file: SourceFile,
    ) : Resolution

    /**
     * An action that stays a black box, with its [type] (`JS action (node20)`, `Docker (alpine)`) and [lines] saying
     * what else is known, or a `./…` in a remote file, which refers to the caller's workspace.
     */
    data class Annotated(
        val lines: List<String>,
        val type: String? = null,
    ) : Resolution

    /** A reference not followed, for the [reason] a box with [text] shows; [key] the target if known. */
    data class Stopped(
        val reason: Reason,
        val text: String,
        val key: Target? = null,
    ) : Resolution

    enum class Reason {
        /** No such file. */
        NOT_FOUND,

        /** A remote file not in the cache, and the network was not to be used. */
        NOT_FETCHED,

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
