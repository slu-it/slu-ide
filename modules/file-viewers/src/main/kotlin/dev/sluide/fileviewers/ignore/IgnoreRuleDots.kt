package dev.sluide.fileviewers.ignore

import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreLineEffect
import dev.sluide.fileviewers.code.LineEdit

/** What a dot beside a pattern line of an ignore file says. */
internal enum class DotKind(
    val styleClass: String,
) {
    /** The line decides at least one untracked path. */
    ACTIVE("ignore-rule-active"),

    /** The line decides no untracked path, but matches tracked files, which git keeps tracking. */
    TRACKED("ignore-rule-tracked"),

    /** The line has no effect. */
    NONE("ignore-rule-none"),
}

/** A dot with the [tooltip] explaining it. */
internal data class IgnoreDot(
    val kind: DotKind,
    val tooltip: String,
)

/** The dots of an ignore file, computed from git's answer (ADR 0080); plain logic without controls. */
internal object IgnoreRuleDots {
    /** Files with more lines are not compared line by line when edited. */
    private const val DIFF_LIMIT = 5000

    /** The most cells of the comparison table of the lines that differ; bigger ones are compared as one edit. */
    private const val TABLE_LIMIT = 250_000L

    /** Added to every tooltip when git checked only part of the paths. */
    private const val LIMIT_NOTE = "Not all paths could be checked: too many, or git took too long."

    /** Whether [line] is a pattern: neither blank nor a comment (`#` as the very first character; git takes no indent). */
    fun isPattern(line: String): Boolean = line.isNotBlank() && !line.startsWith('#')

    /** The kind of the dot of the pattern line [index] with [effects]. */
    fun kind(
        effects: IgnoreFileEffects,
        index: Int,
    ): DotKind {
        val effect = effects.lines[index] ?: return DotKind.NONE
        return when {
            effect.ignored + effect.unignored > 0 -> DotKind.ACTIVE
            effect.tracked > 0 -> DotKind.TRACKED
            else -> DotKind.NONE
        }
    }

    /** The dots of the saved text [saved], by 0-based line index; comment and blank lines get none. */
    fun of(
        saved: String,
        effects: IgnoreFileEffects,
    ): Map<Int, IgnoreDot> {
        val dots = linkedMapOf<Int, IgnoreDot>()
        saved.split('\n').forEachIndexed { index, line ->
            if (!isPattern(line)) return@forEachIndexed
            val kind = kind(effects, index)
            dots[index] = IgnoreDot(kind, tooltip(kind, effects.lines[index], effects.complete))
        }
        return dots
    }

    /**
     * The [dots] of the saved text [saved] for the edited text [text]: dots of lines that are still there (a line diff,
     * ADR 0080) move along; changed and new lines have none until the next save. Files over [DIFF_LIMIT] lines are
     * compared as one replacement of everything between the first and the last change.
     */
    fun edited(
        saved: String,
        text: String,
        dots: Map<Int, IgnoreDot>,
    ): Map<Int, IgnoreDot> {
        if (saved == text) return dots
        if (dots.isEmpty()) return dots
        val old = saved.split('\n')
        val new = text.split('\n')
        val matches = if (old.size > DIFF_LIMIT || new.size > DIFF_LIMIT) null else matches(old, new)
        if (matches == null) return shiftedByOneEdit(saved, text, dots)
        val result = linkedMapOf<Int, IgnoreDot>()
        for ((oldIndex, newIndex) in matches) dots[oldIndex]?.let { result[newIndex] = it }
        return result
    }

    private fun shiftedByOneEdit(
        saved: String,
        text: String,
        dots: Map<Int, IgnoreDot>,
    ): Map<Int, IgnoreDot> {
        val edit = LineEdit.between(saved, text) ?: return dots
        val result = linkedMapOf<Int, IgnoreDot>()
        for ((index, dot) in dots) {
            when {
                index < edit.line -> result[index] = dot
                index >= edit.line + edit.removedLines -> result[index - edit.removedLines + edit.insertedLines] = dot
            }
        }
        return result
    }

    /** The pairs of indexes of equal lines of [old] and [new] in a longest common subsequence; `null` if too big. */
    private fun matches(
        old: List<String>,
        new: List<String>,
    ): List<Pair<Int, Int>>? {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) {
            suffix++
        }
        val n = old.size - prefix - suffix
        val m = new.size - prefix - suffix
        if (n.toLong() * m > TABLE_LIMIT) return null
        val pairs = ArrayList<Pair<Int, Int>>()
        for (i in 0 until prefix) pairs += i to i
        // lengths[i][j]: the common subsequence of the middle parts from i and j on.
        val lengths = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lengths[i][j] =
                    if (old[prefix + i] == new[prefix + j]) {
                        lengths[i + 1][j + 1] + 1
                    } else {
                        maxOf(lengths[i + 1][j], lengths[i][j + 1])
                    }
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[prefix + i] == new[prefix + j] -> pairs += (prefix + i++) to (prefix + j++)
                lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                else -> j++
            }
        }
        for (k in 0 until suffix) pairs += (old.size - suffix + k) to (new.size - suffix + k)
        return pairs
    }

    /** The tooltip of a dot of [kind] for a line with [effect] (`null`: none). */
    fun tooltip(
        kind: DotKind,
        effect: IgnoreLineEffect?,
        complete: Boolean,
    ): String {
        val lines = mutableListOf<String>()
        when {
            kind == DotKind.NONE || effect == null -> {
                lines += "No effect: this line decides no untracked path"
            }

            kind == DotKind.TRACKED -> {
                val files = trackedFiles(effect.tracked)
                lines += listed("Matches only $files, which git keeps tracking", effect.trackedExamples, effect.tracked)
            }

            else -> {
                if (effect.ignored > 0) {
                    lines += listed("Ignores ${paths(effect.ignored)}", effect.examples, effect.ignored)
                }
                if (effect.unignored > 0) {
                    lines += listed("Un-ignores ${paths(effect.unignored)}", effect.examples, effect.unignored)
                }
                if (effect.tracked > 0) {
                    val files = trackedFiles(effect.tracked)
                    lines +=
                        listed("Also matches $files, which git keeps tracking", effect.trackedExamples, effect.tracked)
                }
            }
        }
        if (!complete) lines += LIMIT_NOTE
        return lines.joinToString("\n")
    }

    private fun trackedFiles(count: Int) = if (count == 1) "1 tracked file" else "$count tracked files"

    private fun paths(count: Int) = if (count == 1) "1 path" else "$count paths"

    /** [head], then ": " and the [examples] followed by "… and N more" if there are [count] in all; just [head] without. */
    private fun listed(
        head: String,
        examples: List<String>,
        count: Int,
    ): String {
        if (examples.isEmpty()) return head
        val more = count - examples.size
        val text = examples.joinToString(", ")
        return if (more > 0) "$head: $text, … and $more more" else "$head: $text"
    }
}
