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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

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

    private fun backend(timeout: Duration = Duration.ofSeconds(30)): SecretToolBackend {
        val script = file("secret-tool")
        Files.writeString(script, FAKE_TOOL)
        script.toFile().setExecutable(true)
        if (!Files.exists(file("mode"))) mode("ok")
        return SecretToolBackend(
            attributes = mapOf("application" to "test"),
            timeout = timeout,
            path = dir.toRealPath().toString(),
        )
    }

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

    private companion object {
        /** POSIX sh only; keeps its store, mode, argv log and pid next to itself. */
        val FAKE_TOOL =
            """
            #!/bin/sh
            d=${'$'}(dirname "${'$'}0")
            echo "${'$'}*" >> "${'$'}d/argv.log"
            case "${'$'}(cat "${'$'}d/mode")" in
              sleep) sleep 30 & echo ${'$'}! > "${'$'}d/pid"; wait ;;
              error) echo "boom: it broke" >&2; echo "second line" >&2; exit 2 ;;
              silent) exit 3 ;;
              nodbus) echo "Cannot autolaunch D-Bus without X11 \${'$'}DISPLAY" >&2; exit 1 ;;
              stdout1) echo "junk"; exit 1 ;;
              other1) echo "Operation was cancelled" >&2; exit 1 ;;
            esac
            case "${'$'}1" in
              lookup) if [ -f "${'$'}d/store" ] && [ ! -f "${'$'}d/locked" ]; then cat "${'$'}d/store"; echo; exit 0; else exit 1; fi ;;
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
