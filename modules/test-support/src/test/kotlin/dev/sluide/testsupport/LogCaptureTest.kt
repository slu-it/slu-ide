package dev.sluide.testsupport

import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class LogCaptureTest {
    private val name = "dev.sluide.testsupport.LogCaptureTest.logger"
    private val logger = Logger.getLogger(name)

    @Test
    fun `captures level, message and throwable`() {
        val error = IllegalStateException("boom")
        LogCapture(name).use { log ->
            logger.log(Level.WARNING, "first", error)
            logger.log(Level.FINE, "second")

            assertEquals(listOf(Level.WARNING to "first", Level.FINE to "second"), log.lines.toList())
            assertEquals(listOf("first", "second"), log.messages())
            assertEquals(1, log.thrown.size)
            assertSame(error, log.thrown.single())
        }
    }

    @Test
    fun `fills in the parameters of a message`() {
        LogCapture(name).use { log ->
            logger.log(Level.INFO, "a {0} and {1}", arrayOf<Any>("x", 7))

            assertEquals(listOf("a x and 7"), log.messages())
        }
    }

    @Test
    fun `keeps a message without parameters as it is`() {
        LogCapture(name).use { log ->
            logger.log(Level.INFO, "braces {0} stay")

            assertEquals(listOf("braces {0} stay"), log.messages())
        }
    }

    @Test
    fun `keeps a message with an apostrophe as it is without parameters or with an empty list`() {
        LogCapture(name).use { log ->
            logger.log(Level.INFO, "it's {0}")
            logger.log(Level.INFO, "it's {0}", emptyArray<Any>())

            assertEquals(listOf("it's {0}", "it's {0}"), log.messages())
        }
    }

    @Test
    fun `keeps the raw message when its pattern cannot be formatted`() {
        LogCapture(name).use { log ->
            logger.log(Level.INFO, "unclosed {0", arrayOf<Any>("x"))

            assertEquals(listOf("unclosed {0"), log.messages())
        }
    }

    @Test
    fun `restores the level and removes its handler on close`() {
        logger.level = Level.SEVERE
        val handlers = logger.handlers.size

        val log = LogCapture(name)
        assertEquals(Level.ALL, logger.level)
        assertEquals(handlers + 1, logger.handlers.size)
        log.close()

        assertEquals(Level.SEVERE, logger.level)
        assertEquals(handlers, logger.handlers.size)
        logger.log(Level.SEVERE, "after")
        assertFalse(log.messages().contains("after"))
        logger.level = null
    }
}
