package dev.sluide.docker.model

import dev.sluide.testsupport.collidingWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The words and labels of the model that the CLI parsers and the compose pane rely on. */
class ResourcesTest {
    @ParameterizedTest
    @CsvSource(
        "created, CREATED",
        "configured, CREATED",
        "initialized, CREATED",
        "running, RUNNING",
        "up, RUNNING",
        "paused, PAUSED",
        "restarting, RESTARTING",
        "exited, EXITED",
        "stopped, EXITED",
        "dead, DEAD",
        "removing, REMOVING",
        "'  Up ', RUNNING",
        "RESTARTING, RESTARTING",
    )
    fun `every word of the engines maps to its state, ignoring case and blanks`(
        text: String,
        state: ContainerState,
    ) {
        assertEquals(state, ContainerState.of(text))
    }

    @Test
    fun `no word and an empty one is an unknown state`() {
        assertEquals(ContainerState.UNKNOWN, ContainerState.of(null))
        assertEquals(ContainerState.UNKNOWN, ContainerState.of(""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "created", "configured", "initialized", "running", "up", "paused", "restarting", "exited", "stopped",
            "dead", "removing",
        ],
    )
    fun `a word that only has the hash code of a known one is an unknown state`(word: String) {
        val other = collidingWith(word) { it.trim().lowercase() }

        assertNotEquals(word, other)
        assertEquals(word.hashCode(), other.hashCode())
        assertEquals(other, other.lowercase(), "the word is compared in lower case")
        assertEquals(ContainerState.UNKNOWN, ContainerState.of(other))
    }

    @Test
    fun `compose labels that are blank count as missing`() {
        val blank =
            container("c", project = " ", service = "", workingDir = "  ")

        assertNull(blank.composeProject)
        assertNull(blank.composeService)
        assertNull(blank.composeWorkingDir)
        val set = container("c", project = "shop", service = "web", workingDir = "/home/me/shop")
        assertEquals("shop", set.composeProject)
        assertEquals("web", set.composeService)
        assertEquals("/home/me/shop", set.composeWorkingDir)
    }

    @ParameterizedTest
    @CsvSource("True, true", "true, true", "' true ', true", "False, false", "yes, false", "'', false")
    fun `the one-off label is true only for the word true`(
        label: String,
        oneOff: Boolean,
    ) {
        val labels = mapOf(COMPOSE_ONEOFF_LABEL to label)

        assertEquals(oneOff, container("c").copy(labels = labels).composeOneOff)
    }

    @Test
    fun `a container without the one-off label is no one-off`() {
        assertFalse(container("c").composeOneOff)
        assertTrue(container("c", oneOff = true).composeOneOff)
    }

    @Test
    fun `an image is named by repository and tag, else by its id`() {
        fun image(
            repository: String?,
            tag: String?,
        ) = Image("sha256:abc", repository, tag, null, 1, null, dangling = false)

        assertEquals("app:1", image("app", "1").reference)
        assertEquals("sha256:abc", image("app", null).reference)
        assertEquals("sha256:abc", image(null, "1").reference)
        assertEquals("sha256:abc", image(null, null).reference)
    }
}
