package dev.sluide.languageservers

/**
 * One edit of a synced document ([SyncedDocument.changed]): at UTF-16 offset [start], the text [removed] was replaced
 * by [inserted]. Offsets are in the text the change before left, as the editor's `TextChange` delivers them.
 */
data class DocumentChange(
    val start: Int,
    val removed: String,
    val inserted: String,
)
