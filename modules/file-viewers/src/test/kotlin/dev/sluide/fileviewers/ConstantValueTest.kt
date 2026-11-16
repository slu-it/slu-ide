package dev.sluide.fileviewers

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class ConstantValueTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a constant boolean reads as its value`(value: Boolean) {
        val constant = ConstantBoolean(value)

        assertEquals(value, constant.get())
        assertEquals(value, constant.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a", "b"])
    fun `a constant value reads as its value`(value: String) {
        assertEquals(value, ConstantValue(value).value)
    }
}
