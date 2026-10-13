package dev.sluide.fileexplorer

/**
 * A colour marking a file or folder, e.g. for its version control status: either as the colour of its name or as a
 * bubble after the name ([FileMarkers]). The colours are in `file-explorer.css`.
 */
enum class FileMarker(
    /** Style class of the bubble. */
    val bubbleStyleClass: String,
    /** Style class of the name. */
    val nameStyleClass: String,
) {
    UNVERSIONED("file-marker-unversioned", "file-name-unversioned"),
    ADDED("file-marker-added", "file-name-added"),
    MODIFIED("file-marker-modified", "file-name-modified"),
    IGNORED("file-marker-ignored", "file-name-ignored"),
}

/** How an entry is marked: the colour of its [name] (`null`: the normal one), and [bubbles] after it in this order. */
data class FileMarkers(
    val name: FileMarker? = null,
    val bubbles: List<FileMarker> = emptyList(),
) {
    companion object {
        /** No marks. */
        val NONE = FileMarkers()
    }
}
