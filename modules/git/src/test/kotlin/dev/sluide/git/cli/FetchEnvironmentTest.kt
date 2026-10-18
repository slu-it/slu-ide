package dev.sluide.git.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class FetchEnvironmentTest {
    @Test
    fun `the batch mode is added only where git would run plain ssh`() {
        val batch = mapOf("GIT_SSH_COMMAND" to "ssh -o BatchMode=yes")
        assertEquals(batch, fetchEnvironment(mapOf("PATH" to "/bin"), sshConfigured = false))
        assertEquals(emptyMap(), fetchEnvironment(emptyMap(), sshConfigured = true))
        assertEquals(emptyMap(), fetchEnvironment(mapOf("GIT_SSH_COMMAND" to "ssh -i key"), sshConfigured = false))
        assertEquals(emptyMap(), fetchEnvironment(mapOf("GIT_SSH" to "/usr/bin/plink"), sshConfigured = false))
        assertEquals(emptyMap(), fetchEnvironment(mapOf("GIT_SSH_VARIANT" to "plink"), sshConfigured = false))
    }
}
