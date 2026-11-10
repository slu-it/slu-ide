package dev.sluide.servertools

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.ServerInstaller
import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.InstalledTool
import dev.sluide.localmodels.ToolInstaller
import dev.sluide.localmodels.ToolRelease
import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.beans.binding.ObjectBinding
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.value.ObservableValue
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

private val log = System.getLogger(ServerToolInstaller::class.java.name)

/**
 * A language server Thoreau downloads (ADR 0159): Thoreau's download under the [tool]'s root (a [ToolInstaller]: the
 * pinned version on [install], a newer one on [update], the previous one kept for [rollback]), or the executable the
 * user set in [setting], which wins while it is set. A language module's installer bean extends it.
 *
 * The properties, [availability] and the actions belong to the FX thread; [command] may be called from any thread.
 * The installed version is read from disk when the installer is created (on Spring's start-up thread, before the
 * FX thread uses it), so that [command] knows it before anything on the FX thread asked.
 */
open class ServerToolInstaller(
    val tool: ServerTool,
    private val setting: ServerPathSetting,
    private val tools: ToolInstaller,
) : ServerInstaller {
    /** With the real [ToolInstaller] on the tool's root and its smoke test. */
    constructor(tool: ServerTool, setting: ServerPathSetting) :
        this(tool, setting, ToolInstaller(tool.root, tool.smokeTest()::check, tool::taskName))

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var installedExecutable: Path? = null

    /** The executable the command named when the listeners were last told; FX thread. */
    private var lastExecutable: Path?

    /** Where Thoreau's downloads of the tool live. */
    val root: Path get() = tool.root

    /** The version this Thoreau was tested with, which [install] installs. */
    val pinnedVersion: String get() = tool.pin.version

    /** The size of the pinned download for this platform; null when there is no build for it. */
    val pinnedDownloadBytes: Long? get() = tool.pinnedAsset?.bytes

    /** Whether Thoreau has a build of the tool for this platform (a custom path works anywhere). */
    val platformSupported: Boolean get() = tool.pinnedAsset != null

    /** The download's install state: Downloading also while an update runs, Failed until the next action. */
    val state: ReadOnlyObjectProperty<InstallState> get() = tools.state

    /** The version of Thoreau's download in use, null when none is installed. */
    val installed: ReadOnlyObjectProperty<InstalledTool?> get() = tools.current

    /** The version [rollback] goes back to, null when there is none. */
    val previous: ReadOnlyObjectProperty<InstalledTool?> get() = tools.previous

    private val availabilityBinding: ObjectBinding<ServerAvailability>

    /**
     * Whether the server can run, see [availabilityOf]. Follows the custom path, the install state and the installed
     * version; whether the custom file is still executable is checked again on [recheckCustomPath].
     */
    override val availability: ObservableValue<ServerAvailability> get() = availabilityBinding

    override val toolName: String get() = tool.name

    init {
        // An invalidation listener registered before the availability binding: JavaFX tells invalidation listeners
        // first and in order, so the command names the new version before anyone sees the server become ready.
        installedExecutable = tools.current.get()?.executable
        tools.current.addListener(InvalidationListener { installedExecutable = tools.current.get()?.executable })
        lastExecutable = configuredExecutable()
        setting.path.subscribe { _ -> commandMayHaveChanged() }
        tools.current.subscribe { _ -> commandMayHaveChanged() }
        availabilityBinding =
            Bindings.createObjectBinding(
                Callable {
                    val custom = setting.path.get()
                    availabilityOf(
                        custom,
                        customPathProblem(custom),
                        tools.state.get(),
                        tools.current.get(),
                        pinnedDownloadBytes,
                        tool.noBuild,
                    )
                },
                setting.path,
                tools.state,
                tools.current,
            )
    }

    /** Checks again whether the custom executable exists and is executable (the settings page calls it on load). */
    fun recheckCustomPath() = availabilityBinding.invalidate()

    /** Whether [installed] is the version this Thoreau was tested with. */
    fun isPinned(installed: InstalledTool?): Boolean = installed?.version == tool.pin.version

    /** Installs the pinned version; nothing without a build for this platform or while another action runs. */
    override fun install(tasks: BackgroundTasks?) {
        tool.pinnedRelease()?.let { tools.install(it, tasks) }
    }

    /** Installs [release] (a newer version the user asked for) and makes it current; the old one stays as previous. */
    fun update(
        release: ToolRelease,
        tasks: BackgroundTasks?,
    ) = tools.install(release, tasks)

    override fun cancel() = tools.cancel()

    /** Swaps the installed and the previous version. */
    fun rollback() = tools.rollback()

    /** Deletes every downloaded version (the caller asks the user first). */
    fun deleteAll() = tools.deleteAll()

    /**
     * The executable with the tool's server arguments: the custom executable if one is set (null if it is not an
     * executable file), else Thoreau's download (null if none is installed); any thread. The environment is inherited
     * unchanged.
     */
    fun command(): ServerCommand? {
        val custom = setting.currentPath()
        val executable =
            if (custom.isNotEmpty()) {
                custom
                    .takeIf {
                        customPathProblem(it) == null
                    }?.let(Path::of)
            } else {
                installedExecutable
            }
        return executable?.let { ServerCommand(it, tool.serverArguments) }
    }

    override fun onCommandChanged(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** The executable the command names, executable or not: the custom path if set, else the installed one. */
    private fun configuredExecutable(): Path? {
        val custom = setting.path.get()
        return if (custom.isNotEmpty()) pathOrNull(custom) else tools.current.get()?.executable
    }

    private fun commandMayHaveChanged() {
        val executable = configuredExecutable()
        if (executable == lastExecutable) return
        lastExecutable = executable
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "A listener of ${tool.name}'s command failed", e)
            }
        }
    }

    companion object {
        /** Null when [path] (non-empty) names an executable file by an absolute path, else why it does not. */
        fun customPathProblem(path: String): String? {
            if (path.isEmpty()) return null
            val file = pathOrNull(path)
            val usable = file != null && file.isAbsolute && Files.isRegularFile(file) && Files.isExecutable(file)
            return if (usable) null else "$path is not an executable file"
        }

        private fun pathOrNull(path: String): Path? =
            try {
                Path.of(path)
            } catch (_: InvalidPathException) {
                null
            }

        /**
         * Whether the server can run: a set [customPath] decides alone (Failed with its [customProblem]); else an
         * installed version is Ready (also while an update downloads or after one failed), then the download's
         * [state], then Missing with the size of the pinned download, or Failed with [noBuild] without a build for
         * this platform. Only a failed download can be retried; the custom path and the missing build are fixed in
         * Settings.
         */
        fun availabilityOf(
            customPath: String,
            customProblem: String?,
            state: InstallState,
            current: InstalledTool?,
            pinnedBytes: Long?,
            noBuild: String,
        ): ServerAvailability =
            when {
                customPath.isNotEmpty() -> {
                    customProblem?.let { ServerAvailability.Failed(it, canRetry = false) }
                        ?: ServerAvailability.Ready
                }

                current != null -> {
                    ServerAvailability.Ready
                }

                state is InstallState.Downloading -> {
                    ServerAvailability.Installing(state.progress)
                }

                state is InstallState.Failed -> {
                    ServerAvailability.Failed(state.message)
                }

                pinnedBytes != null -> {
                    ServerAvailability.Missing(pinnedBytes)
                }

                else -> {
                    ServerAvailability.Failed(noBuild, canRetry = false)
                }
            }
    }
}
