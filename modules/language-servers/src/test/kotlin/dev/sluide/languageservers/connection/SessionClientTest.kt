package dev.sluide.languageservers.connection

import org.eclipse.lsp4j.MessageType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class SessionClientTest {
    @ParameterizedTest
    @CsvSource("Error, WARNING", "Warning, INFO", "Info, DEBUG", "Log, DEBUG")
    fun `a server's log messages are logged at a level below its own`(
        type: MessageType,
        level: System.Logger.Level,
    ) {
        assertEquals(level, logMessageLevel(type))
    }
}
