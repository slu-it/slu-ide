package dev.sluide.java.settings

import dev.sluide.java.JavaHomes
import dev.sluide.java.JavaProjectSettings
import dev.sluide.java.Jdk
import dev.sluide.java.JdkSource
import dev.sluide.java.Jdks
import dev.sluide.java.ProjectJdk
import javafx.beans.property.ObjectProperty
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executor

/** One entry of the JDK combo box: [path] is what [JavaProjectSettingsPageModel.apply] stores (null: JAVA_HOME). */
internal data class JdkChoice(
    val path: Path?,
    val text: String,
    val other: Boolean = false,
)

private val log = System.getLogger(JavaProjectSettingsPageModel::class.java.name)

private const val MAX_VERSION_CHARS = 40
private const val MAX_PATH_CHARS = 300
private const val OTHER = "Other…"

/** [text] on one line, for a label: control characters (newlines) become `?`, and it is cut at [max] characters. */
private fun oneLine(
    text: String,
    max: Int,
): String {
    val line = text.map { if (Character.isISOControl(it)) '?' else it }.joinToString("")
    return if (line.length > max) line.take(max) + "…" else line
}

private fun describe(jdk: Jdk) =
    "${oneLine(jdk.version, MAX_VERSION_CHARS)} (${oneLine(jdk.home.toString(), MAX_PATH_CHARS)})"

private fun same(
    a: Path,
    b: Path?,
) = b != null && a.normalize() == b.normalize()

/** Whether [a] and [b] are the same JDK setting: both none, or the same folder. */
private fun sameJdk(
    a: Path?,
    b: Path?,
) = if (a == null) b == null else same(a, b)

/**
 * The JDK field of the Java page in Project Settings (ADR 0179): the entries, the draft choice and Other…. The login
 * shell and the folder scan run on [io] (the results reach the properties through [dispatch], the FX thread);
 * [userHome], [macOs] and [thoreauHome] say where to look. [apply] blocks on the project's data file.
 */
internal class JavaProjectSettingsPageModel(
    private val settings: JavaProjectSettings,
    private val homes: JavaHomes,
    private val userHome: Path,
    private val macOs: Boolean,
    private val thoreauHome: Path,
    private val systemFolders: List<Path> = Jdks.defaults,
    private val io: Executor,
    private val dispatch: (Runnable) -> Unit,
) {
    /** The entries: JAVA_HOME (stores null), the installed JDKs, Thoreau's, the stored one if unlisted, Other…. */
    val choices: ObservableList<JdkChoice> = FXCollections.observableArrayList()

    /** The draft choice; null until loaded. */
    val selected: ObjectProperty<JdkChoice?> = SimpleObjectProperty()

    private val loadingWrapper = ReadOnlyBooleanWrapper()

    /** Whether the entries are being looked for. */
    val loading: ReadOnlyBooleanProperty = loadingWrapper.readOnlyProperty

    private val errorWrapper = ReadOnlyStringWrapper("")

    /** Why Other… was refused; empty for none. */
    val error: ReadOnlyStringProperty = errorWrapper.readOnlyProperty

    /** Bumped by every load and every change of the choice; an answer from the io thread for an older one is dropped. */
    private var version = 0
    private var previous: JdkChoice? = null

    /** The JDK the project has stored, as loaded or last applied; null for none. */
    private var stored: Path? = null

    init {
        selected.subscribe { choice ->
            version++
            if (choice != null && !choice.other) {
                previous = choice
                errorWrapper.set("")
            }
        }
    }

    /** Looks for the entries off the FX thread and selects the JDK stored for the project in [folder]. */
    fun load(folder: Path) {
        loadingWrapper.set(true)
        choices.clear()
        selected.set(null)
        errorWrapper.set("")
        val mine = ++version
        io.execute {
            val found =
                try {
                    val stored = settings.jdk(folder)
                    Found(
                        stored,
                        stored?.let(Jdks::read),
                        homes.resolve(null),
                        Jdks.installed(userHome, macOs, systemFolders),
                        Jdks.read(thoreauHome),
                    )
                } catch (e: Exception) {
                    log.log(System.Logger.Level.WARNING, "Cannot look for JDKs", e)
                    e
                }
            dispatch(
                Runnable {
                    if (mine != version) return@Runnable
                    if (found is Found) {
                        show(found)
                    } else if (found is Exception) {
                        errorWrapper.set("Cannot look for JDKs: ${oneLine(found.message ?: found.toString(), 200)}")
                    }
                    loadingWrapper.set(false)
                },
            )
        }
    }

    private data class Found(
        val stored: Path?,
        val storedJdk: Jdk?,
        val resolved: ProjectJdk?,
        val installed: List<Jdk>,
        val thoreau: Jdk?,
    )

    private fun show(found: Found) {
        val (stored, storedJdk, resolved, installed, thoreau) = found
        this.stored = stored
        val javaHome =
            resolved
                ?.takeIf { it.source == JdkSource.LOGIN_SHELL || it.source == JdkSource.ENVIRONMENT }
                ?.let { "JAVA_HOME: ${describe(it.jdk)}" }
                ?: "JAVA_HOME: not set or not a JDK (Thoreau's JDK is used)"
        val entries =
            listOf(JdkChoice(null, javaHome)) +
                installed.map { JdkChoice(it.home, describe(it)) } +
                listOfNotNull(thoreau?.let { JdkChoice(it.home, "Thoreau's JDK: ${describe(it)}") })
        val own =
            stored
                ?.takeIf { path -> entries.none { same(path, it.path) } }
                ?.let {
                    val text = storedJdk?.let(::describe) ?: "${oneLine(it.toString(), MAX_PATH_CHARS)} (not found)"
                    JdkChoice(it, text)
                }
        choices.setAll(entries + listOfNotNull(own) + JdkChoice(null, OTHER, other = true))
        selected.set(
            if (stored == null) choices.first() else choices.first { same(stored, it.path) },
        )
    }

    /** Stores the draft choice for the project in [folder]; nothing before the entries are loaded, and nothing while the
     * choice is what is stored already (so OK writes no data file for a project that has no JDK setting). */
    @Throws(IOException::class)
    fun apply(folder: Path) {
        val choice = selected.get()?.takeIf { !it.other } ?: return
        val loaded = stored
        if (sameJdk(loaded, choice.path)) return
        settings.setJdk(folder, choice.path)
        stored = choice.path
    }

    /**
     * After the user picked Other…: asks [chooser] (FX thread) for a folder. A JDK joins the entries and is selected;
     * a cancel or a folder that is no JDK goes back to the previous entry, the latter with an [error].
     */
    fun chooseOther(chooser: () -> Path?) {
        val back = previous
        val mine = version
        val folder = chooser()
        if (folder == null) {
            selected.set(back)
            return
        }
        io.execute {
            val jdk = Jdks.read(folder)
            dispatch(
                Runnable {
                    if (mine != version) return@Runnable // the user chose another entry, or the page was loaded again
                    if (jdk == null) {
                        selected.set(back)
                        errorWrapper.set(
                            "${oneLine(folder.toString(), MAX_PATH_CHARS)} isn't a JDK: no bin/java or release file",
                        )
                        return@Runnable
                    }
                    val existing = choices.firstOrNull { same(folder, it.path) }
                    val choice =
                        existing ?: JdkChoice(jdk.home, describe(jdk)).also { choices.add(choices.size - 1, it) }
                    selected.set(choice)
                },
            )
        }
    }
}
