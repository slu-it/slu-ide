package dev.sluide.servertools

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import dev.sluide.settings.SettingsStore
import java.net.URI
import java.nio.file.Path

/** The asset of the fake pin, on the fake platform `linux-aarch64`. */
internal const val FAKE_ASSET = "acme-ls-aarch64-linux.tar.gz"

/**
 * A made-up server tool, `acme-ls`, so that every text and argument the shared code shows comes from the tool: pinned
 * at [pinned] with [FAKE_ASSET] (13.7 MB) for `linux-aarch64`, run as `acme-ls serve --stdio`, checked with
 * `acme-ls --version` printing `acme-ls …`.
 */
internal fun acmeTool(
    dir: Path,
    platform: String? = "linux-aarch64",
    pinned: String = "0.0.99",
    name: String = "acme-ls",
    commandName: String = name,
) = ServerTool(
    name = name,
    root = dir.resolve("tools/acme-ls"),
    pin =
        ToolPin(
            pinned,
            mapOf(
                "linux-aarch64" to
                    ToolAsset(
                        FAKE_ASSET,
                        URI("https://example.com/acme/$pinned/$FAKE_ASSET"),
                        13_659_057,
                        Checksum.Sha256("a".repeat(64)),
                    ),
            ),
        ),
    platform = platform,
    format = ArchiveFormat.TAR_GZ,
    executableIn = { "${it.removeSuffix(".tar.gz")}/acme-ls" },
    serverArguments = listOf("serve", "--stdio"),
    versionArguments = listOf("--version"),
    versionPrefix = "acme-ls ",
    commandName = commandName,
)

/** The custom path setting of the fake tool, `acme.ls.path` in a settings file in [dir]. */
internal fun acmeSetting(dir: Path) =
    ServerPathSetting(SettingsStore(dir.resolve("settings.properties")), "acme.ls.path")

/** An update source whose latest release the test sets. */
internal class FakeSource(
    var answer: () -> LatestRelease,
) : UpdateSource {
    override val name = "Acme"

    override fun latest(): LatestRelease = answer()
}

/** A release of [version] whose asset for `linux-aarch64` is [FAKE_ASSET] with 1000 bytes; other platforms: none. */
internal fun acmeRelease(
    version: String,
    prerelease: Boolean = false,
): LatestRelease.Release =
    LatestRelease.Release(version, URI("https://example.com/acme/releases/$version"), prerelease) { platform ->
        if (platform != "linux-aarch64") {
            PlatformAsset.Missing
        } else {
            val url = URI("https://example.com/acme/$version/$FAKE_ASSET")
            PlatformAsset.Found(ToolAsset(FAKE_ASSET, url, 1000, Checksum.Sha256("c".repeat(64))))
        }
    }
