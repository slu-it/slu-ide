package dev.sluide.media

import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Plays audio (mp3, wav, aif, aiff, m4a) and video (mp4, m4v) files in a [MediaFileView] (ADR 0123). */
@Component
class MediaFileViewer internal constructor(
    private val coordinator: PlaybackCoordinator,
    private val preferences: MediaPreferences,
    private val playbackFactory: (Path) -> Playback,
) : ExtensionFileViewer {
    @Autowired
    constructor(
        coordinator: PlaybackCoordinator,
        preferences: MediaPreferences,
    ) : this(coordinator, preferences, ::JavaFxPlayback)

    override val fileExtensions: Set<String> = AUDIO + VIDEO

    override fun canOpen(file: Path): Boolean {
        if (file.extension.lowercase(Locale.ROOT) !in fileExtensions) return false
        return try {
            file.isRegularFile() && Files.isReadable(file)
        } catch (_: IOException) {
            false
        }
    }

    override fun open(file: Path): FileView = MediaFileView(file, coordinator, preferences, playbackFactory)

    internal companion object {
        val AUDIO = setOf("mp3", "wav", "aif", "aiff", "m4a")
        val VIDEO = setOf("mp4", "m4v")

        fun isVideo(file: Path) = file.extension.lowercase(Locale.ROOT) in VIDEO
    }
}
