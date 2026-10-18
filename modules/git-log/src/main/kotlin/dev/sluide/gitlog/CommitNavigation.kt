package dev.sluide.gitlog

import dev.sluide.git.GitRef
import dev.sluide.git.GitRevisionResolution
import dev.sluide.gitlog.data.CommitMissing
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.GitLogState
import javafx.scene.control.TableView
import javafx.scene.control.skin.VirtualFlow
import java.nio.file.Path
import java.util.Locale

/**
 * Goes to commits in the [table] of the [pages], loading pages until they are there (ADRs 0040, 0045): those of the
 * branches selected in the branches pane ([navigateTo], [branchMoved]), the commits linked from the details
 * ([goToCommit]), and those Go to Hash/Branch/Tag ([goToPopup], offering [goToItems]) resolves with [resolveCommits] on
 * [background], handled through [dispatch]. Why a commit is not there goes to [tell] (`null` clears the header) or to
 * the popup. The queries say which branch the pane has selected ([selectedRef]), whether it is shown ([branchesShown])
 * and whether the details have the focus ([detailsFocused]).
 */
internal class CommitNavigation(
    private val pages: GitLogPages,
    private val table: TableView<GitLogRow>,
    private val resolveCommits: (Path, List<String>) -> List<GitRevisionResolution>?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    goToItems: () -> List<FilterItem>,
    private val selectedRef: () -> GitRef?,
    private val branchesShown: () -> Boolean,
    private val detailsFocused: () -> Boolean,
    private val tell: (String?) -> Unit,
) {
    /** Clears the filters, so that Go to finds the commit they hide; set by the view. */
    var clearFilters: () -> Unit = {}

    /** Go to Hash/Branch/Tag (ADR 0045), with the branches and tags read last. */
    internal val goToPopup =
        GoToPopup(goToItems).apply {
            go = ::goTo
            clearFilters = ::clearFiltersAndGo
            closed = ::goToClosed
        }

    /** Counts the requests of Go to, so that the result of an earlier one, or one after the popup closed, is dropped. */
    private var goToRequests = 0L

    /** The commit Go to looks for in the log and what it is called, while it does. */
    private var goToTarget: Pair<String, String>? = null

    /**
     * Selects the row of [ref]'s commit and scrolls to it, loading pages until it is there; if it is not, the header
     * tells why. Only while the branches pane still has [ref] selected; `null` stops looking.
     */
    fun navigateTo(ref: GitRef?) {
        if (ref == null) {
            pages.stopLoadingUntil()
            return
        }
        tell(null)
        pages.loadUntil(
            ref.commit,
            { index -> if (selectedRef() == ref) showRow(index) },
            { missing -> if (selectedRef() == ref) tell(missingMessage(ref.shortName, missing)) },
        )
    }

    /**
     * Selects the row of the commit [hash] and scrolls to it, loading pages until it is there; if it is not, the header
     * tells why, calling it [name] (ADR 0045). Replaces a search for another commit, e.g. of a branch. The table takes
     * the focus from the details (a clicked link), whose texts the next commit may not have.
     */
    fun goToCommit(
        hash: String,
        name: String,
    ) {
        tell(null)
        pages.loadUntil(
            hash,
            { index ->
                showRow(index)
                if (detailsFocused()) table.requestFocus()
            },
            { missing -> tell(missingMessage(name, missing)) },
        )
    }

    /** Opens Go to Hash/Branch/Tag at the top of the table (ADR 0045); not without a repository. */
    fun openGoTo() {
        if (pages.root == null) return
        goToPopup.show(table)
    }

    /**
     * Resolves [revision] (a full refname, or what was typed) in the background and goes to its commit (ADR 0045); the
     * popup says why not, calling it [name].
     */
    private fun goTo(
        revision: String,
        name: String,
    ) {
        val root = pages.root ?: return
        val request = ++goToRequests
        goToTarget = null
        background {
            val resolution = resolveCommits(root, listOf(revision))?.singleOrNull()
            dispatch {
                if (request != goToRequests || pages.root != root || !goToPopup.isShowing) return@dispatch
                when (resolution) {
                    is GitRevisionResolution.Commit -> seekGoTo(request, resolution.hash, name)
                    GitRevisionResolution.Ambiguous -> goToPopup.say("'$name' is ambiguous.")
                    GitRevisionResolution.Missing, null -> goToPopup.say("Unknown revision '$name'.")
                }
            }
        }
    }

    /**
     * Selects the row of the commit [hash] Go to resolved, loading pages until it is there: the popup closes and the
     * table takes the focus; if it is not there, the popup says why, offering to clear the filters that hide it.
     */
    private fun seekGoTo(
        request: Long,
        hash: String,
        name: String,
    ) {
        goToTarget = hash to name
        pages.loadUntil(
            hash,
            { index ->
                if (request == goToRequests) {
                    goToTarget = null
                    goToPopup.hide()
                    showRow(index)
                    table.requestFocus()
                }
            },
            { missing ->
                if (request == goToRequests) {
                    val hidden =
                        missing == CommitMissing.NOT_IN_LOG &&
                            pages.comparison == null &&
                            !pages.effectiveFilter.isDefault
                    goToPopup.say(missingMessage(name, missing), offerClearFilters = hidden)
                }
            },
        )
    }

    /** Clears the filters that hide the commit Go to looked for, and goes on to it. */
    private fun clearFiltersAndGo() {
        val (hash, name) = goToTarget ?: return
        clearFilters()
        goToPopup.say(null)
        seekGoTo(goToRequests, hash, name)
    }

    /** Go to closed: what it still waits for is dropped, the log stops loading pages for it. */
    private fun goToClosed() {
        goToRequests++
        val target = goToTarget ?: return
        goToTarget = null
        if (pages.loadingUntil == target.first) pages.stopLoadingUntil()
    }

    /** Why the commit called [name] is not in the table, as [GitLogPages.loadUntil] found it [missing]. */
    private fun missingMessage(
        name: String,
        missing: CommitMissing,
    ): String =
        when {
            missing == CommitMissing.UNREADABLE -> {
                GitLogState.FAILED.message
            }

            missing == CommitMissing.BEYOND_LIMIT -> {
                "$name is more than ${"%,d".format(Locale.ENGLISH, GitLogPages.SEEK_LIMIT)} commits down."
            }

            pages.comparison != null -> {
                "$name is not in this comparison."
            }

            pages.effectiveFilter.isDefault -> {
                "$name is not in the log."
            }

            else -> {
                "$name is hidden by the filters."
            }
        }

    /**
     * Follows a selected branch that a refresh moved from [before] to [after], but only while the user is still on it:
     * the pane is shown and the table still has exactly its old commit selected, or its commit is still looked for.
     */
    fun branchMoved(
        before: GitRef,
        after: GitRef,
    ) {
        val looking = pages.loadingUntil == before.commit
        val onIt = table.selectionModel.selectedItems.map { it.commit.hash } == listOf(before.commit)
        if (branchesShown() && (looking || onIt)) {
            navigateTo(after)
        } else if (looking) {
            pages.stopLoadingUntil()
        }
    }

    /** Selects only the row at [index] and scrolls it into the middle of the table, unless it is in view already. */
    private fun showRow(index: Int) {
        table.selectionModel.clearAndSelect(index)
        val flow = table.lookup(".virtual-flow") as? VirtualFlow<*>
        val first = flow?.firstVisibleCell?.index ?: -1
        val last = flow?.lastVisibleCell?.index ?: -1
        // The first and last rows may be cut off.
        if (index in first + 1..<last) return
        table.scrollTo(maxOf(0, index - maxOf(0, last - first) / 2))
    }
}
