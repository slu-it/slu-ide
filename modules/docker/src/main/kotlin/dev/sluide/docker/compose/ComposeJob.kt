package dev.sluide.docker.compose

import dev.sluide.commandruns.RunJob

/**
 * A compose command of the Compose view, named by its verb and the service if any ([label]: `up`, `down -v`,
 * `up web`), e.g. `▶ up web — running 0:02`; [fileName] names the compose file in its background task row.
 */
internal data class ComposeJob(
    override val label: String,
    val fileName: String,
) : RunJob {
    override val backgroundName: String get() = "Compose: $label ($fileName)"
}
