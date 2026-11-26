package dev.sluide.previews.preview

/**
 * The [line] a jump of the table of contents went to, and the [page] right after it: the line holds only while the
 * page looks the same.
 */
internal class TocJump(
    val line: Int,
    val page: String,
)
