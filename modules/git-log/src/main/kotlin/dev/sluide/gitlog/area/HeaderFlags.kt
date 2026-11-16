package dev.sluide.gitlog.area

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ObservableBooleanValue

/** What the view tells the header's buttons about the repository, besides what [QuickActionsState] reads. */
internal class HeaderFlags {
    /** Whether the last read found no remote to fetch; `false` while that is not known (ADR 0043). */
    val noRemotes = SimpleBooleanProperty(false)

    /** Whether the log shows a repository; the review window reports an unborn `HEAD` itself (ADR 0168). */
    val hasRepository = SimpleBooleanProperty(false)

    /** Whether the window's git status shows local changes: the header's Stash needs some (ADR 0182). */
    val localChanges = SimpleBooleanProperty(false)

    /** The header's Review and Stash are off without a repository. */
    val noRepository = Bindings.not(hasRepository)

    /** When the header's Rebase is off: [blocked] (an operation or the pending bar is busy) or not possible. */
    fun rebaseOff(
        quickActions: QuickActionsState,
        blocked: ObservableBooleanValue,
    ): ObservableBooleanValue = Bindings.or(blocked, quickActions.rebasePossible.not())

    /** When the header's New Branch is off: [blocked] or not possible. */
    fun newBranchOff(
        quickActions: QuickActionsState,
        blocked: ObservableBooleanValue,
    ): ObservableBooleanValue = Bindings.or(blocked, quickActions.newBranchPossible.not())

    /** When the header's Stash is off (ADR 0182): [blocked], no repository or no local changes. */
    fun stashOff(blocked: ObservableBooleanValue): ObservableBooleanValue =
        Bindings.or(blocked, Bindings.or(noRepository, Bindings.not(localChanges)))

    /** When the header's Pop is off (ADR 0182): [blocked] or no stash known. */
    fun popOff(
        quickActions: QuickActionsState,
        blocked: ObservableBooleanValue,
    ): ObservableBooleanValue = Bindings.or(blocked, quickActions.stashCount.isEqualTo(0))
}
