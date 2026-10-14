package dev.sluide.httpclient.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class BasicAuthTest {
    @Test
    fun `encodes user and password separated by a space or a colon`() {
        assertEquals("Basic dXNlcjpwYXNz", BasicAuth.normalize("Basic user pass"))
        assertEquals("Basic dXNlcjpwYXNz", BasicAuth.normalize("Basic user:pass"))
        assertEquals("Basic dXNlcjpwYSBzcw==", BasicAuth.normalize("basic  user  pa ss"))
    }

    @Test
    fun `keeps encoded credentials and other schemes`() {
        assertEquals("Basic dXNlcjpwYXNz", BasicAuth.normalize("Basic dXNlcjpwYXNz"))
        assertEquals("Bearer token", BasicAuth.normalize("Bearer token"))
        assertEquals("Basic", BasicAuth.normalize("Basic"))
    }

    @Test
    fun `encodes non-ASCII credentials as UTF-8`() {
        assertEquals("Basic w6Q6w7Y=", BasicAuth.normalize("Basic ä ö"))
    }
}
