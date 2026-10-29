package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.UnmergedEntry
import dev.sluide.git.cli.attributes
import dev.sluide.git.cli.blob
import dev.sluide.git.cli.missingFiles
import dev.sluide.git.cli.objectSize
import dev.sluide.git.cli.storeFiles
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Checks the conflicted files before git continues what stopped at them (ADR 0042), and stages them: shared by the
 * cherry-pick, revert and rebase continue. [canNameFile] tells whether the JVM can name the working-tree file at a path
 * (ADR 0038).
 */
internal class GitConflictCheck(
    private val git: GitCli,
    private val canNameFile: (String) -> Boolean,
) {
    /** Git failed while a conflict was sorted. */
    private class ReadFailed : RuntimeException()

    /**
     * Sorts the conflicted files of the unmerged [entries] (ADR 0042): the text conflicts present on disk are checked
     * for lines starting with a conflict marker as long as their `conflict-marker-size` (7 by default); the others are
     * unchecked, with the reason. A file is read directly if the JVM can name it and it is a regular file (a symbolic
     * link in its place is unchecked, a folder or special file refused, neither is followed or read), a larger one than
     * [MAX_CHECKED_SIZE] is not read but unchecked; one it cannot name (non-ASCII in a POSIX locale) is stored with
     * `git hash-object` first, its path on git's standard input, and read from there. `null` if git or reading fails.
     */
    fun check(
        root: Path,
        entries: List<UnmergedEntry>,
    ): ConflictCheck? {
        val byPath = entries.groupBy { it.path }
        val missing = git.missingFiles(root) ?: return null
        val attributes = git.attributes(root, byPath.keys.toList(), listOf("merge", MARKER_SIZE)) ?: return null
        val unchecked = mutableListOf<GitUncheckedConflict>()
        val notCheckedOut = mutableListOf<String>()
        val notFiles = mutableListOf<String>()
        val text = mutableListOf<String>()
        for ((path, stages) in byPath) {
            val reason =
                try {
                    reason(root, stages, path in missing, attributes[path].orEmpty()["merge"])
                } catch (_: ReadFailed) {
                    return null
                }
            when {
                reason == null -> text += path
                reason == GitConflictReason.SUBMODULE && !checkedOut(root, path) -> notCheckedOut += path
                else -> unchecked += GitUncheckedConflict(path, reason)
            }
        }

        fun markerSize(path: String) = attributes[path].orEmpty()[MARKER_SIZE]?.toIntOrNull()?.takeIf { it > 0 } ?: 7
        val marked = mutableSetOf<String>()
        val unnamed = mutableListOf<String>()
        for (path in text) {
            val file = localFile(root, path, canNameFile)
            if (file == null) {
                unnamed += path
                continue
            }
            try {
                if (Files.isSymbolicLink(file)) {
                    unchecked += GitUncheckedConflict(path, GitConflictReason.SYMLINK)
                } else if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    // Reading a FIFO would block for good.
                    notFiles += path
                } else if (Files.size(file) > MAX_CHECKED_SIZE) {
                    unchecked += GitUncheckedConflict(path, GitConflictReason.TOO_LARGE)
                } else if (hasConflictMarker(Files.readAllBytes(file), markerSize(path))) {
                    marked += path
                }
            } catch (_: IOException) {
                return null
            }
        }
        // Their size cannot be read before they are stored.
        val stored = git.storeFiles(root, unnamed) ?: return null
        for ((path, name) in unnamed.zip(stored)) {
            val size = git.objectSize(root, name) ?: return null
            if (size > MAX_CHECKED_SIZE) {
                unchecked += GitUncheckedConflict(path, GitConflictReason.TOO_LARGE)
            } else if (hasConflictMarker(git.blob(root, name) ?: return null, markerSize(path))) {
                marked += path
            }
        }
        return ConflictCheck(text.filter { it in marked }, unchecked, notCheckedOut, notFiles)
    }

    /**
     * Stages exactly the paths of the unmerged [entries] as they are on disk: `git update-index -z --add --remove
     * --stdin`, the paths on its standard input, so a deleted file is staged as deleted and nothing else is staged.
     */
    fun stage(
        root: Path,
        entries: List<UnmergedEntry>,
    ): GitResult {
        val input =
            entries
                .map { it.path }
                .distinct()
                .joinToString("") { "$it\u0000" }
                .toByteArray(Charsets.UTF_8)
        return git.write(
            root,
            listOf("update-index", "-z", "--add", "--remove", "--stdin"),
            input,
            GitCli.CHECKOUT_TIMEOUT,
        )
    }

    /**
     * Whether the submodule at [path] is checked out (it has a `.git`), so that `git update-index` can stage its
     * commit; also `true` if the JVM cannot name it (git then tells).
     */
    private fun checkedOut(
        root: Path,
        path: String,
    ): Boolean =
        localFile(root, path, canNameFile)?.let { Files.exists(it.resolve(".git"), LinkOption.NOFOLLOW_LINKS) } ?: true

    /**
     * Why the conflicted file of these unmerged [stages] cannot be checked for conflict markers; `null` for a text
     * conflict on disk ([missing] tells it is not, also for a submodule's folder). [merge] is its `merge` attribute.
     * Throws [ReadFailed] if git fails.
     */
    private fun reason(
        root: Path,
        stages: List<UnmergedEntry>,
        missing: Boolean,
        merge: String?,
    ): GitConflictReason? {
        val ours = stages.firstOrNull { it.stage == 2 }
        val theirs = stages.firstOrNull { it.stage == 3 }
        return when {
            // Before anything else: whatever it was, its deletion is committed.
            missing -> {
                GitConflictReason.DELETED_ON_DISK
            }

            stages.any { it.mode == GITLINK } -> {
                GitConflictReason.SUBMODULE
            }

            stages.any { it.mode == SYMLINK } -> {
                GitConflictReason.SYMLINK
            }

            ours == null || theirs == null -> {
                GitConflictReason.DELETED_ON_ONE_SIDE
            }

            merge == "unset" || merge == "binary" -> {
                GitConflictReason.BINARY
            }

            else -> {
                val sizes = listOf(ours, theirs).map { git.objectSize(root, it.objectName) ?: throw ReadFailed() }
                if (sizes.any { it > MAX_CHECKED_SIZE }) return GitConflictReason.TOO_LARGE
                val binary = listOf(ours, theirs).any { isBinary(git.blob(root, it.objectName) ?: throw ReadFailed()) }
                if (binary) GitConflictReason.BINARY else null
            }
        }
    }

    companion object {
        /** The mode of a submodule (gitlink) entry. */
        const val GITLINK = "160000"
        const val SYMLINK = "120000"
        const val MARKER_SIZE = "conflict-marker-size"

        /** The largest file read to check it, as the git log reads files for diffs. */
        const val MAX_CHECKED_SIZE = 5L * 1024 * 1024

        /** Git's test for binary content: a NUL byte among the first 8000 bytes. */
        const val BINARY_SAMPLE = 8000

        fun isBinary(bytes: ByteArray): Boolean = bytes.take(BINARY_SAMPLE).contains(0)
    }
}

