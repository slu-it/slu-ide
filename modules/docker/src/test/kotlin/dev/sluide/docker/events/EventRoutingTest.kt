package dev.sluide.docker.events

import dev.sluide.docker.model.DockerEvent
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class EventRoutingTest {
    @ParameterizedTest
    @CsvSource(
        "container, start, CONTAINER",
        "container, die, CONTAINER",
        "container, health_status: healthy, CONTAINER",
        "container, rename, CONTAINER",
        "container, create, CONTAINER VOLUME NETWORK",
        "container, destroy, CONTAINER VOLUME NETWORK",
        "container, remove, CONTAINER VOLUME NETWORK",
        "container, commit, CONTAINER IMAGE",
        "container, exec_create: /bin/sh -c true, ''",
        "container, exec_start: /bin/sh -c true, ''",
        "container, exec_die, ''",
        "container, attach, ''",
        "container, resize, ''",
        "image, pull, IMAGE",
        "image, untag, IMAGE",
        "image, delete, IMAGE",
        "volume, create, VOLUME",
        "volume, destroy, VOLUME",
        "volume, prune, VOLUME",
        "volume, mount, ''",
        "volume, unmount, ''",
        "network, create, NETWORK",
        "network, destroy, NETWORK",
        "network, connect, CONTAINER",
        "network, disconnect, CONTAINER",
        "builder, prune, ''",
        "pod, create, ''",
        "daemon, reload, ''",
    )
    fun `an event reloads the lists it changes`(
        type: String,
        action: String,
        kinds: String,
    ) {
        val expected = kinds.split(' ').filter { it.isNotEmpty() }.toSet()

        assertEquals(expected, reloadsFor(DockerEvent(type, action, "id")).map { it.name }.toSet())
    }

    @ParameterizedTest
    @CsvSource("Container, Start, CONTAINER", "IMAGE, TAG, IMAGE")
    fun `type and action are compared without case`(
        type: String,
        action: String,
        kind: String,
    ) {
        assertEquals(setOf(kind), reloadsFor(DockerEvent(type, action, "id")).map { it.name }.toSet())
    }

    @Test
    fun `a type that only shares the hash code of a known type reloads nothing`() {
        // Same hashCode as container, image, volume and network (two neighbouring characters shifted by 1 and 31).
        val colliding =
            mapOf(
                "container" to "dPntainer",
                "image" to "jNage",
                "volume" to "wPlume",
                "network" to "oFtwork",
            )

        colliding.forEach { (type, other) ->
            assertEquals(type.hashCode(), other.hashCode(), other)
            assertEquals(emptySet(), reloadsFor(DockerEvent(other, "create", "id")), other)
        }
    }
}
