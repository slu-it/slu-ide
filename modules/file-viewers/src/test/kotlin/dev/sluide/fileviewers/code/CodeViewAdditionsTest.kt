package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Node
import javafx.scene.layout.Region
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The locks and activities that extensions add to a code view; how the view shows them is `CodeEditorExtensionsUiTest`'s. */
class CodeViewAdditionsTest {
    @Test
    fun `status items show in order, the bar shows only while it has items, closing twice is harmless`() {
        val bar = StatusBar()
        val first = Region()
        val second = Region()
        assertFalse(bar.isVisible)
        assertFalse(bar.isManaged)

        val one = bar.add(first)
        val two = bar.add(second)
        assertEquals(listOf<Node>(first, second), bar.children.toList())
        assertTrue(bar.isVisible && bar.isManaged)

        one.close()
        one.close()
        assertEquals(listOf<Node>(second), bar.children.toList())
        assertTrue(bar.isVisible, "the second is still there")
        two.close()
        assertFalse(bar.isVisible)
        assertFalse(bar.isManaged)
    }

    @Test
    fun `locks are held until all are closed, closing one twice changes nothing`() {
        var changes = 0
        val locks = EditLocks { changes++ }

        val first = locks.lock()
        val second = locks.lock()
        assertTrue(locks.held)
        assertEquals(1, changes, "told when the first is taken")

        first.close()
        first.close()
        assertTrue(locks.held, "the second is still open")
        second.close()
        assertFalse(locks.held)
        assertEquals(2, changes, "told when the last is closed")
    }

    @Test
    fun `read-only locks count as locks, refuse saving and mark until all are closed`() {
        var changes = 0
        val locks = EditLocks { changes++ }

        val plain = locks.lock()
        val first = locks.readOnlyLock()
        val second = locks.readOnlyLock()
        assertTrue(locks.readOnlyHeld)
        assertTrue(locks.readOnlyMarked.value)
        assertEquals(1, changes, "the plain lock came first")

        first.close()
        first.close()
        assertTrue(locks.readOnlyMarked.value, "the second is still open")
        second.close()
        assertFalse(locks.readOnlyHeld)
        assertFalse(locks.readOnlyMarked.value)
        assertTrue(locks.held, "the plain lock is still held")
        plain.close()
        assertEquals(2, changes)

        locks.readOnlyLock().close()
        assertEquals(4, changes, "a read-only lock tells the change like any lock")
    }

    @ParameterizedTest
    @CsvSource(
        "'', NONE",
        "NONE NONE, NONE",
        "NONE FAILED, FAILED",
        "FAILED RUNNING, RUNNING",
        "RUNNING NONE, RUNNING",
    )
    fun `running wins over failed, failed over none`(
        activities: String,
        combined: ViewActivity,
    ) {
        val list = activities.split(' ').filter { it.isNotEmpty() }.map(ViewActivity::valueOf)
        assertEquals(combined, combinedActivity(list))
    }

    @Test
    fun `the combined activity follows the added ones until they are unsubscribed or the view is disposed`() {
        val activities = ViewActivities()
        val first = SimpleObjectProperty(ViewActivity.FAILED)
        val second = SimpleObjectProperty(ViewActivity.NONE)

        val firstSubscription = activities.add(first)
        val secondSubscription = activities.add(second)
        assertEquals(ViewActivity.FAILED, activities.activity.value)
        second.set(ViewActivity.RUNNING)
        assertEquals(ViewActivity.RUNNING, activities.activity.value)

        secondSubscription.unsubscribe()
        assertEquals(ViewActivity.FAILED, activities.activity.value)
        secondSubscription.unsubscribe()
        assertEquals(ViewActivity.FAILED, activities.activity.value, "unsubscribing twice is harmless")

        activities.dispose()
        first.set(ViewActivity.RUNNING)
        assertEquals(ViewActivity.FAILED, activities.activity.value, "no longer followed")
        firstSubscription.unsubscribe()
        activities.add(SimpleObjectProperty(ViewActivity.RUNNING))
        assertEquals(ViewActivity.FAILED, activities.activity.value, "added after dispose: ignored")
    }
}
