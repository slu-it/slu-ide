package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.EditorAttachment
import dev.sluide.codeintelligence.completion.CompletionFeature
import dev.sluide.codeintelligence.diagnostics.DiagnosticsFeature
import dev.sluide.codeintelligence.navigation.BindingNavigation
import dev.sluide.codeintelligence.navigation.DEFINITION
import dev.sluide.codeintelligence.navigation.Navigation
import dev.sluide.codeintelligence.navigation.NavigationFeature
import dev.sluide.codeintelligence.navigation.OccurrencesFeature
import dev.sluide.codeintelligence.navigation.REFERENCES
import dev.sluide.codeintelligence.navigation.navigationBlocker
import dev.sluide.codeintelligence.rename.RenameFeature
import dev.sluide.codeintelligence.semantic.SemanticTokensFeature
import dev.sluide.codeintelligence.server.RestartServerItem
import dev.sluide.codeintelligence.server.ServerBannerFeature
import dev.sluide.codeintelligence.server.hasStarted
import dev.sluide.codeintelligence.signature.SignatureFeature
import dev.sluide.codeintelligence.ui.HoverFeature
import dev.sluide.codeintelligence.ui.ServerHoverFeature
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LanguageServers
import dev.sluide.languageservers.ServerAvailability
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import java.io.IOException
import java.nio.file.Path

/**
 * The editor's code intelligence of one window (ADR 0145), a [CodeEditorExtension]: binds every code editor whose file
 * a language server serves ([LanguageServers.specFor]) to that server, and binds again when the file is renamed (to or
 * from `.py`, out of the project). Each binding gets the [features], in order. When an installer's command changes
 * (install, update, rollback, custom path) the servers that were started restart; when one becomes ready, the servers
 * of bound editors start. FX thread; [close] with the window.
 */
