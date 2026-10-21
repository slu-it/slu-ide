package dev.sluide.dockersandbox

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.editorarea.EditorTab
import dev.sluide.terminal.Terminals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import kotlin.io.path.isRegularFile

private val log = System.getLogger(DockerSandbox::class.java.name)

/** Thrown when a sandbox may not be started in a folder; the message says why. */
class SandboxNotAllowedException(
    message: String,
) : RuntimeException(message)

/**
 * Starts Docker sandboxes (`sbx`) in terminal tabs. They run only when `sbx` is installed ([sbxInstalled]) and only in
 * folders below the user's home folder (see [SandboxFolder]): [problem] tells the menu whether an action can run, and
 * [open] checks again before it starts one.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class DockerSandbox(
    private val terminals: Terminals,
    /** The user's home folder. */
    private val home: Path,
    /** The environment of the started shells. */
    private val environment: Map<String, String>,
    /** Whether `sbx` is installed; until this completes, it counts as not installed. */
    private val sbxCheck: CompletableFuture<Boolean>,
) {
    @Autowired
    constructor(terminals: Terminals) : this(terminals, Path.of(System.getProperty("user.home")), System.getenv())

    private constructor(terminals: Terminals, home: Path, environment: Map<String, String>) : this(
        terminals,
        home,
        environment,
        // Checked once per window, in the background, as the login shell may take a while.
        CompletableFuture.supplyAsync(
            { SbxCheck.installed(environment) },
            { NamedThreads.virtual("sluide-sbx-check", it) },
        ),
    )

    /** Completes with whether `sbx` is installed, i.e. found by the login shell the sandboxes run in. */
    val sbxInstalled: CompletionStage<Boolean> =
        sbxCheck
            .handle { installed, failure ->
                // A failed check is logged once and counts as not installed; this stage never fails.
                if (failure != null) log.log(System.Logger.Level.ERROR, "The check for sbx failed", failure)
                failure == null && installed == true
            }.minimalCompletionStage()

    private val started = mutableMapOf<SandboxAction, Int>()

    /** Why [action] cannot run in [folder] (`null`: no folder is open), or `null` if it can. */
    fun problem(
        action: SandboxAction,
        folder: Path?,
    ): String? {
        if (!installed()) return SBX_MISSING
        if (folder == null) return NO_FOLDER
        SandboxFolder.check(folder, home)?.let { return it }
        val required = action.requiredFile ?: return null
        return if (folder.resolve(required).isRegularFile()) null else "There is no $required in $folder."
    }

    /** Whether the check found `sbx`; `false` while it runs or if it failed (logged by [sbxInstalled]). */
    private fun installed(): Boolean =
        try {
            sbxCheck.getNow(false) == true
        } catch (_: CompletionException) {
            false
        } catch (_: CancellationException) {
            false
        }

    /**
     * Runs [action] in a terminal tab in [folder], named e.g. "Claude Code 1", and returns the tab. Must be called on
     * the FX thread.
     *
     * @throws SandboxNotAllowedException if [problem] reports one (e.g. "No folder is open."), also when the menu
     *   should not have offered it.
     * @throws dev.sluide.terminal.TerminalStartException if the shell cannot be started; the next tab of [action]
     *   gets the same number.
     */
    fun open(
        action: SandboxAction,
        folder: Path?,
    ): EditorTab {
        if (folder == null) throw SandboxNotAllowedException(problem(action, null) ?: NO_FOLDER)
        // Checked again here, on the very folder the terminal starts in, not only when the menu is shown.
        problem(action, folder)?.let { throw SandboxNotAllowedException(it) }
        val number = started.getOrDefault(action, 0) + 1
        return terminals
            .open(folder, "${action.title} $number", action.command, environment)
            .also { started[action] = number }
    }
}

private const val NO_FOLDER = "No folder is open."
private const val SBX_MISSING = "sbx is not installed (not found on the PATH of your login shell)."
