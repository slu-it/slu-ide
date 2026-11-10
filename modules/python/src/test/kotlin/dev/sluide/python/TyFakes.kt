package dev.sluide.python

import dev.sluide.localmodels.Checksum
import dev.sluide.localmodels.ToolRelease
import dev.sluide.servertools.ServerToolFakes
import dev.sluide.servertools.ToolAsset
import dev.sluide.servertools.ToolPin
import dev.sluide.settings.SettingsStore
import java.nio.file.Path

/** The asset of the fake pin, on the fake platform `linux-aarch64`. */
internal const val FAKE_ASSET = "ty-aarch64-unknown-linux-musl.tar.gz"

/**
 * A [TyInstaller] over a real [dev.sluide.localmodels.ToolInstaller] in [dir] ([ServerToolFakes]: the downloads write
 * a tar.gz holding a shell script as ty, the work runs when the test calls [runQueued]), with a pin of 0.0.99.
 */
internal class TyFakes(
    dir: Path,
) {
    val pin =
        ToolPin(
            "0.0.99",
            mapOf(
                "linux-aarch64" to
                    ToolAsset(
                        FAKE_ASSET,
                        TyTool.downloadUrl("0.0.99", "linux-aarch64", FAKE_ASSET),
                        13_659_057,
                        Checksum.Sha256("a".repeat(64)),
                    ),
            ),
        )
    val tool = TyTool.tool(pin, "linux-aarch64", dir.resolve("tools/ty"))
    val root: Path get() = tool.root
    val settings by lazy { PythonSettings(SettingsStore(dir.resolve("settings.properties"))) }
    val server = ServerToolFakes(tool)

    fun installer() = TyInstaller(settings, tool, server.tools())

    fun release(version: String): ToolRelease = server.release(version)

    fun runQueued() = server.runQueued()
}
