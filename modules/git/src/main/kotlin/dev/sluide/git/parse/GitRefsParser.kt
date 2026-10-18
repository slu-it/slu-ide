package dev.sluide.git.parse

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs

/**
 * Reads the output of `git for-each-ref --format=%(objectname)%1f%(*objectname)%1f%(refname)%1f%(symref)`: one record
 * per line (refnames cannot contain control characters), its fields separated by U+001F. Symbolic refs are skipped,
 * and so are records that do not have these fields or that are not below `refs/heads/`, `refs/remotes/` or
 * `refs/tags/`.
 */
internal object GitRefsParser {
    private val HASH = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

    /**
     * The refs in [output], plus `HEAD` at [headCommit] if there is one (not in a repository without commits);
     * [currentBranch] is the full refname `HEAD` is attached to, `null` (or empty) when it is detached.
     */
    fun parse(
        output: String,
        headCommit: String?,
        currentBranch: String?,
    ): GitRefs {
        val refs =
            output.lineSequence().mapNotNull { record ->
                val fields = record.split('\u001f')
                if (fields.size != 4) return@mapNotNull null
                val (objectName, peeled, name, symref) = fields
                if (symref.isNotEmpty()) return@mapNotNull null
                val kind =
                    when {
                        name.startsWith("refs/heads/") -> GitRefKind.LOCAL_BRANCH
                        name.startsWith("refs/remotes/") -> GitRefKind.REMOTE_BRANCH
                        name.startsWith("refs/tags/") -> GitRefKind.TAG
                        else -> return@mapNotNull null
                    }
                // An annotated tag is an object of its own; the commit is the one it points at.
                val commit = peeled.ifEmpty { objectName }
                if (!HASH.matches(commit)) return@mapNotNull null
                GitRef(name, kind, commit)
            }
        val head = headCommit?.takeIf(HASH::matches)?.let { GitRef("HEAD", GitRefKind.HEAD, it) }
        return GitRefs(listOfNotNull(head) + refs, currentBranch?.ifEmpty { null })
    }
}
