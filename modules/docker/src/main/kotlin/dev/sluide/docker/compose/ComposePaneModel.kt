package dev.sluide.docker.compose

import dev.sluide.docker.DockerSession
import dev.sluide.docker.EngineState
import dev.sluide.docker.cli.DockerCli
import dev.sluide.docker.events.Scheduler
import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.ContainerState
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import java.nio.file.Path
import java.util.concurrent.CompletionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What [ComposePaneModel] needs of the Compose view: its editor, dialogs, runner and the Docker tool. FX thread. */
internal interface ComposeHost {
    /** Whether the editor has unsaved changes. */
    val isDirty: Boolean

    /** Asks [question] about the unsaved changes. */
    fun askUnsaved(question: String): UnsavedChoice

    /** Saves the editor's text as File → Save does (the host shows errors); `false` if it could not. */
    fun save(): Boolean

    /** Asks [question]; `true` to go on. */
    fun confirm(question: String): Boolean

    /**
     * Starts [job] in the Output with the command line [command] (whose [IllegalArgumentException] the status line
     * shows), in the compose file's folder, with [environment] laid over the terminal's; `true` if it started (not when
     * the question about the running one was answered no, or it failed to start).
     */
    fun start(
        job: ComposeJob,
        command: () -> String,
        environment: Map<String, String>,
    ): Boolean

    /** Shows the container [id] with its logs in the Docker tab. */
    fun showLogs(id: String)

    /** Opens a shell in the container [id] named [name] in a terminal tab. */
    fun openShell(
        id: String,
        name: String,
    )
}

/**
 * The state behind one Compose pane, without controls (ADR 0084, ADR 0119): the [options] of the file (loaded from the
 * [store], saved to it once [debounce] passed after a change, and on [close]), its resolved [config] (read with `docker
 * compose config` through the [session] on attach, after saves, after changes of the project name or environment once
 * [debounce] passed, and when the engine becomes available; results of older loads are dropped), the [services] with
 * their containers, whether the [commandsEnabled] and the commands of the buttons. It subscribes to the [session] until
 * [close]d. FX thread; results of the work the session runs elsewhere come back through [fx], the debounce runs on the
 * [scheduler].
 */
