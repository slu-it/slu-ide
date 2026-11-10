package dev.sluide.typescript

import dev.sluide.localmodels.Checksum
import dev.sluide.localmodels.ToolRelease
import dev.sluide.servertools.ServerToolFakes
import dev.sluide.servertools.ToolAsset
import dev.sluide.servertools.ToolPin
import dev.sluide.settings.SettingsStore
import java.nio.file.Path

/** The asset of the fake pin, on the fake platform `linux-aarch64`. */
internal const val FAKE_ASSET = "typescript-linux-arm64-7.0.2.tgz"

/** The integrity of linux-arm64 7.0.2, also in the pin resource. */
internal const val ARM64_INTEGRITY =
    "sha512-Qh4eU4/y3yDjnfjjyPYihMj5/ODIlmt+Bzu17OI+fiSRDW57QmU5SiN63exPRNJPKUzcc1INa1NXdrJ+MqHjUQ=="

/**
 * A [TypeScriptInstaller] over a real [dev.sluide.localmodels.ToolInstaller] in [dir] ([ServerToolFakes]: the downloads
 * write a tar.gz holding a shell script as tsc, the work runs when the test calls [runQueued]), with a pin of 7.0.2.
 */
internal class TypeScriptFakes(
    dir: Path,
) {
    val pin =
        ToolPin(
            "7.0.2",
            mapOf(
                "linux-aarch64" to
                    ToolAsset(
                        FAKE_ASSET,
                        TypeScriptTool.downloadUrl("7.0.2", "linux-aarch64", FAKE_ASSET),
                        8_900_516,
                        checkNotNull(Checksum.sri(ARM64_INTEGRITY)),
                    ),
            ),
        )
    val tool = TypeScriptTool.tool(pin, "linux-aarch64", dir.resolve("tools/typescript"))
    val root: Path get() = tool.root
    val settings by lazy { TypeScriptSettings(SettingsStore(dir.resolve("settings.properties"))) }
    val server = ServerToolFakes(tool)

    fun installer() = TypeScriptInstaller(settings, tool, server.tools())

    fun release(version: String): ToolRelease = server.release(version)

    fun runQueued() = server.runQueued()
}
