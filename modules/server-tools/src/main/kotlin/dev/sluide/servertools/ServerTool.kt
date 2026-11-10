package dev.sluide.servertools

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.ToolRelease
import java.nio.file.Path

/**
 * What Thoreau knows about one language server it downloads (ADR 0159), the configuration a language module gives the
 * shared installer, update check, smoke test and settings page:
 * - [name]: what users know it by, in every text (`ty`, `TypeScript`);
 * - [root]: its [dev.sluide.localmodels.ToolInstaller] root (`~/.sluide/tools/ty`);
 * - [pin] and [platform] (a [ToolPin.PLATFORMS] key, null without a build): what Install installs;
 * - [format] and [executableIn]: how a release file holds the executable (its path inside the archive, by the asset's
 *   name);
 * - [serverArguments]: how it runs as a language server on standard input and output (`server`);
 * - [versionArguments] and [versionPrefix]: the smoke test's version command and the start of its first line;
 * - [commandName]: the executable in the smoke test's messages (`` `tsc --version` ``), the [name] by default.
 */
class ServerTool(
    val name: String,
    val root: Path,
    val pin: ToolPin,
    val platform: String?,
    val format: ArchiveFormat,
    val executableIn: (asset: String) -> String,
    val serverArguments: List<String>,
    val versionArguments: List<String>,
    val versionPrefix: String,
    val commandName: String = name,
) {
    /** The pinned file for [platform]; null when there is no build for it. */
    val pinnedAsset: ToolAsset? get() = pin.asset(platform)

    /** Why Thoreau cannot download the tool here. */
    val noBuild: String get() = "No $name build for this platform"

    /** The release of [version] with [asset]. */
    fun release(
        version: String,
        asset: ToolAsset,
    ) = ToolRelease(version, asset.url, asset.bytes, asset.checksum, format, executableIn(asset.name))

    /** The pinned release for [platform], or null if there is no build for it. */
    fun pinnedRelease(): ToolRelease? = pinnedAsset?.let { release(pin.version, it) }

    /** The background task row of an install: the download's percent, then unpacking and checking. */
    fun taskName(
        release: ToolRelease,
        percent: Int?,
    ): String =
        when (percent) {
            null -> "Installing $name ${release.version}"
            else -> "Downloading $name ${release.version} $percent %"
        }

    /** The check an install runs before it switches: the version command, then one `initialize`. */
    fun smokeTest() = LspSmokeTest(name, versionArguments, versionPrefix, serverArguments, commandName)
}
