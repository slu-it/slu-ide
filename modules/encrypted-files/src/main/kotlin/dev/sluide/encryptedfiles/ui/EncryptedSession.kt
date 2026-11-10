package dev.sluide.encryptedfiles.ui

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.encryptedfiles.AtomicFileWrite
import dev.sluide.encryptedfiles.Created
import dev.sluide.encryptedfiles.FileKey
import dev.sluide.encryptedfiles.Sluenc
import dev.sluide.encryptedfiles.SluencException
import dev.sluide.encryptedfiles.SluencFailure
import javafx.application.Platform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val log = System.getLogger(EncryptedSession::class.java.name)

/** Where the tab of an encrypted file is: locked, deriving the key of a typed password, or showing the content. */
internal enum class SessionState { LOCKED, UNLOCKING, UNLOCKED }

/** What a change of the file on disk means for an unlocked tab ([EncryptedSession.diskChanged]). */
internal sealed interface DiskDecision {
    /** The tab is not unlocked: nothing to do. */
    data object Ignore : DiskDecision

    /** The tab's key reads the file (or it is gone): the inner view reloads by its usual rules. */
    data object Reload : DiskDecision

    /** The key cannot read the file and the tab is clean: lock it, with [message] in the locked pane. */
    data class Lock(
        val message: String,
    ) : DiskDecision

    /** The key cannot read the file and the tab has unsaved changes: stay unlocked, the strip shows [message]. */
    data class Warn(
        val message: String,
    ) : DiskDecision
}

/**
 * The state of one encrypted file's tab, without JavaFX (ADR 0084): the key while unlocked, the message of a failed
 * unlock, the warning after the file changed elsewhere, and what the content last read or written was. Used on the FX
 * thread; only the key derivation of [unlock] runs on [background], and its result comes back through [fx], where a
 * result that is no longer wanted (the tab locked or closed meanwhile) closes its key.
 *
 * The session owns its [key]: [lock] and [dispose] close (zero) it. Passwords given to [unlock] are zeroed after the
 * derivation, whatever its outcome.
 */
