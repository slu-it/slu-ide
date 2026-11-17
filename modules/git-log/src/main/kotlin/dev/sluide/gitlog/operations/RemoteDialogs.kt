package dev.sluide.gitlog.operations

import dev.sluide.git.GitRefNames
import dev.sluide.git.GitRemoteUrls
import dev.sluide.git.maskedUrl
import dev.sluide.git.unmaskedUrl
import javafx.application.Platform
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.Window

internal val ADD_BUTTON = ButtonType("Add", ButtonBar.ButtonData.OK_DONE)
internal val CHANGE_BUTTON = ButtonType("Change", ButtonBar.ButtonData.OK_DONE)
internal val REMOVE_BUTTON = ButtonType("Remove", ButtonBar.ButtonData.OK_DONE)

private const val MASK = "****"
private const val FIELD_COLUMNS = 40
private const val SPACING = 6.0
private const val CREDENTIALS_NEW_HOST = "Enter the credentials for the new host, or remove them."
private const val SEVERAL_NOTE = "Several URLs are configured; change them in a terminal."
private const val HIDDEN_NOTE = "Credentials are hidden as $MASK; leave them to keep them."

/** What the Add Remote dialog asks for: the remote's [name] and [url] (trimmed), and whether to [fetch] it. */
internal data class NewRemote(
    val name: String,
    val url: String,
    val fetch: Boolean,
) {
    /** Without the URL's credentials. */
    override fun toString() = "NewRemote(name=$name, url=${maskedUrl(url)}, fetch=$fetch)"
}

/**
 * What is wrong with [name] as a remote's name, or `null`: empty, an option for git, not valid (git's rule,
 * [GitRefNames.isValidRemoteName]), not passable to git ([canPass]), taken or in conflict with another of the [remotes]
 * as a path prefix, either way (git does not check that when renaming; [except] is the remote being renamed). The
 * [unchanged] name (renaming to itself) is an empty problem: the dialog cannot be confirmed, but there is nothing to say.
 */
internal fun remoteNameProblem(
    name: String,
    remotes: List<String>,
    canPass: (String) -> Boolean,
    except: String? = null,
    unchanged: String? = null,
): String? {
    val conflict = GitRefNames.remoteNameConflict(name, remotes, except)
    return when {
        name.isEmpty() -> "Enter a name."
        name == unchanged -> ""
        name.startsWith('-') -> "A name must not start with '-'."
        !GitRefNames.isValidRemoteName(name) -> "'$name' is not a valid remote name."
        !canPass(name) -> "This name cannot be passed to git in this locale."
        name in remotes -> "A remote named '$name' already exists."
        conflict != null -> "'$name' conflicts with the remote '$conflict'."
        else -> null
    }
}

/** What is wrong with [url] as a remote's URL, or `null`: it is blank. */
internal fun remoteUrlProblem(url: String): String? = "Enter a URL.".takeIf { url.isBlank() }

/**
 * What the Change URL dialog's fields [change][changed]: the new [url] and [pushUrl] (credentials put back where the
 * mask stayed; `null` for a field that was not changed), whether the push URL was cleared ([removePushUrl]), and what is
 * wrong with the input ([problem]).
 */
internal data class UrlsEdit(
    val problem: String?,
    val url: String?,
    val pushUrl: String?,
    val removePushUrl: Boolean,
) {
    val changed get() = url != null || pushUrl != null || removePushUrl

    /** Without the credentials, which [url] and [pushUrl] hold again ([unmaskedUrl]). */
    override fun toString() =
        "UrlsEdit(problem=$problem, url=${url?.let(::maskedUrl)}, pushUrl=${pushUrl?.let(::maskedUrl)}, " +
            "removePushUrl=$removePushUrl)"
}

/**
 * What editing the [urls] of a remote to [urlText] and [pushText] (the fields as the user left them, starting from
 * [maskedUrl] of the stored values) changes. A field whose key has several values is left alone; one still showing what
 * it started with writes nothing; an empty push URL clears an existing one.
 */
internal fun urlsEdit(
    urls: GitRemoteUrls,
    urlText: String,
    pushText: String,
): UrlsEdit {
    val storedUrl = urls.url.orEmpty()
    val storedPush = urls.pushUrl.orEmpty()
    val urlEdited = !urls.severalUrls && urlText.trim() != maskedUrl(storedUrl)
    val pushEdited = !urls.severalPushUrls && pushText.trim() != maskedUrl(storedPush)
    if (!urls.severalUrls) remoteUrlProblem(urlText)?.let { return UrlsEdit(it, null, null, false) }
    val url = if (urlEdited) unmaskedUrl(urlText, storedUrl) else null
    val clear = pushEdited && pushText.isBlank()
    val push = if (pushEdited && !clear) unmaskedUrl(pushText, storedPush) else null
    if ((urlEdited && url == null) || (pushEdited && !clear && push == null)) {
        return UrlsEdit(CREDENTIALS_NEW_HOST, null, null, false)
    }
    return UrlsEdit(null, url, push, clear)
}

