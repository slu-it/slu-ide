package dev.sluide.java

import com.google.gson.JsonElement
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.SessionAccess
import dev.sluide.languageservers.SessionExtension
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the spec remembers per window folder; written on the folder worker and the outbound thread, read on FX. */
internal class JavaWindowState {
    /** Whether this window restarted its server for Lombok. */
    val lombokRestarted = AtomicBoolean()

    /** Whether Kotlin sources sit next to a file, as `workspaceFolder` found when it last asked about it. */
    val kotlinNear = ConcurrentHashMap<Path, Boolean>()

    /** The build tool of each build root `workspaceFolder` named. */
    val tools = ConcurrentHashMap<Path, BuildTool>()

    /** The file a root was found for by `workspaceFolder`, as the root the banner and status line look up. */
    val fileRoots = ConcurrentHashMap<Path, Path>()

    /** Where each announced build root's import stands. */
    val imports = ConcurrentHashMap<Path, ImportState>()

    /** The JDK the settings were last resolved with, with its source. */
    @Volatile var jdk: ProjectJdk? = null

    /** Whether the next command of this window's server first deletes jdtls's data folder (Clean Java Workspace). */
    val cleanPending = AtomicBoolean()
}

/**
 * The Java part of one window's jdtls session (ADR 0179): the workspace folder in use, the one restart for Lombok, the
 * JDK change and the build file changes jdtls does not watch. Every call comes on the FX thread; slow work runs on
 * [worker] and comes back through [dispatch].
 */
class JavaSession internal constructor(
    windowFolder: Path,
    private val access: SessionAccess,
    private val state: JavaWindowState,
    private val settings: () -> Map<String, Any?>,
    private val lombokJar: () -> Path?,
    private val usesLombok: (Path) -> Boolean,
    private val projectSettings: JavaProjectSettings,
    private val workspaceLease: AutoCloseable,
    private val worker: ExecutorService,
    private val scheduler: ScheduledExecutorService,
    private val dispatch: (Runnable) -> Unit,
    private val debounceMillis: Long,
    internal val imports: JavaImports,
    private val onClose: () -> Unit,
) : SessionExtension {
    /** The window folder, absolute and normalized. */
    val windowFolder: Path = windowFolder.toAbsolutePath().normalize()

    /** `language/eventNotification` carries jdtls's import events, `language/status` its acknowledgement of each folder change. */
    override val notifications: Set<String> = setOf("language/status", "language/eventNotification")

    override fun onNotification(
        method: String,
        params: JsonElement?,
    ) {
        when (method) {
            "language/eventNotification" -> imports.onEvent(params)
            "language/status" -> imports.onStatus(params)
        }
    }

    override fun onProgress(
        token: String,
        value: JsonElement,
    ) = imports.onProgress(token, value)

    private val closed = AtomicBoolean()
    private val roots = LinkedHashSet<Path>()
    private val pending = ConcurrentHashMap<Path, ScheduledFuture<*>>()
    private val notifiedPending = ConcurrentHashMap.newKeySet<Path>()
    private val jdkSubscription: AutoCloseable =
        projectSettings.onChange { folder -> if (folder == this.windowFolder) jdkChanged() }

    override fun onFolderAdded(folder: Path) {
        if (closed.get()) return
        val root = folder.toAbsolutePath().normalize()
        roots.add(root)
        imports.onFolderAdded(root)
        checkLombok(root)
    }

    /** A Retry or a JDK change refreshed [folder]: a new import, in one notification; its build may now use Lombok. */
    override fun onFolderRefreshed(folder: Path) {
        if (closed.get()) return
        val root = folder.toAbsolutePath().normalize()
        imports.onFolderRefreshed(root)
        checkLombok(root)
    }

    /** Restarts the server once with the Lombok agent when [root]'s build uses Lombok; the check runs on [worker]. */
    private fun checkLombok(root: Path) {
        if (state.lombokRestarted.get()) return
        worker.execute {
            if (usesLombok(root) && lombokJar() != null) dispatch(Runnable { restartForLombok() })
        }
    }

    override fun onFolderRemoved(folder: Path) {
        val root = folder.toAbsolutePath().normalize()
        roots.remove(root)
        imports.onFolderRemoved(root)
    }

    override fun onConnected() = imports.onConnected()

    override fun onStopped() {
        imports.onStopped()
        // The next server gets every root announced again.
        roots.clear()
    }

    /** The one restart per window that gives jdtls the Lombok agent; the next command carries it. */
    private fun restartForLombok() {
        if (closed.get()) return
        if (state.lombokRestarted.compareAndSet(false, true)) access.restart()
    }

    /** Restarts the server, as the user's "Restart Java Language Server"; nothing once the session is closed. */
    fun restart() {
        if (!closed.get()) access.restart()
    }

    private fun jdkChanged() {
        worker.execute {
            val resolved = settings()
            dispatch(
                Runnable {
                    if (closed.get()) return@Runnable
                    access.configurationChanged(resolved)
                    // A failed root has no project for jdtls to update: the new JDK needs a fresh import.
                    imports.failedRoots().forEach(access::refreshFolder)
                },
            )
        }
    }

    /**
     * A change of [path], a `settings.gradle(.kts)` or `gradle-wrapper.properties`, which jdtls does not watch: one
     * `java/projectConfigurationUpdate` for the build root that holds it, once a debounce passed without another
     * change there.
     */
    fun buildFileChanged(path: Path) {
        if (closed.get()) return
        val file = path.toAbsolutePath().normalize()
        val root = roots.filter { file.startsWith(it) }.maxByOrNull { it.nameCount } ?: return
        val notified = file.fileName?.toString() in NOTIFIED_BUILD_FILES
        if (notified) notifiedPending.add(root)
        pending.remove(root)?.cancel(false)
        pending[root] = scheduler.schedule({ fire(root) }, debounceMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * A failed root is imported again; a root that imports or imported gets the update of a build file jdtls misses.
     * The scheduler looks for the build file; which of the two is decided on the FX thread, where the state is current
     * (the user may have clicked Retry Import meanwhile).
     */
    private fun fire(root: Path) {
        val notified = notifiedPending.remove(root)
        val buildFile = if (notified) BUILD_FILES.map(root::resolve).firstOrNull { Files.isRegularFile(it) } else null
        dispatch(
            Runnable {
                if (closed.get()) return@Runnable
                if (state.imports[root] is ImportState.Failed) {
                    access.refreshFolder(root)
                } else if (buildFile != null) {
                    access.notify("java/projectConfigurationUpdate", mapOf("uri" to LspUris.of(buildFile)))
                }
            },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jdkSubscription.close()
        imports.close()
        workspaceLease.close()
        worker.shutdownNow()
        scheduler.shutdownNow()
        onClose()
    }

    private companion object {
        /** The files jdtls does not watch, whose change the session tells it about. */
        val NOTIFIED_BUILD_FILES = setOf("settings.gradle", "settings.gradle.kts", "gradle-wrapper.properties")

        /** The build file whose URI jdtls takes to reimport a build: the settings file first. */
        val BUILD_FILES = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
    }
}
