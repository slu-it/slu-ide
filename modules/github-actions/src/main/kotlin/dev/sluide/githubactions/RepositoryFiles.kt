package dev.sluide.githubactions

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Reads local references from the files under [root], confined to it (also after following symbolic links), at most
 * [MAX_BYTES] each, as UTF-8.
 */
class RepositoryFiles(
    root: Path,
) : WorkflowSources {
    private val root = root.toAbsolutePath().normalize()

    override fun local(path: String): String? {
        val file =
            try {
                root.resolve(path).normalize()
            } catch (_: InvalidPathException) {
                throw IOException("invalid path")
            }
        if (!file.startsWith(root)) throw IOException(OUTSIDE)
        // Checked and opened as the real path, without following links again.
        val real =
            try {
                file.toRealPath()
            } catch (_: NoSuchFileException) {
                return null
            }
        if (!real.startsWith(root.toRealPath())) throw IOException(OUTSIDE)
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 1) }
        if (bytes.size > MAX_BYTES) throw IOException("larger than 1 MB")
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        const val OUTSIDE = "outside the repository"
    }
}
