package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.binding.SourcePosition
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Back and Forward of one window (ADR 0146): the places navigation jumps left (go to definition, a chooser, usages),
 * across files. [record] keeps the place before a jump and forgets what lay ahead; [back] and [forward] step through
 * them, keeping where the user is now for the way back. At most [cap] places each way, the oldest dropped first;
 * places in files that no longer exist are skipped. Plain model, FX thread.
 *
 * @param exists whether a file still exists, asked when stepping onto one of its places.
 */
class NavigationHistory(
    private val cap: Int = 50,
    private val exists: (Path) -> Boolean = { Files.exists(it) },
) {
    private val backStack = ArrayDeque<SourcePosition>()
    private val forwardStack = ArrayDeque<SourcePosition>()

    /** Where the last jump or step arrived; stands for the current place when it is not known. */
    private var arrival: SourcePosition? = null

    private val canBack = ReadOnlyBooleanWrapper(false)
    private val canForward = ReadOnlyBooleanWrapper(false)

    /** Whether there are places to go back to. */
    val canGoBack: ReadOnlyBooleanProperty = canBack.readOnlyProperty

    /** Whether there are places to go forward to. */
    val canGoForward: ReadOnlyBooleanProperty = canForward.readOnlyProperty

    /** A jump went [from] one place [to] another: [from] is the way back, the places ahead are gone. */
    fun record(
        from: SourcePosition,
        to: SourcePosition,
    ) {
        if (from == to) return
        push(backStack, from)
        forwardStack.clear()
        arrival = to
        update()
    }

    /**
     * The place to go back to from [current] (the caret of the focused editor; `null` if unknown, then where the last
     * jump arrived), which becomes the way forward; `null` if there is none.
     */
    fun back(current: SourcePosition?): SourcePosition? = step(backStack, forwardStack, current)

    /** The place to go forward to from [current], which becomes the way back; `null` if there is none. */
    fun forward(current: SourcePosition?): SourcePosition? = step(forwardStack, backStack, current)

    /** The places back and forward, nearest last; for tests. */
    internal val places: Pair<List<SourcePosition>, List<SourcePosition>> get() =
        backStack.toList() to
            forwardStack.toList()

    private fun step(
        from: ArrayDeque<SourcePosition>,
        to: ArrayDeque<SourcePosition>,
        current: SourcePosition?,
    ): SourcePosition? {
        val here = current ?: arrival
        var target: SourcePosition? = null
        while (target == null && from.isNotEmpty()) {
            val next = from.removeLast()
            if (next != here && exists(next.file)) target = next
        }
        if (target != null) {
            here?.let { push(to, it) }
            arrival = target
        }
        update()
        return target
    }

    private fun push(
        stack: ArrayDeque<SourcePosition>,
        position: SourcePosition,
    ) {
        if (stack.lastOrNull() == position) return
        stack.addLast(position)
        while (stack.size > cap) stack.removeFirst()
    }

    private fun update() {
        canBack.set(backStack.isNotEmpty())
        canForward.set(forwardStack.isNotEmpty())
    }
}
