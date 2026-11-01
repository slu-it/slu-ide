package dev.sluide.docker.tab.tree

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
        val sync = TreeSync().apply { update(tree) }
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
}
