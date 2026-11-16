package dev.sluide.java

import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.ProblemAction
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

private const val BUILDING_TITLE = "Building"

/**
 * One window's offer to ignore jdtls's compiled output in git (ADR 0179): for each build root whose import is done,
 * which of its output folders git does not ignore ([OutputIgnoreChecks]), and the banner that offers a `.gitignore`
 * line for them ([OutputIgnore]).
 *
 * A root is asked when it becomes done ([rootDone]) and at the end of each jdtls token titled `Building` while one of
 * its output folders has no answer: jdtls's auto-build writes the output then. Git is asked about the unanswered
 * folders that hold a file by then ([OutputIgnore.hasFile]); an answer of git, ignored or not, settles those folders
 * until the root's next [rootDone], and the root's offer is the unignored ones among the answered folders. No answer
 * (no repository, trust, a failure) settles nothing. A root counts as answered only when every output folder is: an
 * aggregator project whose own folder never gets a file costs one `hasFile` walk per build end and no git call. A
 * project that said "Don't Ask Again" is not asked at all.
 *
 * All calls are on the FX thread; files and the project's data are written on [worker] and come back through
 * [dispatch].
 *
 * @param toolOf the build tool of a root, null if unknown.
 * @param declined whether the project declined the offer for good; read on [worker].
 * @param decline stores that decision, throws IOException; called on [worker].
 * @param changed recomputes the banners.
 */
