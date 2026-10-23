package dev.sluide.gitlog.operations

import dev.sluide.git.GitRef
import dev.sluide.git.GitRemoteDeletion
import dev.sluide.git.GitRemoteDeletionOutcome
import dev.sluide.git.GitRemoteDeletionResult
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.GitLogWrites
import java.nio.file.Path

/**
 * Deletes the local [branches] and the [tags] (short names); branches that are not fully merged only with [force]. The
 * branches git refuses to delete are handed back, to be deleted with force once confirmed. The tags are deleted on the
 * [remotes] first (ADR 0078), one remote after the other; if that fails there or is cancelled, nothing local is deleted,
 * so the tags stay to try again. Cancellable while it deletes on remotes.
 */
internal data class DeleteRefs(
    val branches: List<String>,
    val tags: List<String>,
    val force: Boolean = false,
    val remotes: List<String> = emptyList(),
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
    override val cancellable get() = tags.isNotEmpty() && remotes.isNotEmpty()

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        val onRemotes = if (tags.isEmpty()) emptyList() else remotes
        // By remote, the tags deleted there, to tell when a later remote fails.
        val deletedOn = LinkedHashMap<String, List<String>>()
        for (remote in onRemotes) {
            if (context.cancellation.cancelled) return remoteTagsCancelled(deletedOn, null)
            context.progress(
                "Deleting ${tags.singleOrNull()?.let { "tag $it" } ?: plural(tags.size, "tag")} on $remote…",
            )
            val deletions = tags.map { GitRemoteDeletion("$TAG_REFS$it") }
            val deleted = writes.deleteRemoteRefs(root, remote, deletions, context.cancellation)
            if (interrupted(deleted, context)) return remoteTagsCancelled(deletedOn, remote)
            if (!deleted.result.success) {
                val partly =
                    tags.filter {
                        deleted.outcomes["$TAG_REFS$it"]?.outcome == GitRemoteDeletionOutcome.DELETED
                    }
                if (partly.isNotEmpty()) deletedOn[remote] = partly
                val reasons =
                    tags.filter { it !in partly }.map { tag ->
                        "$tag: ${deleted.outcomes["$TAG_REFS$tag"]?.summary?.ifEmpty { null } ?: "not deleted"}"
                    }
                val message =
                    (reasons + deletedBefore(deletedOn) + listOf("", deleted.result.message)).joinToString("\n")
                val what = tags.singleOrNull()?.let { "Tag $it" } ?: "The tags"
                val failure = "$what could not be deleted on $remote; nothing was deleted locally."
                return RefOutcome(GitResult(false, message, changed = false), null, failure = failure)
            }
            deletedOn[remote] = tags
        }
        val (local, deletedBranches) = deleteLocally(writes, root)
        if (deletedOn.isEmpty()) return local
        val where = joinedWithAnd(deletedOn.keys)
        val done =
            when {
                local.done == null -> {
                    "Deleted ${tags.singleOrNull()?.let { "tag $it" } ?: "the tags"} on $where."
                }

                deletedBranches == 0 -> {
                    "${local.done} Also deleted on $where."
                }

                else -> {
                    val which = tags.singleOrNull()?.let { "tag $it" } ?: "the tags"
                    "${local.done.removeSuffix(".")}, $which also on $where."
                }
            }
        return local.copy(done = done)
    }

    /**
     * What the header says when deleting the tags was cancelled: what the remotes before deleted ([deletedOn]), and
     * that the push to [interrupted] may have reached it, or, before a push started, only that it was cancelled.
     */
    private fun remoteTagsCancelled(
        deletedOn: Map<String, List<String>>,
        interrupted: String?,
    ): RefOutcome {
        val done = (deletedBefore(deletedOn) + cancelledText(interrupted)).joinToString(" ")
        return RefOutcome(GitResult(true, "", changed = false), done, cancelled = true)
    }

    /** Deletes the local branches and tags; the outcome and how many branches were deleted. */
    private fun deleteLocally(
        writes: GitLogWrites,
        root: Path,
    ): Pair<RefOutcome, Int> {
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
        return RefOutcome(result, done, refused, deletion?.result?.message.orEmpty()) to
            if (failures.isEmpty()) deletedBranches else 0
    }
}

