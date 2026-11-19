package dev.sluide.languageservers.process

import java.util.concurrent.ConcurrentHashMap

/**
 * The server processes of all windows that still run, for one JVM shutdown hook that kills them: window beans are not
 * destroyed on SIGTERM (`ProjectWindows`), and a server outliving Thoreau would keep its memory (ADR 0142).
 */
internal object LiveServerProcesses {
    private val live = ConcurrentHashMap.newKeySet<ProcessHandle>()

    private var hook: Thread? = null

    /** Adds [process]; the first call installs the hook. */
    fun add(process: ProcessHandle) {
        installHook()
        live.add(process)
    }

    fun remove(process: ProcessHandle) {
        live.remove(process)
    }

    /** The shutdown hook once the first process was added: for tests. */
    @Synchronized
    internal fun hookThread(): Thread? = hook

    /** The processes listed now: for tests. */
    fun all(): Set<ProcessHandle> = live.toSet()

    /** Kills every listed process with its descendants; what the hook does. */
    fun destroyAll() {
        live.toList().forEach { process ->
            destroyTree(process)
            live.remove(process)
        }
    }

    @Synchronized
    private fun installHook() {
        if (hook != null) return
        val thread = Thread(::destroyAll, "sluide-language-servers-shutdown")
        Runtime.getRuntime().addShutdownHook(thread)
        hook = thread
    }
}

/** Kills [process] and its descendants, the descendants first: once the parent is gone they are no longer listed. */
internal fun destroyTree(process: ProcessHandle) {
    val descendants = process.descendants().toList()
    descendants.forEach { it.destroyForcibly() }
    process.destroyForcibly()
}
