package dev.sluide.gitlog.filter

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCommit
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import dev.sluide.gitlog.data.GitLogPages
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilterSourcesTest {
    private val repo = Path.of("/repo")
    private val other = Path.of("/other")

    private var authors = listOf("bob", "Alice", "alice2", "Me")
    private var refs: GitRefs? = null

    /** What the user name read answers for each root; `null` means the read fails. */
    private val userNames = mutableMapOf<Path, String?>(repo to "Me", other to "Other Me")

    /** What the tree read answers for each root; `null` means the read fails. */
    private val treePaths =
        mutableMapOf<Path, List<GitTreePath>?>(
            repo to listOf(GitTreePath("src", true), GitTreePath("src/a.txt", false)),
            other to emptyList(),
        )
    private val userNameLoads = mutableListOf<Path>()
    private val pathLoads = mutableListOf<Path>()
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()

    private val pages =
        GitLogPages(
            load = { _, _, _, _, _ ->
                GitLogPage(
                    authors.mapIndexed { i, author ->
                        GitCommit("h$i", emptyList(), author, "a@example.com", Instant.EPOCH, "Commit $i")
                    },
                    complete = true,
                )
            },
            loadRefs = { _, _ -> refs },
            loadHashMatch = { _, _, _ -> null },
            background = background::addLast,
            dispatch = dispatched::addLast,
            tasks = BackgroundTasks.forTests { },
        )
    private val sources =
        FilterSources(
            pages,
            loadUserName = {
                userNameLoads.add(it)
                userNames[it]
            },
            loadPaths = {
                pathLoads.add(it)
                treePaths[it]
            },
            background = background::addLast,
            dispatch = dispatched::addLast,
        )
    private var userReads = 0
    private var pathReads = 0

    init {
        sources.userNameRead = { userReads++ }
        sources.pathsRead = { pathReads++ }
    }

    private fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
        }
    }

    private fun open(root: Path) {
        pages.open(root)
        pages.visible = true
        settle()
    }

    private fun List<FilterItem>.texts() = map { it.text }

    @Test
    fun `the branch items are HEAD, then local, remote branches and tags, each by name`() {
        refs =
            GitRefs(
                listOf(
                    GitRef("refs/tags/v1", GitRefKind.TAG, "h0"),
                    GitRef("refs/remotes/origin/b", GitRefKind.REMOTE_BRANCH, "h0"),
                    GitRef("refs/heads/z", GitRefKind.LOCAL_BRANCH, "h0"),
                    GitRef("refs/heads/a", GitRefKind.LOCAL_BRANCH, "h0"),
                    GitRef("refs/remotes/origin/a", GitRefKind.REMOTE_BRANCH, "h0"),
                ),
                "refs/heads/a",
            )
        assertEquals(listOf("HEAD"), sources.branchItems().texts(), "nothing read yet")

        open(repo)

        val items = sources.branchItems()
        assertEquals(listOf("HEAD", "a", "z", "origin/a", "origin/b", "v1"), items.texts())
        assertEquals(
            listOf(
                "HEAD",
                "refs/heads/a",
                "refs/heads/z",
                "refs/remotes/origin/a",
                "refs/remotes/origin/b",
                "refs/tags/v1",
            ),
            items.map { it.value },
        )
    }

    @Test
    fun `the user items are me first, then the other authors by name ignoring case`() {
        open(repo)
        assertEquals(listOf("Alice", "alice2", "bob", "Me"), sources.userItems().texts(), "no user name yet")

        sources.readUserName()
        settle()

        val items = sources.userItems()
        assertEquals(listOf("me (Me)", "Alice", "alice2", "bob"), items.texts())
        assertEquals("Me", items.first().value)
        assertEquals("me", items.first().short)
        assertEquals(1, userReads)
    }

    @Test
    fun `the authors of the rows loaded since the root was opened are kept until reset`() {
        open(repo)
        authors = listOf("carol")
        pages.refresh()
        settle()
        assertEquals(listOf("Alice", "alice2", "bob", "carol", "Me"), sources.userItems().texts())

        sources.reset()
        assertEquals(emptyList(), sources.userItems().texts())
    }

    @Test
    fun `the user name is read once per root`() {
        open(repo)
        sources.readUserName()
        sources.readUserName()
        settle()
        sources.readUserName()
        settle()
        assertEquals(listOf(repo), userNameLoads)

        sources.reset()
        open(other)
        sources.readUserName()
        settle()
        assertEquals(listOf(repo, other), userNameLoads)
        assertEquals("me (Other Me)", sources.userItems().first().text)
    }

    @Test
    fun `nothing is read without an opened root`() {
        sources.readUserName()
        sources.readPaths()
        settle()
        assertEquals(emptyList(), userNameLoads + pathLoads)
    }

    @Test
    fun `a failed user name read is asked again the next time`() {
        userNames[repo] = null
        open(repo)
        sources.readUserName()
        settle()
        assertEquals(0, userReads)
        assertEquals(listOf("Alice", "alice2", "bob", "Me"), sources.userItems().texts())

        userNames[repo] = "Me"
        sources.readUserName()
        settle()
        assertEquals(listOf(repo, repo), userNameLoads)
        assertEquals("me (Me)", sources.userItems().first().text)
    }

    @Test
    fun `a failed path read is asked again the next time`() {
        treePaths[repo] = null
        open(repo)
        sources.readPaths()
        settle()
        assertEquals(0, pathReads)
        assertEquals(emptyList(), sources.pathItems().texts())

        treePaths[repo] = listOf(GitTreePath("src", true))
        sources.readPaths()
        settle()
        assertEquals(listOf(repo, repo), pathLoads)
        assertEquals(listOf("src"), sources.pathItems().texts())
        assertEquals(1, pathReads)
    }

    @Test
    fun `a failed read that ends after a newer one started does not drop the newer one's result`() {
        treePaths[repo] = null
        open(repo)
        sources.readPaths()
        // The first read has failed; its result is not handled yet.
        while (background.isNotEmpty()) background.removeFirst().run()

        treePaths[repo] = listOf(GitTreePath("docs", true))
        sources.markPathsStale()
        sources.readPaths()
        settle()

        assertEquals(listOf("docs"), sources.pathItems().texts())
        assertEquals(1, pathReads)
        sources.readPaths()
        settle()
        assertEquals(listOf(repo, repo), pathLoads, "the newer read counts as done")
    }

    @Test
    fun `the paths are read once per root, and again after they were marked stale`() {
        open(repo)
        sources.readPaths()
        settle()
        sources.readPaths()
        settle()
        assertEquals(listOf(repo), pathLoads, "a filter change keeps the tree")
        assertEquals(listOf("src", "src/a.txt"), sources.pathItems().texts())

        sources.markPathsStale()
        treePaths[repo] = listOf(GitTreePath("docs", true))
        sources.readPaths()
        settle()
        sources.readPaths()
        settle()

        assertEquals(listOf(repo, repo), pathLoads)
        assertEquals(listOf("docs"), sources.pathItems().texts())
        assertEquals(2, pathReads)
    }

    @Test
    fun `a read that ends after another root was opened is dropped`() {
        open(repo)
        sources.readUserName()
        sources.readPaths()
        // Both reads are done; the root changes before their results are handed on.
        while (background.isNotEmpty()) background.removeFirst().run()
        sources.reset()
        open(other)

        assertEquals(emptyList(), sources.userItems().texts().filter { it.startsWith("me") })
        assertEquals(emptyList(), sources.pathItems().texts())
        assertEquals(0, userReads + pathReads)

        sources.readUserName()
        sources.readPaths()
        settle()
        assertEquals("me (Other Me)", sources.userItems().first().text)
        assertEquals(emptyList(), sources.pathItems().texts(), "the other root has no tree")
        assertEquals(listOf(repo, other), userNameLoads)
    }

    @Test
    fun `a typed path is normalized, and nothing is offered for one outside the repository`() {
        open(repo)
        sources.readPaths()
        settle()

        assertEquals("src", sources.freePath("./src/")?.value)
        assertEquals("docs/x", sources.freePath(" docs/x ")?.text)
        assertNull(sources.freePath("../x"))
        assertNull(sources.freePath("  "))
    }
}
