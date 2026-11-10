package dev.sluide.servertools

import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.InstalledTool
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.math.roundToLong

/** The questions a server tool's settings page asks; tests replace them (ADR 0084). */
interface ServerToolDialogs {
    /** Asks [question] with a Delete button and Cancel, which is the default; true for Delete. */
    fun confirmDelete(question: String): Boolean

    /** Lets the user pick the tool's executable, starting at [current] if it names one; null when cancelled. */
    fun chooseExecutable(current: String): Path?
}

/** What the page's buttons and lines show for one state of the installer and the update check. */
internal data class ServerToolPageControls(
    val status: String,
    val installText: String,
    val installDisabled: Boolean,
    val checkDisabled: Boolean,
    val result: String?,
    val releaseNotesShown: Boolean,
    val updateShown: Boolean,
    val updateDisabled: Boolean,
    val rollbackText: String?,
    val rollbackDisabled: Boolean,
    val deleteDisabled: Boolean,
    val pinText: String?,
    val pinDisabled: Boolean,
)

/**
 * A server tool's settings page: Install, Cancel, Check for Updates, Update, Roll Back and Delete act at once through
 * the [installer] and [updates]; the choice of the executable is a draft applied to [setting] only by [apply].
 * [language] names the code intelligence the tool serves in the delete question. FX thread.
 */
