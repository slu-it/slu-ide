package dev.sluide.ide.compare

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitFileDiff
import java.nio.file.Path

/**
 * Two files compared as they were read once: the [left] and [right] file with their texts and the lines that differ
 * (ADR 0113). [swapped] trades the sides without reading or comparing anything again.
 */
internal data class FileComparison(
    val left: Path,
    val right: Path,
    val leftText: String,
    val rightText: String,
    val changes: List<LineChange>,
) {
    /** The same comparison with the sides traded: texts, paths and the ranges of each change. */
    fun swapped() =
        FileComparison(right, left, rightText, leftText, changes.map { LineChange(it.current, it.original) })

    /** `a.txt ↔ b.txt`; `name (dirA ↔ dirB)` with the parent folders' names if both files have the same name. */
    fun title(): String {
        val a = fileNameOf(left)
        val b = fileNameOf(right)
        if (a != b) return "$a ↔ $b"
        return "$a (${folderNameOf(left)} ↔ ${folderNameOf(right)})"
    }

    /** Names both files without implying sides, as the tooltip is read once and the sides can be swapped later. */
    fun tooltip() = "Comparing ${left.toAbsolutePath()} and ${right.toAbsolutePath()}"

    /** This comparison with the sides of [shown], a comparison of the same two files, so a refreshed tab keeps them. */
    fun inSidesOf(shown: FileComparison) = if (left != right && shown.left == right) swapped() else this

    /** Whether this compares [a] and [b], in either order. */
    fun shows(
        a: Path,
        b: Path,
    ) = (left == a && right == b) || (left == b && right == a)

    fun toTextDiff(
        leftTitle: String,
        rightTitle: String,
    ) = TextDiff(leftText, changes, leftTitle, rightTitle)

    companion object {
        /** The comparison of [left] and [right] that git found, [diff]. */
        fun of(
            left: Path,
            right: Path,
            diff: GitFileDiff.Text,
        ) = FileComparison(
            left,
            right,
            diff.original,
            diff.current,
            diff.hunks.map { LineChange(it.original, it.current) },
        )
    }
}

private fun fileNameOf(file: Path) = file.fileName?.toString() ?: file.toString()

private fun folderNameOf(file: Path) =
    file
        .toAbsolutePath()
        .parent
        ?.fileName
        ?.toString() ?: ""

/**
 * The title above one side: [file] relative to the [projectFolder] with `/` separators where it is below it, else the
 * absolute path; with ` (saved)` if an editor has [unsaved] changes of it, as the diff against `HEAD` shows them.
 */
internal fun columnTitle(
    file: Path,
    projectFolder: Path?,
    unsaved: Boolean,
): String {
    val absolute = file.toAbsolutePath().normalize()
    val base = projectFolder?.toAbsolutePath()?.normalize()
    val text =
        if (base != null && absolute != base && absolute.startsWith(base)) {
            base.relativize(absolute).joinToString("/")
        } else {
            absolute.toString()
        }
    return if (unsaved) "$text (saved)" else text
}

/** Why [left] and [right] cannot be compared, for the user; `null` if [result] is a text comparison. */
internal fun comparisonMessage(
    left: Path,
    right: Path,
    result: GitFileDiff?,
): String? {
    val names = "${fileNameOf(left)} and ${fileNameOf(right)}"
    return when (result) {
        is GitFileDiff.Text -> null
        GitFileDiff.Binary -> "$names cannot be compared: one of them is a binary file."
        GitFileDiff.TooLarge -> "$names cannot be compared: one of them is larger than 5 MB."
        else -> "$names cannot be compared."
    }
}