/** A remote branch to delete on its [remote] (ADR 0078): the remote-tracking [ref], `refs/remotes/origin/fix`. */
internal data class RemoteBranch(
    val remote: String,
    val ref: GitRef,
) {
    /**
     * The branch's name on the remote as its short name says it, after the remote (`fix` for `origin/fix`); the fetch
     * refspecs may map another one, which only git tells ([GitLogWrites.remoteBranch]).
     */
    val nameOnRemote: String get() = ref.shortName.removePrefix("$remote/")
}

/**
 * Deletes the remote [branches] on their remotes (ADR 0078), one push per remote, each only if the remote still has it
 * where the last fetch saw it (the lease). The branch on the remote is the one the fetch refspecs map to the
 * remote-tracking ref; one that none maps is not deleted. A protected branch ([isProtectedBranch]), by its short name
 * or as the refspecs map it, is never deleted: then nothing is, and git does not run for the push. The local branches
 * of the same name stay. The header names what was deleted and which branches changed or are gone on the remote;
 * refusals of the remote are shown in the error dialog, which lists every branch that was not deleted with the reason.
 * Cancellable, as a push: cancelled, the remotes after it are skipped, and the header says the deletion may have
 * reached the remote all the same.
 */
internal data class DeleteRemoteBranches(
    val branches: List<RemoteBranch>,
) : RefOperation {
    override val progress
        get() =
            branches.singleOrNull()?.let { "Deleting remote branch ${it.ref.shortName}…" }
                ?: "Deleting ${branches.size} remote branches…"
    override val errorTitle get() = "Delete Failed"
    override val failure
        get() =
            branches.singleOrNull()?.let { "${it.ref.shortName} could not be deleted." }
                ?: "Not every remote branch could be deleted."
    override val fileChange get() = null
    override val cancellable get() = true

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        val deleted = mutableListOf<String>()
        // What the header says about branches that were not deleted, and what the error dialog lists.
        val notes = mutableListOf<String>()
        val notDeleted = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var failed = false
        var cancelled = false
        // The remote whose push a cancel interrupted; `null` if remotes were skipped before their push started.
        var interruptedOn: String? = null
        protectedRefusal(branches.filter { isProtectedBranch(it.nameOnRemote) })?.let { return it }
        val byRemote = LinkedHashMap<String, MutableList<Pair<RemoteBranch, String>>>()
        for (branch in branches) {
            val target = writes.remoteBranch(root, branch.remote, branch.ref.name)
            if (target == null) {
                failed = true
                notDeleted += "${branch.ref.shortName}: no fetch refspec of ${branch.remote} maps a branch to it, " +
                    "so the branch on ${branch.remote} is not known."
            } else {
                byRemote.getOrPut(branch.remote) { mutableListOf() } += branch to target
            }
        }
        // Checked again as the refspecs map it, before git runs: the Delete item only knew the short name.
        val mappedProtected =
            byRemote.values.flatten().filter { (_, target) -> isProtectedBranch(target.removePrefix("refs/heads/")) }
        protectedRefusal(mappedProtected.map { it.first })?.let { return it }
        for ((remote, targets) in byRemote) {
            if (context.cancellation.cancelled) {
                cancelled = true
                break
            }
            if (byRemote.size > 1) context.progress("Deleting remote branches on $remote…")
            val deletions = targets.map { (branch, target) -> GitRemoteDeletion(target, branch.ref.commit) }
            val result = writes.deleteRemoteRefs(root, remote, deletions, context.cancellation)
            if (interrupted(result, context)) {
                cancelled = true
                interruptedOn = remote
                break
            }
            var refused = false
            for ((branch, target) in targets) {
                val name = branch.ref.shortName
                val outcome = result.outcomes[target]
                val note =
                    when (outcome?.outcome) {
                        GitRemoteDeletionOutcome.DELETED -> {
                            deleted += name
                            null
                        }

                        GitRemoteDeletionOutcome.STALE -> {
                            "$name changed since your last fetch. Fetch and check it before deleting."
                        }

                        GitRemoteDeletionOutcome.MISSING -> {
                            "$name no longer exists on $remote. Fetch to update."
                        }

                        GitRemoteDeletionOutcome.STALE_OR_MISSING -> {
                            "$name changed or was deleted since your last fetch. Fetch and check it before deleting."
                        }

                        GitRemoteDeletionOutcome.REJECTED, null -> {
                            refused = true
                            notDeleted += "$name: ${outcome?.summary?.ifEmpty { null } ?: "not deleted"}"
                            null
                        }
                    }
                if (note != null) {
                    notes += note
                    notDeleted += "$name: ${note.substringAfter("$name ")}"
                }
            }
            if (refused) {
                failed = true
                messages += if (byRemote.size > 1) "$remote:\n${result.result.message}" else result.result.message
            }
        }
        val summary =
            when (deleted.size) {
                0 -> null
                1 -> "Deleted remote branch ${deleted.single()}."
                else -> "Deleted ${deleted.size} remote branches."
            }
        val done =
            (listOfNotNull(summary) + notes + listOfNotNull(cancelledText(interruptedOn).takeIf { cancelled }))
                .joinToString(" ")
                .ifEmpty { null }
        val result =
            if (failed) {
                GitResult(false, (notDeleted + messages.map { "\n$it" }).joinToString("\n"), changed = false)
            } else {
                GitResult(true, "")
            }
        return RefOutcome(result, done, cancelled = cancelled)
    }
}

