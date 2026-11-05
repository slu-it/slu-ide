package dev.sluide.diagnostics

/** The name JavaFX gives its application thread; its stacks are always in the report. */
internal const val FX_THREAD = "JavaFX Application Thread"

/** One thread as one dump saw it. */
internal data class ThreadSnapshot(
    val id: Long,
    val name: String,
    val state: Thread.State,
    val daemon: Boolean,
    /** Innermost frame first, at most the depth asked for. */
    val stack: List<StackTraceElement>,
    /** What it waits for or is blocked on, e.g. `java.lang.Object@1a2b3c`; null when it waits for nothing. */
    val lock: String? = null,
    /** The thread that holds [lock], if any. */
    val lockOwner: String? = null,
)

/** Collections of one garbage collector so far. */
internal data class GcCount(
    val name: String,
    val count: Long,
    val millis: Long,
)

/** Heap use in bytes; [max] is -1 when undefined. */
internal data class Heap(
    val used: Long,
    val committed: Long,
    val max: Long,
)

/** What [ThreadSampler] reads from the JVM; `ManagementThreadSource` reads the management beans, tests fake it. */
internal interface ThreadSource {
    /** CPU time in nanoseconds per thread id, of the threads that have one. */
    fun cpuTimes(): Map<Long, Long>

    /** All live platform threads with stacks of at most [depth] frames. */
    fun dump(depth: Int): List<ThreadSnapshot>

    /** CPU time of the whole process in nanoseconds, null when the JVM cannot tell. */
    fun processCpuNanos(): Long?

    fun gcCounts(): List<GcCount>

    fun heap(): Heap

    /** The ids of deadlocked threads, empty if none. */
    fun deadlocked(): List<Long>

    fun processors(): Int
}

/** A thread's CPU time in the sampling window; of one that ended in it, as last read. */
internal data class ThreadCpu(
    val id: Long,
    val name: String,
    /** Its state in the last dump, null when it ended in the window. */
    val state: Thread.State?,
    val cpuNanos: Long,
)

/** A stack seen [count] times among a thread's running or blocked samples, innermost frame first. */
internal data class FrequentStack(
    val frames: List<StackTraceElement>,
    val count: Int,
    /** Whether the thread's stack was deeper than [frames]. */
    val cut: Boolean,
)

/**
 * The most frequent stacks of one thread, seen in [samples] samples: [active] of them running or blocked ([stacks]),
 * [nativeWaits] in the native event loop or a selector wait and [nestedLoops] in a nested event loop ([waitStacks]).
 */
internal data class HotThread(
    val name: String,
    val cpuNanos: Long?,
    val samples: Int,
    val active: Int,
    val stacks: List<FrequentStack>,
    val nativeWaits: Int = 0,
    val nestedLoops: Int = 0,
    val waitStacks: List<FrequentStack> = emptyList(),
)

/** The change of one garbage collector over the window. */
internal data class GcDelta(
    val name: String,
    val count: Long,
    val millis: Long,
)

/** What [ThreadSampler] measured over its window. */
internal data class SamplingResult(
    val wallNanos: Long,
    val samples: Int,
    val processors: Int,
    val processCpuNanos: Long?,
    /** All threads with CPU time, also those that ended in the window, the busiest first; not the sampling one. */
    val threads: List<ThreadCpu>,
    /** The CPU time of the sampling thread itself, null when unknown. */
    val samplerCpuNanos: Long?,
    /**
     * Process CPU time no listed thread accounts for (GC, JIT compiler, JFR and other JVM threads, and threads that
     * started and ended between two samples); null when unknown.
     */
    val unattributedNanos: Long?,
    val hot: List<HotThread>,
    val gc: List<GcDelta>,
    val heap: Heap,
    val deadlocked: List<String>,
    /** All threads at the end, with stacks of at most [DUMP_DEPTH] frames. */
    val finalDump: List<ThreadSnapshot>,
)

