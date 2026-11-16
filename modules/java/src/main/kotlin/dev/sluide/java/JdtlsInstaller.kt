package dev.sluide.java

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.languageservers.ServerCommand
import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.ToolInstaller
import dev.sluide.localmodels.ToolRelease
import dev.sluide.servertools.ServerTool
import dev.sluide.servertools.ServerToolInstaller
import dev.sluide.servertools.compareVersions
import javafx.beans.InvalidationListener
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private val log = System.getLogger(JdtlsInstaller::class.java.name)

/**
 * jdtls for Java code intelligence (ADR 0179, ADR 0159): Thoreau's download under `~/.sluide/tools/jdtls` or the
 * `bin/jdtls` the user set in [JavaSettings], and the pinned Lombok jar beside it. Install and Update install Lombok
 * after jdtls worked; Install of a jdtls that is there and at least at its pin installs only Lombok, which is what a
 * failed Lombok install or a newer Lombok pin needs ([needsInstall], [statusNote] read the disk, not the last
 * failure). Delete removes both. Command listeners also hear of a change of the maximum memory: the command changes,
 * the server restarts. The smoke test of an install runs between two clean-ups of [smokeRoot].
 */
@Component
class JdtlsInstaller internal constructor(
    private val settings: JavaSettings,
    tool: ServerTool,
    smokeRoot: Path = JdtlsTool.DEFAULT_SMOKE_ROOT,
    tools: ToolInstaller =
        ToolInstaller(tool.root, JdtlsTool.smokeTest(tool, smokeRoot), tool::taskName),
    private val lombokTools: ToolInstaller =
        ToolInstaller(LombokTool.DEFAULT_ROOT, LombokTool.smokeTest(), LombokTool::taskName),
    private val lombokPin: LombokPin = LombokTool.PIN,
) : ServerToolInstaller(tool, settings, tools) {
    @Autowired
    constructor(settings: JavaSettings) : this(
        settings,
        JdtlsTool.tool(maxMemoryMb = settings::currentMaxMemoryMb),
    )

    @Volatile private var lombokExecutable: Path? = lombokTools.current.get()?.executable

    private val noteWrapper = ReadOnlyObjectWrapper<String?>()

    /** Whether Lombok is to be installed when the running jdtls install ends well; FX thread. */
    private var lombokPending = false
    private var pendingTasks: BackgroundTasks? = null
    private var deleteLombokWhenDone = false
    private var lombokMissing = false

    private val commandListeners = CopyOnWriteArrayList<() -> Unit>()
    private var batching = false
    private var changedInBatch = false

    init {
        // Before the properties' other listeners, so that lombokJar() is current when the jar's version shows.
        lombokTools.current.addListener(
            InvalidationListener { lombokExecutable = lombokTools.current.get()?.executable },
        )
        installed.addListener { _, _, _ -> refreshNote() }
        lombokTools.state.addListener { _, _, state -> lombokStateChanged(state) }
        refreshNote()
        state.subscribe { state ->
            if (lombokPending && state !is InstallState.Downloading) {
                lombokPending = false
                if (state is InstallState.Installed) installLombok(pendingTasks)
            }
        }
        // One listener for the path (the base class) and the memory, so that a batch can hold both back.
        super.onCommandChanged { commandChanged() }
        settings.maxMemoryMb.addListener { _, _, _ -> commandChanged() }
    }

    /** The install banner reads "Code intelligence for Java needs the Java language server (…)". */
    override val toolName: String get() = "the Java language server (jdtls)"

    /**
     * What the settings page appends to "Installed: jdtls …": Lombok's install running, its last failure, or that it
     * is not installed (read from the disk when the installer is created and when Lombok or jdtls change); null when
     * nothing is wrong or no jdtls is installed.
     */
    override val statusNote: ReadOnlyObjectProperty<String?> get() = noteWrapper.readOnlyProperty

    /** The installed Lombok jar, null if there is none; any thread. */
    fun lombokJar(): Path? = lombokExecutable?.takeIf { Files.isRegularFile(it) }

    /**
     * For jdtls this is only the smoke and availability command (a throwaway configuration and data folder, no Lombok):
     * the Java language-server spec builds the real one per window with [JdtlsLaunch.command] from [executable].
     */
    override fun command(): ServerCommand? = super.command()

    /** Whether Lombok is missing (or not at the pin) beside an installed jdtls and no install of it runs. */
    override fun needsInstall(): Boolean = lombokMissing && lombokTools.state.get() !is InstallState.Downloading

    /**
     * Runs [block] and tells the command listeners once afterwards if the command changed meanwhile, however many
     * changes it made (one Apply that changes the path and the memory restarts the server once). FX thread.
     */
    fun <T> batchCommandChanges(block: () -> T): T {
        if (batching) return block()
        batching = true
        try {
            return block()
        } finally {
            batching = false
            if (changedInBatch) {
                changedInBatch = false
                notifyCommandListeners()
            }
        }
    }

    override fun install(tasks: BackgroundTasks?) {
        if (onlyLombokIsMissing()) {
            installLombok(tasks)
        } else {
            expectLombok(tasks)
            super.install(tasks)
        }
    }

    override fun update(
        release: ToolRelease,
        tasks: BackgroundTasks?,
    ) {
        expectLombok(tasks)
        super.update(release, tasks)
    }

    override fun cancel() {
        lombokPending = false
        super.cancel()
    }

    override fun deleteAll() {
        super.deleteAll()
        if (lombokTools.state.get() is InstallState.Downloading) {
            // A busy installer ignores a delete: cancel the install and delete what is there once it has ended.
            deleteLombokWhenDone = true
            lombokTools.cancel()
        } else {
            lombokTools.deleteAll()
        }
    }

    override fun onCommandChanged(listener: () -> Unit): AutoCloseable {
        commandListeners += listener
        return AutoCloseable { commandListeners -= listener }
    }

    private fun commandChanged() {
        if (batching) changedInBatch = true else notifyCommandListeners()
    }

    private fun notifyCommandListeners() {
        for (listener in commandListeners) {
            try {
                listener()
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "A listener of jdtls's command failed", e)
            }
        }
    }

    /** A jdtls that works and is at least at the pin stays; Install then has only Lombok to do. */
    private fun onlyLombokIsMissing(): Boolean {
        val current = installed.get() ?: return false
        return installedProblem() == null && compareVersions(current.version, pinnedVersion) >= 0
    }

    private fun expectLombok(tasks: BackgroundTasks?) {
        lombokPending = true
        pendingTasks = tasks
    }

    private fun installLombok(tasks: BackgroundTasks?) {
        if (lombokUpToDate()) return
        lombokTools.install(lombokPin.release(), tasks)
    }

    private fun lombokUpToDate() = lombokTools.current.get()?.version == lombokPin.version && lombokJar() != null

    private fun lombokStateChanged(state: InstallState) {
        if (deleteLombokWhenDone && state !is InstallState.Downloading) {
            deleteLombokWhenDone = false
            lombokTools.deleteAll()
        }
        refreshNote()
    }

    private fun refreshNote() {
        val lombokState = lombokTools.state.get()
        lombokMissing = installed.get() != null && !lombokUpToDate()
        noteWrapper.set(
            when {
                installed.get() == null -> null
                lombokState is InstallState.Downloading -> "Installing Lombok…"
                lombokState is InstallState.Failed -> "Lombok could not be installed: ${lombokState.message}"
                lombokMissing -> "Lombok is not installed"
                else -> null
            },
        )
    }
}
