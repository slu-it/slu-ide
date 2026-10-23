package dev.sluide.githubactions.resolve

import dev.sluide.githubactions.RemoteFile
import dev.sluide.githubactions.RemoteReader
import dev.sluide.githubactions.RemoteText
import dev.sluide.githubactions.RepositoryFiles
import dev.sluide.githubactions.WorkflowParser
import dev.sluide.githubactions.WorkflowSources
import dev.sluide.githubactions.actions.ActionManifest
import dev.sluide.githubactions.actions.ActionManifests
import dev.sluide.githubactions.actions.Runs
import dev.sluide.githubactions.model.UsesReference
import dev.sluide.githubactions.model.WorkflowJson
import dev.sluide.githubactions.resolve.Resolution.Reason
import java.nio.file.FileSystemException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Follows `uses:` for one graph: reads the reusable workflow or the action's manifest, local ones (`./…`) from
 * [sources] (unless [WorkflowSources.NONE]), remote ones (`owner/repo[/path]@ref`) from [remote] (if there is one),
 * parses it ([parser], [ActionManifests]) and says what to draw. Paths are resolved against the repository's root (as
 * GitHub does) and must stay in it. In a remote file, a reusable workflow's `./.github/workflows/…` is in the same
 * repository and ref, an action's `./…` in the caller's workspace (not followed). References to what is being expanded
 * ([resolve]'s `stack`) are cycles, to what was expanded already ([resolve]'s `expanded`) say where. At most
 * [MAX_DEPTH] expansions nest and [MAX_FILES] distinct files are read; results are kept per file, so a file is read and
 * parsed once per graph. After the parser threw once it is not called again for this graph, and nothing more is read
 * once [cancelled] says so. When a remote file was fetched is shown as a date in [zone].
 */
