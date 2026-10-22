package dev.sluide.githubactions.actions

import dev.sluide.githubactions.model.Step

/** A local action's `action.yml` as far as the graph needs it: its [name], [inputs] and how it [runs]. */
internal data class ActionManifest(
    val name: String?,
    val inputs: List<ActionInput>,
    val runs: Runs,
)

internal data class ActionInput(
    val name: String,
    val required: Boolean,
)

/** `runs:` of a manifest, by its `using`. */
internal sealed interface Runs {
    /** `using: composite` with its `steps`, lines 1-based in the manifest. */
    data class Composite(
        val steps: List<Step>,
    ) : Runs

    /** `using: node20` and the like. */
    data class JavaScript(
        val using: String,
    ) : Runs

    /** `using: docker` with its `image` (a Dockerfile or `docker://…`). */
    data class Docker(
        val image: String?,
    ) : Runs

    /** No `runs.using`, or one GitHub does not know; [using] null when missing. */
    data class Unknown(
        val using: String?,
    ) : Runs
}
