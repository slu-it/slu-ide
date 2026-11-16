package dev.sluide.java

import dev.sluide.languageservers.ProblemAction
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutputIgnoreOffersTest {
    @TempDir
    lateinit var dir: Path

    private val window by lazy { dir.resolve("repo").createDirectories() }
    private val tools = mutableMapOf<Path, BuildTool>()
    private val asked = mutableListOf<List<Path>>()
    private val answers = mutableListOf<(List<Path>?) -> Unit>()
    private var declined = false
    private var declinedReads = 0
    private var declineFailure: IOException? = null
    private var declines = 0
    private var changes = 0
    private val queuedWork = mutableListOf<Runnable>()
    private val queuedDispatch = mutableListOf<Runnable>()
    private var deferWork = false
    private var deferDispatch = false

    private val checks = OutputIgnoreChecks()

    private val offers by lazy {
        checks.register(window) { folders, done ->
            asked += folders
            answers += done
        }
        OutputIgnoreOffers(
            window,
            checks,
            tools::get,
            {
                declinedReads++
                declined
            },
            {
                declines++
                declineFailure?.let { throw it }
            },
            { task -> if (deferWork) queuedWork += task else task.run() },
            { task -> if (deferDispatch) queuedDispatch += task else task.run() },
            { changes++ },
        )
    }

    private fun root(
        name: String = "app",
        tool: BuildTool = BuildTool.GRADLE,
    ): Path = window.resolve(name).createDirectories().also { tools[it] = tool }

    /** The folders `getAll` listed: the build root itself and the given subfolders. */
    private fun done(
        root: Path,
        vararg sub: String,
        withFiles: Boolean = true,
    ) {
        val projects = listOf(root) + sub.map(root::resolve)
        if (withFiles) {
            tools[root]?.let { OutputIgnore.outputFolders(it, projects) }?.forEach { filesIn(it) }
        }
        offers.rootDone(root, projects)
    }

    private fun filesIn(folder: Path) {
        folder.createDirectories().resolve("A.class").writeText("x")
    }

    private fun answer(unignored: List<Path>?) {
        assertTrue(answers.isNotEmpty(), "a check was asked")
        answers.removeFirst()(unignored)
    }

    private fun build(root: Path) = root.resolve("build/jdtls-bin")

    private fun building(
        token: String,
        title: String? = "Building",
    ) {
        offers.progressBegan(token, title)
        offers.progressEnded(token)
    }

    private fun offered(root: Path = root()) = assertNotNull(offers.problem(root))

    @Test
    fun `a Done root asks for the output folders of Gradle projects, build jdtls-bin`() {
        val root = root()

        done(root, "sub")

        assertEquals(listOf(listOf(build(root), build(root.resolve("sub")))), asked)
    }

    @Test
    fun `a Done Maven root asks for the target folders`() {
        val root = root("lib", BuildTool.MAVEN)

        done(root, "core")

        assertEquals(listOf(listOf(root.resolve("target"), root.resolve("core/target"))), asked)
    }

    @Test
    fun `a Done root of no known build tool asks nothing`() {
        done(window.resolve("unknown").createDirectories())

        assertEquals(emptyList(), asked)
    }

    @Test
    fun `an output folder that appears after the first check is offered at the next build's end`() {
        val root = root()
        done(root, withFiles = false)
        assertEquals(emptyList(), asked, "no file to ask git about yet")
        offers.progressBegan("b", "Building")

        filesIn(build(root))
        offers.progressEnded("b")

        assertEquals(listOf(listOf(build(root))), asked)
        answer(listOf(build(root)))
        assertNotNull(offers.problem(root))
    }

    @Test
    fun `a folder that gets its first file later is asked at the next build's end, an answered one is not`() {
        val root = root()
        val lib = root.resolve("lib")
        done(root, "lib", withFiles = false)
        filesIn(build(root))
        building("b")
        assertEquals(listOf(listOf(build(root))), asked)
        answer(listOf(build(root)))
        assertEquals("build/jdtls-bin isn't ignored by git; jdtls compiles into it.", offered(root).message)

        building("c")
        assertEquals(1, asked.size, "lib has no file yet and the root's own folder is answered")
        filesIn(build(lib))
        building("d")

        assertEquals(listOf(listOf(build(lib))), asked.drop(1))
        answer(listOf(build(lib)))
        assertEquals(
            "build/jdtls-bin and lib/build/jdtls-bin aren't ignored by git; jdtls compiles into them.",
            offered(root).message,
        )
        building("e")
        assertEquals(2, asked.size, "every folder is answered now")
    }

    @Test
    fun `a root whose folders are all answered costs no work at a build's end`() {
        val root = root()
        done(root)
        answer(emptyList())
        val reads = declinedReads

        building("b")

        assertEquals(reads, declinedReads)
    }

    @Test
    fun `an ignored answered folder stays out of the offer when another folder is answered later`() {
        val root = root()
        val lib = root.resolve("lib")
        done(root, "lib", withFiles = false)
        filesIn(build(root))
        building("b")
        answer(emptyList())
        assertNull(offers.problem(root))

        filesIn(build(lib))
        building("c")
        answer(listOf(build(lib)))

        assertEquals("lib/build/jdtls-bin isn't ignored by git; jdtls compiles into it.", offered(root).message)
    }

    @Test
    fun `only the folders that hold a file are asked about`() {
        val root = root()
        done(root, "sub", withFiles = false)
        filesIn(build(root))

        building("b")

        assertEquals(listOf(listOf(build(root))), asked)
    }

    @Test
    fun `a folder with only empty subfolders is not asked about, as git lists no folder`() {
        val root = root()
        build(root).resolve("classes/main").createDirectories()

        done(root, withFiles = false)
        building("b")

        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a Done root with no project folder asks nothing`() {
        offers.rootDone(root(), emptyList())

        assertEquals(emptyList(), asked)
        assertEquals(0, declinedReads, "not even the project's decision is read")
    }

    @Test
    fun `the banner names one unignored folder relative to the build root`() {
        val root = root()
        done(root)

        answer(listOf(build(root)))

        assertEquals("build/jdtls-bin isn't ignored by git; jdtls compiles into it.", offered(root).message)
    }

    @Test
    fun `the banner joins several folders with a comma and and`() {
        val root = root()
        done(root, "app", "lib")

        answer(listOf(build(root), build(root.resolve("app")), build(root.resolve("lib"))))

        assertEquals(
            "build/jdtls-bin, app/build/jdtls-bin and lib/build/jdtls-bin aren't ignored by git; " +
                "jdtls compiles into them.",
            offered(root).message,
        )
    }

    @Test
    fun `the banner for two folders joins them with and`() {
        val root = root()
        done(root, "app")

        answer(listOf(build(root), build(root.resolve("app"))))

        assertEquals(
            "build/jdtls-bin and app/build/jdtls-bin aren't ignored by git; jdtls compiles into them.",
            offered(root).message,
        )
    }

    @Test
    fun `the banner is not dismissible and has its own three actions in order`() {
        val root = root()
        done(root)

        answer(listOf(build(root)))

        val problem = offered(root)
        assertEquals(root, problem.folder)
        assertFalse(problem.dismissible)
        assertEquals(listOf("Add to .gitignore", "Not Now", "Don't Ask Again"), problem.actions.map { it.label })
        assertTrue(problem.actions.all { it is ProblemAction.Callback })
    }

    @Test
    fun `an empty answer makes no banner and a null answer none, but only null is asked again`() {
        val root = root()
        done(root)
        answer(emptyList())
        assertNull(offers.problem(root))
        building("b1")
        assertEquals(1, asked.size, "an answer of git settles the root")

        val other = root("other")
        done(other)
        answer(null)
        assertNull(offers.problem(other))
        building("b2")
        assertEquals(3, asked.size, "no answer yet: asked again at the next end of a build")
        assertEquals(listOf(build(other)), asked.last())
    }

    @Test
    fun `an unignored answer is not asked again at the end of a build`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))

        building("b")

        assertEquals(1, asked.size)
        assertNotNull(offers.problem(root))
    }

    @Test
    fun `only a token titled Building that ends asks, and only the roots without an answer`() {
        val first = root("first")
        val second = root("second")
        done(first)
        done(second)
        answer(null)
        answer(listOf(build(second)))
        asked.clear()

        offers.progressBegan("u", "Updating workspace folders")
        offers.progressEnded("u")
        offers.progressEnded("never began")
        offers.progressBegan("b", "Building")
        assertEquals(emptyList(), asked, "a build that has not ended")
        offers.progressEnded("b")

        assertEquals(listOf(listOf(build(first))), asked)
    }

    @Test
    fun `a title that only contains Building is not a build`() {
        val root = root()
        done(root)
        answer(null)
        asked.clear()

        building("b", "Rebuilding workspace")
        building("c", null)

        assertEquals(emptyList(), asked)
    }

    @Test
    fun `the next Done of a root forgets its answer and asks again`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))

        done(root)

        assertNull(offers.problem(root), "no banner from the old answer while the new one is awaited")
        assertEquals(2, asked.size)
        answer(listOf(build(root)))
        assertNotNull(offers.problem(root))
    }

    @Test
    fun `an answer that comes after a newer Done is dropped`() {
        val root = root()
        done(root)
        done(root)

        answer(listOf(build(root)))
        assertNull(offers.problem(root), "the first request is stale")
        answer(emptyList())

        assertNull(offers.problem(root))
        building("b")
        assertEquals(2, asked.size, "the newer answer settled the root")
    }

    @Test
    fun `a forgotten root has no banner, is not asked at a build's end and drops a late answer`() {
        val root = root()
        done(root)
        offers.forget(root)

        answer(listOf(build(root)))
        building("b")

        assertNull(offers.problem(root))
        assertEquals(1, asked.size)
    }

    @Test
    fun `clearing forgets every root`() {
        val first = root("first")
        val second = root("second")
        done(first)
        done(second)
        answer(listOf(build(first)))

        offers.clear()
        answer(listOf(build(second)))
        building("b")

        assertNull(offers.problem(first))
        assertNull(offers.problem(second))
        assertEquals(2, asked.size)
    }

    @Test
    fun `the same answer again keeps the very same banner, a different one a new banner`() {
        val root = root()
        done(root, "sub")
        answer(listOf(build(root)))
        val first = offered(root)

        // Add to .gitignore asks git again; with the same answer the buttons stay the very same ones.
        (first.actions[0] as ProblemAction.Callback).perform()
        answer(listOf(build(root)))
        assertEquals(first.actions, offered(root).actions)
        assertSame(offered(root), offered(root), "stable between recomputes")

        (first.actions[0] as ProblemAction.Callback).perform()
        answer(listOf(build(root), build(root.resolve("sub"))))
        assertNotSame(first, offered(root))
    }

    @Test
    fun `a written line leaves the banner as it was while git is asked again`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))
        val first = offered(root)

        (first.actions[0] as ProblemAction.Callback).perform()

        assertSame(first, offered(root))
    }

    @Test
    fun `when git still lists a folder after the line was added, the banner says so`() {
        val root = root()
        done(root, "sub")
        answer(listOf(build(root), build(root.resolve("sub"))))

        (offered(root).actions[0] as ProblemAction.Callback).perform()
        assertEquals(
            "build/jdtls-bin and sub/build/jdtls-bin aren't ignored by git; jdtls compiles into them.",
            offered(root).message,
        )
        answer(listOf(build(root.resolve("sub"))))

        assertEquals(
            "Added to ${root.resolve(".gitignore")}, but git still doesn't ignore sub/build/jdtls-bin.",
            offered(root).message,
        )
        assertEquals(listOf("Add to .gitignore", "Not Now", "Don't Ask Again"), offered(root).actions.map { it.label })
    }

    @Test
    fun `Not Now hides the banner of that root only and the root keeps it through its next Done`() {
        val first = root("first")
        val second = root("second")
        done(first)
        done(second)
        answer(listOf(build(first)))
        answer(listOf(build(second)))
        val changesBefore = changes

        (offered(first).actions[1] as ProblemAction.Callback).perform()

        assertNull(offers.problem(first))
        assertNotNull(offers.problem(second))
        assertTrue(changes > changesBefore, "the banner is recomputed")
        done(first)
        answer(listOf(build(first)))
        assertNull(offers.problem(first), "Not Now holds until the next session")
    }

    @Test
    fun `Not Now holds until the window's session is cleared`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))
        (offered(root).actions[1] as ProblemAction.Callback).perform()

        offers.clear()
        done(root)
        answer(listOf(build(root)))

        assertNotNull(offers.problem(root))
    }

    @Test
    fun `Don't Ask Again stores the decision off the FX thread and hides every root, asking no more`() {
        val first = root("first")
        val second = root("second")
        done(first)
        done(second)
        answer(listOf(build(first)))
        answer(listOf(build(second)))
        deferWork = true

        (offered(first).actions[2] as ProblemAction.Callback).perform()

        assertEquals(0, declines, "not on the calling thread")
        deferWork = false
        queuedWork.single().run()
        assertEquals(1, declines)
        assertNull(offers.problem(first))
        assertNull(offers.problem(second))
        val askedBefore = asked.size
        done(first)
        building("b")
        assertEquals(askedBefore, asked.size)
    }

    @Test
    fun `a decision that cannot be saved is said in the banner and the banner stays`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))
        declineFailure = IOException("disk full")

        (offered(root).actions[2] as ProblemAction.Callback).perform()

        assertEquals("Cannot save the choice: disk full", offered(root).message)
    }

    @Test
    fun `a project that declined gets no check and no banner`() {
        declined = true
        val root = root()

        done(root)

        assertEquals(emptyList(), asked)
        assertNull(offers.problem(root))
    }

    @Test
    fun `Add to gitignore writes the lines into the nearest gitignore and asks git again`() {
        val root = root()
        window.resolve(".gitignore").writeText("*.log\n")
        done(root, "sub")
        answer(listOf(build(root), build(root.resolve("sub"))))
        deferWork = true
        deferDispatch = true

        (offered(root).actions[0] as ProblemAction.Callback).perform()

        assertEquals("*.log\n", window.resolve(".gitignore").readText(), "written by the worker only")
        queuedWork.single().run()
        deferWork = false
        assertEquals(
            "*.log\n/app/build/jdtls-bin/\n/app/sub/build/jdtls-bin/\n",
            window.resolve(".gitignore").readText(),
        )
        assertEquals(1, asked.size, "the result comes back through dispatch")
        deferDispatch = false
        queuedDispatch.single().run()
        assertEquals(2, asked.size)
        assertNotNull(offers.problem(root), "the banner goes once git agrees")
        answer(emptyList())
        assertNull(offers.problem(root))
    }

    @Test
    fun `Add to gitignore creates one in the build root when there is none`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))

        (offered(root).actions[0] as ProblemAction.Callback).perform()

        assertEquals("/build/jdtls-bin/\n", root.resolve(".gitignore").readText())
    }

    @Test
    fun `a refused write is said in the banner and a later success takes it away`() {
        val root = root()
        val real = dir.resolve("real").apply { writeText("") }
        window.resolve(".gitignore").createSymbolicLinkPointingTo(real)
        done(root)
        answer(listOf(build(root)))
        val actions = offered(root).actions

        (actions[0] as ProblemAction.Callback).perform()

        assertEquals(
            "Cannot add to ${window.resolve(".gitignore")}: it is a symbolic link",
            offered(root).message,
        )
        assertEquals(actions, offered(root).actions, "the same buttons")
        assertEquals(1, asked.size, "nothing was written, so git is not asked")

        java.nio.file.Files
            .delete(window.resolve(".gitignore"))
        (actions[0] as ProblemAction.Callback).perform()

        assertEquals("build/jdtls-bin isn't ignored by git; jdtls compiles into it.", offered(root).message)
        assertEquals(2, asked.size)
    }

    @Test
    fun `a write result for a root that was forgotten meanwhile changes nothing`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))
        deferDispatch = true
        (offered(root).actions[0] as ProblemAction.Callback).perform()
        offers.forget(root)

        val changesBefore = changes

        queuedDispatch.single().run()

        assertEquals(1, asked.size)
        assertNull(offers.problem(root))
        assertEquals(changesBefore, changes, "nothing to recompute")
    }

    @Test
    fun `the banners are recomputed when an offer appears, and not for an answer that changes nothing`() {
        val root = root()
        done(root)
        answer(emptyList())
        assertEquals(0, changes, "no offer, none to hide")

        offers.forget(root)
        done(root)
        answer(listOf(build(root)))
        assertEquals(1, changes, "an offer appeared")
    }

    @Test
    fun `an answer that arrives after Don't Ask Again shows no banner`() {
        val first = root("first")
        val second = root("second")
        done(first)
        done(second)
        answer(listOf(build(first)))
        (offered(first).actions[2] as ProblemAction.Callback).perform()

        answer(listOf(build(second)))

        assertNull(offers.problem(second))
    }

    @Test
    fun `the banners are recomputed once the decision is saved or could not be`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))
        val before = changes

        (offered(root).actions[2] as ProblemAction.Callback).perform()
        assertEquals(before + 1, changes, "saved")

        offers.clear()
        done(root)
        answer(listOf(build(root)))
        declineFailure = IOException("disk full")
        val failedBefore = changes
        (offered(root).actions[2] as ProblemAction.Callback).perform()

        assertEquals(failedBefore + 1, changes, "not saved")
    }

    @Test
    fun `a project that declined in the meantime hides the offers and recomputes the banners`() {
        val first = root("first")
        val second = root("second")
        done(first)
        answer(listOf(build(first)))
        declined = true
        val before = changes

        done(second)

        assertEquals(emptyList(), asked.drop(1))
        assertNull(offers.problem(first))
        assertEquals(before + 1, changes)
    }

    @Test
    fun `a decision that a project declined is read once, not at every check`() {
        val first = root("first")
        val second = root("second")
        declined = true

        done(first)
        done(second)
        building("b")

        assertEquals(1, declinedReads)
    }

    @Test
    fun `a project that declined is asked about its decision again in the next session`() {
        val root = root()
        declined = true
        done(root)
        declined = false

        offers.clear()
        done(root)

        assertEquals(1, asked.size)
    }

    @Test
    fun `an ask that a newer one replaced before its decision was read does not go to git`() {
        val root = root()
        deferDispatch = true
        done(root)
        done(root)

        deferDispatch = false
        queuedDispatch.forEach { it.run() }

        assertEquals(1, asked.size)
    }

    @Test
    fun `an old answer is forgotten when a root is done again, so no answer asks at the next build's end`() {
        val root = root()
        done(root)
        answer(emptyList())
        done(root)
        answer(null)

        building("b")

        assertEquals(3, asked.size)
    }

    @Test
    fun `a token of an ended session does not end a build in the next one`() {
        val root = root()
        done(root)
        answer(null)
        offers.progressBegan("old", "Building")
        offers.clear()
        done(root)
        answer(null)

        offers.progressEnded("old")

        assertEquals(2, asked.size)
    }

    @Test
    fun `after a written line git is asked again and if it cannot answer the next build asks once more`() {
        val root = root()
        done(root)
        answer(listOf(build(root)))
        val before = changes

        (offered(root).actions[0] as ProblemAction.Callback).perform()
        assertTrue(changes > before, "the banners are recomputed")
        answer(null)
        building("b")

        assertEquals(3, asked.size)
    }
}
