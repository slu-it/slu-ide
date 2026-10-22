package dev.sluide.githubactions.model

/** Where a `uses:` is written: in a step (an action) or in a job (a reusable workflow). */
internal enum class UsesSite { STEP, JOB }

/**
 * What a `uses:` refers to, read from its text alone; nothing is looked up. Paths are kept as written, not
 * normalised: they may contain `..` (`./../x`, `o/r/../x@v1`), so resolving them later must confine them to their
 * repository.
 */
internal sealed interface UsesReference {
    /** `./path/to/action` in the same repository, [path] as written. */
    data class LocalAction(
        val path: String,
    ) : UsesReference

    /** `owner/repo@ref` or `owner/repo/sub/path@ref`. */
    data class RemoteAction(
        val owner: String,
        val repo: String,
        val path: String?,
        val ref: String,
    ) : UsesReference

    /** `docker://image`, [image] without the scheme. */
    data class DockerImage(
        val image: String,
    ) : UsesReference

    /** `./.github/workflows/x.yml` in the same repository, [path] as written. */
    data class LocalWorkflow(
        val path: String,
    ) : UsesReference

    /** `owner/repo/.github/workflows/x.yml@ref`. */
    data class RemoteWorkflow(
        val owner: String,
        val repo: String,
        val path: String,
        val ref: String,
    ) : UsesReference

    /** Anything else: expressions, typos, a form that does not fit the [UsesSite]. */
    data class Unparsed(
        val text: String,
    ) : UsesReference

    companion object {
        fun parse(
            text: String,
            site: UsesSite,
        ): UsesReference {
            val trimmed = text.trim()
            if ("\${{" in trimmed || trimmed.any { it.isWhitespace() }) return Unparsed(text)
            return when (site) {
                UsesSite.STEP -> action(trimmed)
                UsesSite.JOB -> workflow(trimmed)
            } ?: Unparsed(text)
        }

        private fun action(text: String): UsesReference? =
            when {
                text.startsWith(DOCKER) -> text.removePrefix(DOCKER).takeIf { it.isNotEmpty() }?.let(::DockerImage)
                text.startsWith("./") -> LocalAction(text)
                else -> remote(text)?.let { (owner, repo, path, ref) -> RemoteAction(owner, repo, path, ref) }
            }

        /**
         * GitHub's rule: a reusable workflow is a file directly in `.github/workflows`, a local one without a ref.
         * Anything else in a job (an action-like `owner/repo/path@ref`, a file elsewhere) cannot be called there.
         */
        private fun workflow(text: String): UsesReference? =
            if (text.startsWith("./")) {
                text.takeIf { WORKFLOW_FILE.matches(it.removePrefix("./")) }?.let(::LocalWorkflow)
            } else {
                remote(text)?.let { (owner, repo, path, ref) ->
                    path?.takeIf(WORKFLOW_FILE::matches)?.let { RemoteWorkflow(owner, repo, it, ref) }
                }
            }

        /** `owner/repo[/path]@ref`; the ref may hold slashes (`@releases/v1`), so the text is split at the first `@`. */
        private fun remote(text: String): Remote? {
            val at = text.indexOf('@')
            if (at < 0) return null
            val ref = text.substring(at + 1)
            val segments = text.substring(0, at).split('/')
            if (ref.isEmpty() || segments.size < 2 || segments.any { it.isEmpty() }) return null
            val path = if (segments.size > 2) segments.drop(2).joinToString("/") else null
            return Remote(segments[0], segments[1], path, ref)
        }

        private data class Remote(
            val owner: String,
            val repo: String,
            val path: String?,
            val ref: String,
        )

        private const val DOCKER = "docker://"
        private val WORKFLOW_FILE = Regex("""\.github/workflows/[^/@]+\.ya?ml""")
    }
}
