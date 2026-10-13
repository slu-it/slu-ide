package dev.sluide.fileexplorer

/**
 * A coloured bubble after the name of a file or folder, e.g. for its version control status. The colours are in
 * `file-explorer.css`; an entry shows its markers in this order.
 */
enum class FileMarker(
    val styleClass: String,
) {
    UNVERSIONED("file-marker-unversioned"),
    ADDED("file-marker-added"),
    MODIFIED("file-marker-modified"),
    IGNORED("file-marker-ignored"),
}
