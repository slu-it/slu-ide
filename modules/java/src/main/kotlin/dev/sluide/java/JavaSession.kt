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

    /** The JDK the settings were last resolved with, with its source. */
    @Volatile var jdk: ProjectJdk? = null
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
    private val onClose: () -> Unit,
) : SessionExtension {
    /** The window folder, absolute and normalized. */
    val windowFolder: Path = windowFolder.toAbsolutePath().normalize()

    /**
     * `language/status` and `language/eventNotification`: the import tracking of the next step (ADR 0179) takes them;
     * this class only keeps them routed to [onNotification].
     */
    override val notifications: Set<String> = setOf("language/status", "language/eventNotification")

    override fun onNotification(
        method: String,
        params: JsonElement?,
    ) {}

    private val closed = AtomicBoolean()
    private val roots = LinkedHashSet<Path>()
    private val pending = ConcurrentHashMap<Path, ScheduledFuture<*>>()
    private val jdkSubscription: AutoCloseable =
        projectSettings.onChange { folder -> if (folder == this.windowFolder) jdkChanged() }

    override fun onFolderAdded(folder: Path) {
        if (closed.get()) return
        val root = folder.toAbsolutePath().normalize()
        roots.add(root)
        if (state.lombokRestarted.get()) return
        worker.execute {
            if (usesLombok(root) && lombokJar() != null) dispatch(Runnable { restartForLombok() })
        }
    }

    override fun onFolderRemoved(folder: Path) {
        roots.remove(folder.toAbsolutePath().normalize())
    }

    override fun onStopped() {
        // The next server gets every root announced again.
        roots.clear()
    }

    /** The one restart per window that gives jdtls the Lombok agent; the next command carries it. */
    private fun restartForLombok() {
        if (closed.get()) return
        if (state.lombokRestarted.compareAndSet(false, true)) access.restart()
    }

    private fun jdkChanged() {
        worker.execute {
            val resolved = settings()
            dispatch(Runnable { if (!closed.get()) access.configurationChanged(resolved) })
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
        pending.remove(root)?.cancel(false)
        pending[root] = scheduler.schedule({ sendUpdate(root) }, debounceMillis, TimeUnit.MILLISECONDS)
    }

    private fun sendUpdate(root: Path) {
        val buildFile =
            BUILD_FILES.map(root::resolve).firstOrNull { Files.isRegularFile(it) } ?: return
        val params = mapOf("uri" to LspUris.of(buildFile))
        dispatch(Runnable { if (!closed.get()) access.notify("java/projectConfigurationUpdate", params) })
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jdkSubscription.close()
        workspaceLease.close()
        worker.shutdownNow()
        scheduler.shutdownNow()
        onClose()
    }

    private companion object {
        /** The build file whose URI jdtls takes to reimport a build: the settings file first. */
        val BUILD_FILES = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
    }
}
