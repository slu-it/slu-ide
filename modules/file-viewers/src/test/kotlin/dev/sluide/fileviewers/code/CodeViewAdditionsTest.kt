package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.ViewActivity
import javafx.beans.property.SimpleObjectProperty
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The locks and activities that extensions add to a code view; how the view shows them is `CodeEditorExtensionsUiTest`'s. */
class CodeViewAdditionsTest {
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
