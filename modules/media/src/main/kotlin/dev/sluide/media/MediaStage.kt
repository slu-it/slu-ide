package dev.sluide.media

import javafx.beans.binding.Bindings
import javafx.collections.MapChangeListener
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.media.MediaView
import javafx.scene.shape.Rectangle
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.math.max
import kotlin.math.min

/**
 * The center of the media view: the video, or cover art with the tags for audio, or a notice while the player failed
 * (ADR 0123). FX thread only; [playback] belongs to the caller.
 */
internal class MediaStage(
    private val playback: Playback,
    private val file: Path,
    private val video: Boolean,
) {
    private val videoNode: Node? = if (video) playback.createVideoNode() else null

    private val videoPane =
        StackPane().apply {
            styleClass += "media-stage-video"
            setMinSize(0.0, 0.0)
            if (videoNode != null) {
                children += videoNode
                if (videoNode is MediaView) {
                    videoNode.isPreserveRatio = true
                    // A fit size of 0 means "no fit": never let it reach 0.
                    videoNode.fitWidthProperty().bind(
                        Bindings.createDoubleBinding({ max(1.0, width) }, widthProperty()),
                    )
                    videoNode.fitHeightProperty().bind(
                        Bindings.createDoubleBinding({ max(1.0, height) }, heightProperty()),
                    )
                }
            }
        }

    private val coverView =
        ImageView().apply {
            styleClass += "media-stage-cover"
            isPreserveRatio = true
        }
    private val icon =
        FontIcon(MaterialDesignM.MUSIC_NOTE).apply {
            styleClass += "media-stage-icon"
            iconSize = 120
        }
    private val coverHolder = StackPane().apply { alignment = Pos.CENTER }
    private val title = label("media-stage-title")
    private val artist = label("media-stage-artist")
    private val album = label("media-stage-album")
    private val audioColumn =
        VBox(coverHolder, title, artist, album).apply { styleClass += "media-stage-column" }

    private val failedName = label("media-stage-title")
    private val failedSize = label("media-stage-size")
    private val failedMessage = label("media-stage-message")
    private val failedColumn =
        VBox(failedName, failedSize, failedMessage).apply { styleClass += "media-stage-column" }

    private val root =
        StackPane().apply {
            styleClass += "media-stage"
            setMinSize(0.0, 0.0)
            stylesheets +=
                checkNotNull(MediaStage::class.java.getResource("/dev/sluide/media/media-stage.css")).toExternalForm()
        }

    val node: Node = root

    init {
        root.clip =
            Rectangle().apply {
                widthProperty().bind(root.widthProperty())
                heightProperty().bind(root.heightProperty())
            }
        // The cover shrinks with the area (room is kept for the padding and the text lines), at most COVER_SIZE.
        coverView.fitWidthProperty().bind(
            Bindings.createDoubleBinding(
                { coverFit(root.width, root.height).first },
                root.widthProperty(),
                root.heightProperty(),
            ),
        )
        coverView.fitHeightProperty().bind(
            Bindings.createDoubleBinding(
                { coverFit(root.width, root.height).second },
                root.widthProperty(),
                root.heightProperty(),
            ),
        )
        playback.status.addListener { _, _, _ -> refresh() }
        playback.error.addListener { _, _, _ -> refresh() }
        playback.metadata.addListener(MapChangeListener { refresh() })
        refresh()
    }

    private fun refresh() {
        when (stageKind(playback.status.value, video, videoNode != null)) {
            StageKind.VIDEO -> {
                root.children.setAll(videoPane)
            }

            StageKind.AUDIO -> {
                showAudio()
                root.children.setAll(audioColumn)
            }

            StageKind.FAILED -> {
                showFailed()
                root.children.setAll(failedColumn)
            }
        }
    }

    private fun label(styleClass: String) =
        Label().apply {
            this.styleClass += styleClass
            isWrapText = true
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER
        }

    private fun showAudio() {
        val lines = audioLines(playback.metadata, file.name)
        title.text = lines.title
        show(artist, lines.artist)
        show(album, lines.album)
        val cover = playback.metadata["image"] as? Image
        if (cover != null) {
            coverView.image = cover
            coverHolder.children.setAll(coverView)
        } else {
            coverHolder.children.setAll(icon)
        }
    }

    private fun show(
        label: Label,
        text: String?,
    ) {
        label.text = text.orEmpty()
        label.isVisible = text != null
        label.isManaged = text != null
    }

    private fun showFailed() {
        val size = runCatching { file.fileSize() }.getOrDefault(0L)
        val notice = failedNotice(file.name, size, playback.error.value)
        failedName.text = notice.name
        failedSize.text = notice.size
        failedMessage.text = notice.message
    }
}
