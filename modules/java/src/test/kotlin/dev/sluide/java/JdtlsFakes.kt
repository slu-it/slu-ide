package dev.sluide.java

import dev.sluide.languageservers.ServerCommand
import dev.sluide.localmodels.ModelDownloader
import dev.sluide.localmodels.ModelFile
import dev.sluide.localmodels.ToolInstallException
import dev.sluide.localmodels.ToolInstaller
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ServerToolFakes
import dev.sluide.settings.SettingsStore
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.writeBytes

/** The size of the fake Lombok jar. */
internal const val FAKE_LOMBOK_BYTES = 100L

/**
 * A [JdtlsInstaller] over real [ToolInstaller]s in [dir]: jdtls through [ServerToolFakes] (the downloads write a tar.gz
 * holding a shell script as `bin/jdtls`), Lombok through a download that writes a jar of [FAKE_LOMBOK_BYTES] bytes
 * (or fails with [lombokFailure]); the work of both runs when the test calls [runQueued].
 */
internal class JdtlsFakes(
    private val dir: Path,
    lombokVersion: String = "1.18.48",
) {
    // The fake archive holds only bin/jdtls, so the launch function is one that accepts it (JdtlsToolTest has the real one).
    val tool =
        JdtlsTool.tool(platform = "linux-aarch64", root = dir.resolve("tools/jdtls")).let { real ->
            ServerTool(
                real.name,
                real.root,
                real.pin,
                real.platform,
                real.format,
                real.executableIn,
                real.serverArguments,
                real.versionArguments,
                real.versionPrefix,
                real.commandName,
                launch = { ServerCommand(it) },
            )
        }
    val settings by lazy { JavaSettings(SettingsStore(dir.resolve("settings.properties"))) }
    val server = ServerToolFakes(tool)

    /** The smoke folder the installs clean around their smoke test, as the real installer's default does. */
    val smokeRoot: Path = dir.resolve("smoke")

    /** What an install's smoke test answers: null for "works". It runs inside the clean-ups of [smokeRoot]. */
    var smoke: (Path) -> String? = { null }

    init {
        server.smoke = JdtlsTool.smokeTest(tool, smokeRoot) { smoke(it) }
    }

    val lombokPin =
        LombokPin(
            lombokVersion,
            URI("https://repo1.maven.org/maven2/org/projectlombok/lombok/$lombokVersion/lombok-$lombokVersion.jar"),
            FAKE_LOMBOK_BYTES,
            "a".repeat(64),
        )
    val lombokRoot: Path = dir.resolve("tools/lombok")

    /** The Lombok files the installs downloaded, in order. */
    val lombokDownloads = mutableListOf<ModelFile>()

    /** Why the next Lombok downloads fail; null: they work. */
    var lombokFailure: String? = null

    private val lombokQueue = ArrayDeque<Runnable>()

    private val lombokDownloader =
        object : ModelDownloader() {
            override fun download(
                files: List<ModelFile>,
                folder: Path,
                progress: (Double) -> Unit,
                cancelled: () -> Boolean,
            ) {
                lombokDownloads += files
                lombokFailure?.let { throw ToolInstallException(it) }
                progress(0.5)
                val file = files.single()
                folder.resolve(file.path).writeBytes(ByteArray(file.bytes.toInt()))
            }
        }

    fun lombokTools() =
        ToolInstaller(
            lombokRoot,
            LombokTool.smokeTest(lombokPin),
            LombokTool::taskName,
            lombokDownloader,
            { lombokQueue += it },
            { it.run() },
        )

    fun installer() =
        JdtlsInstaller(settings, tool, tools = server.tools(), lombokTools = lombokTools(), lombokPin = lombokPin)

    /** Runs the queued work of both installers, also the work queued meanwhile (Lombok after jdtls). */
    fun runQueued() {
        while (server.hasQueuedWork || lombokQueue.isNotEmpty()) {
            server.runQueued()
            while (lombokQueue.isNotEmpty()) lombokQueue.removeFirst().run()
        }
    }

    val hasQueuedLombokWork: Boolean get() = lombokQueue.isNotEmpty()
}
