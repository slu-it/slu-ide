package dev.sluide.media

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FocusWhenShown
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize

/**
 * An audio or video file in a tab (ADR 0123): the [MediaStage] above, the [MediaControlBar] and a status line below.
 * FX thread only. Volume and mute start from the [MediaPreferences] (set before the view listens, so opening saves
 * nothing) and every later change is saved there. Starting playback tells the [PlaybackCoordinator]. A reload builds
 * a new player (with new stage and bar) that continues where the old one was. The view adds listeners only to its own
 * playback, and removes them when the playback goes. A reload happens only when the file's size or modification time
 * changed. The resumed play after a reload counts as a start and pauses another playing tab (accepted).
 */
internal class MediaFileView(
    private val file: Path,
    private val coordinator: PlaybackCoordinator,
    private val preferences: MediaPreferences,
    private val playbackFactory: (Path) -> Playback,
) : FileView {
    /** What belongs to one playback; replaced together on reload (a bar's key handler sits on its own [content]). */
    private class Session(
        val playback: Playback,
        val bar: MediaControlBar,
        val content: BorderPane,
        val status: Label,
    ) {
        private val detachers = mutableListOf<() -> Unit>()

        fun <T> listen(
            value: ObservableValue<T>,
            action: () -> Unit,
        ) {
            val listener = ChangeListener<T> { _, _, _ -> action() }
            value.addListener(listener)
            detachers += { value.removeListener(listener) }
        }

        fun detach() {
            detachers.forEach { it() }
            detachers.clear()
        }
    }

    private val root = StackPane().apply { setMinSize(0.0, 0.0) }
    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)
    private val pendingFocus = FocusWhenShown()
    private var disposed = false

    /** A reload whose new playback is not ready yet; a second reload keeps it, as the new playback knows nothing yet. */
    private var resume: ResumePlan? = null
    private var stamp: FileStamp? = stampOf()
    private var session: Session = start(preferences.volume, preferences.muted)

    init {
        root.children.setAll(session.content)
        // A click anywhere in the view gives it the focus (the stage and the buttons take none), so the keys work.
        root.addEventFilter(MouseEvent.MOUSE_PRESSED) { session.content.requestFocus() }
    }

    private fun stampOf(): FileStamp? =
        try {
            FileStamp(file.fileSize(), Files.getLastModifiedTime(file))
        } catch (_: IOException) {
            null
        }

    override val node: Node get() = root

    override val activity: ObservableValue<ViewActivity> get() = activityWrapper.readOnlyProperty

    private fun start(
        volume: Double,
        muted: Boolean,
    ): Session {
        val playback = playbackFactory(file)
        // Before the listeners below: opening must not save.
        playback.volume.set(volume)
        playback.muted.set(muted)
        val bar = MediaControlBar(playback) { coordinator.started(playback) }
        val status = Label().apply { styleClass += "media-status" }
        val content =
            BorderPane().apply {
                styleClass += "media-view"
                isFocusTraversable = true
                stylesheets +=
                    checkNotNull(
                        MediaFileView::class.java.getResource("/dev/sluide/media/media-view.css"),
                    ).toExternalForm()
                center = MediaStage(playback, file, MediaFileViewer.isVideo(file)).node
                bottom = VBox(bar.node, status)
            }
        bar.install(content)
        val created = Session(playback, bar, content, status)
        created.listen(playback.volume) { preferences.save(playback.volume.get(), playback.muted.get()) }
        created.listen(playback.muted) { preferences.save(playback.volume.get(), playback.muted.get()) }
        created.listen(playback.status) { statusChanged(created) }
        created.listen(playback.videoSize) { refreshStatusLine(created) }
        refreshStatusLine(created)
        statusChanged(created)
        return created
    }

    private fun statusChanged(current: Session) {
        val status = current.playback.status.get()
        val failed = status == PlaybackStatus.FAILED
        current.bar.node.isVisible = !failed
        current.bar.node.isManaged = !failed
        activityWrapper.set(activityFor(status))
    }

    private fun refreshStatusLine(current: Session) {
        val size = runCatching { file.fileSize() }.getOrDefault(0L)
        current.status.text = MediaInfo.describe(file, size, current.playback.videoSize.get())
    }

    @Throws(IOException::class)
    override fun reload() {
        if (disposed) return
        val current = FileStamp(file.fileSize(), Files.getLastModifiedTime(file))
        if (!needsReload(stamp, current)) return
        stamp = current
        val old = session
        val plan = resume ?: resumePlan(old.playback.status.get(), old.playback.currentTime.get())
        // The scene's focus owner, not isFocused: that is false while the window is inactive (a file rewritten by another app).
        val hadFocus = root.scene?.focusOwner === old.content
        old.detach()
        old.playback.dispose()
        coordinator.disposed(old.playback)
        resume = plan
        // This tab's own volume goes on, which is what the preferences hold unless the save is still on its way.
        val next = start(old.playback.volume.get(), old.playback.muted.get())
        session = next
        root.children.setAll(next.content)
        if (hadFocus) next.content.requestFocus()
        whenReady(next) {
            resume = null
            next.playback.seek(clampPosition(plan.position, next.playback.totalDuration.get()))
            if (plan.play) {
                coordinator.started(next.playback)
                next.playback.play()
            }
        }
    }

    /**
     * Runs [action] once [target]'s playback is READY, never if [target] is replaced first. A failure does not end the
     * wait: the engine undoes a failure that was another media's, and the plan stays for the next reload.
     */
    private fun whenReady(
        target: Session,
        action: () -> Unit,
    ) {
        if (target.playback.status.get() == PlaybackStatus.READY) return action()
        var done = false
        target.listen(target.playback.status) {
            if (done || session !== target) return@listen
            when (target.playback.status.get()) {
                PlaybackStatus.READY -> {
                    done = true
                    action()
                }

                else -> {}
            }
        }
    }

    override fun focusText() = session.content.requestFocus()

    override fun focus() = pendingFocus.request(root) { if (!disposed) focusText() }

    override fun dispose() {
        if (disposed) return
        disposed = true
        pendingFocus.cancel()
        session.detach()
        session.playback.dispose()
        coordinator.disposed(session.playback)
    }
}
