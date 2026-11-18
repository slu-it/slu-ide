package dev.sluide.secrets

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class SecretToolBackendTest {
    @TempDir
    lateinit var dir: Path

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val encoded = Base64.getEncoder().encodeToString(key)

    private fun file(name: String): Path = dir.resolve(name)

    private fun mode(mode: String) = Files.writeString(file("mode"), mode)

    private fun stored(value: String) = Files.writeString(file("store"), value)

    private fun argvLog(): List<String> =
        if (Files.exists(file("argv.log"))) Files.readAllLines(file("argv.log")) else emptyList()

    private fun installTool(into: Path = dir) {
        val script = into.resolve("secret-tool")
        Files.writeString(script, FAKE_TOOL)
        script.toFile().setExecutable(true)
        if (!Files.exists(file("mode"))) mode("ok")
    }

    private fun backend(
        timeout: Duration = Duration.ofSeconds(30),
        readerJoin: Duration = Duration.ofSeconds(5),
    ): SecretToolBackend {
        installTool()
        return SecretToolBackend(
            attributes = mapOf("application" to "test"),
            timeout = timeout,
            path = dir.toRealPath().toString(),
            readerJoin = readerJoin,
        )
    }

    // every array the backend zeroed, in order; a reader thread may add one late
    private val zeroed = CopyOnWriteArrayList<ByteArray>()

    private fun watched(
        timeout: Duration = Duration.ofSeconds(30),
        readerJoin: Duration = Duration.ofSeconds(5),
    ): SecretToolBackend {
        installTool()
        return SecretToolBackend(
            attributes = mapOf("application" to "test"),
            timeout = timeout,
            path = dir.toRealPath().toString(),
            readerJoin = readerJoin,
            onZeroed = zeroed::add,
        )
    }

    private fun assertZeroed(vararg sizes: Int) {
        assertEquals(sizes.toList(), zeroed.map { it.size })
        assertTrue(zeroed.all { array -> array.all { it == 0.toByte() } })
    }

    /** A backend whose tool is looked up on [path] only; the fake [installTool]ed where the test wants it. */
    private fun backendOn(path: String?) = SecretToolBackend(attributes = mapOf("application" to "test"), path = path)

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun pid() = Files.readString(file("pid")).trim().toLong()

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /** The number of open files of this process, `null` where `/proc` has no list of them (macOS). */
    private fun openFiles(): Long? =
        Path.of("/proc/self/fd").takeIf { Files.isDirectory(it) }?.let { fds -> Files.list(fds).use { it.count() } }

    private fun collectors() = Thread.getAllStackTraces().keys.filter { it.name == "secret-tool-output" && it.isAlive }

    @Test
    fun `without the tool the store is unavailable`() {
        val missing = SecretToolBackend(path = dir.toRealPath().toString())
        assertEquals("needs secret-tool", missing.unavailableReason())
        assertEquals(KeyRead.Failed("needs secret-tool"), missing.read())
        assertNull(backend().unavailableReason())
    }

    @Test
    fun `a stored key is read`() {
        val backend = backend()
        stored(encoded)
        val found = assertInstanceOf(KeyRead.Found::class.java, backend.read())
        assertArrayEquals(key, found.key)
        assertEquals(listOf("lookup application test"), argvLog())
    }

    @Test
    fun `an empty answer with exit 1 is missing`() {
        assertEquals(KeyRead.Missing, backend().read())
    }

    @ParameterizedTest
    @CsvSource(
        "error, boom: it broke",
        "silent, secret-tool failed (exit 3)",
        "nodbus, Cannot autolaunch D-Bus without X11 \$DISPLAY",
        "stdout1, secret-tool failed (exit 1)",
        "other1, Operation was cancelled",
    )
    fun `other errors fail the read`(
        mode: String,
        reason: String,
    ) {
        val backend = backend()
        mode(mode)
        assertEquals(KeyRead.Failed(reason), backend.read())
    }

    @ParameterizedTest
    @CsvSource("'not base64 !!'", "''")
    fun `an unreadable stored key fails`(garbage: String) {
        val backend = backend()
        stored(garbage)
        assertEquals(KeyRead.Failed("unreadable key in the Secret Service"), backend.read())
    }

    @Test
    fun `creating stores the base64 key on stdin only`() {
        val backend = backend()
        val before = key.copyOf()
        assertEquals(KeyCreate.Created, backend.create(key))
        assertArrayEquals(before, key)
        assertEquals(encoded, Files.readString(file("store")))
        assertEquals(
            listOf(
                "lookup application test",
                "search application test",
                "store --label=Thoreau Safe Storage application test",
            ),
            argvLog(),
        )
        assertTrue(argvLog().none { it.contains(encoded) })
    }

    @Test
    fun `creating over an existing key keeps it`() {
        val backend = backend()
        stored(encoded)
        assertEquals(KeyCreate.AlreadyExists, backend.create(ByteArray(32) { 5 }))
        assertEquals(listOf("lookup application test"), argvLog())
        assertEquals(encoded, Files.readString(file("store")))
    }

    @Test
    fun `an item in a locked collection is denied not missing`() {
        val backend = backend()
        stored(encoded)
        Files.writeString(file("locked"), "")
        assertEquals(KeyRead.Denied("Secret Service locked"), backend.read())
        assertEquals(KeyCreate.Failed("Secret Service locked"), backend.create(key))
        assertTrue(argvLog().none { it.startsWith("store") })
    }

    @Test
    fun `creating fails when the lookup fails`() {
        val backend = backend()
        mode("nodbus")
        assertEquals(KeyCreate.Failed("Cannot autolaunch D-Bus without X11 \$DISPLAY"), backend.create(key))
        mode("error")
        assertEquals(KeyCreate.Failed("boom: it broke"), backend.create(key))
        assertTrue(argvLog().none { it.startsWith("store") })
    }

    @Test
    fun `deleting clears the item also when none is stored`() {
        val backend = backend()
        stored(encoded)
        assertTrue(backend.delete())
        assertFalse(Files.exists(file("store")))
        assertTrue(backend.delete())
        mode("error")
        assertFalse(backend.delete())
    }

    @Test
    fun `deleting an item the locked collection keeps fails`() {
        val backend = backend()
        stored(encoded)
        Files.writeString(file("locked"), "")
        assertFalse(backend.delete())
    }

    @Test
    fun `a tool that does not answer fails the call and is stopped`() {
        val backend = backend(Duration.ofSeconds(3))
        mode("sleep")
        assertEquals(KeyRead.Failed("the Secret Service did not answer"), backend.read())
        // the pid is the script's background sleep, a descendant of the process that timed out
        val handle = ProcessHandle.of(Files.readString(file("pid")).trim().toLong())
        val deadline = System.currentTimeMillis() + 10_000
        while (handle.isPresent && handle.get().isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertFalse(handle.isPresent && handle.get().isAlive)
    }

    @Test
    fun `a key without a trailing newline is read whole and one with it is stripped`() {
        val backend = backend()
        val long = ByteArray(33) { (it + 3).toByte() }
        stored(Base64.getEncoder().encodeToString(long))
        assertArrayEquals(long, assertInstanceOf(KeyRead.Found::class.java, backend.read()).key)

        stored(encoded + "\n")
        assertArrayEquals(key, assertInstanceOf(KeyRead.Found::class.java, backend.read()).key)
    }

    @ParameterizedTest
    @CsvSource(
        "lookup:silent, secret-tool failed (exit 3)",
        "lookup:stdout1, secret-tool failed (exit 1)",
        "lookup:other1, Operation was cancelled",
        "search:error, boom: it broke",
        "search:silent, secret-tool failed (exit 3)",
    )
    fun `an error of one command is not mistaken for a missing key`(
        mode: String,
        reason: String,
    ) {
        val backend = backend()
        mode(mode)
        assertEquals(KeyRead.Failed(reason), backend.read())
    }

    @Test
    fun `creating reports a failing store and a store that does not answer`() {
        val backend = backend(Duration.ofSeconds(2))
        mode("store:error")
        assertEquals(KeyCreate.Failed("boom: it broke"), backend.create(key))
        assertFalse(Files.exists(file("store")))

        mode("store:silent")
        assertEquals(KeyCreate.Failed("secret-tool failed (exit 3)"), backend.create(key))

        mode("store:sleep")
        assertEquals(KeyCreate.Failed("the Secret Service did not answer"), backend.create(key))
    }

    @Test
    fun `a search that does not answer fails the read and the delete`() {
        val backend = backend(Duration.ofSeconds(2))
        mode("search:sleep")
        assertEquals(KeyRead.Failed("the Secret Service did not answer"), backend.read())
        assertFalse(backend.delete())
    }

    @ParameterizedTest
    @CsvSource("clear:error", "clear:silent", "clear:other1", "search:silent", "search:error")
    fun `deleting fails unless the tool or the search says the key is gone`(mode: String) {
        val backend = backend()
        mode(mode)
        assertFalse(backend.delete())
    }

    @Test
    fun `a failed clear of a stored key is not taken for a locked one`() {
        val backend = backend()
        stored(encoded)
        mode("clear:silent")
        assertFalse(backend.delete())
        assertTrue(Files.exists(file("store")))
    }

    @Test
    fun `the tool is the first executable file of the path`() {
        val folders = listOf("a", "b", "c", "d").map { Files.createDirectories(file(it)) }
        // a directory and a file without the executable bit come first and are skipped
        Files.createDirectories(folders[0].resolve("secret-tool"))
        Files.writeString(folders[1].resolve("secret-tool"), "#!/bin/sh\nexit 7\n")
        installTool(folders[2])
        Files.writeString(folders[2].resolve("mode"), "ok")
        Files.writeString(folders[2].resolve("store"), encoded)
        Files.writeString(folders[3].resolve("secret-tool"), "#!/bin/sh\nexit 7\n")
        folders[3].resolve("secret-tool").toFile().setExecutable(true)
        val path = folders.joinToString(File.pathSeparator) { it.toRealPath().toString() }

        val backend = backendOn(path)

        assertNull(backend.unavailableReason())
        assertArrayEquals(key, assertInstanceOf(KeyRead.Found::class.java, backend.read()).key)
    }

    @Test
    fun `without a path or with only empty entries there is no tool`() {
        assertEquals("needs secret-tool", backendOn(null).unavailableReason())
        // an empty entry would make File("", "bin/sh") the file /bin/sh, found nowhere on this path
        for (path in listOf("", ":", "::")) {
            val backend = SecretToolBackend(command = "bin/sh", path = path)
            assertEquals("needs secret-tool", backend.unavailableReason(), "path '$path'")
        }
    }

    @Test
    fun `a tool that cannot be started fails the call with a short reason`() {
        val folder = Files.createDirectories(dir.resolve("x".repeat(200)))
        val tool = folder.resolve("secret-tool")
        Files.writeString(tool, "#!/nonexistent/interpreter\n")
        tool.toFile().setExecutable(true)
        val backend = backendOn(folder.toRealPath().toString())

        val failed = assertInstanceOf(KeyRead.Failed::class.java, backend.read())

        assertTrue(failed.reason.startsWith("Cannot run program"), failed.reason)
        assertEquals(200, failed.reason.length, "cut to the reason limit")
    }

    @Test
    fun `a tool that ignores the time limit is killed itself`() {
        val backend = backend(Duration.ofSeconds(2))
        mode("hang")

        assertEquals(KeyRead.Failed("the Secret Service did not answer"), backend.read())

        val pid = pid()
        waitUntil("the tool to be killed") { !alive(pid) }
    }

    @Test
    fun `a tool that ends but leaves its output open fails the call and frees the pipes`() {
        val backend = backend(readerJoin = Duration.ofMillis(300))
        mode("orphan")
        val before = openFiles()
        try {
            assertEquals(KeyRead.Failed("secret-tool output could not be read"), backend.read())
            if (before != null) assertEquals(before, openFiles(), "the pipes are closed though the orphan holds them")
        } finally {
            ProcessHandle.of(pid()).ifPresent { it.destroyForcibly() }
        }
    }

    @ParameterizedTest
    @CsvSource("holdout", "holderr")
    fun `a tool that ends but leaves one output open fails the call`(mode: String) {
        val backend = backend(readerJoin = Duration.ofMillis(300))
        mode(mode)
        try {
            assertEquals(KeyRead.Failed("secret-tool output could not be read"), backend.read())
        } finally {
            ProcessHandle.of(pid()).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `an output that closes shortly after the tool ended is still read`() {
        val backend = backend()
        stored(encoded)
        mode("lookup:lateout")
        assertArrayEquals(key, assertInstanceOf(KeyRead.Found::class.java, backend.read()).key)

        mode("lookup:lateerr")
        assertEquals(KeyRead.Failed("boom: it broke"), backend.read())
    }

    @Test
    fun `the base64 text of a key read and the output it came from are zeroed`() {
        val backend = watched()
        stored(encoded)

        backend.read()

        // the copy without a newline, then the tool's output
        assertZeroed(44, 44)
    }

    @Test
    fun `the output of a missing and of a locked lookup is zeroed`() {
        val backend = watched()
        backend.read()
        // lookup and search printed nothing
        assertZeroed(0, 0)

        zeroed.clear()
        stored(encoded)
        Files.writeString(file("locked"), "")
        backend.read()
        // the search listed the item: its output is not empty, and zeroed
        assertEquals(2, zeroed.size)
        assertTrue(zeroed[0].isNotEmpty(), "the search's output comes first")
        assertEquals(0, zeroed[1].size, "then the lookup's")
        assertTrue(zeroed.all { array -> array.all { it == 0.toByte() } })
    }

    @Test
    fun `creating zeroes the key it was given in base64 and the output of the tool`() {
        val backend = watched()

        backend.create(key)

        // lookup, search, store output, then the base64 of the key
        assertZeroed(0, 0, 0, 44)
    }

    @Test
    fun `an existing key found while creating is zeroed`() {
        val backend = watched()
        stored(encoded)

        backend.create(key)

        // the lookup's copy and output, then the key itself
        assertZeroed(44, 44, 32)
    }

    @Test
    fun `deleting zeroes the output of clear and of the confirming search`() {
        val backend = watched()
        stored(encoded)
        backend.delete()
        assertZeroed(0)

        zeroed.clear()
        Files.writeString(file("locked"), "")
        stored(encoded)
        backend.delete()
        // clear, then the search that found the item
        assertEquals(2, zeroed.size)
        assertEquals(0, zeroed[0].size)
        assertTrue(zeroed[1].isNotEmpty())
        assertTrue(zeroed.all { array -> array.all { it == 0.toByte() } })
    }

    @Test
    fun `the output of a tool that left its error output open is zeroed`() {
        val backend = watched(readerJoin = Duration.ofMillis(300))
        mode("holderr")
        try {
            backend.read()
            assertZeroed(0)
        } finally {
            ProcessHandle.of(pid()).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `the error output of a call that could not read the standard output is zeroed`() {
        val backend = watched(readerJoin = Duration.ofMillis(300))
        mode("holdout")
        try {
            backend.read()
            assertZeroed(15)
        } finally {
            ProcessHandle.of(pid()).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `what an interrupted call had collected of the key is zeroed`() {
        val backend = watched()
        stored(encoded)
        mode("lookup:holdkey")
        val caller = Thread { backend.read() }
        caller.start()
        // the tool printed the key and ended; an orphan keeps stdout open for five seconds, so its reader still runs
        waitUntil("the readers") { Files.exists(file("argv.log")) && collectors().isNotEmpty() }

        caller.interrupt()
        caller.join(10_000)

        waitUntil("the output the readers collected to be zeroed") {
            zeroed.any { it.size == 44 } && zeroed.any { it.size == 15 }
        }
        assertTrue(zeroed.all { array -> array.all { it == 0.toByte() } })
    }

    @Test
    fun `what a call that timed out had collected of the key is zeroed`() {
        val backend = watched(timeout = Duration.ofSeconds(2))
        stored(encoded)
        mode("lookup:keyhang")

        assertEquals(KeyRead.Failed("the Secret Service did not answer"), backend.read())

        waitUntil("the output the readers collected to be zeroed") {
            zeroed.any { it.size == 44 } && zeroed.any { it.size == 15 }
        }
        assertTrue(zeroed.all { array -> array.all { it == 0.toByte() } })
    }

    @Test
    fun `the output readers are daemon threads`() {
        val backend = backend(Duration.ofSeconds(3))
        mode("sleep")
        val caller = Thread { backend.read() }
        caller.start()
        try {
            waitUntil("the output readers") { collectors().size == 2 }
            assertTrue(collectors().all { it.isDaemon })
        } finally {
            caller.join(10_000)
        }
    }

    @Test
    fun `an interrupted call stops the tool, keeps the flag and fails`() {
        val backend = backend()
        mode("sleep")
        var result: KeyRead? = null
        var flag = false
        val caller =
            Thread {
                result = backend.read()
                flag = Thread.currentThread().isInterrupted
            }
        caller.start()
        waitUntil("the tool to run") { Files.exists(file("pid")) && Files.size(file("pid")) > 0 }
        val pid = pid()

        caller.interrupt()
        caller.join(10_000)

        assertEquals(KeyRead.Failed("interrupted"), result)
        assertTrue(flag, "the interrupt flag is set again")
        waitUntil("the tool to be killed") { !alive(pid) }
    }

    private companion object {
        /**
         * POSIX sh only; keeps its store, mode, argv log and pid next to itself. The mode `name` applies to every
         * command, `command:name` to that command only (the others behave as `ok`).
         *
         * Checked against the real secret-tool (gnome-keyring in `dbus-run-session`, 2026-11-18): lookup of an absent
         * item exits 1 with no output, lookup of a stored one prints the secret without a newline and exits 0, search
         * of nothing exits 0 with no output, search of an item lists it, clear of an absent item exits 1, store reads
         * the secret from stdin; without a D-Bus session every command prints "Cannot autolaunch D-Bus without X11
         * $DISPLAY" to stderr and exits 1.
         */
        val FAKE_TOOL =
            """
            #!/bin/sh
            d=${'$'}(dirname "${'$'}0")
            echo "${'$'}*" >> "${'$'}d/argv.log"
            m=${'$'}(cat "${'$'}d/mode")
            case "${'$'}m" in "${'$'}1":*) m=${'$'}{m#*:} ;; *:*) m=ok ;; esac
            case "${'$'}m" in
              sleep) sleep 30 & echo ${'$'}! > "${'$'}d/pid"; wait ;;
              hang) echo ${'$'}${'$'} > "${'$'}d/pid"; exec sleep 30 ;;
              orphan) (sleep 20 & echo ${'$'}! > "${'$'}d/pid"); exit 0 ;;
              holdout) echo "boom: it broke" >&2; (sleep 20 2>/dev/null & echo ${'$'}! > "${'$'}d/pid"); exit 0 ;;
              holderr) (sleep 20 >/dev/null & echo ${'$'}! > "${'$'}d/pid"); exit 0 ;;
              keyhang) cat "${'$'}d/store"; echo "boom: it broke" >&2; exec sleep 30 ;;
              holdkey) cat "${'$'}d/store"; echo "boom: it broke" >&2; (sleep 5 2>/dev/null &); exit 0 ;;
              lateout) cat "${'$'}d/store"; (sleep 1 2>/dev/null &); exit 0 ;;
              lateerr) echo "boom: it broke" >&2; (sleep 1 >/dev/null &); exit 2 ;;
              error) echo "boom: it broke" >&2; echo "second line" >&2; exit 2 ;;
              silent) exit 3 ;;
              nodbus) echo "Cannot autolaunch D-Bus without X11 \${'$'}DISPLAY" >&2; exit 1 ;;
              stdout1) echo "junk"; exit 1 ;;
              other1) echo "Operation was cancelled" >&2; exit 1 ;;
            esac
            case "${'$'}1" in
              lookup) if [ -f "${'$'}d/store" ] && [ ! -f "${'$'}d/locked" ]; then cat "${'$'}d/store"; exit 0; else exit 1; fi ;;
              search) if [ -f "${'$'}d/store" ]; then echo "[/1]"; echo "label = x"; fi; exit 0 ;;
              store) cat > "${'$'}d/store"; exit 0 ;;
              clear)
                if [ -f "${'$'}d/locked" ]; then exit 1; fi
                if [ -f "${'$'}d/store" ]; then rm -f "${'$'}d/store"; exit 0; fi
                exit 1 ;;
            esac
            exit 9
            """.trimIndent() + "\n"
    }
}
