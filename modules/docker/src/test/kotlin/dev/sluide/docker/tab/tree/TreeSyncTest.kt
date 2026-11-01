package dev.sluide.docker.tab.tree

import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.container
import javafx.collections.ListChangeListener
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TreeSyncTest {
    private val sync = TreeSync()

    private fun show(vararg containers: Container) =
        sync.update(resourceTree(Resources(containers = containers.toList()), Path.of("/p"), ""))

    private val web = container("web-1", project = "shop", service = "web")
    private val db = container("db-1", project = "shop", service = "db")
    private val solo = container("solo")

    @Test
    fun `a reload keeps the items of kept resources with their expansion and updates their values`() {
        show(web, db, solo)
        val project = checkNotNull(sync.itemOf(ResourceKey.Project("shop")))
        val webItem = checkNotNull(sync.itemOf(ResourceKey.ContainerKey(web.id)))
        assertTrue(project.isExpanded, "projects start expanded")
        project.isExpanded = false

        val stopped = web.copy(state = ContainerState.EXITED, status = "Exited (0)")
        show(stopped, db, solo)

        assertSame(project, sync.itemOf(ResourceKey.Project("shop")))
        assertFalse(project.isExpanded)
        assertSame(webItem, sync.itemOf(ResourceKey.ContainerKey(web.id)))
        assertEquals(stopped, (webItem.value as ContainerRow).container)
    }

    @Test
    fun `items of gone resources are removed, new ones added in the tree's order`() {
        show(web, solo)
        val containers = sync.root.children.first()

        show(db, web)

        assertNull(sync.itemOf(ResourceKey.ContainerKey(solo.id)))
        assertNotNull(sync.itemOf(ResourceKey.ContainerKey(db.id)))
        val shop = containers.children.single()
        assertEquals(listOf("db-1", "web-1"), shop.children.map { it.value.label })
    }

    @Test
    fun `a reload that changes nothing does not touch the child lists`() {
        show(web, db, solo)
        var changes = 0
        val listener = ListChangeListener<Any> { changes++ }
        sync.root.children.addListener(listener)
        sync.root.children.forEach { it.children.addListener(listener) }
        sync.root.children
            .first()
            .children
            .forEach { it.children.addListener(listener) }

        show(web, db, solo)

        assertEquals(0, changes)
    }
}
