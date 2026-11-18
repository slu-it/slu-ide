package dev.sluide.docker.model

import kotlin.test.Test
import kotlin.test.assertEquals

class PruneCandidatesTest {
    private fun image(
        id: String,
        repository: String? = null,
        tag: String? = null,
    ) = Image(id, repository, tag, null, 1, null, dangling = repository == null && tag == null)

    private fun volume(
        name: String,
        inUse: Boolean? = null,
    ) = Volume(name, "local", null, emptyMap(), anonymous = false, inUse = inUse)

    private fun network(name: String) = Network("id-$name", name, "bridge", "local")

    @Test
    fun `dangling images that no container was created from are candidates, once each`() {
        val dangling = "sha256:4afe37744622e8281a79b0d2cf403ab748b32653d540c1924c58c5670968a0e1"
        val used = "sha256:a4215986369f2968adeb5f20a7e8115b7a90547b25ba77f8d23684448e5bef12"
        val candidates =
            pruneCandidates(
                images = listOf(image(dangling), image(dangling), image(used), image("sha256:1", "bash", "5.2")),
                volumes = emptyList(),
                networks = emptyList(),
                // A container of an untagged image names it by a short id; bash:5.2 is no id.
                containers = listOf(container("old", image = "a4215986369f"), container("web", image = "bash:5.2")),
            )

        assertEquals(listOf(dangling), candidates.images.map { it.id })
    }

    @Test
    fun `volumes in use by the engine's word or by a container's mounts are kept`() {
        val candidates =
            pruneCandidates(
                images = emptyList(),
                volumes =
                    listOf(
                        volume("data", inUse = true),
                        volume("cache", inUse = false),
                        volume("mounted"),
                        volume("unmounted"),
                    ),
                networks = emptyList(),
                containers = listOf(container("web", volumes = listOf("mounted"), state = ContainerState.EXITED)),
            )

        assertEquals(listOf("cache", "unmounted"), candidates.volumes.map { it.name })
    }

    @Test
    fun `networks without containers are candidates, the engine's own ones never`() {
        val candidates =
            pruneCandidates(
                images = emptyList(),
                volumes = emptyList(),
                networks = listOf("bridge", "host", "none", "podman", "shop_default", "unused").map(::network),
                containers = listOf(container("web", networks = listOf("shop_default"))),
            )

        assertEquals(listOf("unused"), candidates.networks.map { it.name })
    }

    @Test
    fun `a container keeps a dangling image by its image id, and every other dangling image is a candidate`() {
        val used = "sha256:4afe37744622e8281a79b0d2cf403ab748b32653d540c1924c58c5670968a0e1"
        val free = "sha256:a4215986369f2968adeb5f20a7e8115b7a90547b25ba77f8d23684448e5bef12"
        val candidates =
            pruneCandidates(
                images = listOf(image(used), image(free)),
                volumes = emptyList(),
                networks = emptyList(),
                containers = listOf(container("web", image = "web:1", imageId = used)),
            )

        assertEquals(listOf(free), candidates.images.map { it.id })
    }

    @Test
    fun `a dangling image whose id is no hex id is a candidate whatever the containers use`() {
        val candidates =
            pruneCandidates(
                images = listOf(image("sha256:1")),
                volumes = emptyList(),
                networks = emptyList(),
                containers = listOf(container("web", image = "a4215986369f")),
            )

        assertEquals(listOf("sha256:1"), candidates.images.map { it.id })
    }

    @Test
    fun `an image reference that looks like the start of an id keeps no image`() {
        // A repository called abc is no id (too short), so it cannot keep the image whose id starts with abc.
        val dangling = "sha256:abc4afe37744622e8281a79b0d2cf403ab748b32653d540c1924c58c5670968a"
        val candidates =
            pruneCandidates(
                images = listOf(image(dangling)),
                volumes = emptyList(),
                networks = emptyList(),
                containers = listOf(container("web", image = "abc")),
            )

        assertEquals(listOf(dangling), candidates.images.map { it.id })
    }

    @Test
    fun `a network a container is connected to by id is kept`() {
        val candidates =
            pruneCandidates(
                images = emptyList(),
                volumes = emptyList(),
                networks = listOf(network("by-id"), network("free")),
                containers = listOf(container("web", networks = listOf("id-by-id"))),
            )

        assertEquals(listOf("free"), candidates.networks.map { it.name })
    }
}
