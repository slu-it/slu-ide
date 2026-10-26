package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test

class HtaccessHighlightingTest {
    private val htaccess =
        Highlighted(
            """
            |# Rewrite rules
            |RewriteEngine On
            |RewriteCond %{HTTP_HOST} ^example\.com${'$'} [NC]
            |RewriteRule ^(.*)${'$'} https://www.example.com/${'$'}1 [R=301,L]
            |<IfModule mod_headers.c>
            |  Header set Cache-Control "max-age=3600"
            |ExpiresByType image/jpeg "access plus 1 year"
            |Header set X-Frame-Options "SAMEORIGIN"
            |</IfModule>
            |php_value upload_max_filesize 64M
            |
            """.trimMargin(),
            RSyntaxHighlighter.htaccess,
        )

    @Test
    fun `directives are builtins, also those the lexer does not know`() {
        htaccess.assertExactly(TokenStyle.BUILTIN, "RewriteEngine")
        htaccess.assertExactly(TokenStyle.BUILTIN, "RewriteRule")
        htaccess.assertExactly(TokenStyle.BUILTIN, "Header")
        htaccess.assertExactly(TokenStyle.BUILTIN, "php_value")
    }

    @Test
    fun `sections are tags, their arguments plain`() {
        htaccess.assertExactly(TokenStyle.TAG, "IfModule")
        htaccess.assertPlain("mod_headers.c")
    }

    @Test
    fun `server variables and back references are annotations`() {
        htaccess.assertExactly(TokenStyle.ANNOTATION, "%{HTTP_HOST}")
        htaccess.assertExactly(TokenStyle.ANNOTATION, "\$1")
    }

    @Test
    fun `regex arguments and flags are plain, comments and strings are not`() {
        htaccess.assertPlain("^example\\.com")
        htaccess.assertPlain("[NC]")
        htaccess.assertExactly(TokenStyle.COMMENT, "# Rewrite rules")
        htaccess.assertExactly(TokenStyle.STRING, "\"access plus 1 year\"")
        htaccess.assertExactly(TokenStyle.STRING, "\"SAMEORIGIN\"")
    }

    @Test
    fun `spans are well-formed`() = htaccess.assertWellFormed()
}