/**
 * The conflicted files with markers left, those that cannot be checked, and what `git update-index` cannot stage:
 * the submodules that are not checked out (an empty folder), and folders or special files (a FIFO) in place of a
 * text conflict.
 */
internal class ConflictCheck(
    val markers: List<String>,
    val unchecked: List<GitUncheckedConflict>,
    val notCheckedOut: List<String>,
    val notFiles: List<String>,
) {
    /** Why the files cannot be staged ([notCheckedOut], [notFiles]), a line each; `null` if they can. */
    val refusal: String?
        get() =
            listOfNotNull(
                notCheckedOut.takeIf { it.isNotEmpty() }?.let(::notCheckedOut),
                notFiles.takeIf { it.isNotEmpty() }?.let(::notFiles),
            ).takeIf { it.isNotEmpty() }?.joinToString("\n")

    private fun notFiles(paths: List<String>): String {
        val names = paths.joinToString(", ")
        val which = if (paths.size == 1) "$names is not a regular file" else "$names are not regular files"
        return "$which (a folder or a special file), which git cannot stage. Resolve it in the terminal."
    }

    private fun notCheckedOut(paths: List<String>): String {
        val names = paths.joinToString(", ")
        val which = if (paths.size == 1) "Submodule $names is" else "Submodules $names are"
        return "$which not checked out: the commit to keep cannot be taken from disk. Resolve it in the terminal."
    }
}

