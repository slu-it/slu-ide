package dev.sluide.encryptedfiles

import dev.sluide.encryptedfiles.ui.EncryptedFileView
import dev.sluide.encryptedfiles.ui.lockAllQuestion
import dev.sluide.encryptedfiles.ui.showLockAllMessage
import dev.sluide.encryptedfiles.ui.showLockAllQuestion
import dev.sluide.encryptedfiles.ui.stayedUnlockedText
import dev.sluide.fileviewers.FileView
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableIntegerValue
import javafx.stage.Window
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** An answer to a question before locking with unsaved changes. */
enum class LockChoice { SAVE, DISCARD, CANCEL }

/**
 * The encrypted files' tabs of all windows (ADR 0162): keys handed over to the next tab that opens a file, whether any
 * tab is unlocked, Lock All, and which tabs closed unlocked with empty content. FX thread, except [handOver].
 */
@Component
class EncryptedFiles internal constructor(
    private val handoverTimeout: Duration,
    private val schedule: (Duration, Runnable) -> Unit,
) {
    @Autowired
    constructor() : this(HANDOVER_TIMEOUT, { delay, task ->
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS).execute(task)
    })

    private val handovers = ConcurrentHashMap<Path, FileKey>()
    private val views = LinkedHashSet<EncryptedFileView>()
    private val closedEmpty = HashMap<Path, ByteArray>()
    private val anyUnlockedWrapper = ReadOnlyBooleanWrapper(false)
    private val revisionWrapper = ReadOnlyIntegerWrapper(0)

    /** Whether a tab of an encrypted file is unlocked in any window, e.g. to enable Lock All. */
    val anyUnlocked: ObservableBooleanValue = anyUnlockedWrapper.readOnlyProperty

    /** Counts every change of a tab's lock state, for what depends on one tab's state ([isUnlockedEncrypted]). */
    val revision: ObservableIntegerValue = revisionWrapper.readOnlyProperty

    /**
     * Asked once before [lockAll] locks tabs of which `count` have unsaved changes: save them all, discard the changes,
     * or lock nothing. A dialog owned by `owner`, Cancel the default; tests replace it.
     */
    var askLockAll: (owner: Window?, count: Int) -> LockChoice = { owner, count ->
        showLockAllQuestion(owner, lockAllQuestion(count))
    }

    /**
     * Told after [lockAll] when `count` tabs stayed unlocked because saving their changes failed (each strip says
     * why): one message owned by `owner`; tests replace it.
     */
    var showStayedUnlocked: (owner: Window?, count: Int) -> Unit = { owner, count ->
        showLockAllMessage(owner, stayedUnlockedText(count))
    }

    /**
     * Gives [key], derived for [file] (a file just created with it), to the next tab that opens [file], which then
     * starts unlocked without deriving it again. The registry owns the key until then; one not taken within a minute
     * is closed (zeroed), and so is one replaced by a newer handover for the same file. Any thread.
     */
    fun handOver(
        file: Path,
        key: FileKey,
    ) {
        val path = normalized(file)
        handovers.put(path, key)?.takeIf { it !== key }?.close()
        schedule(handoverTimeout) { if (handovers.remove(path, key)) key.close() }
    }

    /** The key handed over for [file], once; the caller owns it then. */
    internal fun takeHandover(file: Path): FileKey? = handovers.remove(normalized(file))

    /**
     * Locks every unlocked encrypted tab of all windows. If some have unsaved changes, asks once ([askLockAll], owned
     * by [owner]): save them and lock (a tab whose save fails stays unlocked and shows why, and one message
     * ([showStayedUnlocked]) counts those tabs), discard the changes and lock, or lock nothing.
     */
    fun lockAll(owner: Window?) {
        val unlocked = views.filter { it.isUnlocked || it.isUnlocking }
        val dirty = unlocked.count { it.isDirty }
        val saveFirst =
            if (dirty == 0) {
                false
            } else {
                when (askLockAll(owner, dirty)) {
                    LockChoice.SAVE -> true
                    LockChoice.DISCARD -> false
                    LockChoice.CANCEL -> return
                }
            }
        val stayed = unlocked.count { !it.lockNow(saveFirst) }
        if (stayed > 0) showStayedUnlocked(owner, stayed)
    }

    /**
     * The file's bytes as last read or written, if [file]'s tab closed unlocked with empty content since the last
     * call; once. For deleting an empty encrypted scratch only if its bytes on disk are still those.
     */
    fun takeClosedEmpty(file: Path): ByteArray? = closedEmpty.remove(normalized(file))

    /** Whether [view] is an encrypted file's view that is unlocked now, e.g. to enable Lock Encrypted File. */
    fun isUnlockedEncrypted(view: FileView): Boolean = (view as? EncryptedFileView)?.isUnlocked == true

    /** Locks [view] if it is an unlocked encrypted file's view, asking first about unsaved changes. */
    fun lock(view: FileView) {
        (view as? EncryptedFileView)?.lock()
    }

    internal fun register(view: EncryptedFileView) {
        views += view
        stateChanged()
    }

    /** Forgets [view], which closed at [file]; records [closedEmpty] (see [takeClosedEmpty]) unless `null`. */
    internal fun closed(
        view: EncryptedFileView,
        file: Path,
        closedEmpty: ByteArray?,
    ) {
        val path = normalized(file)
        if (closedEmpty != null) this.closedEmpty[path] = closedEmpty else this.closedEmpty.remove(path)
        views -= view
        stateChanged()
    }

    /** Called by a view whose state changed. */
    internal fun stateChanged() {
        anyUnlockedWrapper.set(views.any { it.isUnlocked })
        revisionWrapper.set(revisionWrapper.get() + 1)
    }

    private fun normalized(file: Path): Path = file.toAbsolutePath().normalize()

    private companion object {
        val HANDOVER_TIMEOUT: Duration = Duration.ofMinutes(1)
    }
}