internal class ReferenceResolver(
    private val sources: WorkflowSources,
    private val parser: WorkflowParser,
    private val cancelled: () -> Boolean = { false },
    private val remote: RemoteReader? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The files read (or tried), at most [MAX_FILES]. */
    private val read = HashSet<Target>()
    private val results = HashMap<Target, Resolution>()
    private var parserFailed = false

    /**
     * What [reference] leads to while the targets in [stack] are being expanded (outermost first: the workflow drawn,
     * then its expansions; [reference] is written in the last one) and those in [expanded] were expanded (by key,
     * where: `build / compile`, all of [stack] among them). Unless [canExpand], a reusable workflow is not read: its
     * expansion could not be drawn.
     */
    fun resolve(
        reference: UsesReference,
        stack: List<Target>,
        expanded: Map<Target, String>,
        canExpand: Boolean,
    ): Resolution {
        val frame = stack.lastOrNull()?.repository
        val local = sources !== WorkflowSources.NONE
        val (repository, written) =
            when (reference) {
                is UsesReference.LocalWorkflow -> {
                    if (frame == null && !local) return Resolution.Opaque
                    frame to reference.path
                }

                is UsesReference.LocalAction -> {
                    if (frame != null) return WORKSPACE_RELATIVE
                    if (!local) return Resolution.Opaque
                    null to reference.path
                }

                is UsesReference.RemoteAction -> {
                    if (remote == null) return Resolution.Opaque
                    RemoteRepository(reference.owner, reference.repo, reference.ref) to reference.path.orEmpty()
                }

                is UsesReference.RemoteWorkflow -> {
                    if (remote == null) return Resolution.Opaque
                    RemoteRepository(reference.owner, reference.repo, reference.ref) to reference.path
                }

                else -> {
                    return Resolution.Opaque
                }
            }
        val key = Target(repository, normalise(written) ?: return Resolution.Stopped(Reason.FAILED, OUTSIDE))
        val isWorkflow = reference is UsesReference.LocalWorkflow || reference is UsesReference.RemoteWorkflow
        val where = expanded[key]
        return when {
            key in stack -> Resolution.Stopped(Reason.CYCLE, "cycle: back to $where")
            where != null -> Resolution.Stopped(Reason.SEEN, "expanded in $where")
            stack.size > MAX_DEPTH -> Resolution.Stopped(Reason.LIMIT, "not expanded (depth limit)")
            cancelled() -> Resolution.Stopped(Reason.LIMIT, "not expanded (cancelled)")
            !isWorkflow -> action(key)
            canExpand -> workflow(key)
            else -> Resolution.Stopped(Reason.DIAGRAM_LIMIT, NOT_EXPANDED)
        }
    }

    private fun workflow(key: Target): Resolution {
        results[key]?.let { return it }
        val file = read(key)
        if (file !is Read.Text) return stopped(key, file, "not found")
        if (parserFailed) return keep(key, failed("parser unavailable"))
        val fileName = key.path.substringAfterLast('/')
        val json =
            try {
                parser.parse(fileName, file.text)
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
                        Resolution.CalledWorkflow(key, fileName, workflow, file.asOf)
                    }
                }
            }
        return keep(key, result)
    }

    private fun action(key: Target): Resolution {
        results[key]?.let { return it }
        val folder = if (key.path.isEmpty()) "" else "${key.path}/"
        var file = read(key.copy(path = "${folder}action.yml"))
        if (file is Read.Missing) file = read(key.copy(path = "${folder}action.yaml"))
        if (file !is Read.Text) return stopped(key, file, "not found (no action.yml)")
        val result =
            when (val parsed = ActionManifests.parse(file.text)) {
                is ActionManifests.Result.Failed -> failed(parsed.reason)
                is ActionManifests.Result.Parsed -> manifest(key, parsed.manifest, file.asOf)
            }
        return keep(key, result)
    }

    /** Why [file], read for [key], is not drawn, kept for [key] unless it was not read for the file limit. */
    private fun stopped(
        key: Target,
        file: Read,
        notFound: String,
    ): Resolution =
        when (file) {
            is Read.Text, is Read.Capped -> CAPPED
            is Read.Missing -> keep(key, Resolution.Stopped(Reason.NOT_FOUND, notFound))
            is Read.NotFetched -> keep(key, Resolution.Stopped(Reason.NOT_FETCHED, NOT_FETCHED, key))
            is Read.Failed -> keep(key, Resolution.Stopped(Reason.FAILED, file.reason))
        }

    private fun manifest(
        key: Target,
        manifest: ActionManifest,
        asOf: String?,
    ): Resolution {
        val inputs =
            manifest.inputs
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = "inputs: ") { if (it.required) "${it.name}*" else it.name }
        return when (val runs = manifest.runs) {
            is Runs.Composite -> {
                // A remote one by its reference: the step's box, which said it, is replaced.
                val title = if (key.repository == null) manifest.name ?: key.written else key.written
                Resolution.Composite(key, title, runs.steps, asOf)
            }

            is Runs.JavaScript -> {
                Resolution.Annotated(listOfNotNull("JS action (${runs.using})", inputs, asOf))
            }

            is Runs.Docker -> {
                Resolution.Annotated(listOfNotNull("Docker (${runs.image ?: "no image"})", inputs, asOf))
            }

            is Runs.Unknown -> {
                failed(runs.using?.let { "unknown runs.using: $it" } ?: "no runs.using")
            }
        }
    }

    private fun read(file: Target): Read {
        if (file !in read) {
            if (read.size >= MAX_FILES) return Read.Capped
            read += file
        }
        val repository = file.repository
        return try {
            if (repository == null) {
                sources.local(file.path)?.let { Read.Text(it, null) } ?: Read.Missing
            } else {
                remote(RemoteFile(repository.owner, repository.repo, repository.ref, file.path))
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            // A FileSystemException's message is the path, which the box shows already.
            val reason =
                if (e is FileSystemException) {
                    "unreadable (${e.javaClass.simpleName})"
                } else {
                    e.message?.let(::firstLine)?.takeIf { it.isNotBlank() } ?: "unreadable"
                }
            Read.Failed(reason)
        }
    }

    /** [file] from [remote], as a remote reference's box shows it. */
    private fun remote(file: RemoteFile): Read =
        when (val text = remote?.read(file) ?: RemoteText.NotFetched) {
            is RemoteText.Found -> {
                if (text.text.length > RepositoryFiles.MAX_BYTES) {
                    Read.Failed("larger than 1 MB")
                } else {
                    Read.Text(text.text, text.asOf?.let(::asOf))
                }
            }

            is RemoteText.Missing -> {
                Read.Missing
            }

            is RemoteText.NotFetched -> {
                Read.NotFetched
            }

            is RemoteText.Failed -> {
                Read.Failed(firstLine(text.reason).ifEmpty { "unreadable" })
            }
        }

    /** `as of 2026-10-20`, the date in [zone]. */
    private fun asOf(instant: Instant) = "as of ${DateTimeFormatter.ISO_LOCAL_DATE.format(instant.atZone(zone))}"

    private fun keep(
        key: Target,
        result: Resolution,
    ): Resolution = result.also { results[key] = it }

    private fun failed(reason: String) = Resolution.Stopped(Reason.FAILED, firstLine(reason))

    private sealed interface Read {
        /** [asOf]: `as of <date>` for a remote file fetched for a branch or tag. */
        data class Text(
            val text: String,
            val asOf: String?,
        ) : Read

        data object Missing : Read

        data object NotFetched : Read

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

        /** The line of a remote reference's box whose file is not in the cache. */
        const val NOT_FETCHED = "not fetched"

        /** An action's `./…` in a remote file: in the caller's workspace, whatever it checked out; not followed. */
        const val WORKSPACE = "workspace-relative"

        /** Not cached: nothing more is read anyway. */
        private val CAPPED = Resolution.Stopped(Reason.FILE_LIMIT, "not expanded (file limit)")

        private val WORKSPACE_RELATIVE = Resolution.Annotated(listOf(WORKSPACE))

        private const val MAX_REASON = 160

        /** The first line of [text], trimmed, without a closing `.` or `:`, at most [MAX_REASON] characters. */
        private fun firstLine(text: String): String {
            val line =
                text
                    .lineSequence()
                    .first()
                    .trim()
                    .trimEnd('.', ':')
            return if (line.length > MAX_REASON) line.take(MAX_REASON - 1) + "…" else line
        }

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
