package dev.sluide.languageservers

import com.google.gson.JsonElement
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [SessionExtension] that records every call as text (`notification language/status {...}`, `progress t1 {...}`,
 * `added /path`, `connected`, `stopped`, `close`) and counts the calls [isDispatched] denies; [failing] makes every call
 * throw after recording it.
 */
internal class RecordingExtension(
    override val notifications: Set<String> = emptySet(),
    private val failing: Boolean = false,
    /** Whether the calling thread runs what the session dispatched to the FX thread. */
    private val isDispatched: () -> Boolean = { true },
) : SessionExtension {
    val events = CopyOnWriteArrayList<String>()

    /** The calls that came outside the session's dispatch. */
    val undispatched = AtomicInteger()

    /** What the session gave it. */
    @Volatile
    lateinit var access: SessionAccess

    /** The window folder it was created for. */
    @Volatile
    var projectFolder: Path? = null

    private fun record(event: String) {
        events += event
        if (!isDispatched()) undispatched.incrementAndGet()
        if (failing) throw IllegalStateException("extension bug")
    }

    override fun onNotification(
        method: String,
        params: JsonElement?,
    ) = record("notification $method $params")

    override fun onProgress(
        token: String,
        value: JsonElement,
    ) = record("progress $token $value")

    override fun onFolderAdded(folder: Path) = record("added $folder")

    override fun onFolderRemoved(folder: Path) = record("removed $folder")

    override fun onConnected() = record("connected")

    override fun onStopped() = record("stopped")

    override fun close() = record("close")
}

/** A spec whose sessions get [extension], which learns its access and window folder. */
internal fun extendedSpec(
    extension: RecordingExtension,
    workspaceFolderMode: WorkspaceFolderMode = WorkspaceFolderMode.WINDOW_FIRST,
    folders: ((file: Path, projectFolder: Path) -> Path?)? = null,
) = TestSpec(
    workspaceFolderMode = workspaceFolderMode,
    folders = folders,
    extension = { projectFolder, access ->
        extension.projectFolder = projectFolder
        extension.access = access
        extension
    },
)
