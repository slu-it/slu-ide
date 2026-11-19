package dev.sluide.editorarea

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TabMenuHooksTest {
    @Test
    fun `no menu item is offered or enabled until the window sets its hook`() {
        val hooks = TabMenuHooks()

        assertNull(hooks.showHistory)
        assertNull(hooks.selectInExplorer)
        assertFalse(hooks.canShowHistory(Path.of("a.txt")))
        assertFalse(hooks.canSelectInExplorer(Path.of("a.txt")))
    }
}
