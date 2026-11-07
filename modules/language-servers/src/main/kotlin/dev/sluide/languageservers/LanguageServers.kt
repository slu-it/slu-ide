package dev.sluide.languageservers

import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.connection.SleepingScheduler
import dev.sluide.languageservers.process.OsServerStarter
import dev.sluide.languageservers.process.ServerStarter
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import java.nio.file.Path

/**
 * The language servers of one window (ADR 0142): one [LanguageServerSession] per spec, started when first used and
 * ended by [close] with the window. Servers serve files inside the project folder only; a window without a folder
 * has none.
 */
class LanguageServers internal constructor(
    /** The languages, in order of precedence for [specFor]. */
    val specs: List<LanguageServerSpec>,
    private val projectFolder: () -> Path?,
    trustGate: TrustGate,
    dispatch: (Runnable) -> Unit,
    starter: ServerStarter,
    scheduler: Scheduler,
    timing: ServerTiming,
    nanoTime: () -> Long,
) : AutoCloseable {
    /**
     * @param projectFolder the window's folder, asked at each start (`null`: no servers).
     * @param trustGate the window's project trust (`ProjectTrust::awaitAllows`), for specs that run project code.
     * @param dispatch hands results, listener calls and states to the FX thread.
     */
    constructor(
        specs: List<LanguageServerSpec>,
        projectFolder: () -> Path?,
        trustGate: TrustGate,
        dispatch: (Runnable) -> Unit = Platform::runLater,
    ) : this(
        specs,
        projectFolder,
        trustGate,
        dispatch,
        OsServerStarter,
        SleepingScheduler,
        ServerTiming(),
        System::nanoTime,
    )

    init {
        require(specs.map { it.id }.toSet().size == specs.size) { "Language server specs need distinct ids" }
    }

    private val sessions =
        specs.associateWith { spec ->
            LanguageServerSession(spec, projectFolder, trustGate, dispatch, starter, scheduler, timing, nanoTime)
        }

    /** The spec that serves [file]: the first that matches, if the file is inside the project folder. */
    fun specFor(file: Path): LanguageServerSpec? {
        val folder = projectFolder()?.toAbsolutePath()?.normalize() ?: return null
        val absolute = file.toAbsolutePath().normalize()
        if (absolute == folder || !absolute.startsWith(folder)) return null
        return specs.firstOrNull { it.matches(absolute) }
    }

    /** The session of [spec], one per window; the server starts with its first use. */
    fun session(spec: LanguageServerSpec): LanguageServerSession =
        requireNotNull(sessions[spec]) { "${spec.id} is not a spec of these language servers" }

    /** The state of [spec]'s server; changes on the FX thread. */
    fun state(spec: LanguageServerSpec): ReadOnlyObjectProperty<ServerState> = session(spec).state

    /** Stops [spec]'s server and starts it again. */
    fun restart(spec: LanguageServerSpec) = session(spec).restart()

    /** Ends every server (`shutdown`, `exit`, kill after the timeouts) off the calling thread. */
    override fun close() = sessions.values.forEach { it.close() }
}