internal class ComposePaneModel(
    private val session: DockerSession,
    private val store: ComposeStore,
    /** The compose file (absolute); it changes when the file is moved. */
    private val file: () -> Path,
    private val projectFolder: () -> Path?,
    /** The docker executable for the command lines. */
    private val executable: () -> String,
    private val host: ComposeHost,
    private val scheduler: Scheduler,
    private val fx: (Runnable) -> Unit = Platform::runLater,
    private val debounce: Duration = 300.milliseconds,
) : AutoCloseable {
    private val optionsWrapper = ReadOnlyObjectWrapper(store.load(file(), projectFolder()))
    private val configWrapper = ReadOnlyObjectWrapper<ConfigLoad>(ConfigLoad.None)
    private val servicesWrapper = ReadOnlyObjectWrapper<List<ServiceState>>(emptyList())
    private val enabledWrapper = ReadOnlyBooleanWrapper(false)
    private val overrideWrapper = ReadOnlyObjectWrapper<Path?>(overrideFor(file()))
    private val engineWrapper = ReadOnlyObjectWrapper(session.state.get())
    private val defaultEnvWrapper = ReadOnlyBooleanWrapper(defaultEnvFile(file()) != null)

    /** What is stored for the file; changed with [update]. */
    val options: ReadOnlyObjectProperty<ComposeOptions> = optionsWrapper.readOnlyProperty

    val config: ReadOnlyObjectProperty<ConfigLoad> = configWrapper.readOnlyProperty

    /**
     * The engine's state, as the session has it, until [close]: the window's session outlives the view, so the pane
     * follows this copy and nothing of a closed view stays reachable from the session.
     */
    val engine: ReadOnlyObjectProperty<EngineState> = engineWrapper.readOnlyProperty

    /** The service names in the order of the file, set by the view from the source ([serviceLines]). */
    val order = SimpleObjectProperty<List<String>>(emptyList())

    /** The rows of the services list: the services in file order with their containers, orphans last. */
    val services: ReadOnlyObjectProperty<List<ServiceState>> = servicesWrapper.readOnlyProperty

    /** Whether the toolbar's commands can run ([commandsEnabled]). */
    val enabled: ReadOnlyBooleanProperty = enabledWrapper.readOnlyProperty

    /** The override file passed besides the file, as of the last load. */
    val override: ReadOnlyObjectProperty<Path?> = overrideWrapper.readOnlyProperty

    /** Whether a `.env` is beside the file, as of the last load. */
    val defaultEnvExists: ReadOnlyBooleanProperty = defaultEnvWrapper.readOnlyProperty

    /** The failure of the last service action, `null` while there is none. */
    val error = SimpleStringProperty()

    private val waitingWrapper = ReadOnlyBooleanWrapper(false)

    /** Whether an Up waits for the engine or the configuration (it runs once that is read, or is dropped). */
    val waitingUp: ReadOnlyBooleanProperty = waitingWrapper.readOnlyProperty

    private var generation = 0
    private var pendingReload: AutoCloseable? = null

    /** Whether the load of the current generation still runs. */
    private var inFlight = false

    /** The options not stored yet, saved when the debounce passes or on [close]. */
    private var pendingStore: AutoCloseable? = null
    private var unstored = false

    /** An Up asked for before the configuration was read, run once it is. */
    private var pendingUp: (() -> Unit)? = null
        set(value) {
            field = value
            waitingWrapper.set(value != null)
        }

    /** What Rerun does: the entry point of the last command that started, asking again what it asks. */
    private var lastRun: (() -> Unit)? = null
    private var closed = false

    private val stateListener = ChangeListener<EngineState> { _, _, state -> engineChanged(state) }
    private val servicesListener = InvalidationListener { updateServices() }
    private val subscription: AutoCloseable

    init {
        session.state.addListener(stateListener)
        session.containers.addListener(servicesListener)
        order.addListener(servicesListener)
        configWrapper.addListener(servicesListener)
        subscription = session.subscribe()
        if (session.state.get() is EngineState.Available && config.get() == ConfigLoad.None) reload()
        updateEnabled()
    }

    private val fileName: String get() = file().fileName.toString()

    /**
     * Changes the options with [transform]; they are stored once [debounce] passed (typing in a field writes once), and
     * the configuration is read again then if they change it.
     */
    fun update(transform: (ComposeOptions) -> ComposeOptions) {
        if (closed) return
        val old = options.get()
        val new = transform(old)
        if (new == old) return
        optionsWrapper.set(new)
        storeLater()
        val changesConfig =
            new.projectName != old.projectName || new.envFiles != old.envFiles || new.variables != old.variables
        if (changesConfig) reloadLater()
    }

    /** The file was saved or read again from disk: reads its configuration again. */
    fun fileSaved() = reload()

    /** The file was moved: its options are stored for the new path, and its configuration read again. */
    fun moved() {
        if (closed) return
        unstored = true
        storeNow()
        reload()
    }

    /** Reads the configuration now (when the engine is available); a load still running is dropped. */
    fun reload() {
        if (closed) return
        pendingReload?.close()
        pendingReload = null
        val load = ++generation
        inFlight = false
        if (session.state.get() !is EngineState.Available) return
        val file = file()
        val override = overrideFor(file)
        overrideWrapper.set(override)
        defaultEnvWrapper.set(defaultEnvFile(file) != null)
        val options = options.get()
        val args =
            try {
                ComposeCommands.baseArgs(file, override, options, allProfiles = true)
            } catch (e: IllegalArgumentException) {
                return loaded(ConfigLoad.Failed(e.message ?: "Invalid options"))
            }
        val environment = ComposeCommands.environment(options)
        val folder = checkNotNull(file.parent)
        if (config.get() !is ConfigLoad.Loaded) loaded(ConfigLoad.Loading)
        inFlight = true
        session
            .submit("compose-config") { cli -> parseComposeConfig(cli.composeConfig(folder, args, environment)) }
            .whenComplete { config, failure ->
                fx {
                    if (closed || load != generation) return@fx
                    inFlight = false
                    loaded(if (config != null) ConfigLoad.Loaded(config) else ConfigLoad.Failed(messageOf(failure)))
                }
            }
    }

    /** Up with the options (detached or attached), asking first what needs asking ([up] flow). */
    fun up(): Unit = runUp(ComposeJob("up", fileName), { up() }) { base() + ComposeCommands.up(options.get()) }

    /** `up -d <service>` with the options (the gutter's ▶ and a row's Up). */
    fun upService(service: String): Unit =
        runUp(ComposeJob("up $service", fileName), { upService(service) }) {
            base() + ComposeCommands.upService(options.get(), service)
        }

    /** Down of the whole project with every profile; with [removeVolumes] after asking. */
    fun down(removeVolumes: Boolean) {
        if (!enabled.get()) return
        val project = (config.get() as? ConfigLoad.Loaded)?.config?.name ?: fileName
        if (removeVolumes && !host.confirm(downVolumesQuestion(project))) return
        val label = if (removeVolumes) "down -v" else "down"
        start(ComposeJob(label, fileName), { down(removeVolumes) }) {
            ComposeCommands.downArgs(file(), overrideFor(file()), options.get(), removeVolumes)
        }
    }

    fun build(): Unit = startEnabled("build", { build() }) { base() + ComposeCommands.build() }

    fun pull(): Unit = startEnabled("pull", { pull() }) { base() + ComposeCommands.pull() }

    fun restart(): Unit = startEnabled("restart", { restart() }) { base() + ComposeCommands.restart() }

    /**
     * Rerun: the last command that started, through its entry point again, so with the current options and the same
     * questions (Down and Remove Volumes asks again, an Up asks about unsaved changes and unresolved variables) and
     * only while the commands are enabled.
     */
    fun rerun() {
        if (!closed) lastRun?.invoke()
    }

    /** Runs [action] of a service's row; Up in the Output, the others directly on its containers. */
    fun perform(
        service: ServiceState,
        action: ServiceAction,
    ) {
        if (closed || action !in serviceActions(service, enabled.get())) return
        when (action) {
            ServiceAction.UP -> {
                upService(service.name)
            }

            ServiceAction.STOP -> {
                act(service, action, ContainerAction.STOP) { it in STOPPABLE }
            }

            ServiceAction.RESTART -> {
                act(service, action, ContainerAction.RESTART) { it == ContainerState.RUNNING }
            }

            ServiceAction.LOGS -> {
                service.containers.firstOrNull()?.let { host.showLogs(it.id) }
            }

            ServiceAction.SHELL -> {
                service.containers.firstOrNull { it.state == ContainerState.RUNNING }?.let {
                    host.openShell(it.id, it.name)
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        storeNow()
        closed = true
        pendingReload?.close()
        pendingUp = null
        session.state.removeListener(stateListener)
        session.containers.removeListener(servicesListener)
        subscription.close()
    }

    private fun base(): List<String> = ComposeCommands.baseArgs(file(), overrideFor(file()), options.get())

    /**
     * Runs an Up: while the engine is being reached or the configuration read (also again after a change of the
     * options or a save), once that is done (if it works out); else asks about unsaved changes, then about unresolved
     * variables, then starts it. After Save and Up it waits for the configuration of the saved file, so that the
     * question about unresolved variables is about that.
     */
    private fun runUp(
        job: ComposeJob,
        again: () -> Unit,
        args: () -> List<String>,
    ) {
        if (closed) return
        val retry = { runUp(job, again, args) }
        if (!enabled.get()) {
            if (waiting()) pendingUp = retry
            return
        }
        if (pendingReload != null || inFlight) {
            pendingUp = retry
            if (pendingReload != null) reload()
            return
        }
        if (host.isDirty) {
            when (host.askUnsaved(unsavedQuestion(fileName))) {
                UnsavedChoice.SAVE_AND_UP -> {
                    if (!host.save()) return
                    pendingUp = retry
                    // Saving reads the configuration again (fileSaved); if it did not, read it now.
                    if (!inFlight) reload()
                    return
                }

                UnsavedChoice.UP_SAVED -> {}

                UnsavedChoice.CANCEL -> {
                    return
                }
            }
        }
        val unresolved = (config.get() as? ConfigLoad.Loaded)?.config?.unresolvedVariables.orEmpty()
        if (unresolved.isNotEmpty() && !host.confirm(unresolvedQuestion(unresolved))) return
        start(job, again, args)
    }

    /** Whether the commands are disabled only until the engine answers and the configuration is read. */
    private fun waiting(): Boolean =
        when (session.state.get()) {
            EngineState.Idle, EngineState.Checking -> true
            is EngineState.Available -> config.get() == ConfigLoad.None || config.get() == ConfigLoad.Loading
            else -> false
        }

    private fun startEnabled(
        verb: String,
        again: () -> Unit,
        args: () -> List<String>,
    ) {
        if (enabled.get()) start(ComposeJob(verb, fileName), again, args)
    }

    /** Starts [job]; if it starts, [again] is what Rerun does. */
    private fun start(
        job: ComposeJob,
        again: () -> Unit,
        args: () -> List<String>,
    ) {
        val environment = ComposeCommands.environment(options.get())
        val started =
            host.start(
                job,
                { ComposeCommands.commandLine(executable(), args(), environment.keys) },
                ComposeCommands.terminalEnvironment(environment),
            )
        if (started) lastRun = again
    }

    private fun act(
        service: ServiceState,
        action: ServiceAction,
        containerAction: ContainerAction,
        applies: (ContainerState) -> Boolean,
    ) {
        val ids = service.containers.filter { applies(it.state) }.map { it.id }
        error.set(null)
        session
            .submit(
                "compose-${action.name.lowercase()}",
            ) { cli: DockerCli -> ids.forEach { cli.act(containerAction, it) } }
            .whenComplete { _, failure ->
                if (failure != null) {
                    fx { if (!closed) error.set("✖ ${action.title} ${service.name} failed: ${messageOf(failure)}") }
                }
            }
    }

    private fun storeLater() {
        unstored = true
        pendingStore?.close()
        pendingStore = scheduler.schedule(debounce) { fx { if (!closed) storeNow() } }
    }

    /** Stores the options now if a change is not stored yet. */
    private fun storeNow() {
        pendingStore?.close()
        pendingStore = null
        if (!unstored || closed) return
        unstored = false
        store.save(file(), projectFolder(), options.get())
    }

    private fun reloadLater() {
        pendingReload?.close()
        pendingReload = scheduler.schedule(debounce) { fx { if (!closed) reload() } }
    }

    private fun loaded(load: ConfigLoad) {
        configWrapper.set(load)
        updateEnabled()
        when (load) {
            is ConfigLoad.Loaded -> {
                runPending()
            }

            is ConfigLoad.Failed -> {
                pendingUp = null
            }

            ConfigLoad.None, ConfigLoad.Loading -> {}
        }
    }

    private fun engineChanged(state: EngineState) {
        if (closed) return
        engineWrapper.set(state)
        updateEnabled()
        when (state) {
            is EngineState.Available -> {
                reload()
            }

            is EngineState.Unavailable, is EngineState.NotFound -> {
                pendingUp = null
            }

            EngineState.Idle, EngineState.Checking -> {}
        }
    }

    private fun runPending() {
        val pending = pendingUp ?: return
        pendingUp = null
        if (enabled.get()) pending()
    }

    private fun updateEnabled() = enabledWrapper.set(commandsEnabled(session.state.get(), config.get()))

    private fun updateServices() {
        val config = (config.get() as? ConfigLoad.Loaded)?.config
        servicesWrapper.set(config?.let { serviceStates(it, session.containers.toList(), order.get()) }.orEmpty())
    }

    private fun messageOf(failure: Throwable?): String {
        val cause = if (failure is CompletionException) failure.cause ?: failure else failure
        return cause?.message?.trim()?.ifEmpty { null } ?: cause?.toString() ?: "Unknown failure"
    }
}
