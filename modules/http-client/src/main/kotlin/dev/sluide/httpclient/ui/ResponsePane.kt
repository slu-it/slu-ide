package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import dev.sluide.httpclient.send.ExchangeResult
import dev.sluide.httpclient.send.HttpFailure
import dev.sluide.httpclient.send.HttpResult
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/**
 * The outcome of the last request run in an [HttpFileView]: a status line (status coloured by its class, reason,
 * time, size, version and final URL), the notes of the exchange, and tabs with the body (pretty-printed or as received,
 * highlighted by its `Content-Type`), the headers and the request as sent. A failure (e.g. an unresolved variable or a
 * refused connection) takes the place of the body.
 */
internal class ResponsePane {
    val status = Label().apply { styleClass += "http-status" }
    val details = Label().apply { styleClass += "http-details" }
    val notes = VBox().apply { styleClass += "http-notes" }
    val error = Label().apply { styleClass += "http-error" }

    val pretty = ToggleButton("Pretty")
    val raw = ToggleButton("Raw")
    val body = CodeEditors.display()
    val headers = CodeEditors.display()
    val request = CodeEditors.display()

    private val bodyPane =
        BorderPane(body.node).apply {
            top =
                HBox(pretty, raw).apply {
                    styleClass += "http-body-toolbar"
                    alignment = Pos.CENTER_LEFT
                }
        }
    val bodyTab = Tab("Body", bodyPane)
    val headersTab = Tab("Headers", headers.node)
    val requestTab = Tab("Request", request.node)
    val tabs =
        TabPane(bodyTab, headersTab, requestTab).apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        }

    val node =
        BorderPane(tabs).apply {
            styleClass += "http-response"
            top =
                VBox(
                    HBox(status, details).apply {
                        styleClass += "http-status-line"
                        alignment = Pos.CENTER_LEFT
                    },
                    notes,
                )
        }

    private var content: ResponseContent? = null

    init {
        val group = ToggleGroup()
        pretty.toggleGroup = group
        raw.toggleGroup = group
        pretty.isSelected = true
        // One of the two stays selected.
        group.selectedToggleProperty().addListener { _, old, new ->
            if (new == null) group.selectToggle(old) else showBody()
        }
    }

    /** Shows that a request runs; the last response stays until its result comes. */
    fun running() {
        setStatus("Running…", null)
        details.text = ""
    }

    /** Shows [content], prepared off the FX thread ([ResponseContent.of]); only texts and styles are set here. */
    fun show(content: ResponseContent) {
        this.content = content
        setStatus(content.status.status, content.status.kind)
        details.text = content.status.details.joinToString("   ")
        request.show(content.request, null)
        headers.show(content.headers, null)
        notes.children.setAll(content.notes.map { Label(it).apply { styleClass += "http-note" } })
        if (content.error == null) {
            bodyTab.content = bodyPane
            showBody()
        } else {
            error.text = content.error
            bodyTab.content = error
        }
        tabs.selectionModel.select(bodyTab)
    }

    private fun setStatus(
        text: String,
        kind: StatusKind?,
    ) {
        status.text = text
        status.styleClass.removeAll(StatusKind.entries.map { it.styleClass })
        kind?.let { status.styleClass += it.styleClass }
    }

    /** The body as the Pretty/Raw toggle says; Raw is only offered when it differs. */
    private fun showBody() {
        val content = content ?: return
        val raw = content.rawBody
        this.raw.isDisable = raw == null
        val text = if (this.raw.isSelected && raw != null) raw else content.body
        body.show(text, content.language?.let(Highlighters::forLanguage))
    }
}

/**
 * What the [ResponsePane] shows of a result, prepared off the FX thread: decoded and formatted, cut to
 * [ResponseTexts.DISPLAY_LIMIT]. [rawBody] is the body as received if it differs from the shown [body]; [error] takes
 * the place of the body for failures.
 */
internal class ResponseContent(
    val status: StatusLine,
    val body: String,
    val rawBody: String?,
    val language: String?,
    val error: String?,
    val headers: String,
    val request: String,
    val notes: List<String>,
) {
    companion object {
        fun of(result: ExchangeResult): ResponseContent {
            val request = result.request?.let(ResponseTexts::request)
            val requestNotes = if (request?.partial == true) listOf("Request body shown in part") else emptyList()
            return when (result) {
                is HttpResult -> {
                    val body = ResponseFormatter.format(result.body, result.header("Content-Type"))
                    val cut = body.text.length > ResponseTexts.DISPLAY_LIMIT
                    val cutNote = if (cut) listOf("Showing the first 5 M characters of the body") else emptyList()
                    val raw = body.raw?.takeIf { it != body.text }
                    ResponseContent(
                        ResponseTexts.statusLine(result),
                        body.text.take(ResponseTexts.DISPLAY_LIMIT),
                        raw?.take(ResponseTexts.DISPLAY_LIMIT),
                        body.language,
                        null,
                        ResponseTexts.headers(result),
                        request?.text.orEmpty(),
                        result.notes + body.notes + cutNote + requestNotes,
                    )
                }

                is HttpFailure -> {
                    ResponseContent(
                        ResponseTexts.statusLine(result),
                        "",
                        null,
                        null,
                        result.message,
                        "",
                        request?.text.orEmpty(),
                        result.notes + requestNotes,
                    )
                }
            }
        }
    }
}
