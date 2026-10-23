package dev.sluide.gitlog.operations

import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.GitLogWrites
import java.nio.file.Path

/**
 * Deletes the local [branches] and the [tags] (short names); branches that are not fully merged only with [force]. The
 * branches git refuses to delete are handed back, to be deleted with force once confirmed.
 */
internal data class DeleteRefs(
    val branches: List<String>,
    val tags: List<String>,
    val force: Boolean = false,
) : RefOperation {
    override val progress
        get() =
            when {
                branches.size == 1 && tags.isEmpty() -> "Deleting branch ${branches.single()}…"
                tags.size == 1 && branches.isEmpty() -> "Deleting tag ${tags.single()}…"
                else -> "Deleting ${refCount(branches.size, tags.size)}…"
            }
    override val errorTitle get() = "Delete Failed"
    override val failure get() = "Not everything could be deleted."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val deletion = if (branches.isEmpty()) null else writes.deleteBranches(root, branches, force)
        val tagResult = if (tags.isEmpty()) null else writes.deleteTags(root, tags)
        // A branch git refuses without force is asked about; other failures are shown.
        val refused = if (force) emptyList() else deletion?.notDeleted.orEmpty()
        val branchFailed = deletion != null && !deletion.result.success && refused.isEmpty()
        val failures = listOfNotNull(deletion?.result?.takeIf { branchFailed }, tagResult?.takeIf { !it.success })
        val deletedBranches = if (deletion?.result?.success == true) branches.size else branches.size - refused.size
        val deletedTags = if (tagResult?.success == true) tags.size else 0
        // Whatever git did when it failed is not known: only what surely happened is told.
        val done =
            if (failures.isNotEmpty() || deletedBranches + deletedTags == 0) {
                null
            } else if (deletedBranches + deletedTags == 1) {
                if (deletedBranches == 1) {
                    "Deleted branch ${branches.single { it !in refused }}."
                } else {
                    "Deleted tag ${tags.single()}."
                }
            } else {
                "Deleted ${deletedBranches + deletedTags} refs."
            }
        val result =
            if (failures.isEmpty()) {
                GitResult(true, "")
            } else {
                GitResult(false, failures.joinToString("\n") { it.message })
            }
        return RefOutcome(result, done, refused, deletion?.result?.message.orEmpty())
    }
}

/** "branch x" / "tag v1" for one, else "2 branches and 1 tag", "3 refs". */
private fun refCount(
    branches: Int,
    tags: Int,
) = when {
    branches + tags > 1 && branches > 0 && tags > 0 -> "${plural(branches, "branch")} and ${plural(tags, "tag")}"
    branches > 1 -> plural(branches, "branch")
    tags > 1 -> plural(tags, "tag")
    else -> if (branches == 1) "branch" else "tag"
}

private fun plural(
    count: Int,
    noun: String,
) = if (count == 1) "1 $noun" else "$count ${if (noun == "branch") "branches" else "${noun}s"}"

/**
 * The question before deleting the local [branches] and [tags] (short names): "Delete branch 'x'?", "Delete tag 'v1'?",
 * "Delete 2 branches?" or "Delete 2 branches and 1 tag?".
 */
internal fun deleteQuestion(
    branches: List<String>,
    tags: List<String>,
): String =
    when {
        branches.size == 1 && tags.isEmpty() -> "Delete branch '${branches.single()}'?"
        tags.size == 1 && branches.isEmpty() -> "Delete tag '${tags.single()}'?"
        else -> "Delete ${refCount(branches.size, tags.size)}?"
    }

/** The question before deleting the [branches] git refused to delete without force. */
internal fun forceDeleteQuestion(branches: List<String>): String =
    if (branches.size == 1) {
        "Force delete branch '${branches.single()}'?"
    } else {
        "Force delete ${branches.size} branches?"
    }
