package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.FileKey
import dev.sluide.encryptedfiles.KdfParams
import dev.sluide.encryptedfiles.Sluenc
import dev.sluide.encryptedfiles.SluencException
import dev.sluide.encryptedfiles.SluencFailure
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class EncryptedSessionTest {
    private val params = KdfParams(64, 1, 1)
    private val password = "correct horse battery"
    private val file = Path.of("/scratches/notes.md.sluenc")

    /** The file system: one file, or none. */
    private var disk: ByteArray? = null

    /** Thrown by every read when set, e.g. an Error the session does not expect. */
    private var readError: Throwable? = null

    private val backgroundTasks = ArrayDeque<Runnable>()
    private val fxTasks = ArrayDeque<Runnable>()

    /** Every key the unlocker made, to check the ones dropped are zeroed. */
    private val keys = mutableListOf<FileKey>()

    private var unlocker: (ByteArray, CharArray) -> FileKey = { bytes, typed ->
        Sluenc.unlock(bytes, typed).also { keys += it }
    }

    private val session = newSession()

    private fun newSession() =
        EncryptedSession(
            file,
            readFile = {
                readError?.let { error -> throw error }
                disk ?: throw NoSuchFileException(it.toString())
            },
            writeFile = { _, bytes -> disk = bytes },
            unlocker = { bytes, typed -> unlocker(bytes, typed) },
            background = backgroundTasks::addLast,
            fx = fxTasks::addLast,
            rekeyer = { bytes, current, new ->
                Sluenc.changePassword(bytes, current, new, ByteArray(0), params).also { keys += it.key }
            },
        )

    private fun create(plain: String): FileKey {
        val created = Sluenc.create(password.toCharArray(), plain.toByteArray(), params)
        disk = created.bytes
        return created.key
    }

    /** Runs the queued unlock and then its result on the "FX thread". */
    private fun runUnlock() {
        while (backgroundTasks.isNotEmpty()) backgroundTasks.removeFirst().run()
        while (fxTasks.isNotEmpty()) fxTasks.removeFirst().run()
    }

    private fun unlock(typed: String = password): CharArray {
        val array = typed.toCharArray()
        assertTrue(session.unlock(array))
        runUnlock()
        return array
    }

    private fun FileKey.isZeroed() = rawDek.all { it == 0.toByte() }

    private fun CharArray.isZeroed() = all { it == '\u0000' }

    @Test
    fun `the right password unlocks and is zeroed afterwards`() {
        create("secret").close()
        val states = mutableListOf<SessionState>()
        session.onChange = { states += session.state }

        val typed = password.toCharArray()
        session.unlock(typed)
        assertEquals(SessionState.UNLOCKING, session.state)
        runUnlock()

        assertEquals(listOf(SessionState.UNLOCKING, SessionState.UNLOCKED), states)
        assertSame(keys.single(), session.key)
        assertNull(session.failure)
        assertTrue(typed.isZeroed())
        assertArrayEquals("secret".toByteArray(), session.read())
    }

    enum class UnlockFailure(
        val message: String,
    ) {
        WRONG_PASSWORD("Wrong password."),
        NOT_SLUENC("Not a Thoreau encrypted file."),
        NEWER_VERSION("Written by a newer Thoreau (format 7)."),
        UNSUPPORTED_PARAMETERS("Unsupported encryption parameters."),
        DAMAGED("The file is damaged."),
        OUT_OF_MEMORY("Not enough memory to unlock."),
        UNREADABLE("Cannot read the file."),
        UNEXPECTED("Cannot unlock the file."),
        READ_ERROR("Cannot unlock the file."),
    }

    @ParameterizedTest
    @EnumSource(UnlockFailure::class)
    fun `a failed unlock stays locked with its message and zeroes the password`(case: UnlockFailure) {
        create("secret").close()
        when (case) {
            UnlockFailure.WRONG_PASSWORD -> {}

            UnlockFailure.NOT_SLUENC -> {
                disk = ByteArray(0)
            }

            UnlockFailure.NEWER_VERSION -> {
                unlocker = { _, _ -> throw SluencException(SluencFailure.NEWER_VERSION, 7) }
            }

            UnlockFailure.UNSUPPORTED_PARAMETERS -> {
                unlocker = { _, _ ->
                    throw SluencException(SluencFailure.UNSUPPORTED_PARAMETERS)
                }
            }

            UnlockFailure.DAMAGED -> {
                tamper()
            }

            UnlockFailure.OUT_OF_MEMORY -> {
                unlocker = { _, _ -> throw OutOfMemoryError("Argon2") }
            }

            UnlockFailure.UNREADABLE -> {
                disk = null
            }

            UnlockFailure.UNEXPECTED -> {
                unlocker = { _, _ -> throw IllegalStateException("secret text") }
            }

            UnlockFailure.READ_ERROR -> {
                // Files.readAllBytes of a file over 2 GiB.
                readError = OutOfMemoryError("Required array size too large")
            }
        }
        val typed = if (case == UnlockFailure.WRONG_PASSWORD) "wrong horse battery" else password

        val array = unlock(typed)

        assertEquals(SessionState.LOCKED, session.state)
        assertEquals(case.message, session.failure)
        assertNull(session.key)
        assertTrue(array.isZeroed(), "password zeroed")
        assertTrue(keys.all { it.isZeroed() })
    }

    @Test
    fun `an unlocked key that cannot be posted to the FX thread is closed`() {
        create("secret").close()
        val unposted =
            EncryptedSession(
                file,
                readFile = { checkNotNull(disk) },
                unlocker = { bytes, typed -> unlocker(bytes, typed) },
                background = Runnable::run,
                fx = { throw IllegalStateException("Toolkit not running") },
            )

        unposted.unlock(password.toCharArray())

        assertNull(unposted.key)
        assertTrue(keys.single().isZeroed())
    }

    @Test
    fun `the plain bytes a read handed out are zeroed on the next read, on lock and on dispose`() {
        session.unlockedWith(create("secret"))
        val first = session.read()
        val second = session.read()

        assertTrue(first.all { it == 0.toByte() })
        assertArrayEquals("secret".toByteArray(), second)
        session.lock()
        assertTrue(second.all { it == 0.toByte() })

        session.unlockedWith(Sluenc.unlock(checkNotNull(disk), password.toCharArray()))
        val third = session.read()
        session.dispose()
        assertTrue(third.all { it == 0.toByte() })
    }

    @Test
    fun `an unlock is refused while one runs, and the refused password is zeroed at once`() {
        create("secret").close()
        session.unlock(password.toCharArray())

        val second = password.toCharArray()
        assertFalse(session.unlock(second))
        assertTrue(second.isZeroed())
        assertEquals(1, backgroundTasks.size)
    }

    @Test
    fun `an unlock that finishes after a lock closes its key`() {
        create("secret").close()
        session.unlock(password.toCharArray())
        session.lock()

        runUnlock()

        assertEquals(SessionState.LOCKED, session.state)
        assertNull(session.key)
        assertTrue(keys.single().isZeroed())
    }

    @Test
    fun `an unlock dropped by a lock closes its key also while a newer unlock runs`() {
        create("secret").close()
        session.unlock(password.toCharArray())
        session.lock()
        session.unlock(password.toCharArray())

        backgroundTasks.removeFirst().run()
        fxTasks.removeFirst().run()

        assertEquals(SessionState.UNLOCKING, session.state)
        assertTrue(keys.single().isZeroed())
        runUnlock()
        assertEquals(SessionState.UNLOCKED, session.state)
        assertSame(keys.last(), session.key)
        assertFalse(keys.last().isZeroed())
    }

    @Test
    fun `an unlock that finishes after the dispose closes its key`() {
        create("secret").close()
        session.unlock(password.toCharArray())
        assertNull(session.dispose())

        runUnlock()

        assertNull(session.key)
        assertTrue(keys.single().isZeroed())
    }

    @Test
    fun `lock and dispose zero the key`() {
        create("secret").close()
        unlock()
        val first = checkNotNull(session.key)

        session.lock("Why")

        assertTrue(first.isZeroed())
        assertEquals("Why", session.failure)
        unlock()
        val second = checkNotNull(session.key)
        session.dispose()
        assertTrue(second.isZeroed())
        assertNull(session.key)
    }

    @Test
    fun `a handed over key unlocks at once, and one handed to an unlocked session is closed`() {
        val key = create("secret")
        session.unlockedWith(key)
        assertEquals(SessionState.UNLOCKED, session.state)

        val other = Sluenc.unlock(checkNotNull(disk), password.toCharArray())
        session.unlockedWith(other)

        assertSame(key, session.key)
        assertTrue(other.isZeroed())
    }

    @Test
    fun `writes encrypt with the key and zero the plain bytes`() {
        session.unlockedWith(create(""))
        val plain = "MARKER-42".toByteArray()

        session.write(plain)

        assertTrue(plain.all { it == 0.toByte() })
        val written = checkNotNull(disk)
        assertFalse(String(written, Charsets.ISO_8859_1).contains("MARKER-42"))
        assertArrayEquals("MARKER-42".toByteArray(), Sluenc.decrypt(written, checkNotNull(session.key)))
    }

    @Test
    fun `a session closed unlocked with empty content returns the bytes last read or written`() {
        session.unlockedWith(create("not empty"))
        session.read()
        session.write(ByteArray(0))

        assertArrayEquals(disk, session.dispose())
    }

    @Test
    fun `a session closed with content, or locked, returns nothing`() {
        session.unlockedWith(create(""))
        session.read()
        session.write("text".toByteArray())
        assertNull(session.dispose())

        val locked = newSession()
        locked.unlockedWith(create(""))
        locked.read()
        locked.lock()
        assertNull(locked.dispose())
    }

    @Test
    fun `a change written with the same key reloads`() {
        val key = create("one")
        session.unlockedWith(key)
        disk = Sluenc.encrypt("two".toByteArray(), key)

        assertEquals(DiskDecision.Reload, session.diskChanged(dirty = true))
        assertNull(session.warning)
    }

    @Test
    fun `a deleted file reloads, so the inner view shows it deleted`() {
        session.unlockedWith(create("one"))
        disk = null

        assertEquals(DiskDecision.Reload, session.diskChanged(dirty = false))
    }

    @Test
    fun `a password changed elsewhere locks a clean tab`() {
        session.unlockedWith(create("one"))
        disk = changedPassword("one")

        assertEquals(DiskDecision.Lock(CHANGED_ELSEWHERE), session.diskChanged(dirty = false))
    }

    @Test
    fun `a damaged file locks a clean tab with its own message`() {
        session.unlockedWith(create("one"))
        tamper()

        assertEquals(DiskDecision.Lock("The file is damaged."), session.diskChanged(dirty = false))
    }

    @Test
    fun `a password changed elsewhere warns a dirty tab until its save replaces the file`() {
        session.unlockedWith(create("one"))
        disk = changedPassword("one")

        val decision = session.diskChanged(dirty = true)

        assertEquals(DiskDecision.Warn(checkNotNull(session.warning)), decision)
        assertEquals(SluencFailure.KEY_MISMATCH, session.warningFailure)
        assertEquals(
            "Replace notes.md.sluenc? It was changed elsewhere with another password; saving writes your version " +
                "under the password you unlocked it with.",
            session.replaceQuestion("notes.md.sluenc"),
        )
        session.write("mine".toByteArray())
        assertNull(session.warning)
        assertNull(session.replaceQuestion("notes.md.sluenc"))
    }

    @Test
    fun `a locked session ignores changes on disk`() {
        create("one").close()

        assertEquals(DiskDecision.Ignore, session.diskChanged(dirty = false))
    }

    @Test
    fun `reading or writing while locked fails`() {
        create("one").close()

        assertNotNull(runCatching { session.read() }.exceptionOrNull() as? IOException)
        assertNotNull(runCatching { session.write(ByteArray(1)) }.exceptionOrNull() as? IOException)
    }

    /** Changes the last byte of the body's tag. */
    private fun tamper() {
        val bytes = checkNotNull(disk)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
    }

    private fun changedPassword(plain: String): ByteArray =
        Sluenc
            .changePassword(
                checkNotNull(disk),
                password.toCharArray(),
                "another long password".toCharArray(),
                plain.toByteArray(),
                params,
            ).also { it.key.close() }
            .bytes

    private val newPassword = "another long password"

    /** Starts Change Password on the unlocked session and runs it; returns what `done` got ([DONE_NOT_CALLED] if not). */
    private fun changePassword(
        current: String = password,
        saveUnder: () -> Unit = { session.write("changed".toByteArray()) },
    ): Pair<String?, List<CharArray>> {
        var result: String? = DONE_NOT_CALLED
        val arrays = listOf(current.toCharArray(), newPassword.toCharArray())
        session.changePassword(arrays[0], arrays[1], saveUnder) { result = it }
        runUnlock()
        return result to arrays
    }

    @Test
    fun `changing the password swaps the key, closes the old one and writes under the new password`() {
        create("secret").close()
        unlock()
        val old = checkNotNull(session.key)

        val (result, arrays) = changePassword()

        assertNull(result)
        val fresh = checkNotNull(session.key)
        assertTrue(old.isZeroed())
        assertTrue(arrays.all { it.isZeroed() })
        assertFalse(fresh.isZeroed())
        // The new key reads the file it wrote: no KEY_MISMATCH for itself.
        assertArrayEquals("changed".toByteArray(), Sluenc.decrypt(disk!!, fresh))
        Sluenc.unlock(disk!!, newPassword.toCharArray()).close()
        val e = assertThrows(SluencException::class.java) { Sluenc.unlock(disk!!, password.toCharArray()) }
        assertEquals(SluencFailure.WRONG_PASSWORD, e.failure)
    }

    @Test
    fun `a wrong current password changes nothing and zeroes both passwords`() {
        create("secret").close()
        unlock()
        val key = checkNotNull(session.key)
        val before = disk!!.copyOf()
        var saved = false

        val (result, arrays) = changePassword("not the password") { saved = true }

        assertEquals(WRONG_CURRENT, result)
        assertFalse(saved)
        assertSame(key, session.key)
        assertFalse(key.isZeroed())
        assertArrayEquals(before, disk)
        assertTrue(arrays.all { it.isZeroed() })
    }

    @Test
    fun `a save that fails keeps the old key and drops the new one`() {
        create("secret").close()
        unlock()
        val old = checkNotNull(session.key)

        val (result, _) = changePassword { throw IOException("disk full") }

        assertEquals("notes.md.sluenc could not be saved. disk full", result)
        assertSame(old, session.key)
        assertFalse(old.isZeroed())
        assertTrue(keys.last().isZeroed())
        Sluenc.unlock(disk!!, password.toCharArray()).close()
    }

    @Test
    fun `an error after the file was written keeps the new key and is reported apart`() {
        create("secret").close()
        unlock()
        val old = checkNotNull(session.key)
        var late: String? = null
        var result: String? = DONE_NOT_CALLED

        session.changePassword(
            password.toCharArray(),
            newPassword.toCharArray(),
            {
                session.write("changed".toByteArray())
                throw IOException("reload failed")
            },
            { late = it },
        ) { result = it }
        runUnlock()

        assertNull(result)
        assertEquals("notes.md.sluenc could not be saved. reload failed", late)
        assertTrue(old.isZeroed())
        assertSame(keys.last(), session.key)
        assertArrayEquals("changed".toByteArray(), Sluenc.decrypt(disk!!, checkNotNull(session.key)))
    }

    @Test
    fun `a view that wrote nothing cannot leave the file under the old key with a new one`() {
        create("secret").close()
        unlock()
        val old = checkNotNull(session.key)

        val (result, _) = changePassword { }

        assertEquals(CANNOT_REWRITE, result)
        assertSame(old, session.key)
        assertTrue(keys.last().isZeroed())
    }

    @Test
    fun `a lock while the new key is derived drops it`() {
        create("secret").close()
        unlock()
        var result: String? = DONE_NOT_CALLED
        session.changePassword(password.toCharArray(), newPassword.toCharArray(), { fail("must not save") }) {
            result = it
        }
        while (backgroundTasks.isNotEmpty()) backgroundTasks.removeFirst().run()

        session.lock()
        while (fxTasks.isNotEmpty()) fxTasks.removeFirst().run()

        assertEquals(NOT_UNLOCKED, result)
        assertNull(session.key)
        assertTrue(keys.last().isZeroed())
    }

    @Test
    fun `changing the password of a locked session says so and zeroes the arrays`() {
        create("secret").close()
        val arrays = listOf(password.toCharArray(), newPassword.toCharArray())
        var result: String? = DONE_NOT_CALLED

        session.changePassword(arrays[0], arrays[1], { fail("must not save") }) { result = it }

        assertEquals(NOT_UNLOCKED, result)
        assertTrue(arrays.all { it.isZeroed() })
    }

    private companion object {
        const val DONE_NOT_CALLED = "done was not called"
    }
}
