package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class SshConfigHighlightingTest {
    private val ssh =
        Highlighted(
            """
            |# Defaults for all hosts
            |Port 2222
            |PermitRootLogin no
            |Host bastion *.internal
            |    HostName 10.0.0.1
            |    IdentityFile ~/.ssh/id_ed25519
            |Match User deploy Address 10.0.0.0/8
            |    ForwardAgent=yes
            |
            """.trimMargin(),
            RSyntaxHighlighter.sshConfig,
        )

    @Test
    fun `options are keywords, their values plain`() {
        for (option in listOf("Port", "PermitRootLogin", "HostName", "IdentityFile", "ForwardAgent", "Host", "Match")) {
            assertEquals(setOf(TokenStyle.KEYWORD), ssh.stylesOf(option), option)
        }
        assertEquals(emptySet(), ssh.stylesOf("2222"))
        assertEquals(emptySet(), ssh.stylesOf("~/.ssh/id_ed25519"))
        assertEquals(emptySet(), ssh.stylesOf("yes"))
    }

    @Test
    fun `what Host and Match apply to is a type`() {
        assertEquals(setOf(TokenStyle.TYPE), ssh.stylesOf("bastion *.internal"))
        assertEquals(setOf(TokenStyle.TYPE), ssh.stylesOf("User deploy Address 10.0.0.0/8"))
        assertEquals(emptySet(), ssh.stylesOf("10.0.0.1"), "HostName starts no block")
    }

    @Test
    fun `comments`() = assertEquals(setOf(TokenStyle.COMMENT), ssh.stylesOf("# Defaults for all hosts"))

    @Test
    fun `spans are well-formed`() = ssh.assertWellFormed()
}