class CodeIntelligence internal constructor(
    private val languageServers: LanguageServers,
    /** The window's Not Now answers of the install banner, by spec id; until the app restarts. */
    internal val notNow: ObservableSet<String>,
    featuresOf: CodeIntelligence.(ObservableSet<String>) -> List<BindingFeature>,
) : CodeEditorExtension,
    AutoCloseable {
    /**
     * @param languageServers the window's servers.
     * @param prompts the user's Don't Ask Again answers of the install banner.
     */
    constructor(languageServers: LanguageServers, prompts: InstallPrompts) :
        this(languageServers, FXCollections.observableSet(), { notNow -> builtInFeatures(notNow, prompts, navigation) })

    private val attachments = LinkedHashSet<EditorAttachment>()

    /** The host of the editor extended last, to open files when no bound editor is left (ADR 0146). */
    private var lastHost: FileViewHost? = null

    /** Back and Forward of the window: the places navigation jumps left (ADR 0146). */
    val history = NavigationHistory()

    internal val navigation = Navigation(history, { attachments.mapNotNull { it.binding } }, { lastHost })

    private val features = featuresOf(notNow)
    private val subscriptions = mutableListOf<AutoCloseable>()
    private var closed = false

    init {
        for (spec in languageServers.specs) {
            val installer = spec.installer ?: continue
            subscriptions += installer.onCommandChanged { restartIfStarted(spec) }
            val ready =
                installer.availability.subscribe { old, now ->
                    if (now == ServerAvailability.Ready && old != ServerAvailability.Ready) startBound(spec)
                }
            subscriptions += AutoCloseable { ready.unsubscribe() }
        }
    }

    /** Binds [editor] while a server serves its file; nothing once the window closed this ([close]). */
    override fun extend(editor: ExtensibleCodeEditor): AutoCloseable? {
        if (closed) return null
        lastHost = editor.host
        lateinit var attachment: EditorAttachment
        attachment =
            EditorAttachment(editor, languageServers, features, ::bindingFor, ::bindings) { attachments -= attachment }
        attachments += attachment
        return attachment
    }

    /** The bound editor that has the focus, `null` if the focus is elsewhere. */
    val focusedBinding: DocumentBinding? get() = navigation.focused()

    /** Why Go to Definition cannot act now ("server starting"…), `null` if it can; for the Navigate menu. */
    fun definitionBlocker(): String? = blocker(DEFINITION)

    /** Why Find Usages cannot act now, `null` if it can; for the Navigate menu. */
    fun usagesBlocker(): String? = blocker(REFERENCES)

    /** Go to Definition at the caret of the focused bound editor; `false` without one. */
    fun goToDefinition(): Boolean = atCaret { actions, offset -> actions.goToDefinition(offset) }

    /** Find Usages at the caret of the focused bound editor; `false` without one. */
    fun findUsages(): Boolean = atCaret { actions, offset -> actions.findUsages(offset) }

    /** Goes back to where the last navigation jump started ([history]); `false` if there is no such place. */
    fun back(): Boolean = navigation.back()

    /** Goes forward again after [back]; `false` if there is nothing ahead. */
    fun forward(): Boolean = navigation.forward()

    private fun blocker(method: String): String? {
        val binding = focusedBinding ?: return "no code editor focused"
        return navigationBlocker(binding, method)
    }

    private fun atCaret(action: (BindingNavigation, Int) -> Unit): Boolean {
        val binding = focusedBinding ?: return false
        val actions = navigation.actionsOf(binding) ?: return false
        action(actions, binding.editor.caretOffset)
        return true
    }

    /**
     * The binding of the editor of [file] in this window, if one is bound (workspace edits apply to it): by path, else
     * by real path (a server may name a file through a link, e.g. `/private/var` for `/var` on macOS).
     */
    internal fun bindingFor(file: Path): DocumentBinding? {
        val wanted = file.toAbsolutePath().normalize()
        val bindings = attachments.mapNotNull { it.binding }
        bindings.firstOrNull { it.file.toAbsolutePath().normalize() == wanted }?.let { return it }
        val real = realPath(wanted) ?: return null
        return bindings.firstOrNull { realPath(it.file) == real }
    }

    /** The bound editors of the window. */
    private fun bindings(): List<DocumentBinding> = attachments.mapNotNull { it.binding }

    /** The editors extended and not disposed yet; for tests. */
    internal val attached: Set<EditorAttachment> get() = attachments

    /** Stops following the installers and unbinds every editor. */
    override fun close() {
        closed = true
        subscriptions.forEach { it.close() }
        subscriptions.clear()
        attachments.toList().forEach { it.close() }
    }

    /** The command of [spec] changed: a server that was started restarts with the new one. */
    private fun restartIfStarted(spec: LanguageServerSpec) {
        if (hasStarted(languageServers.state(spec).value)) languageServers.restart(spec)
    }

    /** [spec]'s server became available: it starts if an editor is bound to it. */
    private fun startBound(spec: LanguageServerSpec) {
        if (attachments.any { it.binding?.spec == spec }) languageServers.session(spec).ensureStarted()
    }
}

/** The real path of [file], `null` if it does not exist or cannot be resolved. */
private fun realPath(file: Path): Path? =
    try {
        file.toRealPath()
    } catch (_: IOException) {
        null
    }

/**
 * The features of every binding, in order: the server banner, navigation, rename, diagnostics and quick fixes,
 * semantic tokens, completion, signature help, the server's hover, the hover popup, the restart item last. Hover
 * providers are asked and context menu items shown in the order their features attach.
 */
internal fun builtInFeatures(
    notNow: ObservableSet<String>,
    prompts: InstallPrompts,
    navigation: Navigation,
): List<BindingFeature> =
    listOf(
        ServerBannerFeature(notNow, prompts),
        NavigationFeature(navigation),
        OccurrencesFeature(),
        RenameFeature(),
        DiagnosticsFeature(),
        SemanticTokensFeature(),
        CompletionFeature(),
        SignatureFeature(),
        ServerHoverFeature(),
        HoverFeature(),
        RestartServerItem,
    )
