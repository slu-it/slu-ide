package dev.sluide.typescript

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ToolPin
import java.net.URI
import java.nio.file.Path

/** The npm registry, where the platform packages of TypeScript 7 are downloaded and looked up. */
internal const val NPM_REGISTRY = "https://registry.npmjs.org/"

/**
 * The npm package of each Thoreau platform: `@typescript/typescript-<package>`. The native server is not in the
 * `typescript` package but in these, one per operating system and architecture.
 */
internal val PLATFORM_PACKAGES =
    mapOf(
        "linux-x64" to "linux-x64",
        "linux-aarch64" to "linux-arm64",
        "mac-aarch64" to "darwin-arm64",
    )

/** The tarball of [version] of the platform package [pkg] (`linux-arm64`), the same URL as `dist.tarball`. */
internal fun tarballUrl(
    pkg: String,
    version: String,
) = URI("${NPM_REGISTRY}@typescript/typescript-$pkg/-/${tarballName(pkg, version)}")

/** The file name of that tarball; it carries the platform and the version. */
internal fun tarballName(
    pkg: String,
    version: String,
) = "typescript-$pkg-$version.tgz"

/**
 * The TypeScript 7 language server as a server tool (ADR 0159): the platform package from the npm registry,
 * downloaded to `~/.sluide/tools/typescript`, pinned in `typescript-release.properties` (version, and per platform the
 * tarball, its size and its SRI integrity), run as `tsc --lsp --stdio`, checked with `tsc --version` printing
 * `Version …`. The whole package is unpacked: the `lib.*.d.ts` files beside `tsc` are needed at run time.
 */
internal object TypeScriptTool {
    val DEFAULT_ROOT: Path = Path.of(System.getProperty("user.home"), ".sluide", "tools", "typescript")

    /** The pin of the module's resource; a missing or broken one is a build error. */
    fun pin(): ToolPin = ToolPin.load(TypeScriptTool::class.java, "typescript-release.properties", ::downloadUrl)

    /** Where [asset] of [version] is downloaded from: the registry's tarball of the platform's package. */
    fun downloadUrl(
        version: String,
        platform: String,
        asset: String,
    ): URI {
        val pkg = checkNotNull(PLATFORM_PACKAGES[platform]) { "no TypeScript package for $platform" }
        check(asset == tarballName(pkg, version)) { "unexpected tarball $asset" }
        return tarballUrl(pkg, version)
    }

    fun tool(
        pin: ToolPin = pin(),
        platform: String? = ToolPin.currentPlatform(),
        root: Path = DEFAULT_ROOT,
    ) = ServerTool(
        name = "TypeScript",
        root = root,
        pin = pin,
        platform = platform,
        format = ArchiveFormat.TAR_GZ,
        // npm tarballs keep everything under `package/`.
        executableIn = { _ -> "package/lib/tsc" },
        serverArguments = listOf("--lsp", "--stdio"),
        versionArguments = listOf("--version"),
        versionPrefix = "Version ",
        commandName = "tsc",
    )
}
