package dev.sluide.gradle

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.gradle.run.StopScheduler
import dev.sluide.gradle.tasks.TaskModel
import dev.sluide.gradle.tasks.extractInitScript
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.loginShellFinds
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private val log = System.getLogger(GradleSession::class.java.name)

/**
 * A loaded task list of a build: [key] is the real path of its root folder; [stale] once a build file under it was saved
 * after loading.
 */
internal class LoadedBuild(
    val key: Path,
    val model: TaskModel,
    val stale: Boolean,
)

/**
 * What the Gradle views of one IDE window share (ADR 0085): whether a global `gradle` exists (asked once, off the FX
 * thread), the task lists loaded per build, which views of the same build all show, and whether build files changed
 * since. Views of build files find it through `FileViewHost.windowService`. Loads happen only when a view asks
 * (opening it, Refresh); a saved build file only marks its build stale.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class GradleSession internal constructor(
    internal val projectTrust: ProjectTrust,
    internal val backgroundTasks: BackgroundTasks,
    internal val terminals: Terminals,
    private val globalCheck: () -> Boolean,
) : AutoCloseable {
    @Autowired
    constructor(projectTrust: ProjectTrust, backgroundTasks: BackgroundTasks, terminals: Terminals) :
        this(projectTrust, backgroundTasks, terminals, System.getenv())

    /** For tests: the global check runs `command -v gradle` in the login shell of [environment]. */
    internal constructor(
        projectTrust: ProjectTrust,
        backgroundTasks: BackgroundTasks,
        terminals: Terminals,
        environment: Map<String, String>,
    ) : this(projectTrust, backgroundTasks, terminals, { loginShellFinds("gradle", environment) })

    /** Opens the trust dialog of the window (Review…); set by the window. */
    var reviewTrust: () -> Unit = {}

    /** Whether the login shell finds `gradle`: asked once per window, by the first caller, which waits. */
    private val global: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { globalCheck() }

    /** Detection, model lookups and reading task lists, off the FX thread. */
    private val executor: ExecutorService = NamedThreads.single("Gradle")

    /** Fires the delayed kills of stopped runs. */
    private val stopTimer = NamedThreads.scheduled("Gradle stop")

    /** Stop escalations schedule their kill here. */
    internal val stopScheduler =
        StopScheduler { delay, action ->
            val future = stopTimer.schedule(action, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            return@StopScheduler { future.cancel(false) }
        }

    private val lock = Any()
    private val builds = mutableMapOf<Path, LoadedBuild>()

    /** The load keys ([claimLoad]) of the loads running now, and of those whose last load failed. */
    private val runningLoads = mutableSetOf<Path>()
    private val failedLoads = mutableSetOf<Path>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var tempFolder: Path? = null
    private var initScript: Path? = null

    @Volatile private var closed = false

    /** Whether the login shell finds `gradle`; blocks the first time, so never on the FX thread. */
    internal fun globalAvailable(): Boolean = global

    /** Runs [work] on the session's thread; dropped once closed. */
    internal fun execute(work: () -> Unit) {
        if (closed) return
        executor.execute(work)
    }

    /**
     * The loaded build one of whose projects has [buildFile] as its build file, or whose root folder holds it; `null`
     * if none. Compares real paths, so it reads the file system: not on the FX thread.
     */
    internal fun modelFor(buildFile: Path): LoadedBuild? {
        val file = realPath(buildFile)
        val loaded = synchronized(lock) { builds.values.toList() }
        return loaded.firstOrNull { build ->
            build.key == file.parent ||
                build.model.projects.any { project -> project.buildFile?.let(::realPath) == file }
        }
    }

    /** The build loaded under [key] now, e.g. after another view refreshed it. */
    internal fun build(key: Path): LoadedBuild? = synchronized(lock) { builds[key] }

    /**
     * Keeps [model] as the task list of its build (by root folder), replacing an older one, not stale; tells every view
     * ([onChange]). Reads the file system (the root's real path). Views store what they load; public, with [onChange],
     * so that the window's tests can follow what saving a file does to a loaded build.
     */
    fun store(model: TaskModel) {
        keep(model)
    }

    /** [store], returning what is kept. */
    internal fun keep(model: TaskModel): LoadedBuild {
        val build = LoadedBuild(realPath(model.rootFolder), model, stale = false)
        synchronized(lock) { builds[build.key] = build }
        changed()
        return build
    }

    /**
     * A load of the build Gradle finds from [key] (the wrapper's folder, or the project root for the global `gradle`)
     * starts, unless one runs already: `false` then, and the caller waits for [loadEnded] through [onChange] instead of
     * loading the same build again.
     */
    internal fun claimLoad(key: Path): Boolean =
        synchronized(lock) {
            runningLoads.add(key).also { claimed -> if (claimed) failedLoads.remove(key) }
        }

    /** Whether a load of [key] runs now. */
    internal fun isLoading(key: Path): Boolean = synchronized(lock) { key in runningLoads }

    /** Whether the last load of [key] failed; views that waited for it say so instead of loading again. */
    internal fun loadFailed(key: Path): Boolean = synchronized(lock) { key in failedLoads }

    /** The load of [key] ended, [failed] or not (a stored model comes first); tells every view. */
    internal fun loadEnded(
        key: Path,
        failed: Boolean,
    ) {
        synchronized(lock) {
            runningLoads.remove(key)
            if (failed) failedLoads.add(key)
        }
        changed()
    }

    /**
     * A file was saved: if it is a build file ([isBuildFile]) under the root folder of a loaded build, that build is
     * stale until it is loaded again ([markStale] on the session's thread). Nothing reloads by itself.
     */
    fun fileSaved(path: Path) {
        if (isBuildFile(path)) execute { markStale(path) }
    }

    /** [fileSaved] on the calling thread: reads the file system. */
    internal fun markStale(path: Path) {
        if (!isBuildFile(path)) return
        val file = realPath(path)
        val marked =
            synchronized(lock) {
                val hit = builds.values.filter { !it.stale && file.startsWith(it.key) }
                hit.forEach { builds[it.key] = LoadedBuild(it.key, it.model, stale = true) }
                hit.isNotEmpty()
            }
        if (marked) changed()
    }

    /**
     * Calls [listener] after every [store], every build marked stale ([fileSaved]) and every end of a load, on the
     * thread that made the change, until the returned handle is closed.
     */
    fun onChange(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** The bundled init script, extracted once into a temporary folder that [close] deletes; not on the FX thread. */
    internal fun initScript(): Path =
        synchronized(lock) {
            initScript ?: extractInitScript(folder()).also { initScript = it }
        }

    /** A new temporary file for a task list, in the folder [close] deletes; not on the FX thread. */
    internal fun taskListFile(): Path = Files.createTempFile(synchronized(lock) { folder() }, "tasks", ".json")

    private fun folder(): Path = tempFolder ?: Files.createTempDirectory("sluide-gradle").also { tempFolder = it }

    private fun changed() = listeners.forEach { it() }

    override fun close() {
        closed = true
        executor.shutdownNow()
        stopTimer.shutdownNow()
        listeners.clear()
        val folder = synchronized(lock) { tempFolder.also { tempFolder = null } } ?: return
        try {
            Files.walk(folder).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "Could not delete $folder: ${e.message}")
        }
    }
}

/**
 * Whether saving [path] can change what Gradle lists: build and settings scripts (Groovy or Kotlin), `gradle.properties`
 * and version catalogs (`*.versions.toml`).
 */
internal fun isBuildFile(path: Path): Boolean {
    val name = path.fileName?.toString() ?: return false
    return name == "gradle.properties" ||
        name.endsWith(".gradle") ||
        name.endsWith(".gradle.kts") ||
        name.endsWith(".versions.toml")
}

/** [path] with links resolved, as far as it exists (the rest appended), absolute and normalized. */
internal fun realPath(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    var existing: Path? = absolute
    while (existing != null) {
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute))
        } catch (_: IOException) {
            existing = existing.parent
        }
    }
    return absolute
}
