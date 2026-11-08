package dev.sluide.codeintelligence.server

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.languageservers.LanguageServerSession
import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerState
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.ObservableSet
import javafx.util.Subscription
import kotlin.math.roundToLong

/** What the banner of a bound editor says about its language server (ADR 0145); one at a time. */
internal sealed interface ServerNotice {
    /** The server is not installed; Install, Not Now, Don't Ask Again. */
    data class Missing(
        val language: String,
        val tool: String?,
        val downloadBytes: Long?,
    ) : ServerNotice

    /** The server is being installed; a progress bar and Cancel. */
    data class Installing(
        val tool: String?,
        val progress: Double,
    ) : ServerNotice

    /** The last install failed (Retry, Not Now), or a setting keeps the server from running ([canRetry] false: Not Now). */
    data class InstallFailed(
        val language: String,
        val tool: String?,
        val message: String,
        val canRetry: Boolean = true,
    ) : ServerNotice

    /** The server crashed too often or could not start; Restart. */
    data class Stopped(
        val language: String,
        val message: String,
    ) : ServerNotice

    /** The server runs project code and waits for the trust; no button. */
    data class Untrusted(
        val language: String,
    ) : ServerNotice
}

/**
 * The notice for a server of [language] in [state] whose installer reports [availability] (`null`: no installer).
 * While the installer is not ready its notices win, unless the user put them off ([putOff]: Not Now in this window or
 * Don't Ask Again); then the server's own: stopped, or waiting for the trust. Nothing otherwise, also for a server
 * without a command and without an installer.
 */
internal fun noticeFor(
    language: String,
    tool: String?,
    availability: ServerAvailability?,
    state: ServerState,
    putOff: Boolean,
): ServerNotice? {
    if (availability != null && availability != ServerAvailability.Ready) {
        if (putOff) return null
        return when (availability) {
            is ServerAvailability.Missing -> {
                ServerNotice.Missing(language, tool, availability.downloadBytes)
            }

            is ServerAvailability.Installing -> {
                ServerNotice.Installing(tool, availability.progress)
            }

            is ServerAvailability.Failed -> {
                ServerNotice.InstallFailed(language, tool, availability.message, availability.canRetry)
            }

            ServerAvailability.Ready -> {
                null
            }
        }
    }
    return when (state) {
        is ServerState.Failed -> ServerNotice.Stopped(language, state.message)
        ServerState.Untrusted -> ServerNotice.Untrusted(language)
        else -> null
    }
}

/** The banner's text for [notice]. */
internal fun noticeText(notice: ServerNotice): String =
    when (notice) {
        is ServerNotice.Missing -> {
            val needs = notice.tool ?: "its language server"
            val size = notice.downloadBytes?.let { " (${megabytes(it)} download)" }.orEmpty()
            "Code intelligence for ${notice.language} needs $needs$size."
        }

        is ServerNotice.Installing -> {
            "Installing ${notice.tool ?: "the language server"}… ${(notice.progress * 100).toInt()} %"
        }

        is ServerNotice.InstallFailed -> {
            if (notice.canRetry) {
                val what = notice.tool ?: "The ${notice.language} language server"
                "$what could not be installed: ${notice.message}"
            } else {
                val sentence = if (notice.message.endsWith('.')) notice.message else "${notice.message}."
                "$sentence Change it in Settings → Languages → ${notice.language}."
            }
        }

        is ServerNotice.Stopped -> {
            "The ${notice.language} language server stopped: ${notice.message}"
        }

        is ServerNotice.Untrusted -> {
            "The ${notice.language} language server waits for this project to be trusted."
        }
    }

/** `14 MB`, as Settings → Languages → Python says it (decimal megabytes, rounded). */
internal fun megabytes(bytes: Long) = "${(bytes / 1_000_000.0).roundToLong()} MB"

/**
 * The banner of one bound editor without its controls (ADR 0145): [notice] follows the installer's availability, the
 * session's state, Not Now of this window ([notNow], spec ids) and Don't Ask Again ([prompts]); the actions are the
 * banner's buttons. [tasks] is the window's `BackgroundTasks` for the install row. FX thread.
 */
internal class ServerBannerModel(
    private val session: LanguageServerSession,
    private val notNow: ObservableSet<String>,
    private val prompts: InstallPrompts,
    private val tasks: () -> BackgroundTasks?,
) : AutoCloseable {
    private val spec = session.spec
    private val installer = spec.installer
    private val noticeWrapper = ReadOnlyObjectWrapper<ServerNotice?>()

    /** What the banner says; `null` hides it. */
    val notice: ReadOnlyObjectProperty<ServerNotice?> = noticeWrapper.readOnlyProperty

    private val subscriptions: List<Subscription> =
        listOfNotNull(
            installer?.availability?.subscribe { _ -> update() },
            session.state.subscribe { _ -> update() },
            notNow.subscribe { update() },
            prompts.declined.subscribe { update() },
        )

    init {
        update()
    }

    /** Install, Retry: installs with the window's background tasks. */
    fun install() {
        installer?.install(tasks())
    }

    /** Cancel of a running install. */
    fun cancel() {
        installer?.cancel()
    }

    /** Not Now: hides the install banners of this language in this window until the app restarts. */
    fun notNow() {
        notNow += spec.id
    }

    /** Don't Ask Again: hides the install banners of this language in every window, also after a restart. */
    fun dontAskAgain() = prompts.decline(spec.id)

    /** Restart of a stopped server. */
    fun restart() = session.restart()

    override fun close() = subscriptions.forEach { it.unsubscribe() }

    private fun update() {
        noticeWrapper.set(
            noticeFor(
                spec.name,
                installer?.toolName,
                installer?.availability?.value,
                session.state.value,
                spec.id in notNow || spec.id in prompts.declined,
            ),
        )
    }
}
