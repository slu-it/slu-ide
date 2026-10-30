package dev.sluide.scratches

import kotlin.test.Test
import kotlin.test.assertEquals

class ScratchGeneratorTest {
    @Test
    fun `names and base names are unique across all generators`() {
        assertEquals(SCRATCH_GENERATORS.size, SCRATCH_GENERATORS.map { it.name }.toSet().size)
        assertEquals(SCRATCH_GENERATORS.size, SCRATCH_GENERATORS.map { it.baseName }.toSet().size)
    }
}
