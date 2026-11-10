package dev.sluide.python

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ToolPin
import java.net.URI
import java.nio.file.Path

/** The release assets of astral-sh/ty: `https://github.com/astral-sh/ty/releases/download/<version>/<asset>`. */
private const val DOWNLOADS = "https://github.com/astral-sh/ty/releases/download/"

/**
 * ty as a server tool (ADR 0144, ADR 0159): downloaded to `~/.sluide/tools/ty` from its GitHub release, pinned in
 * `ty-release.properties` (version, and per platform the asset, its size and SHA-256), run as `ty server`, checked
 * with `ty version` printing `ty …`. The asset names carry no version, so a newer release has the same names.
 */
internal object TyTool {
    val DEFAULT_ROOT: Path = Path.of(System.getProperty("user.home"), ".sluide", "tools", "ty")

    /** The pin of the module's resource; a missing or broken one is a build error. */
    fun pin(): ToolPin = ToolPin.load(TyTool::class.java, "ty-release.properties", ::downloadUrl)

    /** Where [asset] of [version] is downloaded from: the GitHub release, the same for every platform. */
    fun downloadUrl(
        version: String,
        platform: String,
        asset: String,
    ) = URI("$DOWNLOADS$version/$asset")

    /** Where an asset holds ty: `ty-<target>.tar.gz` holds `ty-<target>/ty`. */
    fun executableIn(asset: String) = "${asset.removeSuffix(".tar.gz")}/ty"

    fun tool(
        pin: ToolPin = pin(),
        platform: String? = ToolPin.currentPlatform(),
        root: Path = DEFAULT_ROOT,
    ) = ServerTool(
        name = "ty",
        root = root,
        pin = pin,
        platform = platform,
        format = ArchiveFormat.TAR_GZ,
        executableIn = ::executableIn,
        serverArguments = listOf("server"),
        versionArguments = listOf("version"),
        versionPrefix = "ty ",
    )
}
