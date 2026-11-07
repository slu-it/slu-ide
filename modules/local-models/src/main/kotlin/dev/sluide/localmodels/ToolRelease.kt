package dev.sluide.localmodels

import java.io.IOException
import java.net.URI
import java.nio.file.Path

/** How the file of a [ToolRelease] holds its tool. */
enum class ArchiveFormat {
    /** A gzip-compressed tar archive (`.tar.gz`, `.tgz`). */
    TAR_GZ,

    /** A zip archive. */
    ZIP,

    /** The file is the executable itself. */
    PLAIN,
}

/**
 * One release of a command-line tool, for one platform: its [version] (also the name of its folder, so letters,
 * digits and `.`, `_`, `+`, `-` only), where its file comes from, the file's size and SHA-256 (hex, from a pin or
 * the release's published digest), its [format], and the [executable]'s path inside the archive (`/`-separated; for
 * [ArchiveFormat.PLAIN] the name the file gets).
 */
data class ToolRelease(
    val version: String,
    val url: URI,
    val bytes: Long,
    val sha256: String,
    val format: ArchiveFormat,
    val executable: String,
)

/** The tool a pointer of [ToolInstaller] names: its version and the absolute path of its executable. */
data class InstalledTool(
    val version: String,
    val executable: Path,
)

/** An install failed for a reason the user is told (an archive refused, the smoke test failed); readable message. */
class ToolInstallException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
