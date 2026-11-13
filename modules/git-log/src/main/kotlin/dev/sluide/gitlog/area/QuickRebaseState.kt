package dev.sluide.gitlog.area

import dev.sluide.git.GitRefs
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether the header's rebase onto origin's default branch may start (ADR 0063), also while the log is collapsed and
 * its pages are not loaded: [open] and [refresh] read what the button needs, local and without fetching (`git remote`,
 * the current branch, `HEAD`), with [background] off the JavaFX thread, and hand the answer to [dispatch], which runs
 * on it. Nothing is read while the log is [expanded]: its pages read the same ([refsRead]), and collapsing keeps what
 * was known. An answer that a newer [open], [refresh], [refsRead] or [close] has overtaken is dropped, and its read
 * skips git if it did not start yet. [open], [refresh], [refsRead] and [close] are for the JavaFX thread.
 */
internal class QuickRebaseState(
    private val remotes: (Path) -> List<String>?,
    private val currentBranch: (Path) -> String?,
    private val headCommit: (Path) -> String?,
    private val expanded: () -> Boolean,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
) {
    private val state = ReadOnlyBooleanWrapper(false)

    /** Whether the rebase is possible as far as is known; `false` until read. */
    val possible: ReadOnlyBooleanProperty = state.readOnlyProperty

    private var root: Path? = null
    private val generation = AtomicInteger()

    /** Shows [root] (`null` for none): not possible until it is read, which the expanded log's pages do instead. */
    fun open(root: Path?) {
        this.root = root
        generation.incrementAndGet()
        state.set(false)
        read()
    }

    /** Reads again for the repository shown, e.g. after a change in `.git` or a write. */
    fun refresh() {
        generation.incrementAndGet()
        read()
    }

    /** The expanded log read [readRemotes] and [readRefs] itself: the state follows them. */
    fun refsRead(
        readRemotes: List<String>?,
        readRefs: GitRefs?,
    ) {
        generation.incrementAndGet()
        state.set(rebaseOntoOriginPossible(readRemotes, readRefs))
    }

    /** Forgets the repository; reads that are still to run do nothing. */
    fun close() {
        root = null
        generation.incrementAndGet()
    }

    private fun read() {
        val root = root ?: return
        if (expanded()) return
        val mine = generation.get()
        background {
            if (mine == generation.get()) {
                val possible =
                    rebaseOntoOriginPossible(
                        hasOrigin = remotes(root).orEmpty().contains("origin"),
                        onBranch = currentBranch(root) != null,
                        hasCommit = headCommit(root) != null,
                    )
                dispatch { if (mine == generation.get()) state.set(possible) }
            }
        }
    }
}
