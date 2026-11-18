package dev.sluide.docker.tab.tree

import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.model.container
import dev.sluide.docker.tab.image
import dev.sluide.docker.tab.network
import dev.sluide.docker.tab.volume
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceTreeTest {
    private val folder = Path.of("/home/me/shop")

    private val resources =
        Resources(
            containers =
                listOf(
                    container("solo", image = "nginx:1.27"),
                    container(
                        "shop-web-1",
                        project = "shop",
                        service = "web",
                        workingDir = "/home/me/shop",
                        image = "web:1",
                    ),
                    container(
                        "shop-db-1",
                        project = "shop",
                        service = "db",
                        workingDir = "/home/me/shop",
                        image = "db:1",
                    ),
                    container(
                        "other-api-1",
                        project = "other",
                        service = "api",
                        workingDir = "/srv/other",
                        image = "api:1",
                    ),
                ),
            images =
                listOf(
                    image(null, null, id = "sha256:" + "b".repeat(64)),
                    image("nginx", "1.27"),
                    image("bash", "5.2"),
                ),
            volumes = listOf(volume("f".repeat(64), anonymous = true), volume("data")),
            networks = listOf(network("bridge"), network("shop_default")),
        )

    private fun labels(nodes: List<TreeNode>): List<Any> =
        nodes.map { node -> if (node.children.isEmpty()) node.row.label else node.row.label to labels(node.children) }

    @Test
    fun `four groups with counts, projects of the open folder first, standalone containers last`() {
        val tree = resourceTree(resources, folder, "")

        assertEquals(
            listOf(
                "Containers (4)" to
                    listOf("shop" to listOf("shop-db-1", "shop-web-1"), "other" to listOf("other-api-1"), "solo"),
                "Images (3)" to listOf("bash:5.2", "nginx:1.27", "<none> bbbbbbbbbbbb"),
                "Volumes (2)" to listOf("data", "ffffffffffff…"),
                "Networks (2)" to listOf("bridge", "shop_default"),
            ),
            labels(tree),
        )
        val projects = tree.first().children.mapNotNull { it.row as? ProjectRow }
        assertEquals(listOf("compose · this project", "compose"), projects.map { it.detail })
        assertEquals(
            "25.4 MB",
            tree[1]
                .children
                .first()
                .row.detail,
        )
        assertEquals(
            "local",
            tree[2]
                .children
                .first()
                .row.detail,
        )
    }

    @Test
    fun `the filter matches names, images, tags and compose projects and services, ignoring case`() {
        fun shown(filter: String) =
            resourceTree(resources, folder, filter).flatMap { group ->
                group.children.flatMap { node -> listOf(node.row.label) + node.children.map { it.row.label } }
            }

        assertEquals(listOf("solo", "nginx:1.27"), shown("NGINX"))
        assertEquals(listOf("shop", "shop-db-1", "shop-web-1", "shop_default"), shown("shop"))
        assertEquals(listOf("shop", "shop-db-1"), shown(" db "))
        assertEquals(listOf("bash:5.2"), shown("5.2"))
        assertEquals(listOf("data"), shown("dat"))
        val counts = resourceTree(resources, folder, "zzz").map { it.row.label }
        assertEquals(listOf("Containers (0)", "Images (0)", "Volumes (0)", "Networks (0)"), counts)
    }

    @Test
    fun `an untagged image listed for two repositories gets a row and key for each`() {
        val id = "sha256:" + "c".repeat(64)
        val images = listOf(image("shop/web", null, id = id), image("shop/api", null, id = id))
        val tree = resourceTree(Resources(images = images), folder, "")

        val rows = tree[1].children.map { it.row }
        assertEquals(listOf("shop/api:<none> cccccccccccc", "shop/web:<none> cccccccccccc"), rows.map { it.label })
        assertEquals(2, rows.map { it.key }.toSet().size)
        val sync = TreeSync<ResourceKey, TreeRow>().apply { update(tree.map { it.toSyncNode() }) }
        assertEquals(
            2,
            sync.root.children[1]
                .children.size,
        )
    }

    @Test
    fun `rows are keyed by resource identity, and findRow finds them at any depth`() {
        val tree = resourceTree(resources, folder, "")

        val web = findRow(tree, ResourceKey.ContainerKey("id-shop-web-1"))
        assertEquals("shop-web-1", web?.label)
        assertEquals(ResourceKey.Group(ResourceKind.IMAGE), tree[1].row.key)
        assertEquals(ResourceKey.ImageKey("nginx:1.27"), findRow(tree, ResourceKey.ImageKey("nginx:1.27"))?.key)
        assertEquals("sha256:" + "b".repeat(64), (tree[1].children.last().row as ImageRow).key.reference)
        assertEquals(null, findRow(tree, ResourceKey.VolumeKey("gone")))
    }

    @Test
    fun `toSyncNode keys by row key and starts groups and projects expanded, containers not`() {
        val node = resourceTree(resources, folder, "")[0].toSyncNode()
        val project = node.children.first { it.key is ResourceKey.Project }
        val container = project.children.first()

        assertEquals(ResourceKey.Group(ResourceKind.CONTAINER), node.key)
        assertEquals(true, node.expanded)
        assertEquals(true, project.expanded)
        assertEquals(false, container.expanded)
        assertEquals(container.value.key, container.key)
    }

    @Test
    fun `rows show their detail and tooltip, a group and a network row have no tooltip`() {
        val tree = resourceTree(resources, folder, "")
        val project = tree[0].children.first().row
        val web = (project as ProjectRow).project.containers.last()
        val nginx = tree[1].children[1].row
        val volume = tree[2].children.first().row
        val network = tree[3].children.first().row

        assertEquals("/home/me/shop", project.tooltip)
        assertEquals("web:1\nUp", ContainerRow(web).tooltip)
        assertEquals("", ContainerRow(web).detail)
        assertEquals("sha256:" + "a".repeat(64), nginx.tooltip)
        assertEquals("data", volume.tooltip)
        assertEquals("bridge", network.detail)
        assertEquals(null, network.tooltip)
        assertEquals("", tree[0].row.detail)
        assertEquals(null, tree[0].row.tooltip)
    }

    @Test
    fun `a container state has the CSS class of its dot, every state not running, paused or restarting is stopped`() {
        val expected =
            mapOf(
                ContainerState.RUNNING to "docker-state-running",
                ContainerState.PAUSED to "docker-state-paused",
                ContainerState.RESTARTING to "docker-state-restarting",
            )

        for (state in ContainerState.entries) {
            assertEquals(expected[state] ?: "docker-state-stopped", stateClass(state), state.name)
        }
    }

    @Test
    fun `an image without a repository is none even when it has a tag, a volume is shortened only if anonymous`() {
        assertEquals("<none> aaaaaaaaaaaa", imageLabel(image(null, "v1")))
        assertEquals("nginx:<none> aaaaaaaaaaaa", imageLabel(image("nginx", null)))

        assertEquals("x".repeat(64), volumeLabel(volume("x".repeat(64))))
        assertEquals("x".repeat(12), volumeLabel(volume("x".repeat(12), anonymous = true)))
        assertEquals("x".repeat(12) + "…", volumeLabel(volume("x".repeat(13), anonymous = true)))
    }

    @Test
    fun `images are filtered by repository, tag, reference or a part of the short id`() {
        val dangling = image(null, "v9", id = "sha256:" + "e".repeat(64))
        val untagged = image("shop/web", null, id = "sha256:" + "f".repeat(64))
        val all = Resources(images = listOf(image("nginx", "1.27"), dangling, untagged))

        fun shown(filter: String) = resourceTree(all, folder, filter)[1].children.map { it.row.label }.sorted()

        assertEquals(listOf("nginx:1.27"), shown("x:1"), "the reference joins repository and tag")
        assertEquals(listOf("<none> eeeeeeeeeeee"), shown("v9"), "a tag alone")
        assertEquals(listOf("shop/web:<none> ffffffffffff"), shown("op/w"), "a repository alone")
        assertEquals(listOf("nginx:1.27"), shown("aaaaaaaa"), "the id of a tagged image")
        assertEquals(listOf("<none> eeeeeeeeeeee"), shown("eeeeeeeeeeee"), "the short id")
    }

    @Test
    fun `a container is found by its Compose service, also when it has no project`() {
        val all =
            Resources(
                containers =
                    listOf(
                        container("x1", project = "alpha", service = "frontend", image = "img:1"),
                        container("x2", project = "alpha", service = "backend", image = "img:2"),
                        container("lonely", service = "cache", image = "img:3"),
                    ),
            )

        fun shown(filter: String) =
            resourceTree(all, folder, filter)[0].children.flatMap { node ->
                listOf(node.row.label) + node.children.map { it.row.label }
            }

        assertEquals(listOf("alpha", "x1"), shown("front"))
        assertEquals(listOf("lonely"), shown("cache"))
        assertEquals(listOf("alpha", "x2", "x1"), shown("alpha"), "a matching project keeps all its containers")
    }

    @Test
    fun `an anonymous volume and a dangling image sort last whatever the order they come in`() {
        val named = listOf(volume("data"), volume("0".repeat(64), anonymous = true), volume("zeta"))
        val images =
            listOf(image("nginx", "1.27"), image(null, null, id = "sha256:" + "b".repeat(64)), image("bash", "5.2"))

        for (order in permutations(3)) {
            val tree =
                resourceTree(Resources(images = order.map(images::get), volumes = order.map(named::get)), folder, "")

            assertEquals(listOf("bash:5.2", "nginx:1.27", "<none> bbbbbbbbbbbb"), tree[1].children.map { it.row.label })
            assertEquals(listOf("data", "zeta", "000000000000…"), tree[2].children.map { it.row.label })
        }
    }

    private fun permutations(size: Int): List<List<Int>> =
        if (size == 0) {
            listOf(emptyList())
        } else {
            permutations(size - 1).flatMap { rest ->
                (0..rest.size).map { at ->
                    rest.toMutableList().apply {
                        add(
                            at,
                            size - 1,
                        )
                    }
                }
            }
        }
}
