package dev.sluide.docker.tab.details

import dev.sluide.docker.tab.tree.ResourceKey
import javafx.scene.Node
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailsTabSwitchTest {
    /** Records what it was asked to show; `null` for stop. */
    private class FakeStreamView : StreamView {
        val shown = mutableListOf<String?>()

        override val node: Node get() = error("not shown")

        override fun show(containerId: String?) {
            shown += containerId
        }

        override fun dispose() = Unit
    }

    private val logs = FakeStreamView()
    private val stats = FakeStreamView()
    private val events = mutableListOf<String>()
    private val switch = DetailsTabSwitch(logs, stats, { events += "clear" }) { events += "load $it" }
    private val web = ResourceKey.ContainerKey("web")
    private val db = ResourceKey.ContainerKey("db")

    @Test
    fun `logs and stats stream only while their tab is shown for a selected container`() {
        switch.update(DetailsTab.OVERVIEW, web, "web")
        switch.update(DetailsTab.LOGS, web, "web")
        switch.update(DetailsTab.LOGS, db, "db")
        switch.update(DetailsTab.STATS, db, "db")
        switch.update(DetailsTab.STATS, null, null)

        assertEquals(listOf(null, "web", "db", null, null), logs.shown)
        assertEquals(listOf(null, null, null, "db", null), stats.shown)
    }

    @Test
    fun `Inspect loads once per selection when its tab is shown, another selection clears it first`() {
        switch.update(DetailsTab.OVERVIEW, web, "web")
        switch.update(DetailsTab.INSPECT, web, "web")
        switch.update(DetailsTab.OVERVIEW, web, "web")
        switch.update(DetailsTab.INSPECT, web, "web")
        switch.update(DetailsTab.INSPECT, db, "db")

        assertEquals(listOf("clear", "load $web", "clear", "load $db"), events)
    }
}