/** Frames per sample; the full dump at the end has as many. */
internal const val DUMP_DEPTH = 40

/** Frames of a hot stack: enough to see where it comes from, few enough that stacks differing deep down group. */
internal const val HOT_STACK_DEPTH = 25

/** Hottest threads whose stacks are listed (the JavaFX thread comes on top). */
internal const val HOT_THREADS = 5

/** Most frequent distinct stacks per hot thread. */
internal const val STACKS_PER_THREAD = 3

/** The classes whose native methods wait for I/O events: a thread on top of one is RUNNABLE but waits. */
private val SELECTOR_CLASSES = setOf("sun.nio.ch.EPoll", "sun.nio.ch.KQueue", "sun.nio.ch.WEPoll")

/** What a thread does in one sample. */
internal enum class SampleKind {
    /** Runs or is blocked on a monitor. */
    ACTIVE,

    /** RUNNABLE in a native wait: the JavaFX event loop or a selector. Usually idle, but it may be native work. */
    NATIVE_WAIT,

    /** RUNNABLE in a nested JavaFX event loop: a modal dialog (`showAndWait`) is open. */
    NESTED_LOOP,

    /** Waiting, sleeping or parked. */
    WAITING,
}

internal fun sampleKind(thread: ThreadSnapshot): SampleKind =
    when (thread.state) {
        Thread.State.BLOCKED -> {
            SampleKind.ACTIVE
        }

        Thread.State.RUNNABLE -> {
            val top = thread.stack.firstOrNull()
            when {
                top == null || !top.isNativeMethod -> {
                    SampleKind.ACTIVE
                }

                top.className.startsWith("com.sun.glass.ui.") && top.methodName == "_runLoop" -> {
                    SampleKind.NATIVE_WAIT
                }

                top.className.startsWith("com.sun.glass.ui.") && top.methodName == "_enterNestedEventLoopImpl" -> {
                    SampleKind.NESTED_LOOP
                }

                top.className in SELECTOR_CLASSES -> {
                    SampleKind.NATIVE_WAIT
                }

                else -> {
                    SampleKind.ACTIVE
                }
            }
        }

        else -> {
            SampleKind.WAITING
        }
    }

/**
 * Samples the threads of the process over a window: CPU times at [begin], at every [sample] and at [end] (the last
 * value counts for a thread that ends), a dump at every [sample], and aggregates them into the busiest threads and
 * their most frequent running or blocked stacks. Leaves out the thread [excluded] (the one sampling). One thread calls
 * all of it.
 */
