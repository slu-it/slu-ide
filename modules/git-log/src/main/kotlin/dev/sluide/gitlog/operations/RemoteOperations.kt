package dev.sluide.gitlog.operations

import dev.sluide.git.GitRemoteRemoval
import dev.sluide.git.GitRemoteUrls
import dev.sluide.git.GitResult
import dev.sluide.git.maskedUrl
import dev.sluide.gitlog.data.GitLogWrites
import java.nio.file.Path

/** What the Change URL dialog is asked for: the [urls] stored for the remote [name]; show them only masked. */
internal data class RemoteUrlsPrompt(
    val name: String,
    val urls: GitRemoteUrls,
) {
    /** The URLs masked, as [GitRemoteUrls] prints them. */
    override fun toString() = "RemoteUrlsPrompt(name=$name, urls=$urls)"
}

/**
 * Adds the remote [name] with [url] and, if [fetch], fetches it like [Fetch] does, which is the part that can be
 * cancelled (ADR 0183). A failed fetch leaves the remote added: the header says so and the error dialog is the fetch's.
 * Neither [url] nor git's messages (masked by the git module) are put into any text.
 */
internal data class AddRemote(
    val name: String,
    val url: String,
    val fetch: Boolean,
) : RefOperation {
    override val progress get() = "Adding remote $name…"
    override val errorTitle get() = "Add Remote Failed"
    override val failure get() = "Remote $name could not be added."
    override val fileChange get() = null
    override val cancellable get() = fetch

    /** Without the URL, which may hold credentials. */
    override fun toString() = "AddRemote(name=$name, url=${maskedUrl(url)}, fetch=$fetch)"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ) = run(writes, root, RunContext())

    override fun run(
        writes: GitLogWrites,
        root: Path,
        context: RunContext,
    ): RefOutcome {
        val added = writes.addRemote(root, name, url)
        if (!added.success) return RefOutcome(added, null)
        val done = "Added remote $name."
        if (!fetch) return RefOutcome(added, done)
        val fetched = Fetch(name).run(writes, root, context)
        val summary = fetched.done?.let { "$done $it" } ?: done
        if (fetched.result.success) return fetched.copy(done = summary)
        return fetched.copy(done = done, errorTitle = Fetch(name).errorTitle)
    }
}

/** Renames the remote [old] to [new]. */
internal data class RenameRemote(
    val old: String,
    val new: String,
) : RefOperation {
    override val progress get() = "Renaming remote $old…"
    override val errorTitle get() = "Rename Remote Failed"
    override val failure get() = "Remote $old could not be renamed."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val renamed = writes.renameRemote(root, old, new)
        if (renamed.partial) {
            val failure =
                "Remote $old was renamed to $new in the configuration, but its remote branches could not be moved."
            return RefOutcome(renamed.result, null, failure = failure)
        }
        if (!renamed.result.success) return RefOutcome(renamed.result, null)
        val note =
            if (renamed.refspecsUpdated) {
                ""
            } else {
                " Its fetch refspecs were not updated, as they differ from git's default: edit remote.$new.fetch."
            }
        return RefOutcome(renamed.result, "Renamed remote $old to $new.$note")
    }
}

/** Removes the remote [name]. */
internal data class RemoveRemote(
    val name: String,
) : RefOperation {
    override val progress get() = "Removing remote $name…"
    override val errorTitle get() = "Remove Remote Failed"
    override val failure get() = "Remote $name could not be removed."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val result = writes.removeRemote(root, name)
        return RefOutcome(result, "Removed remote $name.".takeIf { result.success })
    }
}

/** Changes the URLs of the remote [name]; `null` keeps one, [removePushUrl] removes the push URL. */
internal data class ChangeRemoteUrls(
    val name: String,
    val url: String?,
    val pushUrl: String?,
    val removePushUrl: Boolean,
) : RefOperation {
    override val progress get() = "Changing the URL of $name…"
    override val errorTitle get() = "Change URL Failed"
    override val failure get() = "The URL of $name could not be changed."
    override val fileChange get() = null

    /** With the URLs masked: they may hold credentials. */
    override fun toString() =
        "ChangeRemoteUrls(name=$name, url=${url?.let(::maskedUrl)}, pushUrl=${pushUrl?.let(::maskedUrl)}, " +
            "removePushUrl=$removePushUrl)"

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val result = writes.setRemoteUrls(root, name, url, pushUrl, removePushUrl)
        val both = url != null && (pushUrl != null || removePushUrl)
        val done = if (both) "Changed the URLs of $name." else "Changed the URL of $name."
        return RefOutcome(result, done.takeIf { result.success })
    }
}

/** Reads what removing [name] deletes and asks before it is removed (a destructive action, Cancel the default). */
internal data class PrepareRemoveRemote(
    val name: String,
) : RefOperation {
    override val progress get() = "Reading remote $name…"
    override val errorTitle get() = "Remove Remote Failed"
    override val failure get() = "Remote $name could not be read."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val removal =
            writes.remoteRemoval(root, name)
                ?: return RefOutcome(GitResult(false, "What removing the remote deletes could not be read."), null)
        val confirmation =
            RefConfirmation(
                "Remove Remote",
                "Remove remote '$name'?",
                REMOVE_BUTTON,
                RemoveRemote(name),
                removalDetails(name, removal),
            )
        return RefOutcome(GitResult(true, ""), null, confirmation = confirmation)
    }
}

/** Reads the URLs of [name] and asks the Change URL dialog about them. */
internal data class PrepareChangeRemoteUrls(
    val name: String,
) : RefOperation {
    override val progress get() = "Reading the URLs of $name…"
    override val errorTitle get() = "Change URL Failed"
    override val failure get() = "The URLs of $name could not be read."
    override val fileChange get() = null

    override fun run(
        writes: GitLogWrites,
        root: Path,
    ): RefOutcome {
        val urls =
            writes.remoteUrls(root, name)
                ?: return RefOutcome(GitResult(false, "The URLs of the remote could not be read."), null)
        return RefOutcome(GitResult(true, ""), null, remoteUrls = RemoteUrlsPrompt(name, urls))
    }
}

/**
 * What the Remove Remote question says is lost with [removal] of the remote [name], one consequence per line; what is
 * zero or false is left out.
 */
internal fun removalDetails(
    name: String,
    removal: GitRemoteRemoval,
): String {
    val branches = removal.remoteBranches
    val tracking = removal.trackingBranches
    val listed = if (tracking.isEmpty()) "" else listBranches(tracking)
    return listOfNotNull(
        when (branches) {
            0 -> null
            1 -> "Its 1 remote branch is deleted locally."
            else -> "Its $branches remote branches are deleted locally."
        },
        when (tracking.size) {
            0 -> null
            1 -> "1 local branch ($listed) loses its upstream."
            else -> "${tracking.size} local branches ($listed) lose their upstream."
        },
        "$name is no longer the default push remote.".takeIf { removal.pushDefault },
        "Its URL is removed. This cannot be undone.",
    ).joinToString("\n")
}
