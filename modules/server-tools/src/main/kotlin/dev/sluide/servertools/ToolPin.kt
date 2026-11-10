package dev.sluide.servertools

import dev.sluide.localmodels.Checksum
import java.net.URI
import java.util.Locale
import java.util.Properties

/** One platform's file of a server tool's release: its name, where it is downloaded from, its size and checksum. */
data class ToolAsset(
    val name: String,
    val url: URI,
    val bytes: Long,
    val checksum: Checksum,
)

/**
 * The release of a server tool this Thoreau was tested with (a `<tool>-release.properties` resource of the language
 * module, ADR 0159): its [version] and the asset of each Thoreau platform ([PLATFORMS]) that has a build.
 */
class ToolPin(
    val version: String,
    private val assets: Map<String, ToolAsset>,
) {
    /** The asset of [platform] (a [PLATFORMS] key), or null if there is no build for it. */
    fun asset(platform: String?): ToolAsset? = platform?.let(assets::get)

    companion object {
        /** What Thoreau calls its release platforms (ADR 0003), the keys of a pin resource. */
        val PLATFORMS = listOf("linux-x64", "linux-aarch64", "mac-aarch64")

        /**
         * Reads the pin from [resource] next to [owner]; a missing or broken one is a build error. [url] gives where
         * an asset is downloaded from, by version, platform and asset name.
         */
        fun load(
            owner: Class<*>,
            resource: String,
            url: (version: String, platform: String, asset: String) -> URI,
        ): ToolPin {
            val properties = Properties()
            val stream = checkNotNull(owner.getResourceAsStream(resource)) { "$resource is missing" }
            stream.use(properties::load)
            return parse(properties, url)
        }

        /**
         * The pin in [properties]: `version`, and per platform `<platform>.asset`, `.bytes` and the checksum, either
         * `.sha256` (hex, lower-cased) or `.integrity` (an SRI value such as `sha512-<base64>`), not both; a missing
         * or ill-formed value is refused.
         */
        fun parse(
            properties: Properties,
            url: (version: String, platform: String, asset: String) -> URI,
        ): ToolPin {
            val version = requireNotNull(properties.getProperty("version")?.trim()) { "pin without a version" }
            val assets =
                PLATFORMS
                    .mapNotNull { platform ->
                        val name = properties.getProperty("$platform.asset")?.trim() ?: return@mapNotNull null
                        val bytes =
                            requireNotNull(properties.getProperty("$platform.bytes")?.trim()?.toLongOrNull()) {
                                "pin without the size of $name"
                            }
                        val sha256 = properties.getProperty("$platform.sha256")?.trim()
                        val integrity = properties.getProperty("$platform.integrity")?.trim()
                        require(sha256 == null || integrity == null) {
                            "pin with both a sha256 and an integrity of $name"
                        }
                        val checksum =
                            sha256?.let { Checksum.Sha256(it.lowercase(Locale.ROOT)) }
                                ?: integrity?.let {
                                    requireNotNull(Checksum.sri(it)) { "pin with an unreadable integrity of $name" }
                                }
                        requireNotNull(checksum) { "pin without the checksum of $name" }
                        require(checksum.wellFormed) {
                            "pin with a checksum of $name that is not a ${checksum.algorithm.javaName} value"
                        }
                        platform to ToolAsset(name, url(version, platform, name), bytes, checksum)
                    }.toMap()
            return ToolPin(version, assets)
        }

        /**
         * The Thoreau platform of a JVM with these `os.name` and `os.arch` values, or null for one Thoreau has no
         * builds for (macOS on Intel, Windows, other architectures).
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
    }
}
