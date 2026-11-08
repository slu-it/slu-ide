package dev.sluide.codeintelligence.server

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.collections.ObservableSet
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region

/**
 * The banner of a bound editor (ADR 0145), styled like the code view's other banners (`editor-banner`): shows
 * [ServerBannerModel.notice] with its buttons, above the text, while there is one. Highlighting and editing work as
 * before in every state. FX thread.
 */
internal class ServerBanner(
    private val editor: ExtensibleCodeEditor,
    private val model: ServerBannerModel,
) : AutoCloseable {
    private var node: HBox? = null
    private var handle: AutoCloseable? = null
    private var shown: ServerNotice? = null
    private val subscription = model.notice.subscribe { notice -> render(notice) }

    override fun close() {
        subscription.unsubscribe()
        handle?.close()
        handle = null
        model.close()
    }

    private fun render(notice: ServerNotice?) {
        if (notice == shown) return
        val before = shown
        shown = notice
        if (notice == null) {
            handle?.close()
            handle = null
            return
        }
        val banner =
            node ?: HBox()
                .apply {
                    styleClass += listOf("editor-banner", "code-intelligence-banner")
                    alignment = Pos.CENTER_LEFT
                }.also { node = it }
        if (before is ServerNotice.Installing && notice is ServerNotice.Installing) {
            // Only the progress moved: keep the buttons, so a click on Cancel is not lost to a rebuilt row.
            (banner.lookup("#code-intelligence-progress") as? ProgressBar)?.progress = notice.progress
            (banner.children.first() as? Label)?.text = noticeText(notice)
        } else {
            banner.children.setAll(
                listOf(
                    Label(noticeText(notice)).apply {
                        isWrapText = true
                        minWidth = 0.0
                    },
                ) + controls(notice),
            )
        }
        if (handle == null) handle = editor.addBanner(banner)
    }

    private fun controls(notice: ServerNotice) =
        listOf(Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }) +
            when (notice) {
                is ServerNotice.Missing -> {
                    listOf(
                        button("Install", "install", model::install),
                        button("Not Now", "not-now", model::notNow),
                        button("Don't Ask Again", "dont-ask", model::dontAskAgain),
                    )
                }

                is ServerNotice.Installing -> {
                    listOf(
                        ProgressBar(notice.progress).apply { id = "code-intelligence-progress" },
                        button("Cancel", "cancel", model::cancel),
                    )
                }

                is ServerNotice.InstallFailed -> {
                    listOfNotNull(
                        if (notice.canRetry) button("Retry", "retry", model::install) else null,
                        button("Not Now", "not-now", model::notNow),
                    )
                }

                is ServerNotice.Stopped -> {
                    listOf(button("Restart", "restart", model::restart))
                }

                is ServerNotice.Untrusted -> {
                    emptyList()
                }
            }

    private fun button(
        label: String,
        name: String,
        action: () -> Unit,
    ) = Button(label).apply {
        id = "code-intelligence-$name"
        isFocusTraversable = false
        // Never squeezed to "Canc…" in a narrow view; the message wraps instead (as the editor's own banners).
        minWidth = Region.USE_PREF_SIZE
        setOnAction { action() }
    }
}

/**
 * The banner as a feature of every binding, with the window's Not Now ([notNow]) and the user's Don't Ask Again
 * ([prompts]); Install uses the window's `BackgroundTasks` from the editor's host.
 */
internal class ServerBannerFeature(
    private val notNow: ObservableSet<String>,
    private val prompts: InstallPrompts,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val host = binding.editor.host
        val model =
            ServerBannerModel(binding.session, notNow, prompts) { host.windowService(BackgroundTasks::class.java) }
        return ServerBanner(binding.editor, model)
    }
}
