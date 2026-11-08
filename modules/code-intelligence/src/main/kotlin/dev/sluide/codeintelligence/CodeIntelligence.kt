package dev.sluide.codeintelligence

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.EditorAttachment
import dev.sluide.codeintelligence.server.RestartServerItem
import dev.sluide.codeintelligence.server.ServerBannerFeature
import dev.sluide.codeintelligence.server.hasStarted
import dev.sluide.codeintelligence.ui.HoverFeature
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LanguageServers
import dev.sluide.languageservers.ServerAvailability
import javafx.collections.FXCollections
import javafx.collections.ObservableSet

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
    featuresOf: (ObservableSet<String>) -> List<BindingFeature>,
) : CodeEditorExtension,
    AutoCloseable {
    /**
     * @param languageServers the window's servers.
     * @param prompts the user's Don't Ask Again answers of the install banner.
     */
    constructor(languageServers: LanguageServers, prompts: InstallPrompts) :
        this(languageServers, FXCollections.observableSet(), { notNow -> builtInFeatures(notNow, prompts) })

    private val features = featuresOf(notNow)
    private val attachments = LinkedHashSet<EditorAttachment>()
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
        lateinit var attachment: EditorAttachment
        attachment = EditorAttachment(editor, languageServers, features) { attachments -= attachment }
        attachments += attachment
        return attachment
    }

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

/**
 * The features of every binding, in order: the server banner, then one line per feature (P6b navigation, P7
 * completion, P8 diagnostics add theirs here). Hover providers are asked in the order their features attach.
 */
internal fun builtInFeatures(
    notNow: ObservableSet<String>,
    prompts: InstallPrompts,
): List<BindingFeature> =
    listOf(
        ServerBannerFeature(notNow, prompts),
        RestartServerItem,
        HoverFeature(),
    )
