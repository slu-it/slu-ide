package dev.sluide.diagnostics

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.nio.file.Path

/** Reads threads, CPU, GC and heap from the JVM's management beans. */
internal class ManagementThreadSource : ThreadSource {
    private val threads = ManagementFactory.getThreadMXBean()

    override fun cpuTimes(): Map<Long, Long> {
        if (!threads.isThreadCpuTimeSupported) return emptyMap()
        if (!threads.isThreadCpuTimeEnabled) {
            try {
                threads.isThreadCpuTimeEnabled = true
            } catch (_: UnsupportedOperationException) {
                return emptyMap()
            }
        }
        val ids = threads.allThreadIds
        // The batch call of the JDK's own bean reads all threads at once instead of one call per thread.
        val times =
            (threads as? com.sun.management.ThreadMXBean)?.getThreadCpuTime(ids)
                ?: LongArray(ids.size) { threads.getThreadCpuTime(ids[it]) }
        return buildMap { ids.forEachIndexed { index, id -> if (times[index] >= 0) put(id, times[index]) } }
    }

    override fun dump(depth: Int): List<ThreadSnapshot> =
        threads.dumpAllThreads(false, false, depth).filterNotNull().map { it.snapshot() }

    override fun processCpuNanos(): Long? =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.processCpuTime
            ?.takeIf { it >= 0 }

    override fun gcCounts(): List<GcCount> =
        ManagementFactory.getGarbageCollectorMXBeans().map {
            GcCount(it.name, it.collectionCount.coerceAtLeast(0), it.collectionTime.coerceAtLeast(0))
        }

    override fun heap(): Heap =
        ManagementFactory.getMemoryMXBean().heapMemoryUsage.let { Heap(it.used, it.committed, it.max) }

    override fun deadlocked(): List<Long> = threads.findDeadlockedThreads()?.toList() ?: emptyList()

    override fun processors(): Int = Runtime.getRuntime().availableProcessors()

    private fun ThreadInfo.snapshot() =
        ThreadSnapshot(
            id = threadId,
            name = threadName,
            state = threadState,
            daemon = isDaemon,
            stack = stackTrace.toList(),
            lock = lockName,
            lockOwner = lockOwnerName,
        )
}

/**
 * Writes a plain-text dump of all threads to [target], virtual threads included (the `ThreadMXBean` of the samples
 * leaves them out). [target] must not exist.
 */
internal fun writeThreadDump(target: Path) {
    ManagementFactory
        .getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
        .dumpThreads(target.toAbsolutePath().toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN)
}
