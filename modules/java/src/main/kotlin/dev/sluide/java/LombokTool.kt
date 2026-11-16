package dev.sluide.java

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.ToolRelease
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** The Lombok jar this Thoreau was tested with: version, where Maven Central serves it, its size and sha256. */
internal class LombokPin(
    val version: String,
    val url: URI,
    val bytes: Long,
    val sha256: String,
) {
    /** The jar's file name below its version folder. */
    val fileName: String get() = "lombok-$version.jar"

    /** The release the installer installs: a plain file, not an archive. */
    fun release() = ToolRelease(version, url, bytes, sha256, ArchiveFormat.PLAIN, fileName)
}

/**
 * Lombok as a pinned `-javaagent` jar beside jdtls (ADR 0179): downloaded from Maven Central into
 * `~/.sluide/tools/lombok` with jdtls, deleted with it, no update check of its own (the skill
 * `update-external-artifacts` bumps the pin). Maven Central publishes no sha256: the pin's was computed and compared
 * with the published sha1 when it was made.
 */
internal object LombokTool {
    val DEFAULT_ROOT: Path = Path.of(System.getProperty("user.home"), ".sluide", "tools", "lombok")

    val PIN =
        LombokPin(
            version = "1.18.48",
            url = URI("https://repo1.maven.org/maven2/org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar"),
            bytes = 2_055_065,
            sha256 = "85477a4655ebb2c074a9099cfb749be454449fee564d4282610df1b85f7c508b",
        )

    /** The install's check: the jar is a regular file of the pinned size; null if it is, else why not. */
    fun smokeTest(pin: LombokPin = PIN): (Path) -> String? =
        { jar ->
            if (!Files.isRegularFile(jar)) {
                "the jar is not a regular file"
            } else {
                val size = Files.size(jar)
                if (size == pin.bytes) null else "the jar has $size bytes, not the ${pin.bytes} of the pin"
            }
        }

    /** The name of the install's background task row. */
    @Suppress("UNUSED_PARAMETER")
    fun taskName(
        release: ToolRelease,
        percent: Int?,
    ): String =
        when (percent) {
            null -> "Installing Lombok ${release.version}"
            else -> "Downloading Lombok ${release.version} $percent %"
        }
}
