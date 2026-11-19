package dev.sluide.languageservers.connection

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

/** What Thoreau declares at `initialize` decides what servers send, so it is pinned field by field (LSP 3.17 names). */
class ClientCapabilitiesFactoryTest {
    @Test
    fun `the declared capabilities are the reviewed ones`() {
        val expected =
            checkNotNull(javaClass.getResourceAsStream("/client-capabilities.json")).use {
                JsonParser.parseReader(it.reader())
            }

        // As the JSON a server receives, so the field names are the protocol's.
        assertEquals(expected, protocolGson.toJsonTree(clientCapabilities()))
    }
}
