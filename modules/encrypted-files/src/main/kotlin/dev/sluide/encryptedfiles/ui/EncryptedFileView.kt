package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.FileKey
import dev.sluide.encryptedfiles.LockChoice
import dev.sluide.encryptedfiles.SluencException
import dev.sluide.fileviewers.ContentFileViewer
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileContent
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FocusWhenShown
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.value.ObservableBooleanValue
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import java.io.IOException
import java.nio.file.Path

/**
 * The tab of an encrypted file (ADR 0162). Locked, it shows the [LockedPane]; unlocked, the [UnlockedStrip] above the
 * view of the inner type (`notes.md` for `notes.md.sluenc`), which reads and saves through [EncryptedContent] and is
 * shown under the virtual path beside the file. Locking and closing wipe and drop the inner view and zero the key.
 * Not a `TextEditableView`, so no other view or window can read the text; diffs and change markers are never passed
 * on. The decisions are [EncryptedSession]'s; this class shows them. FX thread.
 */
internal class EncryptedFileView(
    file: Path,
    private val registry: EncryptedFiles,
    private val viewerFor: (innerName: String) -> ContentFileViewer?,
    handover: FileKey? = null,
    internal val session: EncryptedSession = EncryptedSession(file),
) : EditableFileView {
    private var path: Path = file
    private val name: String get() = path.fileName.toString()
    private var innerViewer: ContentFileViewer? = viewerFor(innerName(name))
    private val content = EncryptedContent(session)

    /** The inner type's view while unlocked, else `null`. */
    internal var inner: FileView? = null
        private set

    private var hostValue: FileViewHost? = null
    private var disposed = false

    /** Whether the field had the focus when Unlock was pressed: it gets it back if the unlock fails. */
    private var focusAfterUnlock = false

    /** Why the last save from the strip's side (before a lock) failed; shown in the strip until the next save. */
    private var saveError: String? = null

    private val dirtyWrapper = ReadOnlyBooleanWrapper(false)
    private val markedWrapper = ReadOnlyBooleanWrapper(true)
    private val pendingFocus = FocusWhenShown()

    private val lockedPane = LockedPane(::unlock)
    private val strip = UnlockedStrip(::lock)
    private val unlockedPane = BorderPane().apply { top = strip.node }
    private val root =
        StackPane(lockedPane.node).apply {
            styleClass += "encrypted-file-view"
            stylesheets += checkNotNull(EncryptedFileView::class.java.getResource(CSS)).toExternalForm()
        }

    private val lockItem = MenuItem("Lock").apply { setOnAction { lock() } }

    /**
     * Asked before locking with unsaved changes ("`<name>` has unsaved changes."): Save, Don't Save or Cancel. A dialog
     * owned by the view's window, Cancel the default; tests replace it.
     */
    var askUnsaved: (question: String) -> LockChoice = { question ->
        showUnsavedQuestion(root.scene?.window, question)
    }

    /** Asked before a save replaces a file changed elsewhere: Replace (`true`) or Cancel. Tests replace it. */
    var askReplace: (question: String) -> Boolean = { question -> showReplaceQuestion(root.scene?.window, question) }

    /** Whether the tab shows the content now. */
    internal val isUnlocked: Boolean get() = session.state == SessionState.UNLOCKED

    /** Whether a typed password is being checked. */
    internal val isUnlocking: Boolean get() = session.state == SessionState.UNLOCKING

    internal val isDirty: Boolean get() = dirtyWrapper.get()

    override val node: Node get() = root

    override val dirty: ObservableBooleanValue = dirtyWrapper.readOnlyProperty

    override val readOnlyMarked: ObservableBooleanValue = markedWrapper.readOnlyProperty

    override val lockMarkerText: String get() = LOCKED_MARKER_TEXT

    // P3b adds Change Password… after Lock.
    override val tabMenuItems: List<MenuItem> get() = listOf(lockItem)

    override var host: FileViewHost?
        get() = hostValue
        set(value) {
            hostValue = value
            inner?.host = value
        }

    init {
        session.onChange = ::render
        registry.register(this)
        if (handover != null) {
            if (innerViewer != null) session.unlockedWith(handover) else handover.close()
        }
        render()
    }

    /** Locks the tab, asking first if it has unsaved changes (Save, Don't Save, Cancel). */
    fun lock() {
        if (!isUnlocked) return
        if (!isDirty) {
            lockNow(saveFirst = false)
            return
        }
        when (askUnsaved(unsavedQuestion(name))) {
            LockChoice.SAVE -> {
                lockNow(saveFirst = true)
            }

            LockChoice.DISCARD -> {
                lockNow(saveFirst = false)
            }

            LockChoice.CANCEL -> {}
        }
    }

    /**
     * Locks the tab without asking, saving unsaved changes first if [saveFirst] (else dropping them); also stops an
     * unlock in progress. Returns `false`, staying unlocked with the error in the strip, if the save failed.
     */
    internal fun lockNow(saveFirst: Boolean): Boolean {
        if (disposed) return true
        if (isUnlocking) {
            session.lock()
            return true
        }
        if (!isUnlocked) return true
        if (saveFirst && isDirty) {
            try {
                save()
            } catch (e: IOException) {
                saveError = saveErrorText(name, e)
                render()
                return false
            }
            if (isDirty) return false
        }
        lockAndWipe(null)
        return true
    }

    /** Nothing while locked; otherwise saves through the inner view, asking first if the file changed elsewhere. */
    override fun save() {
        val view = inner as? EditableFileView ?: return
        session.replaceQuestion(name)?.let { question ->
            if (!askReplace(question)) throw IOException(REPLACE_CANCELLED)
        }
        view.save()
        saveError = null
        render()
    }

    /**
     * Nothing while locked. Unlocked, the file is decrypted first: with the tab's key, the inner view reloads as usual
     * (or shows the conflict banner); with another key or damaged, a clean tab locks and a dirty one warns.
     */
    override fun reload() {
        val view = inner ?: return
        when (val decision = session.diskChanged(isDirty)) {
            DiskDecision.Reload -> {
                view.reload()
            }

            is DiskDecision.Lock -> {
                lockAndWipe(decision.message)
            }

            is DiskDecision.Warn, DiskDecision.Ignore -> {}
        }
    }

    /** Locks without asking, dropping unsaved changes; the content is wiped as on [lock]. */
    override fun wipe() {
        if (isUnlocked || isUnlocking) lockNow(saveFirst = false)
    }

    /**
     * Wipes and drops the inner view and zeroes the key; records an empty encrypted file closed unlocked for the
     * scratches ([EncryptedFiles.takeClosedEmpty]).
     */
    override fun dispose() {
        if (disposed) return
        pendingFocus.cancel()
        dropInner()
        val closedEmpty = session.dispose()
        disposed = true
        lockedPane.field.clear()
        registry.closed(this, path, closedEmpty)
    }

    override fun focusText() {
        val view = inner
        if (view != null) view.focusText() else lockedPane.field.requestFocus()
    }

    override fun focus() {
        val view = inner
        if (view != null) {
            pendingFocus.cancel()
            view.focus()
        } else {
            pendingFocus.request(lockedPane.field) { if (!disposed && inner == null) lockedPane.field.requestFocus() }
        }
    }

    /**
     * Follows the file to [to]; `false` if the inner view cannot follow. The inner type is taken from the new name: a
     * locked tab shows it at once, an unlocked one keeps its view until it is locked and unlocked again.
     */
    override fun moved(to: Path): Boolean {
        val view = inner
        if (view != null && !view.moved(virtualPath(to))) return false
        path = to
        session.path = to
        innerViewer = viewerFor(innerName(name))
        if (view == null) render()
        return true
    }

    override fun showLine(line: Int) {
        inner?.showLine(line)
    }

    override fun showPosition(
        line: Int,
        column: Int,
    ) {
        inner?.showPosition(line, column)
    }

    private fun unlock() {
        if (session.state != SessionState.LOCKED || innerViewer == null) return
        val password = lockedPane.field.text.toCharArray()
        lockedPane.field.clear()
        if (password.isEmpty()) return
        // Disabling the field may move the focus away; a failed unlock gives it back.
        focusAfterUnlock = focusInside()
        session.unlock(password)
    }

    /** Shows what the session says: the locked pane, or the strip and the inner view. */
    private fun render() {
        if (disposed) return
        if (session.state == SessionState.UNLOCKED) showUnlocked() else showLocked()
        lockItem.isDisable = !isUnlocked
        markedWrapper.set(!isUnlocked)
        registry.stateChanged()
    }

    private fun showLocked() {
        dropInner()
        if (root.children.singleOrNull() !== lockedPane.node) root.children.setAll(lockedPane.node)
        val viewer = innerViewer
        val busy = session.state == SessionState.UNLOCKING
        val text =
            if (viewer == null) {
                cannotShowText(innerName(name))
            } else if (busy) {
                UNLOCKING
            } else {
                session.failure
            }
        lockedPane.show(name, canUnlock = viewer != null, busy = busy, text = text)
        // After a failed unlock, the field is empty and ready for the next try.
        if (!busy && focusAfterUnlock) {
            focusAfterUnlock = false
            lockedPane.field.requestFocus()
        }
    }

    private fun showUnlocked() {
        if (inner == null && !createInner()) return
        strip.show(saveError ?: session.warning)
        if (root.children.singleOrNull() !== unlockedPane) {
            val hadFocus = focusInside() || focusAfterUnlock
            focusAfterUnlock = false
            root.children.setAll(unlockedPane)
            if (hadFocus) inner?.focus()
        }
    }

    /** Opens the inner view on the decrypted content; locks with the reason if that fails. */
    private fun createInner(): Boolean {
        val viewer = innerViewer
        val view =
            try {
                viewer?.open(virtualPath(path), content)
            } catch (e: SluencException) {
                session.lock(failureMessage(e.failure, e.formatVersion))
                return false
            } catch (_: IOException) {
                session.lock(READ_FAILED)
                return false
            }
        if (view == null) {
            session.lock()
            return false
        }
        view.host = hostValue
        inner = view
        unlockedPane.center = view.node
        (view as? EditableFileView)?.let { dirtyWrapper.bind(it.dirty) }
        return true
    }

    /** Locks the session after wiping the inner view; the field gets the focus if the tab had it. */
    private fun lockAndWipe(message: String?) {
        val hadFocus = focusInside()
        dropInner()
        saveError = null
        session.lock(message)
        if (hadFocus) lockedPane.field.requestFocus()
    }

    /** Wipes, disposes and forgets the inner view; the tab is clean afterwards. */
    private fun dropInner() {
        val view = inner ?: return
        inner = null
        dirtyWrapper.unbind()
        dirtyWrapper.set(false)
        unlockedPane.center = null
        view.wipe()
        view.dispose()
        view.host = null
    }

    /** Whether the focus is on a node of this view. */
    private fun focusInside(): Boolean {
        var node: Node? = root.scene?.focusOwner
        while (node != null) {
            if (node === root) return true
            node = node.parent
        }
        return false
    }

    private companion object {
        const val CSS = "/dev/sluide/encryptedfiles/encrypted-files.css"
    }
}

/** The content of an unlocked encrypted file: decrypted on read, encrypted and written atomically on write. */
internal class EncryptedContent(
    private val session: EncryptedSession,
) : FileContent {
    override fun read(): ByteArray = session.read()

    /** Zeroes [bytes] afterwards. */
    override fun write(bytes: ByteArray) = session.write(bytes)

    override val confidential: Boolean get() = true
}
