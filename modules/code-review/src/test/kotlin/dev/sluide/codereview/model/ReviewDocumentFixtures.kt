package dev.sluide.codereview.model

import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.fileviewers.DiffKind
import dev.sluide.fileviewers.LineChange

internal fun modified(path: String) = ReviewFile(ReviewChange.MODIFIED, path)

/**
 * A text diff of [originalLines] and [currentLines] lines with [changes]; lines are `o<i>` and `c<j>`, except the
 * unchanged ones, which are equal on both sides so that the texts match the changes as git would give them.
 */
internal fun textDiff(
    originalLines: Int,
    currentLines: Int,
    vararg changes: LineChange,
): ReviewFileContent.Text {
    val original = MutableList(originalLines) { "o$it" }
    val current = MutableList(currentLines) { "c$it" }
    for (run in unchangedRuns(originalLines, currentLines, changes.toList())) {
        for (k in 0 until run.length) current[run.current + k] = original[run.original + k]
    }
    return ReviewFileContent.Text(
        original.joinToString("") { "$it\n" },
        current.joinToString("") { "$it\n" },
        changes.toList(),
    )
}

/** One modified line [line] in a file of [lines] lines. */
internal fun oneModifiedLine(
    lines: Int,
    line: Int,
) = textDiff(lines, lines, LineChange(line..line, line..line))

internal fun ReviewDocument.load(
    path: String,
    content: ReviewFileContent,
    ignoreWhitespace: Boolean = false,
) = setContent(path, prepareFile(modified(path), content, null, ignoreWhitespace))

/** A document with [files] and their contents loaded. */
internal fun document(
    vararg files: Pair<String, ReviewFileContent>,
    layout: ReviewLayout = ReviewLayout.SPLIT,
): ReviewDocument =
    ReviewDocument().apply {
        this.layout.set(layout)
        setFiles(files.map { modified(it.first) })
        for ((path, content) in files) load(path, content)
    }

/**
 * A row in short: `H path [collapsed] [viewed] +a -r`, `G gap hidden ↑↓ header`, `U o c kind`, `S o c kind`,
 * `N KIND detail`, `-`; `.` for a missing line, kinds as their first letter.
 */
internal fun ReviewRow.short(): String =
    when (this) {
        is ReviewRow.FileHeader -> {
            listOfNotNull("H $path", "collapsed".takeIf { collapsed }, "viewed".takeIf { viewed }, "+$added -$removed")
                .joinToString(" ")
        }

        is ReviewRow.Gap -> {
            "G $gap $hidden ${if (up) "↑" else ""}${if (down) "↓" else ""} $hunkHeader".trimEnd()
        }

        is ReviewRow.UnifiedLine -> {
            "U ${original ?: "."} ${current ?: "."} ${kind.short()}".trimEnd()
        }

        is ReviewRow.SplitLine -> {
            "S ${original ?: "."} ${current ?: "."} ${kind.short()}".trimEnd()
        }

        is ReviewRow.Notice -> {
            "N $kind ${detail.orEmpty()}".trimEnd()
        }

        is ReviewRow.Spacer -> {
            "-"
        }
    }

private fun DiffKind?.short() = this?.name?.take(1).orEmpty()

internal fun ReviewDocument.shortRows(): List<String> = rows.map { it.short() }

/** The rows of the file at [path] in short, without its header and spacer. */
internal fun ReviewDocument.bodyOf(path: String): List<String> =
    rows.filter { it.path == path && it !is ReviewRow.FileHeader && it !is ReviewRow.Spacer }.map { it.short() }