internal class ThreadSampler(
    private val source: ThreadSource,
    private val excluded: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    private class Seen(
        var name: String,
    ) {
        var samples = 0
        var active = 0
        var nativeWaits = 0
        var nestedLoops = 0
        val stacks = HashMap<List<StackTraceElement>, Int>()
        val waitStacks = HashMap<List<StackTraceElement>, Int>()
        val cut = HashSet<List<StackTraceElement>>()
    }

    private fun Seen.frequent(counts: Map<List<StackTraceElement>, Int>) =
        counts.entries
            .sortedByDescending { it.value }
            .take(STACKS_PER_THREAD)
            .map { FrequentStack(it.key, it.value, it.key in cut) }

    private var startedAt = 0L
    private var startCpu: Map<Long, Long> = emptyMap()

    /** The latest CPU time read per thread id. */
    private val lastCpu = HashMap<Long, Long>()
    private var startProcessCpu: Long? = null
    private var startGc: List<GcCount> = emptyList()
    private val seen = LinkedHashMap<Long, Seen>()
    private var samples = 0

    fun begin() {
        startedAt = clock()
        startCpu = source.cpuTimes()
        lastCpu.putAll(startCpu)
        startProcessCpu = source.processCpuNanos()
        startGc = source.gcCounts()
    }

    fun sample() {
        record(source.dump(DUMP_DEPTH))
        lastCpu.putAll(source.cpuTimes())
    }

    /** Takes the last dump (also a sample) and the CPU times at the end. */
    fun end(): SamplingResult {
        val finalDump = source.dump(DUMP_DEPTH).filter { it.id != excluded }
        record(finalDump)
        lastCpu.putAll(source.cpuTimes())
        val wall = clock() - startedAt
        val endProcessCpu = source.processCpuNanos()
        val byId = finalDump.associateBy { it.id }
        val threads =
            lastCpu
                .filterKeys { it != excluded }
                .mapNotNull { (id, cpu) ->
                    val name = byId[id]?.name ?: seen[id]?.name ?: return@mapNotNull null
                    ThreadCpu(id, name, byId[id]?.state, (cpu - (startCpu[id] ?: 0L)).coerceAtLeast(0))
                }.sortedByDescending { it.cpuNanos }
        val gcBefore = startGc.associateBy { it.name }
        val deadlocked = source.deadlocked().map { id -> byId[id]?.name ?: seen[id]?.name ?: "thread $id" }
        val processCpu = startProcessCpu?.let { start -> endProcessCpu?.let { it - start } }
        val samplerCpu = lastCpu[excluded]?.let { it - (startCpu[excluded] ?: 0L) }
        return SamplingResult(
            wallNanos = wall,
            samples = samples,
            processors = source.processors(),
            processCpuNanos = processCpu,
            threads = threads,
            samplerCpuNanos = samplerCpu,
            unattributedNanos =
                processCpu?.let {
                    (
                        it -
                            threads.sumOf { thread ->
                                thread.cpuNanos
                            } - (samplerCpu ?: 0)
                    ).coerceAtLeast(0)
                },
            hot = hotThreads(threads),
            gc =
                source.gcCounts().map { gc ->
                    val before = gcBefore[gc.name]
                    GcDelta(gc.name, gc.count - (before?.count ?: 0), gc.millis - (before?.millis ?: 0))
                },
            heap = source.heap(),
            deadlocked = deadlocked,
            finalDump = finalDump,
        )
    }

    private fun record(dump: List<ThreadSnapshot>) {
        samples++
        for (thread in dump) {
            if (thread.id == excluded) continue
            val entry = seen.getOrPut(thread.id) { Seen(thread.name) }
            entry.name = thread.name
            entry.samples++
            val counted =
                when (sampleKind(thread)) {
                    SampleKind.ACTIVE -> entry.stacks.also { entry.active++ }
                    SampleKind.NATIVE_WAIT -> entry.waitStacks.also { entry.nativeWaits++ }
                    SampleKind.NESTED_LOOP -> entry.waitStacks.also { entry.nestedLoops++ }
                    SampleKind.WAITING -> continue
                }
            val frames = thread.stack.take(HOT_STACK_DEPTH)
            counted.merge(frames, 1, Int::plus)
            if (thread.stack.size > HOT_STACK_DEPTH) entry.cut += frames
        }
    }

    /** The [HOT_THREADS] busiest threads that used CPU and the JavaFX thread, each with its most frequent stacks. */
    private fun hotThreads(threads: List<ThreadCpu>): List<HotThread> {
        val cpu = threads.associate { it.id to it.cpuNanos }
        // Threads that used no CPU have nothing hot to show, unless it is the JavaFX thread.
        val ids =
            threads
                .filter { it.id in seen && it.cpuNanos > 0 }
                .take(HOT_THREADS)
                .map { it.id }
                .toMutableList()
        seen.entries
            .firstOrNull { it.value.name == FX_THREAD }
            ?.key
            ?.let { if (it !in ids) ids += it }
        return ids.map { id ->
            val entry = seen.getValue(id)
            HotThread(
                name = entry.name,
                cpuNanos = cpu[id],
                samples = entry.samples,
                active = entry.active,
                stacks = entry.frequent(entry.stacks),
                nativeWaits = entry.nativeWaits,
                nestedLoops = entry.nestedLoops,
                waitStacks = entry.frequent(entry.waitStacks),
            )
        }
    }
}
