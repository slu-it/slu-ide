package dev.sluide.ebooks.view

import java.nio.file.Path

/**
 * Where a book is, for the position store that runs on another thread: its file (it changes when the explorer moves
 * it) and the folder of the project the view belongs to (known once the host is set).
 */
internal class BookLocation(
    file: Path,
) {
    @Volatile
    var file: Path = file

    @Volatile
    var projectFolder: Path? = null
}
