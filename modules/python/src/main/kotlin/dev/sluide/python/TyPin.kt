package dev.sluide.python

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.ToolRelease
import java.net.URI
import java.util.Locale
import java.util.Properties

/** The release assets of astral-sh/ty: `https://github.com/astral-sh/ty/releases/download/<version>/<asset>`. */
private const val DOWNLOADS = "https://github.com/astral-sh/ty/releases/download/"

/** What Thoreau calls its release platforms (ADR 0003), the keys of `ty-release.properties`. */
internal val TY_PLATFORMS = listOf("linux-x64", "linux-aarch64", "mac-aarch64")

/** One platform's file of a ty release: the asset's name, size and SHA-256 (lower-case hex). */
internal data class TyAsset(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * The ty release this Thoreau was tested with (`ty-release.properties`, ADR 0144): its [version] and the asset of each
 * Thoreau platform that has a build. The asset names carry no version, so a newer release has the same names.
 */
internal class TyPin(
    val version: String,
    private val assets: Map<String, TyAsset>,
) {
    /** The asset of [platform] (a [TY_PLATFORMS] key), or null if there is no build for it. */
    fun asset(platform: String?): TyAsset? = platform?.let(assets::get)

    /** The pinned release for [platform], or null if there is no build for it. */
    fun release(platform: String?): ToolRelease? = asset(platform)?.let { release(version, it) }

    companion object {
        /** Reads the pin from the module's resource; a missing or broken one is a build error. */
        fun load(): TyPin {
            val properties = Properties()
            val stream =
                checkNotNull(TyPin::class.java.getResourceAsStream("ty-release.properties")) {
                    "ty-release.properties is missing"
                }
            stream.use(properties::load)
            return parse(properties)
        }

        /** The pin in [properties]: `version`, and per platform `<platform>.asset`, `.bytes` and `.sha256`. */
        fun parse(properties: Properties): TyPin {
            val version = requireNotNull(properties.getProperty("version")?.trim()) { "ty pin without a version" }
            val assets =
                TY_PLATFORMS
                    .mapNotNull { platform ->
                        val name = properties.getProperty("$platform.asset")?.trim() ?: return@mapNotNull null
                        val bytes =
                            requireNotNull(properties.getProperty("$platform.bytes")?.trim()?.toLongOrNull()) {
                                "ty pin without the size of $name"
                            }
                        val sha256 =
                            requireNotNull(properties.getProperty("$platform.sha256")?.trim()) {
                                "ty pin without the checksum of $name"
                            }
                        platform to TyAsset(name, bytes, sha256.lowercase(Locale.ROOT))
                    }.toMap()
            return TyPin(version, assets)
        }

        /**
         * The Thoreau platform of a JVM with these `os.name` and `os.arch` values, or null for one without a ty build
         * Thoreau knows (macOS on Intel, Windows, other architectures).
         */
        fun platform(
            osName: String,
            osArch: String,
        ): String? {
            val os = osName.lowercase(Locale.ROOT)
            val arch =
                when (osArch.lowercase(Locale.ROOT)) {
                    "amd64", "x86_64" -> "x64"
                    "aarch64", "arm64" -> "aarch64"
                    else -> return null
                }
            return when {
                os.startsWith("linux") -> "linux-$arch"
                os.startsWith("mac") && arch == "aarch64" -> "mac-aarch64"
                else -> null
            }
        }

        /** The platform of this JVM, see [platform]. */
        fun currentPlatform(): String? = platform(System.getProperty("os.name"), System.getProperty("os.arch"))

        /** The release of [version] with [asset], downloaded from the GitHub release. */
        fun release(
            version: String,
            asset: TyAsset,
            url: URI = URI.create("$DOWNLOADS$version/${asset.name}"),
        ) = ToolRelease(version, url, asset.bytes, asset.sha256, ArchiveFormat.TAR_GZ, executableIn(asset.name))

        /** Where an asset holds ty: `ty-<target>.tar.gz` holds `ty-<target>/ty`. */
        fun executableIn(asset: String) = "${asset.removeSuffix(".tar.gz")}/ty"
    }
}
