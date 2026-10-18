package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows OpenSSH configuration (`sshd_config`, `ssh_config`) with options and `Host`/`Match` blocks highlighted. */
@Component
class SshConfigFileViewer :
    CodeFileViewer(
        emptySet(),
        RSyntaxHighlighter.sshConfig,
        CommentSyntax.HASH,
        names = setOf("sshd_config", "ssh_config"),
    )