internal class OutputIgnoreOffers(
    private val windowFolder: Path,
    private val checks: OutputIgnoreChecks,
    private val toolOf: (Path) -> BuildTool?,
    private val declined: () -> Boolean,
    private val decline: () -> Unit,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
) {
    /** The output folders of each done root. */
    private val folders = HashMap<Path, List<Path>>()

    /** Per root, the output folders git answered for since its last [rootDone]: whether it is unignored. */
    private val answers = HashMap<Path, HashMap<Path, Boolean>>()
    private val offers = HashMap<Path, Offer>()

    /** Counts the asks per root, so that an answer to an older one is dropped. */
    private val serials = HashMap<Path, Int>()
    private var asks = 0

    /** The roots whose banner "Not Now" hid, for the life of this object: the window's session, not the server's. */
    private val notNow = HashSet<Path>()
    private var declinedHere = false

    /** The title of each open progress token. */
    private val titles = HashMap<String, String?>()

    /** One root's banner: the unignored output folders, its buttons and, once something failed, why. */
    private inner class Offer(
        val root: Path,
        val unignored: List<Path>,
    ) {
        private val actions: List<ProblemAction> =
            listOf(
                ProblemAction.Callback("Add to .gitignore") { add(this) },
                ProblemAction.Callback("Not Now") {
                    notNow.add(root)
                    changed()
                },
                ProblemAction.Callback("Don't Ask Again") { declineForGood(this) },
            )
        private var failure: String? = null

        /** The `.gitignore` the lines were written to by Add to .gitignore, if that succeeded. */
        var addedTo: Path? = null

        var problem: EnvironmentProblem = build()
            private set

        /** Shows [message] instead of the offer's text, or the text again for null. */
        fun fail(message: String?) {
            if (message == failure) return
            failure = message
            problem = build()
        }

        private fun build() = EnvironmentProblem(root, failure ?: text(), actions, dismissible = false)

        /** Git's newest answer still lists folders: says so if a line was written, instead of the same offer. */
        fun listedAfterAdd() {
            addedTo?.let { fail("Added to $it, but git still doesn't ignore ${names()}.") }
        }

        private fun names(): String {
            val names = unignored.map { root.relativize(it).joinToString("/") }
            val head = names.dropLast(1).joinToString(", ")
            return if (names.size == 1) names.single() else "$head and ${names.last()}"
        }

        private fun text() =
            if (unignored.size == 1) {
                "${names()} isn't ignored by git; jdtls compiles into it."
            } else {
                "${names()} aren't ignored by git; jdtls compiles into them."
            }
    }

    /** The import of [root] is done: [projects] are the project folders jdtls listed below it. */
    fun rootDone(
        root: Path,
        projects: List<Path>,
    ) {
        forget(root)
        val outputs = toolOf(root)?.let { OutputIgnore.outputFolders(it, projects) }.orEmpty()
        if (outputs.isEmpty()) return
        folders[root] = outputs
        check(root, outputs)
    }

    /** [root] is no longer done (imports again, failed, removed): no answer, no banner. "Not Now" stays. */
    fun forget(root: Path) {
        folders.remove(root)
        answers.remove(root)
        offers.remove(root)
        serials.remove(root)
    }

    /**
     * The server stopped: every root, answer and offer is forgotten, but "Not Now" stays, as it lasts for the window's
     * session, which outlives the server (restarts, a memory change, Clean Java Workspace, crashes).
     */
    fun clear() {
        folders.clear()
        answers.clear()
        offers.clear()
        serials.clear()
        titles.clear()
        declinedHere = false
    }

    fun progressBegan(
        token: String,
        title: String?,
    ) {
        titles[token] = title
    }

    /** A token ended: at the end of a build, the roots without an answer are asked. */
    fun progressEnded(token: String) {
        if (titles.remove(token) != BUILDING_TITLE) return
        folders
            .filter { (root, outputs) -> answers[root]?.keys?.containsAll(outputs) != true }
            .forEach { (root, outputs) -> check(root, outputs) }
    }

    /** The banner of [root], or null: no offer, "Not Now" or "Don't Ask Again". */
    fun problem(root: Path): EnvironmentProblem? = if (declinedHere || root in notNow) null else offers[root]?.problem

    private fun check(
        root: Path,
        outputs: List<Path>,
    ) {
        if (declinedHere) return
        val pending = outputs.filter { it !in answers[root].orEmpty() }
        val serial = ++asks
        serials[root] = serial
        execute {
            val decided = declined()
            // Git lists files, never a folder, so it is asked about the folders with a file only: an answer for a
            // folder that jdtls has not compiled into yet would settle the root with "ignored".
            val withFiles = if (decided) emptyList() else pending.filter(OutputIgnore::hasFile)
            dispatch(
                Runnable {
                    if (serials[root] != serial) return@Runnable
                    if (decided) {
                        declinedHere = true
                        changed()
                    } else if (withFiles.isNotEmpty()) {
                        ask(root, withFiles, serial)
                    }
                },
            )
        }
    }

    private fun ask(
        root: Path,
        asked: List<Path>,
        serial: Int,
    ) {
        checks.check(windowFolder, asked) { answer ->
            if (serials[root] != serial || answer == null) return@check
            val known = answers.getOrPut(root) { HashMap() }
            asked.forEach { known[it] = it in answer }
            val unignored = folders[root].orEmpty().filter { known[it] == true }
            val before = offers[root]
            if (unignored.isEmpty()) {
                offers.remove(root)
            } else {
                val offer =
                    before?.takeIf { it.unignored == unignored }
                        ?: Offer(root, unignored).also { it.addedTo = before?.addedTo }
                offers[root] = offer
                offer.listedAfterAdd()
            }
            if (before?.problem !== offers[root]?.problem) changed()
        }
    }

    private fun add(offer: Offer) {
        execute {
            val target = OutputIgnore.target(offer.root, windowFolder)
            val failure = OutputIgnore.add(target, windowFolder, offer.unignored)
            dispatch(
                Runnable {
                    if (offers[offer.root] !== offer) return@Runnable
                    offer.fail(failure)
                    if (failure == null) {
                        offer.addedTo = target
                        answers[offer.root]?.keys?.removeAll(offer.unignored.toSet())
                        folders[offer.root]?.let { check(offer.root, it) }
                    }
                    changed()
                },
            )
        }
    }

    private fun declineForGood(offer: Offer) {
        execute {
            val failure =
                try {
                    decline()
                    null
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                }
            dispatch(
                Runnable {
                    if (failure == null) {
                        declinedHere = true
                    } else {
                        offer.fail("Cannot save the choice: $failure")
                    }
                    changed()
                },
            )
        }
    }

    private fun execute(task: Runnable) {
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            // The session closed.
        }
    }
}
