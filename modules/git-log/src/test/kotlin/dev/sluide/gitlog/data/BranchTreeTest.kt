package dev.sluide.gitlog.data

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchTreeTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    private fun ref(
        name: String,
        kind: GitRefKind,
    ) = GitRef(name, kind, hash)

    private fun local(name: String) = ref("refs/heads/$name", GitRefKind.LOCAL_BRANCH)

    private fun remote(name: String) = ref("refs/remotes/$name", GitRefKind.REMOTE_BRANCH)

    private fun tag(name: String) = ref("refs/tags/$name", GitRefKind.TAG)

    private val head = ref("HEAD", GitRefKind.HEAD)

    /** The tree as indented lines: parents end with `/`, the current branch is marked with `*`. */
    private fun lines(
        nodes: List<BranchNode>,
        indent: String = "",
    ): List<String> =
        nodes.flatMap { node ->
            when (node) {
                is BranchParent -> listOf("$indent${node.name}/") + lines(node.children, "$indent  ")
                is BranchLeaf -> listOf("$indent${node.name}${if (node.current) " *" else ""}")
            }
        }

    @Test
    fun `HEAD, then the local branches, the remotes with their branches and the tags`() {
        val refs =
            GitRefs(
                listOf(
                    head,
                    tag("v1.0"),
                    remote("upstream/main"),
                    local("main"),
                    remote("origin/main"),
                    local("dev"),
                    remote("origin/dev"),
                ),
                "refs/heads/main",
            )

        assertEquals(
            listOf(
                "HEAD (Current Branch) *",
                "Local/",
                "  dev",
                "  main *",
                "Remote/",
                "  origin/",
                "    dev",
                "    main",
                "  upstream/",
                "    main",
                "Tags/",
                "  v1.0",
            ),
            lines(branchNodes(refs)),
        )
    }

    @Test
    fun `groups names with slashes into folders, compacting chains, folders first, by name ignoring case`() {
        val refs =
            GitRefs(
                listOf(
                    local("zeta"),
                    local("feature/b"),
                    local("Alpha"),
                    local("feature/a"),
                    local("team/feature/x"),
                    local("team/feature/y"),
                    local("deep/one/two/leaf"),
                    local("alpha"),
                    local("Feature2/c"),
                ),
                null,
            )

        val nodes = branchNodes(refs)

        assertEquals(
            listOf(
                "Local/",
                "  deep/one/two/",
                "    leaf",
                "  feature/",
                "    a",
                "    b",
                "  Feature2/",
                "    c",
                "  team/feature/",
                "    x",
                "    y",
                "  Alpha",
                "  alpha",
                "  zeta",
            ),
            lines(nodes),
        )
        val folders = (nodes.single() as BranchGroup).children.filterIsInstance<BranchFolder>()
        assertEquals(
            listOf("Local/deep/one/two", "Local/feature", "Local/Feature2", "Local/team/feature"),
            folders.map { it.path },
        )
        assertEquals("Local/team/feature/x", (folders.last().children.first() as BranchLeaf).path)
    }

    @Test
    fun `folders below a remote and in tags, remote branches without a slash directly below Remote`() {
        val refs =
            GitRefs(
                listOf(remote("origin/fix/a"), remote("origin/fix/b"), remote("trunk"), tag("release/1.0")),
                null,
            )

        val nodes = branchNodes(refs)

        assertEquals(
            listOf(
                "Remote/",
                "  origin/",
                "    fix/",
                "      a",
                "      b",
                "  trunk",
                "Tags/",
                "  release/",
                "    1.0",
            ),
            lines(nodes),
        )
        val origin = (nodes.first() as BranchGroup).children.first() as BranchRemote
        assertEquals("Remote/origin", origin.path)
        assertEquals("Remote/origin/fix", origin.children.single().path)
        assertEquals("Tags/release", (nodes.last() as BranchGroup).children.single().path)
    }

    @Test
    fun `a detached HEAD shows its short hash, no branch is current`() {
        val nodes = branchNodes(GitRefs(listOf(head, local("main")), null))

        assertEquals(listOf("HEAD (detached at 01234567)", "Local/", "  main"), lines(nodes))
        val leaf = nodes.first() as BranchLeaf
        assertEquals("HEAD", leaf.path)
        assertEquals(head, leaf.ref)
    }

    @Test
    fun `empty groups are left out, no refs give no nodes`() {
        assertEquals(listOf("Tags/", "  v1"), lines(branchNodes(GitRefs(listOf(tag("v1")), null))))
        assertEquals(emptyList(), branchNodes(GitRefs(emptyList(), null)))
    }

    @Test
    fun `filtering keeps the leaves whose short name has the text, ignoring case, with their parents`() {
        val nodes =
            branchNodes(
                GitRefs(
                    listOf(
                        head,
                        local("main"),
                        local("feature/Login"),
                        local("feature/logout"),
                        remote("origin/main"),
                        remote("origin/feature/login"),
                        tag("v1.0"),
                    ),
                    "refs/heads/main",
                ),
            )

        assertEquals(
            listOf(
                "Local/",
                "  feature/",
                "    Login",
                "    logout",
                "Remote/",
                "  origin/",
                "    feature/",
                "      login",
            ),
            lines(filterBranches(nodes, "LOG")),
        )
        // The short name counts, not only the leaf's name.
        assertEquals(listOf("Remote/", "  origin/", "    main"), lines(filterBranches(nodes, "origin/ma")))
        assertEquals(
            listOf("Local/", "  feature/", "    Login", "Remote/", "  origin/", "    feature/", "      login"),
            lines(filterBranches(nodes, "feature/lOgi")),
        )
        assertEquals(listOf("HEAD (Current Branch) *"), lines(filterBranches(nodes, "head")))
        assertTrue(filterBranches(nodes, "nothing").isEmpty())
        assertEquals(nodes, filterBranches(nodes, "  "))
    }

    @Test
    fun `the leaves in tree order`() {
        val nodes = branchNodes(GitRefs(listOf(tag("v1"), local("b/x"), local("a"), head), "refs/heads/a"))

        assertEquals(
            listOf("HEAD", "refs/heads/b/x", "refs/heads/a", "refs/tags/v1"),
            branchLeaves(nodes).map { it.ref.name },
        )
    }
}