/** The file [path] (relative to [root]) as the JVM can name it ([canNameFile]); `null` if it cannot in its locale. */
internal fun localFile(
    root: Path,
    path: String,
    canNameFile: (String) -> Boolean,
): Path? =
    try {
        if (canNameFile(path)) root.resolve(path) else null
    } catch (_: InvalidPathException) {
        null
    }

/**
 * Whether [content] has a line starting with a conflict marker of [size] characters, `<` or `>` (not `=`, too common
 * alone), followed by a space or the line's end: the lines git writes around a conflict. Read as bytes, not decoded.
 */
internal fun hasConflictMarker(
    content: ByteArray,
    size: Int,
): Boolean {
    var start = 0
    while (start < content.size) {
        val end = (start until content.size).firstOrNull { content[it] == '\n'.code.toByte() } ?: content.size
        val first = content[start]
        if ((first == '<'.code.toByte() || first == '>'.code.toByte()) && end - start >= size) {
            val run = (start until start + size).all { content[it] == first }
            val after = if (start + size < end) content[start + size] else '\n'.code.toByte()
            if (run && (after == ' '.code.toByte() || after == '\r'.code.toByte() || after == '\n'.code.toByte())) {
                return true
            }
        }
        start = end + 1
    }
    return false
}

/** The length of hashes in messages, as in the git log. */
private const val SHORT_HASH = 8

private const val SEQUENCE_MESSAGE = "A %s sequence is in progress; continue or abort it in the terminal."

/** Why nothing was done: what git is in the middle of could not be read. */
internal const val IN_PROGRESS_UNREADABLE =
    "Whether a merge, rebase, cherry-pick or revert is in progress could not be read."

/** Why nothing was done: the unmerged paths could not be read or checked. */
internal const val CONFLICTS_UNREADABLE = "The conflicted files could not be read."

/** "1 file has conflicts", "2 files have conflicts". */
internal fun conflictCount(count: Int): String =
    if (count ==
        1
    ) {
        "1 file has conflicts"
    } else {
        "$count files have conflicts"
    }

/** Why nothing is started while git is in the middle of [busy]. */
internal fun busyMessage(busy: GitInProgress): String =
    when (busy) {
        is GitPendingPick -> {
            "A ${busy.kind.command} of ${busy.commit.take(SHORT_HASH)} is in progress; continue or abort it first."
        }

        is GitPendingRebase -> {
            "A rebase is in progress; finish or abort it first."
        }

        is GitPendingMerge -> {
            "A merge is in progress; finish or abort it first."
        }

        is GitPendingConflicts -> {
            val them = if (busy.conflicts.size == 1) "it" else "them"
            "${conflictCount(busy.conflicts.size)}; resolve $them and mark $them resolved first."
        }

        is GitPendingOperation -> {
            when (busy.operation) {
                GitOperation.AM -> "A git am session is in progress; finish or abort it first."
                GitOperation.CHERRY_PICK_SEQUENCE -> SEQUENCE_MESSAGE.format("cherry-pick")
                GitOperation.REVERT_SEQUENCE -> SEQUENCE_MESSAGE.format("revert")
                GitOperation.SEQUENCE -> SEQUENCE_MESSAGE.format("cherry-pick or revert")
            }
        }
    }