/**
 * What to tell under the Change URL fields: that fields with several values cannot be changed here, and that a field
 * showing a mask keeps the credentials behind it; `null` for neither.
 */
internal fun urlsNote(
    urls: GitRemoteUrls,
    urlText: String,
    pushText: String,
): String? {
    fun hidden(
        text: String,
        stored: String?,
        several: Boolean,
    ) = !several && unmaskedUrl(text, stored.orEmpty()) != text.trim()
    return listOfNotNull(
        SEVERAL_NOTE.takeIf { urls.severalUrls || urls.severalPushUrls },
        HIDDEN_NOTE.takeIf {
            hidden(urlText, urls.url, urls.severalUrls) || hidden(pushText, urls.pushUrl, urls.severalPushUrls)
        },
    ).joinToString("\n").ifEmpty { null }
}

/**
 * Asks for a new remote: its name (starting with [initial], selected), its URL and whether to fetch it (at first yes);
 * [validate] finds what is wrong with a name.
 */
internal class AddRemoteDialog(
    owner: Window?,
    initial: String,
    private val validate: (String) -> String?,
) : RefNameDialog<NewRemote>(owner, "Add Remote", initial, ADD_BUTTON) {
    val url =
        TextField().apply {
            styleClass += "git-remote-url"
            prefColumnCount = FIELD_COLUMNS
        }
    val fetch =
        CheckBox("Fetch after adding").apply {
            styleClass += "git-remote-fetch"
            isSelected = true
        }

    init {
        content.children += listOf(Label("URL:"), url, fetch)
        url.textProperty().addListener { _ -> check() }
        check()
    }

    override fun problem() = validate(field.text) ?: remoteUrlProblem(url.text)

    override fun result() = NewRemote(field.text, url.text.trim(), fetch.isSelected)
}

/** Asks for a remote's new name, starting with its [old] one. */
internal class RenameRemoteDialog(
    owner: Window?,
    old: String,
    private val validate: (String) -> String?,
) : RefNameDialog<String>(owner, "Rename Remote", old, RENAME_BUTTON) {
    init {
        check()
    }

    override fun problem() = validate(field.text)

    override fun result(): String = field.text
}

/**
 * Asks for the URL and push URL of the remote of [prompt], showing them masked ([maskedUrl]; this is the one place a
 * remote's URL is shown, ADR 0183). The result is what to change; Change cannot be confirmed while there is a problem
 * or nothing changed.
 */
internal class ChangeUrlDialog(
    owner: Window?,
    private val prompt: RemoteUrlsPrompt,
) : Dialog<UrlsEdit>() {
    private val urls = prompt.urls

    val url =
        TextField(urls.url?.let(::maskedUrl).orEmpty()).apply {
            styleClass += "git-remote-url"
            prefColumnCount = FIELD_COLUMNS
            isDisable = urls.severalUrls
        }
    val pushUrl =
        TextField(urls.pushUrl?.let(::maskedUrl).orEmpty()).apply {
            styleClass += "git-remote-push-url"
            promptText = "Same as URL"
            prefColumnCount = FIELD_COLUMNS
            isDisable = urls.severalPushUrls
        }
    val messageLabel = Label().apply { styleClass += "git-ref-name-problem" }

    init {
        owner?.let(::initOwner)
        title = "Change URL of '${prompt.name}'"
        dialogPane.buttonTypes.setAll(CHANGE_BUTTON, ButtonType.CANCEL)
        dialogPane.content = VBox(SPACING, Label("URL:"), url, Label("Push URL:"), pushUrl, messageLabel)
        url.textProperty().addListener { _ -> check() }
        pushUrl.textProperty().addListener { _ -> check() }
        setResultConverter { button -> if (button == CHANGE_BUTTON) edit() else null }
        setOnShown { Platform.runLater { if (!url.isDisable) url.requestFocus() } }
        check()
    }

    private fun edit() = urlsEdit(urls, url.text, pushUrl.text)

    private fun check() {
        val edit = edit()
        messageLabel.text = edit.problem ?: urlsNote(urls, url.text, pushUrl.text).orEmpty()
        // A problem leaves nothing changed.
        dialogPane.lookupButton(CHANGE_BUTTON).isDisable = !edit.changed
    }
}
