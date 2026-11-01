package dev.sluide.docker.model

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerTreeTest {
    private val project = Path.of("/home/me/shop")

    @Test
    fun `projects inside the open folder come first, then other projects, then standalone containers, each by name`() {
        val tree =
            containerTree(
                listOf(
                    container("zeta"),
                    container("other-db-1", project = "other", service = "db", workingDir = "/elsewhere/other"),
                    container("shop-web-1", project = "shop", service = "web", workingDir = "/home/me/shop"),
                    container("Alpha"),
                    container("api-1", project = "api", service = "api", workingDir = "/home/me/shop/services/api"),
                    container("beta-1", project = "beta", service = "x"),
                ),
                project,
            )

        assertEquals(listOf("api", "shop", "beta", "other"), tree.projects.map { it.name })
        assertEquals(listOf(true, true, false, false), tree.projects.map { it.local })
        assertEquals(listOf("Alpha", "zeta"), tree.standalone.map { it.name })
    }

    @Test
    fun `containers of a project are sorted by service, then name`() {
        val tree =
            containerTree(
                listOf(
                    container("shop-web-2", project = "shop", service = "web"),
                    container("shop-db-1", project = "shop", service = "db"),
                    container("shop-web-1", project = "shop", service = "web"),
                ),
                project,
            )

        assertEquals(
            listOf("shop-db-1", "shop-web-1", "shop-web-2"),
            tree.projects
                .single()
                .containers
                .map { it.name },
        )
    }

    @Test
    fun `a project is local only inside the folder, not in a sibling with the same prefix or without a folder`() {
        val tree =
            containerTree(
                listOf(
                    container("a-1", project = "a", workingDir = "/home/me/shop-old"),
                    container("b-1", project = "b", workingDir = "/home/me/shop/../shop/sub"),
                    container("c-1", project = "c"),
                ),
                project,
            )

        assertEquals(mapOf("a" to false, "b" to true, "c" to false), tree.projects.associate { it.name to it.local })
        assertEquals(
            listOf(
                false,
            ),
            containerTree(
                listOf(container("b-1", project = "b", workingDir = "/home/me/shop")),
                null,
            ).projects.map {
                it.local
            },
        )
    }
}
