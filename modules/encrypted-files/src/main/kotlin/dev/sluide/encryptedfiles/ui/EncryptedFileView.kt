package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.FileKey
import dev.sluide.encryptedfiles.LockChoice
import dev.sluide.encryptedfiles.SluencException
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettings
import dev.sluide.fileviewers.ContentFileViewer
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileContent
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.FocusWhenShown
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableBooleanValue
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.util.Subscription
import java.io.IOException
import java.nio.file.Path

private val log = System.getLogger(EncryptedFileView::class.java.name)

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
    private val settings: EncryptedFilesSettings,
    handover: FileKey? = null,
    internal val session: EncryptedSession = EncryptedSession(file),
    /** Makes the idle lock from the minutes setting and what to do when idle; tests give it a short unit. */
    idleLockFactory: (minutes: () -> Int, onIdle: () -> Unit) -> IdleLock = { minutes, onIdle ->
        IdleLock(minutes, onIdle)
    },
) : EditableFileView {
    private var path: Path = file
    private val name: String get() = path.fileName.toString()
    private var innerViewer: ContentFileViewer? = viewerFor(innerName(name))

    /** The inner type's view while unlocked, else `null`. */
    internal var inner: FileView? = null
        private set

    private var hostValue: FileViewHost? = null
    private var disposed = false

    /** Whether the field had the focus when Unlock was pressed: it gets it back if the unlock fails. */
    private var focusAfterUnlock = false

    /** Whether a key press locked the tab (overdue idle lock): its typed character must not reach the field. */
    private var swallowTyped = false

    /** Why the last save before a lock failed, or why the idle lock did not lock; in the strip until a save or lock. */
    private var saveError: String? = null

    private val dirtyWrapper = ReadOnlyBooleanWrapper(false)

    /** A read while the tab has unsaved changes is not the inner view's saved state ([EncryptedSession.checkDisk]). */
    private val content = EncryptedContent(session) { dirtyWrapper.get() }
    private val markedWrapper = ReadOnlyBooleanWrapper(true)
    private val pendingFocus = FocusWhenShown()

    private val lockedPane = LockedPane(::unlock)
    private val strip = UnlockedStrip(::lock, ::changePassword)
    private val unlockedPane = BorderPane().apply { top = strip.node }
    private val root =
        StackPane(lockedPane.node).apply {
            styleClass += "encrypted-file-view"
            stylesheets += checkNotNull(EncryptedFileView::class.java.getResource(CSS)).toExternalForm()
        }

    private val lockItem = MenuItem("Lock").apply { setOnAction { lock() } }
    private val changePasswordItem = MenuItem(CHANGE_PASSWORD_TEXT).apply { setOnAction { changePassword() } }

    private val idleLock = idleLockFactory(settings::currentIdleLockMinutes, ::idleFired)
    private val idleSettingListener = ChangeListener<Number> { _, _, _ -> idleLock.settingChanged() }
    private var idleOn = false

    /** The idle time is checked again when the tab's window gets the focus back, e.g. after the computer slept. */
    private val windowFocus: Subscription =
        root
            .sceneProperty()
            .flatMap { it.windowProperty() }
            .flatMap { it.focusedProperty() }
            .subscribe { focused -> if (focused == true) idleLock.check() }

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

    override val tabMenuItems: List<MenuItem> get() = listOf(lockItem, changePasswordItem)

    override var host: FileViewHost?
        get() = hostValue
        set(value) {
            hostValue = value
            inner?.host = value
        }

    init {
        // While unlocked, a key press, mouse press or scroll in the tab counts as activity (the idle lock).
        root.addEventFilter(KeyEvent.KEY_PRESSED, ::activity)
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, ::activity)
        root.addEventFilter(ScrollEvent.SCROLL, ::activity)
        root.addEventFilter(KeyEvent.KEY_TYPED) { if (swallowTyped) it.consume() }
        root.addEventFilter(KeyEvent.KEY_RELEASED) { swallowTyped = false }
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
     * Asks for the current and a new password, then saves the tab's present text, unsaved changes included, under the
     * new key and password (a new data key and salt); the tab stays unlocked and is clean afterwards. The text is saved
     * through the inner view, so its own encoding and line endings stay. Nothing while locked.
     */
    fun changePassword() {
        if (!isUnlocked) return
        // Saving under the new password replaces a file changed elsewhere, as any save does.
        try {
            session.checkDisk()
        } catch (_: IOException) {
            // Checking the current password reads the file again and says why it cannot.
        }
        session.replaceQuestion(name)?.let { question -> if (!askReplace(question)) return }
        showPasswordDialog(
            root.scene?.window,
            CHANGE_PASSWORD_TITLE,
            withCurrent = true,
            note = CHANGE_PASSWORD_NOTE,
            confirmText = CHANGE_PASSWORD_BUTTON,
            busyText = CHANGING,
        ) { current, new, done ->
            var late: String? = null
            session.changePassword(current, new, { (inner as? EditableFileView)?.save() }, { late = it }) { error ->
                if (error == null) {
                    // An error after the file was written (e.g. the view's reload) shows in the strip.
                    saveError = late
                    render()
                }
                done(error, error == NOT_UNLOCKED)
            }
        }
    }

    /**
     * The idle time passed: saves and locks. With unsaved changes it first reads the file and never saves over one
     * that changed since the tab read or wrote it (with any password) or that is gone: the tab then stays unlocked and
     * the next period tries again, as after a failed save, and the strip says so. A clean tab locks with or without the
     * warning.
     */
    private fun idleFired() {
        if (!isUnlocked) return
        if (isDirty) {
            val disk =
                try {
                    session.checkDisk()
                } catch (e: IOException) {
                    saveError = saveErrorText(name, e)
                    render()
                    return
                }
            if (disk != DiskCheck.UNCHANGED) {
                // Until a save or a lock, the strip says why the tab stays unlocked.
                saveError = IDLE_NOT_LOCKED
                render()
                return
            }
        }
        lockNow(saveFirst = true)
    }

    /**
     * A key press, mouse press or scroll in the tab. If the idle time had already passed (the computer slept) the tab
     * locks instead, and the event, meant for content that is gone, goes no further; nor do the characters a locking
     * key press types until it is released, so none lands in the password field.
     */
    private fun activity(event: Event) {
        if (event.eventType == KeyEvent.KEY_PRESSED) swallowTyped = false
        val wasUnlocked = isUnlocked
        idleLock.activity()
        if (wasUnlocked && !isUnlocked) {
            event.consume()
            swallowTyped = event.eventType == KeyEvent.KEY_PRESSED
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

    /**
     * Nothing while locked; otherwise reads the file first (its change event may come late or not at all) and saves
     * through the inner view, asking first if the file changed elsewhere with another password or is damaged.
     */
    override fun save() {
        val view = inner as? EditableFileView ?: return
        session.checkDisk()
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
        try {
            dropInner()
        } finally {
            val closedEmpty = session.dispose()
            disposed = true
            idleLock.stop()
            windowFocus.unsubscribe()
            settings.idleLockMinutes.removeListener(idleSettingListener)
            idleOn = false
            lockedPane.field.clear()
            registry.closed(this, path, closedEmpty)
        }
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
        changePasswordItem.isDisable = !isUnlocked
        followIdleLock()
        markedWrapper.set(!isUnlocked)
        registry.stateChanged()
    }

    /** Counts the idle time while unlocked, by the setting as it changes. */
    private fun followIdleLock() {
        if (isUnlocked == idleOn) return
        idleOn = isUnlocked
        if (idleOn) {
            settings.idleLockMinutes.addListener(idleSettingListener)
            idleLock.start()
        } else {
            settings.idleLockMinutes.removeListener(idleSettingListener)
            idleLock.stop()
        }
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
        try {
            dropInner()
        } finally {
            saveError = null
            session.lock(message)
        }
        if (hadFocus) lockedPane.field.requestFocus()
    }

    /**
     * Wipes, disposes and forgets the inner view; the tab is clean afterwards. Each step runs even if one before it
     * failed, which is logged by its type only.
     */
    private fun dropInner() {
        val view = inner ?: return
        inner = null
        dirtyWrapper.unbind()
        dirtyWrapper.set(false)
        unlockedPane.center = null
        quietly("wipe") { view.wipe() }
        quietly("dispose") { view.dispose() }
        quietly("detach") { view.host = null }
    }

    private inline fun quietly(
        step: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (e: Exception) {
            // Only the type: a message could hold content.
            log.log(System.Logger.Level.WARNING, "Cannot $step the view of an encrypted file: ${e.javaClass.name}")
        }
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
    private val dirty: () -> Boolean,
) : FileContent {
    override fun read(): ByteArray = session.read(adopted = !dirty())

    override fun adopted() = session.adopt()

    /** Zeroes [bytes] afterwards. */
    override fun write(bytes: ByteArray) = session.write(bytes)

    override val confidential: Boolean get() = true
}