internal class EncryptedSession(
    /** The encrypted file; follows it when it is moved. */
    var path: Path,
    private val readFile: (Path) -> ByteArray = Files::readAllBytes,
    private val writeFile: (Path, ByteArray) -> Unit = AtomicFileWrite::write,
    private val unlocker: (ByteArray, CharArray) -> FileKey = Sluenc::unlock,
    private val background: (Runnable) -> Unit = { NamedThreads.virtual(UNLOCK_THREAD, it) },
    private val fx: (Runnable) -> Unit = Platform::runLater,
    /** Checks the current password against the file and makes the new key; the file's bytes it returns are unused. */
    private val rekeyer: (ByteArray, CharArray, CharArray) -> Created = { file, current, new ->
        Sluenc.changePassword(file, current, new, ByteArray(0))
    },
) {
    var state = SessionState.LOCKED
        private set

    /** What the locked pane shows below the field: why the last unlock failed or why the tab locked; else `null`. */
    var failure: String? = null
        private set

    /** Why the file on disk cannot be read with the key while the tab keeps unsaved changes; `null` if it can. */
    var warningFailure: SluencFailure? = null
        private set

    private var warningVersion: Int? = null

    /** The strip's warning text for [warningFailure], or `null`. */
    val warning: String? get() = warningFailure?.let { changedOnDiskWarning(it, warningVersion) }

    /** Called on the FX thread after the state, the failure or the warning changed. */
    var onChange: () -> Unit = {}

    /** The key while [SessionState.UNLOCKED], else `null`. */
    var key: FileKey? = null
        private set

    /** Counts unlocks, locks and the dispose: an unlock result is taken only if nothing happened since it started. */
    private var generation = 0
    private var disposed = false

    /** Counts [write]s, to see that a save under a new key really wrote the file. */
    private var writes = 0

    /** The file's bytes as last read or written through the key, and whether their plain text was empty. */
    private var lastCiphertext: ByteArray? = null
    private var lastPlainEmpty = false

    /**
     * The plain bytes [read] handed out last; zeroed on the next read, on lock and on dispose. The inner view decodes
     * them at once on the FX thread, so they are not used afterwards (the decoded String stays, see ADR 0162).
     */
    private var handedOut: ByteArray? = null

    /**
     * Derives the key of [password] for the file on [background] and unlocks with it, or shows why not. Takes the
     * array: it is zeroed after the derivation, or at once if the session is not locked (then returns `false`).
     */
    fun unlock(password: CharArray): Boolean {
        if (state != SessionState.LOCKED || disposed) {
            password.fill(ZERO)
            return false
        }
        state = SessionState.UNLOCKING
        failure = null
        val run = ++generation
        val file = path
        onChange()
        try {
            background(Runnable { unlockOffFx(run, file, password) })
        } catch (e: RuntimeException) {
            password.fill(ZERO)
            log.log(System.Logger.Level.WARNING, "Cannot start unlocking: ${e.javaClass.name}")
            finishUnlock(run, UnlockResult.Failed(UNLOCK_FAILED))
        }
        return true
    }

    /**
     * The background part of [unlock]: never ends without posting a result, so the tab cannot stay at "Unlocking…",
     * and never leaves a key unclosed when the result cannot be posted.
     */
    private fun unlockOffFx(
        run: Int,
        file: Path,
        password: CharArray,
    ) {
        val result =
            try {
                attempt(file, password)
            } catch (e: Throwable) {
                // E.g. an OutOfMemoryError reading a huge file. Only the type: a message could hold content.
                log.log(System.Logger.Level.WARNING, "Cannot unlock an encrypted file: ${e.javaClass.name}")
                UnlockResult.Failed(UNLOCK_FAILED)
            } finally {
                password.fill(ZERO)
            }
        try {
            fx(Runnable { finishUnlock(run, result) })
        } catch (e: Throwable) {
            (result as? UnlockResult.Unlocked)?.key?.close()
            log.log(System.Logger.Level.WARNING, "Cannot finish unlocking an encrypted file: ${e.javaClass.name}")
        }
    }

    /**
     * Changes the password: on [background] checks [current] against the file and derives the key of [new] (a new DEK and
     * salt), then on [fx] makes it the session's key and calls [saveUnder], which must save the tab's present text
     * through [write]. [done] gets `null` on success (the old key is closed), else the text for the dialog: a wrong
     * current password, a save that wrote nothing (then the old key stays) or a tab locked meanwhile. Once the save
     * wrote the file, it is under the new key whatever [saveUnder] does afterwards (e.g. a reload that fails): the
     * old key is closed, [lateError] gets that later error and [done] gets `null`. Both arrays are zeroed afterwards,
     * also when the tab is not unlocked (then [done] gets a message at once).
     */
    fun changePassword(
        current: CharArray,
        new: CharArray,
        saveUnder: () -> Unit,
        lateError: (String) -> Unit = {},
        done: (String?) -> Unit,
    ) {
        if (state != SessionState.UNLOCKED || disposed) {
            current.fill(ZERO)
            new.fill(ZERO)
            done(NOT_UNLOCKED)
            return
        }
        val run = generation
        val file = path
        val task = Runnable { rekeyOffFx(run, file, current, new, saveUnder, lateError, done) }
        try {
            background(task)
        } catch (e: RuntimeException) {
            current.fill(ZERO)
            new.fill(ZERO)
            log.log(System.Logger.Level.WARNING, "Cannot start changing a password: ${e.javaClass.name}")
            done(CHANGE_FAILED)
        }
    }

    private fun rekeyOffFx(
        run: Int,
        file: Path,
        current: CharArray,
        new: CharArray,
        saveUnder: () -> Unit,
        lateError: (String) -> Unit,
        done: (String?) -> Unit,
    ) {
        val result =
            try {
                rekeyAttempt(file, current, new)
            } catch (e: Throwable) {
                log.log(System.Logger.Level.WARNING, "Cannot change a password: ${e.javaClass.name}")
                RekeyResult.Failed(CHANGE_FAILED)
            } finally {
                current.fill(ZERO)
                new.fill(ZERO)
            }
        try {
            fx(Runnable { finishRekey(run, result, saveUnder, lateError, done) })
        } catch (e: Throwable) {
            (result as? RekeyResult.Made)?.key?.close()
            log.log(System.Logger.Level.WARNING, "Cannot finish changing a password: ${e.javaClass.name}")
        }
    }

    private fun rekeyAttempt(
        file: Path,
        current: CharArray,
        new: CharArray,
    ): RekeyResult {
        val bytes =
            try {
                readFile(file)
            } catch (_: IOException) {
                return RekeyResult.Failed(READ_FAILED)
            }
        return try {
            RekeyResult.Made(rekeyer(bytes, current, new).key)
        } catch (e: SluencException) {
            RekeyResult.Failed(
                if (e.failure == SluencFailure.WRONG_PASSWORD) {
                    WRONG_CURRENT
                } else {
                    failureMessage(e.failure, e.formatVersion)
                },
            )
        } catch (_: OutOfMemoryError) {
            RekeyResult.Failed(OUT_OF_MEMORY)
        }
    }

    private fun finishRekey(
        run: Int,
        result: RekeyResult,
        saveUnder: () -> Unit,
        lateError: (String) -> Unit,
        done: (String?) -> Unit,
    ) {
        if (result is RekeyResult.Failed) {
            done(result.message)
            return
        }
        val fresh = (result as RekeyResult.Made).key
        val old = key
        if (run != generation || disposed || state != SessionState.UNLOCKED || old == null) {
            fresh.close()
            done(NOT_UNLOCKED)
            return
        }
        key = fresh
        val before = writes
        val failure =
            try {
                saveUnder()
                null
            } catch (e: IOException) {
                saveErrorText(path.fileName.toString(), e)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.WARNING, "Cannot save under a new password: ${e.javaClass.name}")
                CHANGE_FAILED
            }
        if (writes == before) {
            key = old
            fresh.close()
            done(failure ?: CANNOT_REWRITE)
            return
        }
        // The file is under the new key now, whatever went wrong after the write.
        old.close()
        failure?.let(lateError)
        done(null)
    }

    private sealed interface RekeyResult {
        class Made(
            val key: FileKey,
        ) : RekeyResult

        class Failed(
            val message: String,
        ) : RekeyResult
    }

    /** Unlocks with [handedOver], a key derived elsewhere (a new file's); closes it if the session is not locked. */
    fun unlockedWith(handedOver: FileKey) {
        if (state != SessionState.LOCKED || disposed) {
            handedOver.close()
            return
        }
        generation++
        key = handedOver
        state = SessionState.UNLOCKED
        failure = null
        onChange()
    }

    /**
     * Closes the key and locks, with [message] for the locked pane; also drops an unlock in progress, whose key is
     * then closed when it arrives.
     */
    fun lock(message: String? = null) {
        if (disposed) return
        generation++
        key?.close()
        key = null
        state = SessionState.LOCKED
        failure = message
        warningFailure = null
        warningVersion = null
        lastCiphertext = null
        lastPlainEmpty = false
        zeroHandedOut()
        onChange()
    }

    /**
     * Ends the session for good: closes the key and drops an unlock in progress. Returns the file's bytes as last
     * read or written if the session was unlocked and their plain text empty (an empty encrypted scratch may be
     * deleted then), else `null`.
     */
    fun dispose(): ByteArray? {
        if (disposed) return null
        // Locking forgets the bytes: a file closed locked is never recorded.
        val closedEmpty = lastCiphertext.takeIf { lastPlainEmpty }
        disposed = true
        generation++
        key?.close()
        key = null
        state = SessionState.LOCKED
        lastCiphertext = null
        zeroHandedOut()
        return closedEmpty
    }

    /**
     * The plain bytes of the file, decrypted with the key; for the inner view's content, which must use them before
     * the next read, lock or dispose, when they are zeroed.
     */
    @Throws(IOException::class)
    fun read(): ByteArray {
        val unlocked = key ?: throw IOException("The file is locked.")
        val bytes = readFile(path)
        val plain = Sluenc.decrypt(bytes, unlocked)
        zeroHandedOut()
        handedOut = plain
        lastCiphertext = bytes
        lastPlainEmpty = plain.isEmpty()
        return plain
    }

    private fun zeroHandedOut() {
        handedOut?.fill(0)
        handedOut = null
    }

    /**
     * Encrypts [plain] with the key and writes it atomically; zeroes [plain] afterwards. The file decrypts with the
     * key again, so a warning about a change elsewhere goes.
     */
    @Throws(IOException::class)
    fun write(plain: ByteArray) {
        val unlocked = key ?: throw IOException("The file is locked.")
        val empty = plain.isEmpty()
        val bytes =
            try {
                Sluenc.encrypt(plain, unlocked)
            } finally {
                plain.fill(0)
            }
        writeFile(path, bytes)
        writes++
        lastCiphertext = bytes
        lastPlainEmpty = empty
        if (warningFailure != null) {
            warningFailure = null
            warningVersion = null
            onChange()
        }
    }

    /**
     * What a change of the file on disk means, given whether the tab is [dirty]: if the key reads it (or it is
     * gone), the inner view reloads; if not, a clean tab locks and a dirty one warns, and its next save must ask
     * before replacing the file. Throws an IOException if the file cannot be read.
     */
    @Throws(IOException::class)
    fun diskChanged(dirty: Boolean): DiskDecision {
        val unlocked = key?.takeIf { state == SessionState.UNLOCKED } ?: return DiskDecision.Ignore
        val bytes =
            try {
                readFile(path)
            } catch (_: NoSuchFileException) {
                return DiskDecision.Reload
            }
        try {
            Sluenc.decrypt(bytes, unlocked).fill(0)
        } catch (e: SluencException) {
            if (!dirty) return DiskDecision.Lock(failureMessage(e.failure, e.formatVersion))
            warningFailure = e.failure
            warningVersion = e.formatVersion
            onChange()
            return DiskDecision.Warn(changedOnDiskWarning(e.failure, e.formatVersion))
        }
        if (warningFailure != null) {
            warningFailure = null
            warningVersion = null
            onChange()
        }
        return DiskDecision.Reload
    }

    /** The question before saving over the changed file, or `null` if saving needs none. */
    fun replaceQuestion(name: String): String? = warningFailure?.let { replaceQuestion(name, it, warningVersion) }

    private fun attempt(
        file: Path,
        password: CharArray,
    ): UnlockResult {
        val bytes =
            try {
                readFile(file)
            } catch (_: IOException) {
                return UnlockResult.Failed(READ_FAILED)
            }
        return try {
            UnlockResult.Unlocked(unlocker(bytes, password))
        } catch (e: SluencException) {
            UnlockResult.Failed(failureMessage(e.failure, e.formatVersion))
        } catch (_: OutOfMemoryError) {
            // A crafted file may ask Argon2 for up to 1 GiB; what it allocated is garbage now.
            UnlockResult.Failed(OUT_OF_MEMORY)
        } catch (_: IOException) {
            UnlockResult.Failed(READ_FAILED)
        } catch (e: RuntimeException) {
            // Only the type: a message could hold content.
            log.log(System.Logger.Level.WARNING, "Cannot unlock an encrypted file: ${e.javaClass.name}")
            UnlockResult.Failed(UNLOCK_FAILED)
        }
    }

    private fun finishUnlock(
        run: Int,
        result: UnlockResult,
    ) {
        if (run != generation || disposed || state != SessionState.UNLOCKING) {
            (result as? UnlockResult.Unlocked)?.key?.close()
            return
        }
        when (result) {
            is UnlockResult.Unlocked -> {
                key = result.key
                state = SessionState.UNLOCKED
            }

            is UnlockResult.Failed -> {
                state = SessionState.LOCKED
                failure = result.message
            }
        }
        onChange()
    }

    private sealed interface UnlockResult {
        class Unlocked(
            val key: FileKey,
        ) : UnlockResult

        class Failed(
            val message: String,
        ) : UnlockResult
    }

    private companion object {
        const val ZERO = '\u0000'
        const val UNLOCK_THREAD = "Unlock encrypted file"
    }
}
