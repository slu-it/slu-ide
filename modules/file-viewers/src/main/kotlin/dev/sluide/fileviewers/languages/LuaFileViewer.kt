package dev.sluide.fileviewers.languages

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import org.springframework.stereotype.Component

/** Shows Lua sources (`.lua`, scripts with a `lua` shebang) with syntax highlighting. */
@Component
class LuaFileViewer :
    CodeFileViewer(
        setOf("lua"),
        RSyntaxHighlighter.lua,
        CommentSyntax.LUA,
        interpreters = setOf("lua"),
    )
