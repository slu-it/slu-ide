package dev.sluide.gitlog.actions

import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.operations.AddRemote
import dev.sluide.gitlog.operations.AddRemoteDialog
import dev.sluide.gitlog.operations.ChangeRemoteUrls
import dev.sluide.gitlog.operations.ChangeUrlDialog
import dev.sluide.gitlog.operations.PrepareChangeRemoteUrls
import dev.sluide.gitlog.operations.PrepareRemoveRemote
import dev.sluide.gitlog.operations.RefOperation
import dev.sluide.gitlog.operations.RemoteUrlsPrompt
import dev.sluide.gitlog.operations.RenameRemote
import dev.sluide.gitlog.operations.RenameRemoteDialog
import dev.sluide.gitlog.operations.remoteNameProblem
import javafx.stage.Window

/**
 * The flows that ask before a remote is added, renamed, changed or removed (ADR 0183) and then [start] the operation:
 * the dialogs are shown on the [window], with the remotes the log read last; no dialog opens while a write
 * [running] (the operations that read first are refused by [start] then). Removing and changing the URL first read
 * what the dialog needs off the FX thread ([PrepareRemoveRemote], [PrepareChangeRemoteUrls]); their outcomes come back
 * to [changeUrls] and the confirmation of [RefActions], and are dropped with the outcome of any run for a folder that
 * is no longer open.
 */
internal class RemoteActions(
    private val pages: GitLogPages,
    private val canPass: (String) -> Boolean,
    private val window: () -> Window?,
    private val running: () -> Boolean,
    private val start: (RefOperation) -> Unit,
) {
    /** Asks for the name and URL of a new remote, `origin` at first for a repository without one, and adds it. */
    fun add() {
        if (running()) return
        val remotes = pages.remotes.orEmpty()
        val initial = if (pages.remotes?.isEmpty() == true) "origin" else ""
        val dialog = AddRemoteDialog(window(), initial) { name -> remoteNameProblem(name, remotes, canPass) }
        val remote = dialog.showAndWait().orElse(null) ?: return
        start(AddRemote(remote.name, remote.url, remote.fetch))
    }

    /** Asks for the new name of the remote [old] and renames it. */
    fun rename(old: String) {
        if (running()) return
        val remotes = pages.remotes.orEmpty()
        val dialog =
            RenameRemoteDialog(window(), old) { name ->
                remoteNameProblem(name, remotes, canPass, except = old, unchanged = old)
            }
        val new = dialog.showAndWait().orElse(null) ?: return
        start(RenameRemote(old, new))
    }

    /** Reads the URLs of [name] and asks about them in [changeUrls]. */
    fun readUrls(name: String) {
        start(PrepareChangeRemoteUrls(name))
    }

    /** Reads what removing [name] deletes and asks whether to remove it. */
    fun remove(name: String) {
        start(PrepareRemoveRemote(name))
    }

    /** Asks for the new URLs as [prompt] offers them and changes what the user changed. */
    fun changeUrls(prompt: RemoteUrlsPrompt) {
        val edit = ChangeUrlDialog(window(), prompt).showAndWait().orElse(null) ?: return
        start(ChangeRemoteUrls(prompt.name, edit.url, edit.pushUrl, edit.removePushUrl))
    }
}
