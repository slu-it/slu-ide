package dev.sluide.git

import java.nio.file.Path
import java.util.EnumSet

/**
 * The git status of every changed, untracked and ignored path in the working tree at one moment. Paths are absolute;
 * paths outside [root] and clean files have no status.
 */
class GitStatusSnapshot internal constructor(
    /** The repository root, or `null` for [EMPTY]. */
    val root: Path?,
    /** Statuses by path relative to [root], as [GitStatusParser] returns them. */
    private val entries: Map<String, GitStatus>,
    /** The checked-out branch or commit, or `null` if unknown (no repository, or [EMPTY]). */
    val head: GitHead? = null,
) {
    private val files = HashMap<Path, GitStatus>()

    /** Untracked and ignored folders that git reports as a whole. */
    private val folders = HashMap<Path, GitStatus>()

    /** For every folder, the statuses found below it, ignored ones excepted. */
    private val below = HashMap<Path, EnumSet<GitStatus>>()

    init {
        if (root != null) {
            for ((relative, status) in entries) {
                val path = root.resolve(relative.removeSuffix("/")).normalize()
                if (relative.endsWith("/")) folders[path] = status else files[path] = status
                if (status == GitStatus.IGNORED) continue
                var parent = path.parent
                while (parent != null && parent.startsWith(root)) {
                    below.getOrPut(parent) { EnumSet.noneOf(GitStatus::class.java) } += status
                    parent = parent.parent
                }
            }
        }
    }

    /** The status of the file at [path]; inside an untracked or ignored folder, that folder's. `null`: clean. */
    fun fileStatus(path: Path): GitStatus? = files[path] ?: folderEntry(path.parent)

    /**
     * The statuses of the folder at [path]: [GitStatus.IGNORED] alone if it is ignored or inside an ignored folder,
     * [GitStatus.UNVERSIONED] alone if it is untracked, otherwise every status found below it except ignored ones.
     * Iterates in [GitStatus] order.
     */
    fun folderStatuses(path: Path): Set<GitStatus> {
        folderEntry(path)?.let { return EnumSet.of(it) }
        return below[path] ?: emptySet()
    }

    /** The status of [path] or its nearest ancestor that git reports as a whole folder. */
    private fun folderEntry(path: Path?): GitStatus? {
        val root = root ?: return null
        var folder = path
        while (folder != null && folder.startsWith(root)) {
            folders[folder]?.let { return it }
            folder = folder.parent
        }
        return null
    }

    override fun equals(other: Any?) =
        other is GitStatusSnapshot && root == other.root && entries == other.entries && head == other.head

    override fun hashCode() = (31 * root.hashCode() + entries.hashCode()) * 31 + head.hashCode()

    override fun toString() = "GitStatusSnapshot($root, $head, $entries)"

    companion object {
        /** No repository, or git is not available. */
        val EMPTY = GitStatusSnapshot(null, emptyMap())

        /** Parses `git status --porcelain=v2 --branch -z --ignored` [output] run in [root]. */
        internal fun parse(
            root: Path,
            output: String,
        ) = GitStatusSnapshot(root, GitStatusParser.parse(output), GitStatusParser.parseHead(output))
    }
}
