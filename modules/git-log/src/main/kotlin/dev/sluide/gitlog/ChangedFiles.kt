package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitHunk
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * What the changed-files pane shows (ADR 0038), loaded together with the commit details ([CommitDetails]): the files
 * the selected commit changed against its first parent.
 */
internal sealed interface ChangedFilesState {
    data object NoneSelected : ChangedFilesState

    data class SeveralSelected(
        val count: Int,
    ) : ChangedFilesState

    /** The changed files of [row] are being read. */
    data class Loading(
        val row: GitLogRow,
    ) : ChangedFilesState

    data class Loaded(
        val row: GitLogRow,
        val files: List<GitChangedFile>,
    ) : ChangedFilesState

    data class Failed(
        val row: GitLogRow,
    ) : ChangedFilesState
}

/** A node of the changed-files tree: a folder, possibly several compacted into one ([ChangeFolder]), or a file. */
internal sealed interface ChangeNode {
    val name: String
}

/**
 * A folder at [path] (from the root); [name] is `main/kotlin` for a chain of folders that each have only the next one
 * in them.
 */
internal data class ChangeFolder(
    override val name: String,
    val path: String,
    val children: List<ChangeNode>,
) : ChangeNode

internal data class ChangeFile(
    override val name: String,
    val file: GitChangedFile,
) : ChangeNode

/**
 * The [files] as a tree by their (new) paths, as IntelliJ shows them: in every folder its folders first, then its
 * files, each by name; a chain of folders that each hold only the next folder is one node (`src/main/kotlin`).
 */
internal fun changeTree(files: List<GitChangedFile>): List<ChangeNode> {
    val root = MutableFolder()
    for (file in files) {
        val segments = file.path.split('/')
        var folder = root
        for (segment in segments.dropLast(1)) folder = folder.folders.getOrPut(segment, ::MutableFolder)
        folder.files += file
    }
    return root.nodes("")
}

private class MutableFolder {
    val folders = LinkedHashMap<String, MutableFolder>()
    val files = mutableListOf<GitChangedFile>()

    /** The nodes in this folder, which is at [path] (empty for the root). */
    fun nodes(path: String): List<ChangeNode> =
        folders.map { (name, folder) -> folder.compacted(name, path) }.sortedWith(BY_NAME) +
            files.map { ChangeFile(it.path.substringAfterLast('/'), it) }.sortedWith(BY_NAME)

    /**
     * This folder as a node named [name] in the folder at [parent], with the folders below it that hold only one
     * folder each joined in.
     */
    fun compacted(
        name: String,
        parent: String,
    ): ChangeFolder {
        var compacted = name
        var folder = this
        while (folder.files.isEmpty() && folder.folders.size == 1) {
            val (child, below) = folder.folders.entries.single()
            compacted = "$compacted/$child"
            folder = below
        }
        val path = if (parent.isEmpty()) compacted else "$parent/$compacted"
        return ChangeFolder(compacted, path, folder.nodes(path))
    }
}

private val BY_NAME = compareBy<ChangeNode, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }

/**
 * A file as a commit changed it, for the IDE to show side by side (ADR 0038): [original] is the file at the
 * commit's first [parent] (at [oldPath] if it was renamed or copied; empty if it was added), [current] at the
 * [commit] (empty if it was deleted), [hunks] the changed lines. [path] is relative to the repository at [root],
 * `/`-separated.
 */
data class CommitFileDiff(
    val root: Path,
    val path: String,
    val oldPath: String?,
    val change: GitChange,
    val commit: String,
    val parent: String?,
    val original: String,
    val current: String,
    val hunks: List<GitHunk>,
)

/**
 * Reads the diff of a changed file with [load] on [background] (ADR 0038) and hands it to the given handler through
 * [dispatch]; latest wins: the result of a request that a newer one (or [close]) superseded is dropped.
 */
internal class FileDiffs(
    private val load: (Path, String, String?, GitChangedFile) -> GitFileDiff?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    private val generation = AtomicLong()

    /** Reads what [commit] changed in [file] against its first parent in the repository at [root]. */
    fun request(
        root: Path,
        commit: GitCommit,
        file: GitChangedFile,
        handle: (GitFileDiff?) -> Unit,
    ) {
        val current = generation.incrementAndGet()
        background {
            if (generation.get() != current) return@background
            val diff = load(root, commit.hash, commit.parents.firstOrNull(), file)
            dispatch { if (generation.get() == current) handle(diff) }
        }
    }

    /** Drops the results of requests still going. */
    fun close() {
        generation.incrementAndGet()
    }
}
