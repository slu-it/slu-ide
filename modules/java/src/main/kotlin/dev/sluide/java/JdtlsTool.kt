package dev.sluide.java

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ToolPin
import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where jdtls's milestones live: `<version>/<asset>`, no redirect. */
internal const val MILESTONES = "https://download.eclipse.org/jdtls/milestones/"

private val log = System.getLogger(JdtlsTool::class.java.name)

private const val LOCK_POLL_MILLIS = 100L

private val VERSION = Regex("[0-9]{1,6}(\\.[0-9]{1,6}){1,3}")
private val ASSET = Regex("jdt-language-server-[0-9.]{1,40}-[0-9]{12}\\.tar\\.gz")

/** The URL of [asset] of the milestone [version]. */
internal fun milestoneUrl(
    version: String,
    asset: String,
): URI {
    require(VERSION.matches(version)) { "unexpected jdtls version $version" }
    require(ASSET.matches(asset)) { "unexpected jdtls archive $asset" }
    return URI("$MILESTONES$version/$asset")
}

/**
 * jdtls (Eclipse JDT Language Server) as a server tool (ADR 0179, ADR 0159): the milestone archive from
 * `download.eclipse.org`, the same for every platform, pinned in `jdtls-release.properties`. Its marker "executable"
 * is `bin/jdtls`, a Python launcher Thoreau never runs; the server runs as Thoreau's own `java` ([JdtlsLaunch]), so
 * there is no version command and the smoke test only does the handshake.
 */
internal object JdtlsTool {
    val DEFAULT_ROOT: Path = Path.of(System.getProperty("user.home"), ".sluide", "tools", "jdtls")

    /** The install's smoke test runs jdtls with its `config/` and `data/` below this folder, in the user's own home. */
    val DEFAULT_SMOKE_ROOT: Path = Path.of(System.getProperty("user.home"), ".sluide", "jdtls", "smoke")

    /**
     * The smoke test of an install of [tool] ([check], the tool's own by default): what the installer and its tests
     * run, see [smokeCheck].
     */
    fun smokeTest(
        tool: ServerTool,
        smokeRoot: Path,
        check: (Path) -> String? = tool.smokeTest()::check,
    ): (Path) -> String? = smokeCheck(smokeRoot, check = check)

    /**
     * [check] (the smoke test) between two clean-ups of [smokeRoot]: the folder is deleted before it runs, so that
     * nothing planted there (a `config.ini` in the `-configuration` folder decides what jdtls loads) is ever used, and
     * after it ends, also by a failure, so that the OSGi cache does not stay. Links are never followed; a [smokeRoot]
     * that is a link is refused. The whole run holds a [FileLock] on `<smokeRoot>.lock` beside the folder (two
     * Thoreau processes would delete each other's folders), waiting at most [lockTimeout]. A failed clean-up before
     * fails the run; one after it is only logged, so that a jdtls still exiting does not fail a passed check.
     */
    fun smokeCheck(
        smokeRoot: Path,
        lockTimeout: Duration = 60.seconds,
        check: (Path) -> String?,
    ): (Path) -> String? =
        { executable ->
            if (Files.isSymbolicLink(smokeRoot)) {
                "$smokeRoot is a link; remove it so that jdtls can be checked"
            } else {
                locked(smokeRoot.resolveSibling("${smokeRoot.fileName}.lock"), lockTimeout) {
                    try {
                        deleteTree(smokeRoot)
                        check(executable)
                    } finally {
                        try {
                            deleteTree(smokeRoot)
                        } catch (e: IOException) {
                            log.log(System.Logger.Level.WARNING, "Cannot delete $smokeRoot: ${e.message}")
                        }
                    }
                }
            }
        }

    /** Runs [action] holding the lock of [lockFile]; a message instead if it cannot be had in [timeout]. */
    private fun locked(
        lockFile: Path,
        timeout: Duration,
        action: () -> String?,
    ): String? {
        val channel =
            try {
                lockFile.parent?.let(Files::createDirectories)
                FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (e: IOException) {
                return "Cannot lock $lockFile: ${e.message}"
            }
        channel.use {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            while (true) {
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null // this process holds it already
                    }
                if (lock != null) return lock.use { action() }
                if (System.nanoTime() >= deadline) return "another Thoreau is checking jdtls; try again in a moment"
                Thread.sleep(LOCK_POLL_MILLIS)
            }
        }
    }

    /** The pin of the module's resource; a missing or broken one is a build error. */
    fun pin(): ToolPin = ToolPin.load(JdtlsTool::class.java, "jdtls-release.properties", ::downloadUrl)

    /** Where [asset] of [version] is downloaded from; [platform] makes no difference. */
    @Suppress("UNUSED_PARAMETER")
    fun downloadUrl(
        version: String,
        platform: String,
        asset: String,
    ): URI = milestoneUrl(version, asset)

    /**
     * The tool; [maxMemoryMb] is read on every launch, [smokeRoot] holds the data and the (writable) configuration
     * folder of the install's smoke test (the launch function has no window, so it names no workspace; it builds
     * paths only, see [smokeCheck] for their clean-up).
     */
    fun tool(
        pin: ToolPin = pin(),
        platform: String? = ToolPin.currentPlatform(),
        root: Path = DEFAULT_ROOT,
        maxMemoryMb: () -> Int = { DEFAULT_MAX_MEMORY_MB },
        smokeRoot: Path = DEFAULT_SMOKE_ROOT,
    ) = ServerTool(
        name = "jdtls",
        root = root,
        pin = pin,
        platform = platform,
        format = ArchiveFormat.TAR_GZ,
        executableIn = { _ -> "bin/jdtls" },
        serverArguments = emptyList(),
        versionArguments = null,
        versionPrefix = "",
        commandName = "jdtls",
        launch = { executable ->
            JdtlsLaunch.home(executable)?.let { home ->
                JdtlsLaunch.configFolder(home)?.let { config ->
                    val writable = smokeRoot.resolve("config")
                    val data = smokeRoot.resolve("data")
                    JdtlsLaunch.command(home, writable, data, maxMemoryMb(), null)?.let { command ->
                        // jdtls writes its OSGi cache into -configuration: a throwaway one, with the install's own
                        // config as the read-only shared one, keeps the unpacked install (which the workspaces copy
                        // the config from) as it was.
                        val jar = command.arguments.indexOf("-jar")
                        val shared = listOf("-Dosgi.sharedConfiguration.area=$config", "-Dosgi.checkConfiguration=true")
                        command.copy(
                            arguments = command.arguments.take(jar) + shared + command.arguments.drop(jar),
                        )
                    }
                }
            }
        },
    )
}

/** The maximum memory jdtls gets when the setting is unset or unusable. */
internal const val DEFAULT_MAX_MEMORY_MB = 2048

/** Deletes [path] and everything below it; links are deleted, not followed. */
private fun deleteTree(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                exc: IOException?,
            ): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