internal class ServerToolPageModel(
    private val language: String,
    private val setting: ServerPathSetting,
    private val installer: ServerToolInstaller,
    private val updates: ToolUpdates,
    private val dialogs: ServerToolDialogs,
    private val openWebPage: (URI) -> Unit,
) {
    private val name = installer.tool.name

    /** The draft: whether a custom executable is used, and its path. */
    val useCustom: BooleanProperty = SimpleBooleanProperty()
    val customPath: StringProperty = SimpleStringProperty("")

    private val checkWrapper = ReadOnlyObjectWrapper<UpdateCheck?>()
    private val checkingWrapper = ReadOnlyBooleanWrapper()

    /** The result of the last Check for Updates; cleared when the installed version changes. */
    val updateCheck: ReadOnlyObjectProperty<UpdateCheck?> = checkWrapper.readOnlyProperty

    /** Whether a check runs. */
    val checking: ReadOnlyBooleanProperty = checkingWrapper.readOnlyProperty

    init {
        load()
        installer.installed.addListener { _, _, _ -> checkWrapper.set(null) }
    }

    fun load() {
        installer.recheckCustomPath()
        val path = setting.path.get()
        useCustom.set(path.isNotEmpty())
        customPath.set(path)
    }

    /** Stores the custom path if one is chosen, else nothing (Thoreau's download). */
    fun apply() {
        setting.apply(if (useCustom.get()) customPath.get() ?: "" else "")
        load()
    }

    /** Install, or Cancel while a download runs (the same button). */
    fun installOrCancel() {
        if (installer.state.get() is InstallState.Downloading) installer.cancel() else installer.install(null)
    }

    /** Asks the update source for a newer version than the installed one; nothing while a check runs. */
    fun checkForUpdates() {
        if (checking.get()) return
        checkingWrapper.set(true)
        updates.check(installer.installed.get()?.version) { result ->
            checkingWrapper.set(false)
            checkWrapper.set(result)
        }
    }

    /** Installs the version the last check found; switches back without a download if it is the previous one. */
    fun update() {
        val available = updateCheck.get() as? UpdateCheck.Available ?: return
        if (installer.previous.get()?.version == available.version) {
            installer.rollback()
        } else {
            installer.update(available.release, null)
        }
    }

    /**
     * Installs the version this Thoreau was tested with over an older installed one (offered by [pinOffer]); switches
     * back without a download if it is the previous one.
     */
    fun installPinned() {
        if (installer.previous.get()?.version == installer.pinnedVersion) {
            installer.rollback()
        } else {
            installer.install(null)
        }
    }

    fun rollback() = installer.rollback()

    /** Deletes every downloaded version after the question; nothing when it is answered with Cancel. */
    fun delete() {
        val question =
            deleteQuestion(name, language, installer.installed.get(), installer.previous.get(), installer.root)
        if (dialogs.confirmDelete(question)) installer.deleteAll()
    }

    /** Picks the custom executable and chooses it. */
    fun browse() {
        val chosen = dialogs.chooseExecutable(customPath.get() ?: "") ?: return
        customPath.set(chosen.toString())
        useCustom.set(true)
    }

    fun openReleaseNotes() {
        releaseNotes(updateCheck.get())?.let(openWebPage)
    }

    /** What the page shows now. */
    fun controls(): ServerToolPageControls {
        val state = installer.state.get()
        val installed = installer.installed.get()
        val previous = installer.previous.get()
        val busy = state is InstallState.Downloading
        val check = updateCheck.get()
        return ServerToolPageControls(
            status =
                statusText(
                    installer.tool,
                    state,
                    installed,
                    installer.pinnedVersion,
                    installer.pinnedDownloadBytes,
                ),
            installText = if (busy) "Cancel" else "Install",
            installDisabled = !busy && (installed != null || !installer.platformSupported),
            checkDisabled = checking.get(),
            result = if (checking.get()) "Checking for updates…" else check?.let { resultText(name, it) },
            releaseNotesShown = !checking.get() && releaseNotes(check) != null,
            updateShown = check is UpdateCheck.Available,
            updateDisabled = busy,
            rollbackText = previous?.let { "Roll Back to ${it.version}" },
            rollbackDisabled = busy,
            deleteDisabled = busy || (installed == null && previous == null),
            pinText = pinOffer(setting.path.get(), installed, installer.pinnedVersion),
            pinDisabled = busy,
        )
    }

    /** The hint below the custom path: empty unless the custom path is chosen and cannot be used. */
    fun customHint(): String {
        if (!useCustom.get()) return ""
        val path = customPath.get()?.trim().orEmpty()
        if (path.isEmpty()) return "Enter the absolute path of a $name executable."
        val absolute =
            try {
                Path.of(path).isAbsolute
            } catch (_: InvalidPathException) {
                false
            }
        if (!absolute) return "Use an absolute path."
        return ServerToolInstaller.customPathProblem(path) ?: ""
    }

    companion object {
        /**
         * The label of the button that installs the pinned version: offered when Thoreau's download is in use and the
         * installed version is older than the pin (a newer Thoreau was installed); not for a custom path, nothing
         * installed, or a version the user updated to beyond the pin. Never installed without the click.
         */
        fun pinOffer(
            customPath: String,
            installed: InstalledTool?,
            pinnedVersion: String,
        ): String? {
            if (customPath.isNotEmpty() || installed == null) return null
            if (compareVersions(installed.version, pinnedVersion) >= 0) return null
            return "Install $pinnedVersion (tested with this Thoreau)"
        }

        fun statusText(
            tool: ServerTool,
            state: InstallState,
            installed: InstalledTool?,
            pinnedVersion: String,
            pinnedBytes: Long?,
        ): String {
            val name = tool.name
            return when {
                state is InstallState.Downloading && state.progress < 0 -> {
                    "Unpacking and checking $name…"
                }

                state is InstallState.Downloading -> {
                    "Downloading ${(state.progress * 100).toInt()} %"
                }

                state is InstallState.Failed -> {
                    "Failed: ${state.message}"
                }

                installed != null && installed.version == pinnedVersion -> {
                    "Installed: $name ${installed.version}"
                }

                installed != null -> {
                    "Installed: $name ${installed.version} (this Thoreau was tested with $pinnedVersion)"
                }

                pinnedBytes != null -> {
                    "Not installed (${megabytes(pinnedBytes)} download)"
                }

                else -> {
                    tool.noBuild
                }
            }
        }

        fun resultText(
            name: String,
            check: UpdateCheck,
        ): String =
            when (check) {
                is UpdateCheck.UpToDate -> {
                    "$name ${check.version} is up to date."
                }

                is UpdateCheck.Available -> {
                    "$name ${check.version} is available" +
                        if (check.newerThanPinned) ", newer than tested with this Thoreau." else "."
                }

                is UpdateCheck.NoBuild -> {
                    "$name ${check.version} is available, but not for this platform."
                }

                is UpdateCheck.Failed -> {
                    "Cannot check for updates: ${check.message}"
                }
            }

        fun releaseNotes(check: UpdateCheck?): URI? =
            when (check) {
                is UpdateCheck.Available -> check.releaseNotes
                is UpdateCheck.NoBuild -> check.releaseNotes
                else -> null
            }

        fun deleteQuestion(
            name: String,
            language: String,
            installed: InstalledTool?,
            previous: InstalledTool?,
            root: Path,
        ): String {
            val versions = listOfNotNull(installed, previous).joinToString(" and ") { "$name ${it.version}" }
            return "Delete $versions? The files in $root are removed permanently; $language code intelligence then " +
                "needs $name to be installed again."
        }

        fun megabytes(bytes: Long) = "${(bytes / 1_000_000.0).roundToLong()} MB"
    }
}
