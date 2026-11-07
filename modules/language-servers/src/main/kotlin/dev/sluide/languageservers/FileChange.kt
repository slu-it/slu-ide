package dev.sluide.languageservers

import java.nio.file.Path

/** A file or folder that changed on disk, for [LanguageServers.filesChanged] (`workspace/didChangeWatchedFiles`). */
data class FileChange(
    val path: Path,
    val kind: FileChangeKind,
)

/** What happened to a [FileChange]'s path; the LSP `FileChangeType`. */
enum class FileChangeKind {
    CREATED,
    CHANGED,
    DELETED,
}
