package dev.sluide.ide

import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusSnapshot
import java.nio.file.Path

/**
 * Whether git could commit [file]: it is in the repository and not ignored. Without a repository (or git) nothing is
 * committed. The HTTP client warns about private environment files that git could commit (ADR 0028).
 */
internal fun GitStatusSnapshot.wouldCommit(file: Path): Boolean {
    val root = root ?: return false
    val path = file.toAbsolutePath().normalize()
    return path.startsWith(root) && fileStatus(path) != GitStatus.IGNORED
}
