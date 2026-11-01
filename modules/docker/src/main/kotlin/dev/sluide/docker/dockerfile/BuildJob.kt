package dev.sluide.docker.dockerfile

import dev.sluide.commandruns.RunJob

/**
 * A build of the Dockerfile view, named by its first tag or else the file ([label]: `build myapp:1.0`, `build
 * Dockerfile`), e.g. `▶ build myapp:1.0 — running 0:02`; [fileName] names the Dockerfile in its background task row
 * (`Build: build myapp:1.0 (Dockerfile)`).
 */
internal data class BuildJob(
    override val label: String,
    val fileName: String,
) : RunJob {
    override val backgroundName: String get() = "Build: $label ($fileName)"
}
