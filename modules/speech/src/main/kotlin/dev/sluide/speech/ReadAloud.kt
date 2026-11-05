package dev.sluide.speech

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.media.PlaybackCoordinator
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.speech.audio.AudioSink
import dev.sluide.speech.audio.JavaSoundSink
import dev.sluide.speech.editor.EditorReading
import dev.sluide.speech.editor.ReadAloudEditorControls
import dev.sluide.speech.editor.ReadAloudExtension
import dev.sluide.speech.engine.SpeechEngines
import dev.sluide.speech.install.ModelInstaller
import dev.sluide.speech.ui.LiveReadAloudHost
import dev.sluide.speech.ui.ReadAloudModel
import dev.sluide.speech.ui.ReadAloudTab
import dev.sluide.speech.ui.ReadAloudView
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.util.Duration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/** How often the highlight follows the reading. */
private const val POLL_MILLIS = 30.0

/**
 * The Read Aloud tool of one window (ADR 0124): creates its tab and keeps the text, voice and speed, so that they
 * outlive the tab (ADR 0110). The `ide` module sets [showTab]. Closing it (with its window) stops a reading.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class ReadAloud internal constructor(
    engines: SpeechEngines,
    preferences: SpeechPreferences,
    coordinator: PlaybackCoordinator,
    installer: ModelInstaller,
    tasks: BackgroundTasks,
    fontSettings: FontSettings,
    sink: () -> AudioSink,
    private val dispatch: (Runnable) -> Unit,
    poller: (Runnable) -> AutoCloseable,
) : AutoCloseable {
    @Autowired
    constructor(
        engines: SpeechEngines,
        preferences: SpeechPreferences,
        coordinator: PlaybackCoordinator,
        installer: ModelInstaller,
        tasks: BackgroundTasks,
        fontSettings: FontSettings,
    ) : this(
        engines,
        preferences,
        coordinator,
        installer,
        tasks,
        fontSettings,
        ::JavaSoundSink,
        Platform::runLater,
        ::fxPoller,
    )

    /** Shows the Read Aloud tab of the window (opening it if needed); set by ide. */
    var showTab: () -> Unit = {}

    /** Shared by the tab's model and the editors' ones, so that starting a reading pauses the running one. */
    private val host =
        LiveReadAloudHost(
            engines,
            preferences,
            coordinator,
            installer,
            { tasks },
            sink,
            dispatch,
            poller,
            NamedThreads.single("sluide-speech-check"),
        )

    private val model = ReadAloudModel(host)
    private val stylesheet =
        checkNotNull(ReadAloud::class.java.getResource("/dev/sluide/speech/speech.css")).toExternalForm()

    private val editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss)

    /** A new Read Aloud tab on the window's text, voice and speed. */
    fun createTab(): EditorTab = ReadAloudTab(model, ReadAloudView(model, editorCss))

    /**
     * Extends the window's code editors with "Read Selection Aloud" and "Read Aloud" (ADR 0124); plain-text and
     * Markdown editors also get the controls and read in place.
     */
    val editorExtension: CodeEditorExtension =
        ReadAloudExtension(::read) { editor ->
            val editorModel = ReadAloudModel(host, ownsText = false)
            EditorReading(
                editorModel,
                editor,
                { play -> ReadAloudEditorControls(editorModel, play, stylesheet) },
                stylesheet,
            )
        }

    /** Puts [text] in the tab, shows it and starts reading, or shows the setup notice when something is missing. */
    fun read(
        text: String,
        markdown: Boolean,
    ) {
        model.read(text, markdown)
        showTab()
    }

    /**
     * Reads the saved [file] (UTF-8, up to 1 MiB) off the FX thread, then [read]s it (Markdown for `.md`); [onError]
     * gets a readable message on the FX thread when it cannot be read.
     */
    fun readFile(
        file: Path,
        onError: (String) -> Unit,
    ) {
        NamedThreads.virtual("sluide-speech-read-file") {
            val result = readableFile(file)
            dispatch(
                Runnable {
                    when (result) {
                        is ReadableFile.Text -> read(result.text, result.markdown)
                        is ReadableFile.Unreadable -> onError(result.message)
                    }
                },
            )
        }
    }

    /** Stops a reading; called when the window closes. */
    override fun close() = model.stop()
}

private fun fxPoller(tick: Runnable): AutoCloseable {
    val timeline =
        Timeline(KeyFrame(Duration.millis(POLL_MILLIS), { tick.run() })).apply {
            cycleCount = Animation.INDEFINITE
            play()
        }
    return AutoCloseable { timeline.stop() }
}
