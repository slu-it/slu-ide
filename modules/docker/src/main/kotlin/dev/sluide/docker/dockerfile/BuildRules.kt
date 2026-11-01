package dev.sluide.docker.dockerfile

import dev.sluide.docker.EngineState
import dev.sluide.docker.compose.engineNote
import java.nio.file.Path

/*
 * The decisions of the Build pane, without controls (ADR 0084): texts, questions, what is enabled and what is marked.
 */

/** The answer to the question about unsaved changes before a build. */
internal enum class BuildUnsavedChoice { SAVE_AND_BUILD, BUILD_SAVED, CANCEL }

/** What the preview under the form shows: the command line Build runs, or why it cannot run. */
internal sealed interface BuildPreview {
    data class Command(
        val line: String,
    ) : BuildPreview

    data class Problem(
        val message: String,
    ) : BuildPreview
}

/** The question before a build of [fileName] while its editor has unsaved changes. */
internal fun unsavedBuildQuestion(fileName: String): String =
    "$fileName has unsaved changes. Save them before the build, or build the saved version?"

/** The problem shown when Build Saved Version cannot read the saved file. */
internal fun savedUnreadableProblem(fileName: String): String =
    "✖ The saved version of $fileName cannot be read; nothing was built"

/**
 * The note under the toolbar: that a build waits for the engine, else why Build is disabled because of the engine
 * ([engineNote]), or `null`.
 */
internal fun buildNote(
    engine: EngineState,
    waiting: Boolean,
): String? = if (waiting) "Build waits for the engine…" else engineNote(engine)

/** The name of a build's job: its first tag, else the file. */
internal fun buildLabel(
    options: BuildOptions,
    fileName: String,
): String = "build ${tagsOf(options.tags).firstOrNull() ?: fileName}"

/** Whether Build can run: the engine answers and the options give a command. */
internal fun buildEnabled(
    engine: EngineState,
    preview: BuildPreview,
): Boolean = engine is EngineState.Available && preview is BuildPreview.Command

/**
 * Whether Run… can run the image of the last build: the engine answers, the last build of the view ended with exit
 * code 0 and had a tag ([builtTag]), and no build runs since.
 */
internal fun runImageEnabled(
    engine: EngineState,
    builtTag: String?,
    running: Boolean,
): Boolean = engine is EngineState.Available && builtTag != null && !running

/** The tooltip of Run…: what it runs, or why it is disabled. */
internal fun runImageTooltip(
    builtTag: String?,
    running: Boolean,
): String =
    when {
        running -> "Runs the image once the build has succeeded"
        builtTag == null -> "Build with a tag first"
        else -> "Run $builtTag in a new container"
    }

/** Whether the stored [target] is not one of the file's [stages] (it is passed anyway, but marked). */
internal fun isMissingTarget(
    target: String,
    stages: List<String>,
): Boolean = target.isNotEmpty() && stages.none { it.equals(target, ignoreCase = true) }

/**
 * How a chosen context folder is stored: relative to the Dockerfile's [folder] when inside it (empty for the folder
 * itself, the default), else absolute.
 */
internal fun contextEntry(
    folder: Path,
    chosen: Path,
): String {
    val base = folder.toAbsolutePath().normalize()
    val path = chosen.toAbsolutePath().normalize()
    return if (path.startsWith(base)) base.relativize(path).toString() else path.toString()
}

/** [values] with [value] for the build arg [name]; an empty value removes it, so that the ARG's default applies. */
internal fun withArg(
    values: Map<String, String>,
    name: String,
    value: String,
): Map<String, String> = if (value.isEmpty()) values - name else values + (name to value)