/**
 * The outcome that refuses to delete the [protected] remote branches ([isProtectedBranch], as Force Push does), none of
 * the selected ones then, without running git; `null` if there are none.
 */
private fun protectedRefusal(protected: List<RemoteBranch>): RefOutcome? {
    if (protected.isEmpty()) return null
    val message = protected.joinToString("\n") { "Deleting ${it.ref.shortName} is not allowed (protected branch)." }
    val failure =
        protected.singleOrNull()?.let { "${it.ref.shortName} is protected; nothing was deleted." }
            ?: "No remote branch was deleted."
    return RefOutcome(GitResult(false, message, changed = false), null, failure = failure)
}

/**
 * What the header says when a deletion was cancelled: if the push to the remote [interrupted] was stopped, the remote
 * may have deleted the refs already, so the remote-tracking refs may be off until a fetch; without one (the remotes left
 * were skipped before their push started), only that it was cancelled.
 */
internal fun cancelledText(interrupted: String?) =
    interrupted?.let { "Delete cancelled; it may have reached $it. Fetch to check." } ?: "Delete cancelled."

/**
 * Whether a cancel interrupted the push that gave [result]: git was stopped, or it failed without reporting on any
 * ref once cancelled. A push that reported on its refs ran to its end, also when a cancel stopped `ls-remote` after it.
 */
private fun interrupted(
    result: GitRemoteDeletionResult,
    context: RunContext,
) = result.cancelled || (!result.result.success && result.outcomes.isEmpty() && context.cancellation.cancelled)

/** "Deleted on origin before that: v1, v2." for each remote of [deletedOn]. */
private fun deletedBefore(deletedOn: Map<String, List<String>>): List<String> =
    deletedOn.map { (remote, tags) -> "Deleted on $remote before that: ${tags.joinToString(", ")}." }

/** "origin", "origin and upstream", "origin, upstream and fork". */
internal fun joinedWithAnd(names: Collection<String>): String =
    when (names.size) {
        0, 1 -> names.joinToString()
        else -> names.toList().dropLast(1).joinToString(", ") + " and " + names.last()
    }

/**
 * The question before deleting the remote [branches] on their remotes: "Delete remote branch 'origin/fix'?" or
 * "Delete 3 remote branches?".
 */
internal fun deleteRemoteQuestion(branches: List<RemoteBranch>): String =
    branches.singleOrNull()?.let { "Delete remote branch '${it.ref.shortName}'?" }
        ?: "Delete ${branches.size} remote branches?"

private const val TAG_REFS = "refs/tags/"

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
