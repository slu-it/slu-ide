package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.WorkflowSources
import dev.sluide.githubactions.actions.ActionManifest
import dev.sluide.githubactions.actions.ActionManifests
import dev.sluide.githubactions.actions.Runs
import dev.sluide.githubactions.model.UsesReference
import dev.sluide.githubactions.model.WorkflowJson
import dev.sluide.githubactions.resolve.Resolution.Reason
import java.nio.file.FileSystemException

/**
 * Follows local `uses:` (`./…`) for one graph: reads the reusable workflow or the action's manifest from [sources],
 * parses it ([parser], [ActionManifests]) and says what to draw. Paths are resolved against the repository's root
 * (as GitHub does) and must stay in it; references to what is being expanded ([resolve]'s `stack`) are cycles, to what
 * was expanded already ([resolve]'s `expanded`) say where. At most [MAX_DEPTH] expansions nest and [MAX_FILES]
 * distinct files are read; results are kept per file, so a file is read and parsed once per graph. After the parser
 * threw once it is not called again for this graph, and nothing more is read once [cancelled] says so.
 */
internal class ReferenceResolver(
    private val sources: WorkflowSources,
    private val parser: WorkflowParser,
    private val cancelled: () -> Boolean = { false },
) {
    /** The paths read (or tried), at most [MAX_FILES]. */
    private val read = HashSet<String>()
    private val results = HashMap<String, Resolution>()
    private var parserFailed = false

    /**
     * What [reference] leads to while the targets in [stack] are being expanded (outermost first: the workflow drawn,
     * then its expansions) and those in [expanded] were expanded (by key, where: `build / compile`, all of [stack]
     * among them). Unless [canExpand], a reusable workflow is not read: its expansion could not be drawn.
     */
    fun resolve(
        reference: UsesReference,
        stack: List<String>,
        expanded: Map<String, String>,
        canExpand: Boolean,
    ): Resolution {
        val written =
            when (reference) {
                is UsesReference.LocalWorkflow -> reference.path
                is UsesReference.LocalAction -> reference.path
                else -> return Resolution.Opaque
            }
        val key = normalise(written) ?: return Resolution.Stopped(Reason.FAILED, OUTSIDE)
        val where = expanded[key]
        return when {
            key in stack -> Resolution.Stopped(Reason.CYCLE, "cycle: back to $where")
            where != null -> Resolution.Stopped(Reason.SEEN, "expanded in $where")
            stack.size > MAX_DEPTH -> Resolution.Stopped(Reason.LIMIT, "not expanded (depth limit)")
            cancelled() -> Resolution.Stopped(Reason.LIMIT, "not expanded (cancelled)")
            reference !is UsesReference.LocalWorkflow -> action(key)
            canExpand -> workflow(key)
            else -> Resolution.Stopped(Reason.DIAGRAM_LIMIT, NOT_EXPANDED)
        }
    }

    private fun workflow(key: String): Resolution {
        results[key]?.let { return it }
        val text =
            when (val file = read(key)) {
                is Read.Text -> file.text
                is Read.Missing -> return keep(key, Resolution.Stopped(Reason.NOT_FOUND, "not found"))
                is Read.Failed -> return keep(key, Resolution.Stopped(Reason.FAILED, file.reason))
                is Read.Capped -> return CAPPED
            }
        if (parserFailed) return keep(key, failed("parser unavailable"))
        val fileName = key.substringAfterLast('/')
        val json =
            try {
                parser.parse(fileName, text)
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                parserFailed = true
                return keep(key, failed("parser failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        val result =
            when (val decoded = WorkflowJson.decode(json)) {
                is WorkflowJson.Result.Unsupported -> {
                    failed(decoded.reason)
                }

                is WorkflowJson.Result.Decoded -> {
                    val workflow = decoded.workflow
                    if (workflow.jobs.isEmpty()) {
                        failed(workflow.problems.firstOrNull()?.message ?: "no jobs")
                    } else {
                        Resolution.CalledWorkflow(key, fileName, workflow)
                    }
                }
            }
        return keep(key, result)
    }

    private fun action(key: String): Resolution {
        results[key]?.let { return it }
        val folder = if (key.isEmpty()) "" else "$key/"
        var file = read("${folder}action.yml")
        if (file is Read.Missing) file = read("${folder}action.yaml")
        val text =
            when (file) {
                is Read.Text -> file.text
                is Read.Missing -> return keep(key, Resolution.Stopped(Reason.NOT_FOUND, "not found (no action.yml)"))
                is Read.Failed -> return keep(key, Resolution.Stopped(Reason.FAILED, file.reason))
                is Read.Capped -> return CAPPED
            }
        val result =
            when (val parsed = ActionManifests.parse(text)) {
                is ActionManifests.Result.Failed -> failed(parsed.reason)
                is ActionManifests.Result.Parsed -> manifest(key, parsed.manifest)
            }
        return keep(key, result)
    }

    private fun manifest(
        key: String,
        manifest: ActionManifest,
    ): Resolution {
        val inputs =
            manifest.inputs
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = "inputs: ") { if (it.required) "${it.name}*" else it.name }
        return when (val runs = manifest.runs) {
            is Runs.Composite -> Resolution.Composite(key, manifest.name ?: "./$key", runs.steps)
            is Runs.JavaScript -> Resolution.Annotated(listOfNotNull("JS action (${runs.using})", inputs))
            is Runs.Docker -> Resolution.Annotated(listOfNotNull("Docker (${runs.image ?: "no image"})", inputs))
            is Runs.Unknown -> failed(runs.using?.let { "unknown runs.using: $it" } ?: "no runs.using")
        }
    }

    private fun read(path: String): Read {
        if (path !in read) {
            if (read.size >= MAX_FILES) return Read.Capped
            read += path
        }
        return try {
            sources.local(path)?.let(Read::Text) ?: Read.Missing
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            // A FileSystemException's message is the path, which the box shows already.
            val reason =
                if (e is FileSystemException) {
                    "unreadable (${e.javaClass.simpleName})"
                } else {
                    e.message?.takeIf { it.isNotBlank() } ?: "unreadable"
                }
            Read.Failed(reason)
        }
    }

    private fun keep(
        key: String,
        result: Resolution,
    ): Resolution = result.also { results[key] = it }

    private fun failed(reason: String) =
        Resolution.Stopped(
            Reason.FAILED,
            reason
                .lineSequence()
                .first()
                .trim()
                .trimEnd('.', ':'),
        )

    private sealed interface Read {
        data class Text(
            val text: String,
        ) : Read

        data object Missing : Read

        data class Failed(
            val reason: String,
        ) : Read

        data object Capped : Read
    }

    companion object {
        /** Nested expansions, GitHub's limit for reusable workflows and composite actions. */
        const val MAX_DEPTH = 10

        /** Distinct files read for one graph. */
        const val MAX_FILES = 50

        const val OUTSIDE = "outside the repository"

        /** The line of a reference box whose expansion did not fit (or would not, so it was not read). */
        const val NOT_EXPANDED = "not expanded (diagram limit)"

        /** Not cached: nothing more is read anyway. */
        private val CAPPED = Resolution.Stopped(Reason.FILE_LIMIT, "not expanded (file limit)")

        /**
         * [written] (`./a/../b/`) as a path relative to the repository's root (`b`, `` for the root itself), or null
         * if it leaves the root or is absolute.
         */
        fun normalise(written: String): String? {
            val relative = written.removePrefix("./")
            if (relative.startsWith("/")) return null
            val segments = ArrayList<String>()
            for (segment in relative.split('/')) {
                when (segment) {
                    "", "." -> {}

                    ".." -> {
                        segments.removeLastOrNull() ?: return null
                    }

                    else -> {
                        segments += segment
                    }
                }
            }
            return segments.joinToString("/")
        }
    }
}
