package dev.sluide.filewatcher

import javafx.application.Platform
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = System.getLogger(FileWatcher::class.java.name)

/**
 * Reports changes in watched directories (ADR 0006). A directory is watched without its subfolders; listeners get
 * the paths of entries that were created, modified or deleted, or the directory itself if events were lost.
 *
 * Events of one directory are collected until it has been quiet for [quietPeriod]; then each listener is called once
 * with all changed paths (each path once), via [dispatch]. The application uses the JavaFX thread; tests may pass
 * their own dispatcher and a shorter quiet period.
 *
 * The bean has an explicit name because Spring Boot's SSL auto-configuration already defines one called "fileWatcher".
 */
@Component("sluideFileWatcher")
class FileWatcher(
    private val dispatch: (Runnable) -> Unit,
    private val quietPeriod: Duration,
) : AutoCloseable {
    @Autowired
    constructor() : this(Platform::runLater, 200.milliseconds)

    private class Subscription(
        val listener: (List<Path>) -> Unit,
    ) {
        @Volatile var active = true
    }

    private class Watched(
        val directory: Path,
        val key: WatchKey,
    ) {
        val subscriptions = mutableListOf<Subscription>()
        val pending = LinkedHashSet<Path>()
        var lastEventNanos = 0L
        var valid = true
    }

    private val lock = Any()
    private val byDirectory = HashMap<Path, Watched>()
    private val byKey = HashMap<WatchKey, Watched>()
    private var service: WatchService? = null
    private var closed = false

    /** Calls [listener] with the changed paths in [directory] until the returned handle is closed. Never throws. */
    fun watch(
        directory: Path,
        listener: (List<Path>) -> Unit,
    ): AutoCloseable {
        val dir = directory.toAbsolutePath().normalize()
        val subscription = Subscription(listener)
        synchronized(lock) {
            if (closed) return AutoCloseable {}
            val watched = byDirectory[dir] ?: register(dir) ?: return AutoCloseable {}
            watched.subscriptions += subscription
        }
        return AutoCloseable { unsubscribe(dir, subscription) }
    }

    override fun close() {
        val service =
            synchronized(lock) {
                closed = true
                byDirectory.clear()
                byKey.clear()
                service
            }
        // Wakes the watcher thread, which then ends.
        service?.close()
    }

    private fun register(dir: Path): Watched? {
        val service = service ?: startService() ?: return null
        val key =
            try {
                dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            } catch (e: IOException) {
                log.log(System.Logger.Level.DEBUG, "Cannot watch $dir", e)
                return null
            } catch (e: SecurityException) {
                log.log(System.Logger.Level.DEBUG, "Cannot watch $dir", e)
                return null
            }
        return Watched(dir, key).also {
            byDirectory[dir] = it
            byKey[key] = it
        }
    }

    private fun startService(): WatchService? =
        try {
            FileSystems.getDefault().newWatchService().also { service ->
                this.service = service
                Thread({ run(service) }, "sluide-file-watcher").apply { isDaemon = true }.start()
            }
        } catch (e: IOException) {
            log.log(System.Logger.Level.WARNING, "File watching is unavailable", e)
            null
        }

    private fun unsubscribe(
        dir: Path,
        subscription: Subscription,
    ) {
        subscription.active = false
        synchronized(lock) {
            val watched = byDirectory[dir] ?: return
            watched.subscriptions -= subscription
            if (watched.subscriptions.isEmpty()) forget(watched)
        }
    }

    private fun forget(watched: Watched) {
        watched.key.cancel()
        byDirectory.remove(watched.directory)
        byKey.remove(watched.key)
    }

    private fun run(service: WatchService) {
        try {
            while (true) {
                val wait = synchronized(lock) { nanosUntilNextFlush() }
                val key = if (wait == null) service.take() else service.poll(wait, TimeUnit.NANOSECONDS)
                if (key != null) collect(key)
                flushQuietDirectories()
            }
        } catch (_: ClosedWatchServiceException) {
            // closed: stop watching
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun collect(key: WatchKey) {
        synchronized(lock) {
            val events = key.pollEvents()
            val watched = byKey[key] ?: return
            for (event in events) {
                // OVERFLOW events have no entry: report the directory, meaning "anything may have changed".
                val changed = (event.context() as? Path)?.let(watched.directory::resolve) ?: watched.directory
                watched.pending.add(changed)
            }
            watched.lastEventNanos = System.nanoTime()
            // An invalid key means the directory itself is gone; deliver what is pending, then drop it.
            if (!key.reset()) watched.valid = false
        }
    }

    /** Nanoseconds until the next directory with pending events has been quiet long enough; `null` if none. */
    private fun nanosUntilNextFlush(): Long? =
        byDirectory.values
            .filter { it.pending.isNotEmpty() }
            .minOfOrNull { it.lastEventNanos + quietPeriod.inWholeNanoseconds - System.nanoTime() }
            ?.coerceAtLeast(0)

    private fun flushQuietDirectories() {
        val batches = mutableListOf<Pair<List<Path>, List<Subscription>>>()
        synchronized(lock) {
            val now = System.nanoTime()
            for (watched in byDirectory.values.toList()) {
                val quiet = now - watched.lastEventNanos >= quietPeriod.inWholeNanoseconds
                if (watched.pending.isNotEmpty() && quiet) {
                    batches += watched.pending.toList() to watched.subscriptions.toList()
                    watched.pending.clear()
                }
                if (!watched.valid && watched.pending.isEmpty()) forget(watched)
            }
        }
        for ((paths, subscriptions) in batches) {
            dispatch(
                Runnable {
                    for (subscription in subscriptions) {
                        if (subscription.active) notify(subscription, paths)
                    }
                },
            )
        }
    }

    private fun notify(
        subscription: Subscription,
        paths: List<Path>,
    ) {
        try {
            subscription.listener(paths)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "File change listener failed for $paths", e)
        }
    }
}
