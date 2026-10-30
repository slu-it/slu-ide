package dev.sluide.httpclient.ui

import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.curl.CopiedCommand
import dev.sluide.httpclient.curl.CurlCopy
import dev.sluide.httpclient.curl.CurlReader
import dev.sluide.httpclient.curl.CurlTokenizer
import dev.sluide.httpclient.curl.HttpRequestWriter
import dev.sluide.httpclient.curl.PrivateValuesAnswer
import dev.sluide.httpclient.curl.insertRequests
import dev.sluide.httpclient.curl.privateValuesQuestion
import dev.sluide.httpclient.parse.HttpFileParser
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.MenuItem
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.stage.Window
import java.nio.file.Path

private val log = System.getLogger(CurlActions::class.java.name)

/**
 * Copy as cURL and Paste cURL as Request in the context menu of a `.http` file's [editor] (ADR 0116). Copying resolves
 * the request's variables against the environment chosen for the [file] in the window's [session], reading the
 * environment files in the background, and asks with [ask] before putting private values in. Failures are shown with
 * [showMessage], never thrown. FX thread.
 */
internal class CurlActions(
    private val editor: CodeEditor,
    private val file: () -> Path,
    private val session: () -> HttpClientSession?,
    private val projectFolder: () -> Path?,
    private val showMessage: (text: String, error: Boolean) -> Unit,
    private val ask: (Window?, List<String>) -> PrivateValuesAnswer = ::askToIncludePrivateValues,
) {
    fun items(target: EditorMenuTarget): List<MenuItem> {
        val clipboard = Clipboard.getSystemClipboard().string
        return listOf(
            MenuItem(COPY).apply { setOnAction { copyAsCurl(target.line) } },
            MenuItem(PASTE).apply {
                isDisable = !editor.canReplaceText || clipboard == null || !CurlTokenizer.looksLikeCurl(clipboard)
                setOnAction { pasteAsRequest(target.line) }
            },
        )
    }

    /** Copies the request whose part of the text holds [line] as a `curl` command. */
    fun copyAsCurl(line: Int) =
        guarded("copy as cURL") {
            val parsed = HttpFileParser.parse(editor.text)
            val block =
                parsed.requestAt(line) ?: return@guarded showMessage("There is no request at line ${line + 1}", true)
            val session = session()
            val file = file()
            val folder = projectFolder()
            val environment = session?.environment(file)
            val task = { CurlCopy.load(file, folder, environment, parsed.variables).command(block) }
            // Without a window there is no environment, so nothing to read.
            if (session == null) copied(runCatching(task)) else session.inBackground(task, ::copied)
        }

    private fun copied(result: Result<CopiedCommand>) =
        guarded("copy as cURL") {
            val command = result.getOrThrow()
            val text = command.copied { names -> ask(editor.node.scene?.window, names) } ?: return@guarded
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
            showMessage(command.message, false)
        }

    /** Inserts the `curl` commands on the clipboard as requests after the request of [line], as one undoable edit. */
    fun pasteAsRequest(line: Int) =
        guarded("paste cURL") {
            val clipboard = Clipboard.getSystemClipboard().string ?: return@guarded
            val requests =
                when (val read = CurlReader.read(clipboard)) {
                    is ReadResult.Failed -> return@guarded showMessage("Could not paste cURL: ${read.message}", true)
                    is ReadResult.Read -> read
                }
            val written =
                when (val write = HttpRequestWriter.write(requests.content)) {
                    is WriteResult.Failed -> return@guarded showMessage("Could not paste cURL: ${write.message}", true)
                    is WriteResult.Written -> write
                }
            val insertion = insertRequests(editor.text, line, written.text)
            if (!editor.replaceText(insertion.text)) return@guarded showMessage("The file is read-only", true)
            editor.moveCaretTo(insertion.line)
            val notes = (requests.notes + written.notes).takeIf { it.isNotEmpty() }?.joinToString("; ")
            showMessage(notes ?: "Pasted ${requests.content.size} request(s)", false)
        }

    /** Runs [action]; what it throws is logged and shown as a message rather than reaching the FX thread. */
    private fun guarded(
        what: String,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (e: Exception) {
            log.log(System.Logger.Level.ERROR, "Could not $what in ${file()}", e)
            showMessage("Could not $what: ${e.message ?: e::class.simpleName}", true)
        }
    }

    companion object {
        const val COPY = "Copy as cURL"
        const val PASTE = "Paste cURL as Request"
    }
}

/** The Include button of [privateValuesDialog]. */
internal val INCLUDE_BUTTON = ButtonType("Include", ButtonBar.ButtonData.YES)

/** The Keep as {{…}} button of [privateValuesDialog]. */
internal val KEEP_BUTTON = ButtonType("Keep as {{…}}", ButtonBar.ButtonData.NO)

/** Asks whether a copied command gets the private values [names] and waits for the answer. */
internal fun askToIncludePrivateValues(
    owner: Window?,
    names: List<String>,
): PrivateValuesAnswer =
    when (privateValuesDialog(owner, names).showAndWait().orElse(ButtonType.CANCEL)) {
        INCLUDE_BUTTON -> PrivateValuesAnswer.INCLUDE
        KEEP_BUTTON -> PrivateValuesAnswer.KEEP
        else -> PrivateValuesAnswer.CANCEL
    }

/** The dialog of [askToIncludePrivateValues], not shown yet. Cancel is the default button, so Enter copies nothing. */
internal fun privateValuesDialog(
    owner: Window?,
    names: List<String>,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, privateValuesQuestion(names), INCLUDE_BUTTON, KEEP_BUTTON, ButtonType.CANCEL)
        .apply {
            owner?.let(::initOwner)
            title = "Copy as cURL"
            headerText = "Private values"
            val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
            (dialogPane.lookupButton(INCLUDE_BUTTON) as Button).isDefaultButton = false
            cancel.isDefaultButton = true
            // Later, since the dialog focuses a button of its own once shown.
            setOnShown { Platform.runLater(cancel::requestFocus) }
        }
