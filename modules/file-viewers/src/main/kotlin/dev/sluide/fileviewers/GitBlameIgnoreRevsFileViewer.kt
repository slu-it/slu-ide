package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows `.git-blame-ignore-revs`, the commits `git blame` skips, with comments and commit hashes highlighted. */
@Component
class GitBlameIgnoreRevsFileViewer :
    CodeFileViewer(
        emptySet(),
        RSyntaxHighlighter.blameIgnoreRevs,
        CommentSyntax.HASH,
        names = setOf(".git-blame-ignore-revs"),
    )
