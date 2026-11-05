package dev.sluide.diagnostics

import jdk.jfr.Configuration
import jdk.jfr.Recording
import java.io.IOException
import java.io.UncheckedIOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.management.ObjectName
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val log = System.getLogger(FlightRecorderBuffer::class.java.name)

/**
 * Events JFR's `default` configuration records that can hold secrets, so the buffer leaves them out: environment
 * variables (tokens), system properties, the JVM's arguments (every `-D`, also from `JAVA_TOOL_OPTIONS`), the command
 * lines of the processes the IDE starts (`git ls-remote https://user:token@…`) and of all processes on the machine.
 */
internal val DISABLED_EVENTS =
    listOf(
        "jdk.InitialEnvironmentVariable",
        "jdk.InitialSystemProperty",
        "jdk.JVMInformation",
        "jdk.ProcessStart",
        "jdk.SystemProcess",
    )

/** JFR names the repository folder of a process `<yyyy_MM_dd_HH_mm_ss>_<pid>`. */
private val PROCESS_REPOSITORY = Regex("""\d{4}(?:_\d{2}){5}_(\d+)""")

/**
 * The always-on JFR ring buffer (ADR 0127): JFR's `default` configuration, kept on disk for the last [maxAge] or
 * [maxSize], whichever is less, without the [DISABLED_EVENTS]. On disk means in [repository], made owner-only, if
 * given (else JFR's own in the temp folder); there, folders of processes that have ended and were not modified for
 * [staleAfter] (left by a crash) are deleted at the start. Never throws: a failure is one WARNING and [unavailable]
 * says why there is no recording.
 */
internal class FlightRecorderBuffer(
    private val repository: Path? = null,
    val maxAge: Duration = Duration.ofMinutes(2),
    private val maxSize: Long = 50L * 1024 * 1024,
    private val staleAfter: Duration = Duration.ofHours(24),
    /** Runs `JFR.configure repositorypath=<folder>` and returns its output. */
    private val configure: (Path) -> String? = ::jfrConfigure,
    /** Receives every WARNING; tests replace it to capture without a logging backend. */
    private val warn: (message: String, error: Throwable?) -> Unit = { message, error ->
        if (error == null) {
            log.log(System.Logger.Level.WARNING, message)
        } else {
            log.log(System.Logger.Level.WARNING, message, error)
        }
    },
) {
    private var recording: Recording? = null
    private var failed: String? = null

    /** Why [dump] gives no recording now: not started, failed to start, stopped. Null while it records. */
    @get:Synchronized
    val unavailable: String?
        get() = if (recording != null) null else failed ?: "the recorder is not running"

    /** Starts recording; any thread, takes a few hundred milliseconds. Does nothing if it records or failed before. */
    @Synchronized
    fun start() {
        if (recording != null || failed != null) return
        repository?.let(::useRepository)
        var started: Recording? = null
        try {
            started =
                Recording(Configuration.getConfiguration("default")).apply {
                    name = "Thoreau ring buffer"
                    isToDisk = true
                    maxAge = this@FlightRecorderBuffer.maxAge
                    maxSize = this@FlightRecorderBuffer.maxSize
                    DISABLED_EVENTS.forEach { disable(it) }
                    start()
                }
            recording = started
        } catch (e: Exception) {
            notStarted(started, e)
        } catch (e: LinkageError) {
            // jdk.jfr is missing from the runtime image.
            notStarted(started, e)
        }
    }

    private fun notStarted(
        started: Recording?,
        error: Throwable,
    ) {
        try {
            started?.close()
        } catch (_: Exception) {
            // It did not start; nothing to keep.
        }
        failed = "JFR could not start: ${error.message ?: error.javaClass.simpleName}"
        warn("The JFR ring buffer could not start; captures have no recording", error)
    }

    /**
     * Makes [folder] owner-only, deletes stale process folders in it and tells JFR to keep its chunks there, through
     * the `JFR.configure` diagnostic command (there is no API for it). A failure is a WARNING: JFR then keeps its
     * default repository.
     */
    private fun useRepository(folder: Path) {
        try {
            Files.createDirectories(folder.parent, *ownerOnly())
            Files.createDirectories(folder, *ownerOnly())
            restrictToOwner(folder)
            deleteStale(folder)
            // The command reports a path it cannot use in its output, not with an exception.
            val output = configure(folder)?.trim().orEmpty()
            if (!output.startsWith("Repository path:")) {
                warn("JFR keeps its chunks in its default folder: $folder could not be used: $output", null)
            }
        } catch (e: Exception) {
            warn("JFR keeps its chunks in its default folder: $folder could not be used", e)
        }
    }

    /** Deletes the folders of processes that have ended and were not modified for [staleAfter]. */
    private fun deleteStale(folder: Path) {
        val before = Instant.now().minus(staleAfter)
        for (entry in folder.listDirectoryEntries()) {
            val pid =
                PROCESS_REPOSITORY
                    .matchEntire(entry.name)
                    ?.groupValues
                    ?.get(1)
                    ?.toLongOrNull() ?: continue
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
            if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) continue
            if (Files.getLastModifiedTime(entry).toInstant().isAfter(before)) continue
            try {
                deleteRecursively(entry)
            } catch (e: IOException) {
                warn("Stale JFR folder $entry could not be deleted", e)
            } catch (e: UncheckedIOException) {
                warn("Stale JFR folder $entry could not be deleted", e)
            }
        }
    }

    /** Writes what the buffer holds to [target]; null when it does not record or writing failed (WARNING). */
    @Synchronized
    fun dump(target: Path): Path? {
        val current = recording ?: return null
        return try {
            current.dump(target)
            target
        } catch (e: Exception) {
            warn("The JFR ring buffer could not be written to $target", e)
            null
        }
    }

    /** Stops and discards the buffer; it does not start again, also if [start] comes later. */
    @Synchronized
    fun stop() {
        failed = "the recorder was stopped"
        val current = recording ?: return
        recording = null
        try {
            current.close()
        } catch (e: Exception) {
            warn("The JFR ring buffer could not be closed", e)
        }
    }
}

/** Tells JFR to keep its chunks in [folder] (`JFR.configure repositorypath=`, there is no API); returns its output. */
private fun jfrConfigure(folder: Path): String? =
    ManagementFactory.getPlatformMBeanServer().invoke(
        ObjectName("com.sun.management:type=DiagnosticCommand"),
        "jfrConfigure",
        arrayOf<Any>(arrayOf("repositorypath=${folder.toAbsolutePath()}")),
        arrayOf(Array<String>::class.java.name),
    ) as? String
