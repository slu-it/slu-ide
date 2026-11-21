package dev.sluide.jshell

import dev.sluide.java.Jdk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/** A `.jsh` file's view, for code outside the module (completion, Evaluate Selection in JShell). FX thread. */
interface JShellFileView {
    /** Run All: restarts the session and evaluates the whole text. */
    fun runAll()

    /**
     * Completion at [caret] (document offset) in the editor's current text; starts the session if there is none.
     * Completes exceptionally when no JDK with JShell is found, when the JDK is not looked up yet (a view that was
     * just opened: the caller shows no items then) or when the session can't start. Any thread may wait on it.
     */
    fun complete(caret: Int): CompletableFuture<Completions>
}

/** The window's JShell services, registered by `ide` in the window services (`FileViewHost.windowService`). */
class JShellWindow(
    val jdks: JShellJdks,
    /** The installed JDKs with JShell, newest first (Jdks.installed + Jdks.hasJShell). Blocks: worker thread. */
    val installedJdks: () -> List<Jdk>,
    /** Opens Project Settings → Java of the window's folder. FX thread. */
    val chooseProjectJdk: () -> Unit,
    /** Starts a session; tests replace it. Blocks: worker thread. */
    val startSession: (Jdk, (SessionEvent) -> Unit) -> JShellSession = { jdk, onEvent ->
        JShellSession.start(jdk, onEvent)
    },
    /** Whether [chooseProjectJdk] can do anything (a window without a project folder has no settings to open). */
    val canChooseProjectJdk: () -> Boolean = { true },
) {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** How many listeners are subscribed (for tests: a view that is closed must be gone from them). */
    internal val listenerCount: Int get() = listeners.size

    /** Listeners told, on the FX thread, that the project's JDK setting changed (`ide` calls [projectJdkChanged] there). */
    fun onProjectJdkChanged(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Called by `ide` on the FX thread when the project's JDK setting changed. */
    fun projectJdkChanged() {
        listeners.forEach { it() }
    }
}
